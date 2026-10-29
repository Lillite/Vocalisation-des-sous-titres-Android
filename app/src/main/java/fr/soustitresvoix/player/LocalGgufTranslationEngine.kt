package fr.soustitresvoix.player

import android.content.Context
import fr.soustitresvoix.core.TranslationPlan
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** A small injectable boundary; production always uses the private native service. */
internal interface LocalGgufBackend : AutoCloseable {
    data class Info(val name: String, val architecture: String,
        val performance: LocalTranslationPerformance = LocalTranslationPerformance(phase = "load", backend = "CPU"),
        val chatTemplateAvailable: Boolean = false)
    data class Generation(val output: String, val stopReason: String = "end_of_generation",
        val performance: LocalTranslationPerformance = LocalTranslationPerformance(backend = "CPU"))
    fun open(file: File, threads: Int, contextTokens: Int, cancelled: () -> Boolean, executionMode: String): Info
    fun generate(prompt: String, maxOutputTokens: Int, cancelled: () -> Boolean): Generation
    fun generateChat(userContent: String, maxOutputTokens: Int, cancelled: () -> Boolean): Generation =
        throw TranslationFailure(TranslationFailure.Reason.LOCAL_TEMPLATE)
    fun cancel()
    fun closeAndAwait(cancelled: () -> Boolean = { false }, timeoutMs: Long = 10_000) { close() }
}

private class NativeTranslationBackend(context: Context) : LocalGgufBackend {
    private val client = LocalGgufRuntime.Client(context)
    override fun open(file: File, threads: Int, contextTokens: Int, cancelled: () -> Boolean, executionMode: String): LocalGgufBackend.Info =
        client.open(file, threads, contextTokens, cancelled, executionMode = executionMode).let {
            LocalGgufBackend.Info(it.name, it.architecture, LocalTranslationPerformance(phase = "load",
                requestedMode = it.requestedMode, backend = it.backend, device = it.device,
                gpuObserved = false, loadMs = it.loadMs, peakRssBytes = it.peakRssBytes,
                currentRssBytes = it.residentBytes, availableRamBytes = it.availableRamBytes, fallbackReason = it.fallbackReason,
                diagnostic = it.diagnostic, openClTransport = it.openClTransport, loadProfile = it.loadProfile),
                chatTemplateAvailable = it.chatTemplateAvailable)
        }
    override fun generate(prompt: String, maxOutputTokens: Int, cancelled: () -> Boolean): LocalGgufBackend.Generation =
        generation(client.generate(prompt, maxOutputTokens, addSpecialTokens = false, cancelled = cancelled))
    override fun generateChat(userContent: String, maxOutputTokens: Int, cancelled: () -> Boolean): LocalGgufBackend.Generation =
        generation(client.generateChat(userContent, maxOutputTokens, cancelled = cancelled))
    private fun generation(value: LocalGgufRuntime.Generation): LocalGgufBackend.Generation = with(value) {
        LocalGgufBackend.Generation(output, stopReason, LocalTranslationPerformance(
            requestedMode = requestedMode, backend = backend, device = device, gpuObserved = gpuObserved,
            promptMs = promptMs, generationMs = generationMs, promptTokens = promptTokens,
            generatedTokens = generatedTokens, cachedPromptTokens = cachedPromptTokens,
            promptEvaluatedTokens = promptEvaluatedTokens, peakRssBytes = peakRssBytes,
            currentRssBytes = residentBytes, availableRamBytes = availableRamBytes,
            gpuGraphNodes = gpuGraphNodes, fallbackReason = fallbackReason, diagnostic = diagnostic,
            openClTransport = openClTransport))
    }
    override fun cancel() = client.cancel()
    override fun close() = client.close()
    override fun closeAndAwait(cancelled: () -> Boolean, timeoutMs: Long) = client.closeAndAwait(cancelled, timeoutMs)
}

/**
 * One model load per explicitly started task. Only complete EOS-terminated
 * translations leave this engine. Numbering, timestamps and cue identity remain
 * the runner's responsibility; they are never delegated to a language model.
 */
class LocalGgufTranslationEngine internal constructor(
    private val model: LocalGgufModel,
    private val store: LocalGgufModelStore,
    private val backendFactory: () -> LocalGgufBackend,
    private val threads: Int = 4,
    private val context: String = "",
    private val profile: LocalGgufProfile = LocalGgufProfile.AUTO,
    private val executionMode: String = "AUTO",
    private val contextPolicy: LocalGgufContextPolicy = LocalGgufContextPolicy.RECENT_TWO
) : ContextualTranslationEngine, LocalTranslationPerformanceSource {
    constructor(appContext: Context, model: LocalGgufModel, threads: Int = 4, context: String = "",
        profile: LocalGgufProfile = LocalGgufProfile.AUTO, executionMode: String = "AUTO",
        contextPolicy: LocalGgufContextPolicy = LocalGgufContextPolicy.RECENT_TWO) :
        this(model, LocalGgufModelStore(appContext), { NativeTranslationBackend(appContext) }, threads, context, profile, executionMode, contextPolicy)

    override val id: String = identity(model, threads, context, profile, executionMode, contextPolicy)
    override val maxBatchItems: Int = 1
    override val maxBatchCharacters: Int = TranslationPlan.MAX_CUE_CHARACTERS
    override val requiresDurableResult: Boolean = true
    override val supportsExactRequestCache: Boolean = true
    override fun onExactRequestCacheHit() {
        // A reused result has no new runtime observation, including no previous fallback.
        runCatching { performanceListener?.invoke(LocalTranslationPerformance(phase = "cache", requestedMode = executionMode)) }
    }
    override fun contextStartIndex(occurrenceIndex: Int): Int = contextPolicy.contextStartIndex(occurrenceIndex)
    private val closed = AtomicBoolean(false)
    private val working = AtomicBoolean(false)
    private val unloadAcknowledged = AtomicBoolean(false)
    private val state = Any()
    @Volatile private var backend: LocalGgufBackend? = null
    @Volatile private var retiredBackend: LocalGgufBackend? = null
    private var lease: AutoCloseable? = null
    private var family: LocalGgufPrompts.Family? = null
    @Volatile private var performanceListener: ((LocalTranslationPerformance) -> Unit)? = null
    private var openPerformance = LocalTranslationPerformance(phase = "load")
    private var runningMode = executionMode
    private var cpuFallbackAttempted = false
    private var fallbackMessage = ""
    private var fallbackDiagnostic: LocalGgufDiagnostic? = null

    override fun setPerformanceListener(listener: ((LocalTranslationPerformance) -> Unit)?) { performanceListener = listener }
    private fun measured(value: LocalTranslationPerformance) {
        val message = if (fallbackMessage.isEmpty()) value.fallbackReason else fallbackMessage
        runCatching { performanceListener?.invoke(value.copy(requestedMode = executionMode, fallbackReason = message,
            diagnostic = fallbackDiagnostic ?: value.diagnostic)) }
    }

    override fun translate(texts: List<String>, sourceLanguage: String, targetLanguage: String,
        cancelled: () -> Boolean): List<String> =
        translateWithContext(texts, sourceLanguage, targetLanguage, emptyList(), emptyList(), cancelled)

    override fun validateExactRequestCacheHit(texts: List<String>, sourceLanguage: String, targetLanguage: String,
        previousSources: List<String>, previousTranslations: List<String>, cancelled: () -> Boolean) {
        checkCancelled(cancelled)
        if (working.get()) throw TranslationFailure(TranslationFailure.Reason.LOCAL_BUSY)
        validateRequest(texts, sourceLanguage, targetLanguage, previousSources, previousTranslations, cancelled)
    }

    private fun validateRequest(texts: List<String>, sourceLanguage: String, targetLanguage: String,
        previousSources: List<String>, previousTranslations: List<String>, cancelled: () -> Boolean): List<LocalGgufPrompts.PairContext> {
        checkCancelled(cancelled)
        if (texts.size != 1 || previousSources.size != previousTranslations.size)
            throw TranslationFailure(TranslationFailure.Reason.CONFIGURATION)
        validateSettings(threads, context, executionMode)
        validateLanguages(sourceLanguage, targetLanguage)
        // Header hints can validate family-specific languages without loading weights.
        // Unknown hints are not a reason to invent a family for a previously completed exact key.
        val knownFamily = family ?: LocalGgufProfiles.preflight(model, profile)
        knownFamily?.let { kind ->
            LocalGgufPrompts.validateLanguage(kind, sourceLanguage)
            LocalGgufPrompts.validateLanguage(kind, targetLanguage)
        }
        val source = texts.single()
        if (!validText(source) || source.isBlank() || source.length > TranslationPlan.MAX_CUE_CHARACTERS)
            throw TranslationFailure(TranslationFailure.Reason.CONFIGURATION)
        if (LocalGgufPrompts.hasControlTokens(source))
            throw TranslationFailure(TranslationFailure.Reason.LOCAL_CONTEXT)
        return previousSources.zip(previousTranslations).takeLast(contextPolicy.maximumPreviousPairs).map { (original, translated) ->
            if (!validText(original) || !validText(translated))
                throw TranslationFailure(TranslationFailure.Reason.RESPONSE)
            if (LocalGgufPrompts.hasControlTokens(original) || LocalGgufPrompts.hasControlTokens(translated))
                throw TranslationFailure(TranslationFailure.Reason.LOCAL_CONTEXT)
            // A clipped source and a separately clipped translation need not
            // mean the same thing. Keep a complete pair or remove it whole
            // when the native input budget rejects the oldest context.
            LocalGgufPrompts.PairContext(original, translated)
        }
    }

    override fun translateWithContext(texts: List<String>, sourceLanguage: String, targetLanguage: String,
        previousSources: List<String>, previousTranslations: List<String>, cancelled: () -> Boolean): List<String> {
        val occurrenceStart = System.nanoTime()
        checkCancelled(cancelled)
        if (!working.compareAndSet(false, true)) throw TranslationFailure(TranslationFailure.Reason.LOCAL_BUSY)
        try {
            val history = validateRequest(texts, sourceLanguage, targetLanguage, previousSources, previousTranslations, cancelled)
            val source = texts.single()
            val alreadyOpen = backend != null
            var worker = openWithFallback(cancelled)
            var occurrenceLoadMs = if (alreadyOpen) 0 else openPerformance.loadMs
            val kind = requireNotNull(family)
            LocalGgufPrompts.validateLanguage(kind, sourceLanguage)
            LocalGgufPrompts.validateLanguage(kind, targetLanguage)
            var requests = 0
            fun generate(text: String, preceding: List<LocalGgufPrompts.PairContext>, depth: Int): String {
                checkCancelled(cancelled)
                if (++requests > MAX_REQUESTS_PER_CUE || depth > 64)
                    throw TranslationFailure(TranslationFailure.Reason.LOCAL_CONTEXT)
                val prompt = LocalGgufPrompts.render(kind, text, sourceLanguage, targetLanguage, preceding, context)
                try {
                    val result = if (kind.usesChatTemplate)
                        worker.generateChat(prompt, OUTPUT_TOKENS) { closed.get() || cancelled() }
                    else worker.generate(prompt, OUTPUT_TOKENS) { closed.get() || cancelled() }
                    checkCancelled(cancelled)
                    if (result.stopReason != "end_of_generation")
                        throw TranslationFailure(TranslationFailure.Reason.LOCAL_TRUNCATED)
                    val output = result.output.replace("\r\n", "\n").replace('\r', '\n').trim()
                    if (output.isBlank() || !validText(output) || output.length > TranslationPlan.MAX_CUE_CHARACTERS ||
                        LocalGgufPrompts.hasControlTokens(output))
                        throw TranslationFailure(TranslationFailure.Reason.RESPONSE)
                    measured(result.performance.copy(phase = "generation", loadMs = occurrenceLoadMs,
                        endToEndMs = ((System.nanoTime() - occurrenceStart) / 1_000_000).coerceAtLeast(0)))
                    return output
                } catch (failure: LocalGgufRuntime.Failure) {
                    if (canRetryCpu(failure, cancelled)) {
                        worker = retryCpu(failure, cancelled)
                        occurrenceLoadMs += openPerformance.loadMs
                        return generate(text, preceding, depth + 1)
                    }
                    if (failure.code == LocalGgufRuntime.Code.INPUT_TOO_LONG && preceding.isNotEmpty())
                        return generate(text, preceding.drop(1), depth + 1)
                    if (failure.code == LocalGgufRuntime.Code.INPUT_TOO_LONG || failure.code == LocalGgufRuntime.Code.OUTPUT_LIMIT) {
                        val parts = split(text)
                        if (parts == null) throw TranslationFailure(if (failure.code == LocalGgufRuntime.Code.OUTPUT_LIMIT)
                            TranslationFailure.Reason.LOCAL_TRUNCATED else TranslationFailure.Reason.LOCAL_CONTEXT)
                        val left = generate(parts.first, preceding, depth + 1)
                        val nextContext = (preceding + LocalGgufPrompts.PairContext(parts.first, left)).takeLast(contextPolicy.maximumPreviousPairs.coerceAtLeast(1))
                        val right = generate(parts.second, nextContext, depth + 1)
                        return "$left\n$right".also {
                            if (it.length > TranslationPlan.MAX_CUE_CHARACTERS)
                                throw TranslationFailure(TranslationFailure.Reason.RESPONSE)
                        }
                    }
                    throw failure
                }
            }
            return listOf(generate(source, history, 0))
        } catch (failure: TranslationFailure) { throw failure }
          catch (_: LocalGgufModelBusyException) { throw TranslationFailure(TranslationFailure.Reason.LOCAL_BUSY) }
          catch (_: LocalGgufModelMissingException) { throw TranslationFailure(TranslationFailure.Reason.LOCAL_MODEL_MISSING) }
          catch (_: LocalGgufModelChangedException) { throw TranslationFailure(TranslationFailure.Reason.LOCAL_MODEL_CHANGED) }
          catch (failure: LocalGgufRuntime.Failure) {
            if (failure.code == LocalGgufRuntime.Code.CANCELLED || closed.get() || cancelled()) throw TranslationCancelledException()
            throw TranslationFailure(when (failure.code) {
                LocalGgufRuntime.Code.BUSY -> TranslationFailure.Reason.LOCAL_BUSY
                LocalGgufRuntime.Code.MODEL -> TranslationFailure.Reason.LOCAL_MODEL
                LocalGgufRuntime.Code.BACKEND -> TranslationFailure.Reason.LOCAL_BACKEND
                LocalGgufRuntime.Code.TEMPLATE -> TranslationFailure.Reason.LOCAL_TEMPLATE
                LocalGgufRuntime.Code.MEMORY -> TranslationFailure.Reason.LOCAL_MEMORY
                LocalGgufRuntime.Code.CONTEXT, LocalGgufRuntime.Code.INPUT_TOO_LONG -> TranslationFailure.Reason.LOCAL_CONTEXT
                LocalGgufRuntime.Code.OUTPUT_LIMIT -> TranslationFailure.Reason.LOCAL_TRUNCATED
                LocalGgufRuntime.Code.DIED, LocalGgufRuntime.Code.TIMEOUT -> TranslationFailure.Reason.LOCAL_PROCESS
                else -> TranslationFailure.Reason.RESPONSE
            }, failure.diagnostic)
        } catch (failure: TranslationCancelledException) { throw failure }
          catch (_: IOException) { throw TranslationFailure(TranslationFailure.Reason.LOCAL_MODEL) }
          catch (_: Exception) { throw TranslationFailure(TranslationFailure.Reason.LOCAL_TRANSLATION) }
        finally {
            working.set(false)
            if (closed.get()) releaseLease()
        }
    }

    private fun open(cancelled: () -> Boolean): LocalGgufBackend {
        backend?.let { return it }
        checkCancelled(cancelled)
        retiredBackend?.let { retired ->
            retired.closeAndAwait({ closed.get() || cancelled() })
            synchronized(state) { if (retiredBackend === retired) retiredBackend = null }
        }
        checkCancelled(cancelled)
        val acquired = store.acquire(model)
        try { LocalGgufProfiles.preflight(model, profile) }
        catch (failure: Exception) { acquired.close(); throw failure }
        val created = try { backendFactory() } catch (failure: Exception) { acquired.close(); throw failure }
        synchronized(state) {
            if (closed.get()) {
                created.close(); acquired.close(); throw TranslationCancelledException()
            }
            lease = acquired
            backend = created
        }
        try {
            val metadata = created.open(model.file, threads, CONTEXT_TOKENS, { closed.get() || cancelled() }, runningMode)
            checkCancelled(cancelled)
            family = LocalGgufProfiles.select(profile, metadata.architecture, metadata.name, model.sha256, model.metadata,
                chatTemplateAvailable = metadata.chatTemplateAvailable)
            openPerformance = metadata.performance
            if (metadata.performance.backend == "CPU") runningMode = "CPU"
            measured(metadata.performance.copy(phase = "load"))
            return created
        } catch (failure: Exception) {
            // Keep this live session available to retryCpu's unloading barrier.
            if (failure is LocalGgufRuntime.Failure && canRetryCpu(failure, cancelled)) throw failure
            synchronized(state) {
                if (backend === created) backend = null
                retiredBackend = created
            }
            runCatching { created.close() }
            releaseLease()
            throw failure
        }
    }

    private fun openWithFallback(cancelled: () -> Boolean): LocalGgufBackend = try { open(cancelled) }
        catch (failure: LocalGgufRuntime.Failure) {
            if (canRetryCpu(failure, cancelled)) retryCpu(failure, cancelled) else throw failure
        }

    private fun canRetryCpu(failure: LocalGgufRuntime.Failure, cancelled: () -> Boolean): Boolean =
        executionMode == "AUTO" && runningMode != "CPU" && !cpuFallbackAttempted && !closed.get() && !cancelled() &&
            !Thread.currentThread().isInterrupted && failure.code in setOf(LocalGgufRuntime.Code.BACKEND,
                LocalGgufRuntime.Code.DIED, LocalGgufRuntime.Code.TIMEOUT)

    private fun retryCpu(failure: LocalGgufRuntime.Failure, cancelled: () -> Boolean): LocalGgufBackend {
        cpuFallbackAttempted = true
        fallbackMessage = when (failure.code) {
            LocalGgufRuntime.Code.BACKEND -> "Le calcul automatique accéléré n’a pas pu être exécuté. Nouvelle tentative sur CPU."
            LocalGgufRuntime.Code.TIMEOUT -> "Le calcul automatique a dépassé son délai. Nouvelle tentative sur CPU."
            else -> "Le processus du calcul automatique s’est arrêté. Nouvelle tentative sur CPU."
        }
        fallbackDiagnostic = failure.diagnostic
        measured(LocalTranslationPerformance(phase = "fallback", requestedMode = executionMode))
        val previous = backend ?: retiredBackend
        // A returned cancellation is not proof of memory release. Wait for the
        // service's native destruction acknowledgement before creating CPU state.
        previous?.closeAndAwait({ closed.get() || cancelled() })
        synchronized(state) {
            if (backend === previous) backend = null
            if (retiredBackend === previous) retiredBackend = null
            family = null
        }
        releaseLease()
        runningMode = "CPU"
        checkCancelled(cancelled)
        return open(cancelled)
    }

    /** Native cancellation and Binder unbinding are asynchronous and never wait for a worker. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        backend?.let { runCatching { it.cancel() }; runCatching { it.close() } }
        retiredBackend?.let { runCatching { it.cancel() }; runCatching { it.close() } }
        if (!working.get()) releaseLease()
    }

    /** Benchmark workers use the native acknowledgement before opening another backend. */
    fun closeAndAwait(cancelled: () -> Boolean = { false }, timeoutMs: Long = 10_000) {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { "Model unloading must run on a worker" }
        if (working.get()) throw LocalGgufRuntime.Failure(LocalGgufRuntime.Code.BUSY)
        closed.set(true)
        if (unloadAcknowledged.get()) return
        val active = listOfNotNull(backend, retiredBackend).distinct()
        try { active.forEach { it.closeAndAwait(cancelled, timeoutMs) }; unloadAcknowledged.set(true) }
        finally {
            active.forEach { runCatching { it.close() } }
            if (!working.get()) releaseLease()
        }
    }

    private fun releaseLease() {
        val held = synchronized(state) { lease.also { lease = null } }
        runCatching { held?.close() }
    }
    private fun checkCancelled(cancelled: () -> Boolean) {
        if (closed.get() || cancelled() || Thread.currentThread().isInterrupted) throw TranslationCancelledException()
    }

    companion object {
        const val CONTEXT_TOKENS = 2048
        const val OUTPUT_TOKENS = 512
        const val MAX_LOCAL_CONTEXT_CHARACTERS = 2000
        private const val MAX_REQUESTS_PER_CUE = 1024

        fun validateSettings(threads: Int, context: String, executionMode: String = "AUTO") {
            if (threads !in 1..8 || context.length > MAX_LOCAL_CONTEXT_CHARACTERS || !validText(context) ||
                LocalGgufPrompts.hasControlTokens(context) || executionMode !in setOf("AUTO", "CPU", "OPENCL"))
                throw TranslationFailure(TranslationFailure.Reason.CONFIGURATION)
        }
        fun validateLanguages(source: String, target: String = "fr") {
            LocalGgufPrompts.language(source)
            LocalGgufPrompts.language(target)
        }
        fun identity(model: LocalGgufModel, threads: Int = 4, context: String = "", profile: LocalGgufProfile = LocalGgufProfile.AUTO,
            executionMode: String = "AUTO", contextPolicy: LocalGgufContextPolicy = LocalGgufContextPolicy.RECENT_TWO): String {
            validateSettings(threads, context, executionMode)
            val input = "local-gguf-text-v4\n${model.sha256}\n${LocalModelProbe.REVISION}\n$threads\n$CONTEXT_TOKENS\n$OUTPUT_TOKENS\n${profile.id}\n$executionMode\n${contextPolicy.id}\n$context"
            val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
            return "local-gguf-v4-$digest"
        }
        internal fun validText(text: String): Boolean {
            var index = 0
            while (index < text.length) {
                val value = text[index]
                if (value == '\u0000' || value.code < 32 && value !in "\n\r\t") return false
                if (Character.isHighSurrogate(value)) {
                    if (index + 1 >= text.length || !Character.isLowSurrogate(text[index + 1])) return false
                    index += 2
                } else {
                    if (Character.isLowSurrogate(value)) return false
                    index++
                }
            }
            return true
        }
        /** Every input character belongs to exactly one child; surrogate pairs stay whole. */
        internal fun split(text: String): Pair<String, String>? {
            val count = text.codePointCount(0, text.length)
            if (count < 64) return null
            val midpoint = text.offsetByCodePoints(0, count / 2)
            val minimum = text.offsetByCodePoints(0, count / 4)
            val maximum = text.offsetByCodePoints(0, count * 3 / 4)
            var index = midpoint
            while (index > minimum && !text[index - 1].isWhitespace() && text[index - 1] !in ".!?。！？；;") index--
            if (index == minimum) {
                index = midpoint
                while (index < maximum && !text[index - 1].isWhitespace() && text[index - 1] !in ".!?。！？；;") index++
                if (index == maximum) index = midpoint
            }
            if (index > 0 && index < text.length && Character.isLowSurrogate(text[index])) index--
            if (index <= 0 || index >= text.length) return null
            return text.substring(0, index) to text.substring(index)
        }
    }
}

/**
 * Text-only family templates. TranslateGemma's default task framing follows
 * models/templates/google-translategemma-4b-it.jinja at the pinned llama.cpp
 * revision; content is kept verbatim instead of applying Jinja's whitespace trim.
 * Personal background is an application extension outside the source text and
 * is repeated consistently on historical turns. It is not an official schema
 * field and does not guarantee better linguistic fidelity.
 */
internal object LocalGgufPrompts {
    enum class Family(val usesChatTemplate: Boolean = false) {
        TRANSLATE_GEMMA, HY_MT, INDEX_TRANSLATE, MODEL_TEMPLATE(true)
    }
    data class PairContext(val source: String, val translated: String)
    data class Language(val code: String, val name: String)
    private val ordinaryLanguages = Locale.getISOLanguages().toSet()
    private val regions = Locale.getISOCountries().toSet()
    private val additionalNames = mapOf("yue" to "Cantonese", "fil" to "Filipino")
    private val code = Regex("[a-zA-Z]{2,3}(?:[-_][a-zA-Z0-9]{2,8}){0,2}")
    // Native tokenization must parse wrapper specials. The same spellings in
    // content must fail visibly rather than acquire structural meaning. These
    // cover the Gemma text/image framing and the Hy-MT tokenizer controls.
    private val controls = listOf("<bos>", "<eos>", "<pad>", "<unk>", "<mask>",
        "<start_of_turn>", "<end_of_turn>", "<start_of_image>", "<end_of_image>", "<image_soft_token>",
        "<|endoftext|>", "<|startoftext|>", "<|bos|>", "<|eos|>", "<|pad|>",
        "<|im_start|>", "<|im_end|>", "<|im_sep|>", "<|object_ref_start|>", "<|object_ref_end|>",
        "<|box_start|>", "<|box_end|>", "<|quad_start|>", "<|quad_end|>", "<|vision_start|>",
        "<|vision_end|>", "<|vision_pad|>", "<|image_pad|>", "<|video_pad|>", "<tool_call>", "</tool_call>",
        "<|fim_prefix|>", "<|fim_middle|>", "<|fim_suffix|>", "<|fim_pad|>", "<|repo_name|>", "<|file_sep|>",
        "<tool_response>", "</tool_response>", "<think>", "</think>", "<|audio_start|>", "<|audio_end|>",
        "<tts_pad>", "<tts_text_bos>", "<tts_text_eod>", "<tts_text_bos_single>", "<|audio_pad|>")
    private val numberedControls = Regex("<\\|extra_[0-9]+\\|>|<unused[0-9]+>")
    private val hyNames = mapOf("zh" to "Chinese", "en" to "English", "fr" to "French", "pt" to "Portuguese",
        "es" to "Spanish", "ja" to "Japanese", "tr" to "Turkish", "ru" to "Russian", "ar" to "Arabic",
        "ko" to "Korean", "th" to "Thai", "it" to "Italian", "de" to "German", "vi" to "Vietnamese",
        "ms" to "Malay", "id" to "Indonesian", "tl" to "Filipino", "hi" to "Hindi", "zh-Hant" to "Traditional Chinese",
        "pl" to "Polish", "cs" to "Czech", "nl" to "Dutch", "km" to "Khmer", "my" to "Burmese",
        "fa" to "Persian", "gu" to "Gujarati", "ur" to "Urdu", "te" to "Telugu", "mr" to "Marathi",
        "he" to "Hebrew", "bn" to "Bengali", "ta" to "Tamil", "uk" to "Ukrainian", "bo" to "Tibetan",
        "kk" to "Kazakh", "mn" to "Mongolian", "ug" to "Uyghur", "yue" to "Cantonese")

    fun family(name: String, architecture: String): Family {
        return LocalGgufProfiles.select(LocalGgufProfile.AUTO, architecture, name, "")
    }
    fun hasControlTokens(text: String): Boolean = controls.any { it in text } || numberedControls.containsMatchIn(text)
    fun language(value: String): Language {
        if (!code.matches(value)) throw TranslationFailure(TranslationFailure.Reason.LANGUAGE)
        val locale = Locale.forLanguageTag(value.replace('_', '-'))
        val base = locale.language
        if (base !in ordinaryLanguages && base !in additionalNames) throw TranslationFailure(TranslationFailure.Reason.LANGUAGE)
        return Language(locale.toLanguageTag(), additionalNames[base] ?: locale.getDisplayLanguage(Locale.ENGLISH))
    }
    fun validateLanguage(family: Family, value: String) {
        val language = language(value)
        if (family == Family.TRANSLATE_GEMMA) {
            // This integration currently supports ISO 639-1 and optional
            // ISO 3166 alpha-2 regions. Some script tags accepted upstream
            // still require explicit language-name mapping here.
            if (!Regex("[a-zA-Z]{2}(?:[-_][a-zA-Z]{2})?").matches(value))
                throw TranslationFailure(TranslationFailure.Reason.LANGUAGE)
            val locale = Locale.forLanguageTag(value.replace('_', '-'))
            if (locale.language !in ordinaryLanguages || locale.country.isNotEmpty() && locale.country !in regions)
                throw TranslationFailure(TranslationFailure.Reason.LANGUAGE)
        }
        if (family == Family.HY_MT && language.code !in hyNames)
            throw TranslationFailure(TranslationFailure.Reason.LANGUAGE)
        if (family == Family.INDEX_TRANSLATE) LocalGgufExtendedPrompts.indexLanguage(value)
    }
    fun render(family: Family, text: String, source: String, target: String,
        previous: List<PairContext>, context: String): String {
        if (hasControlTokens(text) || hasControlTokens(context) ||
            previous.any { hasControlTokens(it.source) || hasControlTokens(it.translated) })
            throw TranslationFailure(TranslationFailure.Reason.LOCAL_CONTEXT)
        val from = language(source)
        val to = language(target)
        validateLanguage(family, source); validateLanguage(family, target)
        return when (family) {
            Family.INDEX_TRANSLATE -> LocalGgufExtendedPrompts.index(text, source, target, previous, context)
            Family.MODEL_TEMPLATE -> LocalGgufExtendedPrompts.generic(text, from, to, previous, context)
            Family.TRANSLATE_GEMMA -> buildString {
                append("<bos>")
                previous.forEach { pair ->
                    append(gemmaTurn(pair.source, from, to, context))
                    append(pair.translated); append("<end_of_turn>\n")
                }
                append(gemmaTurn(text, from, to, context))
            }
            Family.HY_MT -> buildString {
                validateLanguage(family, source); validateLanguage(family, target)
                append("<|startoftext|>")
                if (context.isNotBlank() || previous.isNotEmpty()) {
                    append("[Background Information]\n")
                    if (context.isNotBlank()) { append(context); append('\n') }
                    previous.forEach { pair ->
                        append("Previous source: "); append(pair.source)
                        append("\nPrevious translation: "); append(pair.translated); append('\n')
                    }
                    append("Please translate the following text into "); append(hyNames.getValue(to.code))
                    append(", taking the provided background information into consideration. ")
                    append("Only output the translated text, without additional explanation.\n[Source Text]\n")
                } else {
                    append("Translate the following text into "); append(hyNames.getValue(to.code))
                    append(". Note that you should only output the translated result without any additional explanation:\n")
                }
                append(text); append("<|extra_0|>")
            }
        }
    }
    private fun gemmaTurn(text: String, from: Language, to: Language, context: String): String = buildString {
        append("<start_of_turn>user\nYou are a professional "); append(from.name); append(" ("); append(from.code)
        append(") to "); append(to.name); append(" ("); append(to.code); append(") translator. ")
        append("Your goal is to accurately convey the meaning and nuances of the original "); append(from.name)
        append(" text while adhering to "); append(to.name); append(" grammar, vocabulary, and cultural sensitivities.\n")
        append("Produce only the "); append(to.name); append(" translation, without any additional explanations or commentary. ")
        if (context.isNotBlank()) {
            append("Use this background only to interpret the text; do not translate or reproduce it: ")
            append(context); append('\n')
        }
        append("Please translate the following "); append(from.name); append(" text into "); append(to.name)
        append(":\n\n\n"); append(text); append("<end_of_turn>\n<start_of_turn>model\n")
    }
}

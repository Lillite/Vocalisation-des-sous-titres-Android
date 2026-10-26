package fr.soustitresvoix.core

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/** Final narration text, occurrence identity and original media timeline. Never copied into diagnostics. */
data class OfflineSpeechCue(val identity: String, val startMs: Long, val endMs: Long, val text: String)
data class OfflineSpeechWork(val mediaKey: String, val title: String, val totalDurationMs: Long,
    val sourceComplete: Boolean, val voiceFingerprint: String, val occurrences: Int, val bytes: Long)
data class OfflineSpeechStatus(val mediaKey: String = "", val title: String = "",
    val expectedSegments: Int = 0, val readySegments: Int = 0, val readyThroughMs: Long = 0,
    val totalDurationMs: Long = 0, val ready: Boolean = false, val estimatedBytes: Long = 0,
    val availableBytes: Long = 0, val sourceComplete: Boolean = false,
    val message: String = "Aucune œuvre sélectionnée pour la conservation hors ligne", val additionalBytes: Long = 0)

/**
 * A user-pinned work is durable private data, separate from the disposable LRU.
 * All calls run on the owner's I/O executor. A plan is replaced atomically and
 * WAVs are validated by duration and SHA-256 before being declared available.
 * No eviction occurs here; explicit per-work removal is the only deletion.
 */
class OfflineSpeechLibrary(private val directory: File, private val reserveBytes: Long = 64L shl 20) {
    data class Segment(val key: String, val text: String)
    data class Plan(val mediaKey: String, val title: String, val totalDurationMs: Long,
        val sourceComplete: Boolean, val configuration: String, val languageTag: String?,
        val cues: List<OfflineSpeechCue>) {
        val segments: List<Segment> get() = cues.asSequence().flatMap { SpeechTools.segments(it.text, languageTag).asSequence() }
            .distinct().map { Segment(SpeechTools.key(configuration, it), it) }.toList()
    }
    companion object {
        private const val VERSION = 1
        private const val MAX_CUES = 100_000
        private const val MAX_CHARACTERS = 12_000_000
        private const val MAX_TOTAL_MS = 7L * 24 * 60 * 60 * 1000
        private val safeKey = Regex("[a-f0-9]{64}")
        fun workKey(mediaKey: String) = SpeechTools.key("offline-work-v1", mediaKey)
    }
    private val verifiedEntries = LinkedHashMap<String, SpeechDiskCache.Entry>()
    private var cachedPlan: Plan? = null
    private var cachedSegments: List<Segment> = emptyList()
    private fun workDirectory(key: String): File {
        require(safeKey.matches(key)) { "Identité de conservation invalide" }
        return File(directory, key)
    }
    private fun audioDirectory(key: String) = File(workDirectory(key), "voices")
    private fun requireSpace(bytes: Long) {
        directory.mkdirs()
        if (bytes < 0 || directory.usableSpace - reserveBytes.coerceAtLeast(0) < bytes)
            throw IOException("Espace insuffisant pour conserver les voix ; libère de la place puis reprends")
    }
    private fun atomic(file: File, writer: (DataOutputStream) -> Unit) {
        file.parentFile?.mkdirs()
        val part = File.createTempFile("offline-", ".part", file.parentFile)
        try {
            FileOutputStream(part).use { raw ->
                val output = DataOutputStream(BufferedOutputStream(raw))
                writer(output); output.flush(); raw.fd.sync()
            }
            if (!part.renameTo(file)) throw IOException("Conservation locale impossible")
        } finally { part.delete() }
    }
    private fun DataOutputStream.string(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8); writeInt(bytes.size); write(bytes)
    }
    private fun DataInputStream.string(maximum: Int): String {
        val size = readInt(); require(size in 0..maximum)
        val bytes = ByteArray(size); readFully(bytes)
        return Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }
    private fun candidate(mediaKey: String, title: String, cues: List<OfflineSpeechCue>, totalDurationMs: Long,
        completeSource: Boolean, configuration: String, languageTag: String? = null): Plan {
        require(mediaKey.isNotBlank() && mediaKey.length <= 8192 && configuration.isNotBlank() && configuration.length <= 32_768)
        require(title.length <= 1024 && totalDurationMs in 1..MAX_TOTAL_MS && cues.size <= MAX_CUES)
        require(languageTag == null || languageTag.length <= 64)
        require(cues.sumOf { it.text.length.toLong() } <= MAX_CHARACTERS && cues.sumOf { it.identity.length.toLong() } <= MAX_CHARACTERS)
        require(cues.all { it.identity.isNotBlank() && it.identity.length <= 32_768 && it.text.length <= 24_000 &&
            it.startMs in 0..totalDurationMs && it.endMs in it.startMs..totalDurationMs })
        require(cues.map { it.identity }.distinct().size == cues.size) { "Identités de sous-titres dupliquées" }
        val key = workKey(mediaKey)
        val plan = Plan(key, title, totalDurationMs, completeSource, configuration, languageTag,
            cues.sortedWith(compareBy<OfflineSpeechCue> { it.startMs }.thenBy { it.endMs }.thenBy { it.identity }))
        require(plan.segments.size <= MAX_CUES) { "Trop de segments pour cette conservation" }
        return plan
    }
    /** A pure preview of required storage; raw subtitle data is not persisted. */
    fun preview(mediaKey: String, title: String, cues: List<OfflineSpeechCue>, totalDurationMs: Long,
        completeSource: Boolean, configuration: String, languageTag: String? = null): OfflineSpeechStatus {
        val candidate = candidate(mediaKey, title, cues, totalDurationMs, completeSource, configuration, languageTag)
        val stored = plan(candidate.mediaKey)
        val same = stored != null && planFingerprint(stored) == planFingerprint(candidate)
        val status = statusFor(candidate, deepValidation = true, certify = same)
        return if (same) status else status.copy(ready = false, message =
            "Estimation préalable : ${status.readySegments}/${status.expectedSegments} voix déjà présentes. " +
                "Confirme Conserver pour enregistrer et vérifier cette œuvre.")
    }
    fun configure(mediaKey: String, title: String, cues: List<OfflineSpeechCue>, totalDurationMs: Long,
        completeSource: Boolean, configuration: String, languageTag: String? = null): Plan {
        val plan = candidate(mediaKey, title, cues, totalDurationMs, completeSource, configuration, languageTag)
        val key = plan.mediaKey
        requireSpace((cues.sumOf { it.text.toByteArray(Charsets.UTF_8).size.toLong() } +
            cues.sumOf { it.identity.toByteArray(Charsets.UTF_8).size.toLong() } + 65536).coerceAtLeast(65536))
        val dir = workDirectory(key)
        if (!dir.isDirectory && (directory.listFiles()?.count { it.isDirectory } ?: 0) >= 256)
            throw IOException("Limite de 256 œuvres conservées atteinte")
        // A new meta revision is published last; mixed or missing components
        // cannot pass the shared plan fingerprint check when reopened.
        val fingerprint = planFingerprint(plan)
        atomic(File(dir, "texts.bin")) { out ->
            out.writeInt(VERSION); out.string(fingerprint); out.writeInt(plan.cues.size)
            plan.cues.forEach { out.string(it.text) }
        }
        atomic(File(dir, "index.bin")) { out ->
            out.writeInt(VERSION); out.string(fingerprint); out.writeInt(plan.cues.size)
            plan.cues.forEach { out.string(it.identity); out.writeLong(it.startMs); out.writeLong(it.endMs) }
        }
        atomic(File(dir, "plan.bin")) { out ->
            out.writeInt(VERSION); out.string(fingerprint); out.string(plan.title); out.writeLong(plan.totalDurationMs)
            out.writeBoolean(plan.sourceComplete); out.string(plan.configuration); out.string(plan.languageTag.orEmpty())
            out.writeInt(plan.cues.size)
        }
        cachedPlan = plan; cachedSegments = plan.segments
        return plan
    }
    private fun planFingerprint(plan: Plan): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(value: String) { val bytes = value.toByteArray(Charsets.UTF_8); digest.update("${bytes.size}:".toByteArray()); digest.update(bytes) }
        add(plan.configuration); add(plan.languageTag.orEmpty()); add(plan.totalDurationMs.toString()); add(plan.sourceComplete.toString())
        plan.cues.forEach { add(it.identity); add(it.startMs.toString()); add(it.endMs.toString()); add(it.text) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun plan(key: String): Plan? = runCatching {
        val dir = workDirectory(key)
        val meta = File(dir, "plan.bin"); val texts = File(dir, "texts.bin"); val index = File(dir, "index.bin")
        if (meta.length() !in 1..262_144 || texts.length() !in 1..(4L * MAX_CHARACTERS + 1_000_000) ||
            index.length() !in 1..(4L * MAX_CHARACTERS + 4_000_000)) return null
        DataInputStream(BufferedInputStream(meta.inputStream())).use { input ->
            require(input.readInt() == VERSION)
            val fingerprint = input.string(64); val title = input.string(4096); val duration = input.readLong()
            require(duration in 1..MAX_TOTAL_MS)
            val complete = input.readBoolean(); val configuration = input.string(131_072); val language = input.string(256).ifEmpty { null }
            val count = input.readInt(); require(count in 0..MAX_CUES && input.read() == -1)
            DataInputStream(BufferedInputStream(texts.inputStream())).use { t ->
                DataInputStream(BufferedInputStream(index.inputStream())).use { i ->
                    require(t.readInt() == VERSION && t.string(64) == fingerprint && t.readInt() == count)
                    require(i.readInt() == VERSION && i.string(64) == fingerprint && i.readInt() == count)
                    var characters = 0L
                    val cues = List(count) {
                        val identity = i.string(131_072); val start = i.readLong(); val end = i.readLong(); val text = t.string(96_000)
                        characters += text.length; require(characters <= MAX_CHARACTERS && identity.isNotBlank() && start in 0..duration && end in start..duration)
                        OfflineSpeechCue(identity, start, end, text)
                    }
                    require(t.read() == -1 && i.read() == -1 && cues.map { it.identity }.distinct().size == count)
                    Plan(key, title, duration, complete, configuration, language, cues).also { require(planFingerprint(it) == fingerprint) }
                }
            }
        }
    }.getOrNull()
    fun temporary(key: String): File {
        requireSpace(16L shl 20)
        val dir = audioDirectory(key); dir.mkdirs()
        return File.createTempFile("voice-", ".part", dir)
    }
    fun get(key: String, segmentKey: String): SpeechDiskCache.Entry? {
        require(safeKey.matches(segmentKey))
        val dir = audioDirectory(key); val file = File(dir, "$segmentKey.wav"); val sha = File(dir, "$segmentKey.sha256")
        verifiedEntries.remove("$key/$segmentKey")
        if (!file.isFile || !sha.isFile || sha.length() != 64L) return null
        val info = WavInfo.read(file) ?: return null
        if (!runCatching { sha.readText() == digest(file) }.getOrDefault(false)) return null
        return SpeechDiskCache.Entry(file, info.durationMs).also {
            verifiedEntries["$key/$segmentKey"] = it
            while (verifiedEntries.size > MAX_CUES) verifiedEntries.remove(verifiedEntries.keys.first())
        }
    }
    fun put(key: String, segmentKey: String, temporary: File): SpeechDiskCache.Entry? {
        require(safeKey.matches(segmentKey))
        val info = WavInfo.read(temporary) ?: return null
        val dir = audioDirectory(key); dir.mkdirs()
        // Use a copy so the caller may also publish the same synthesized WAV to its LRU.
        requireSpace(info.bytes)
        val part = File.createTempFile("audio-", ".part", dir)
        try {
            FileOutputStream(part).use { out -> temporary.inputStream().use { it.copyTo(out) }; out.fd.sync() }
            val sha = digest(part)
            if (!part.renameTo(File(dir, "$segmentKey.wav"))) throw IOException("Voix conservée indisponible")
            atomic(File(dir, "$segmentKey.sha256")) { it.write(sha.toByteArray(Charsets.US_ASCII)) }
        } finally { part.delete() }
        return get(key, segmentKey)
    }
    private fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input -> val bytes = ByteArray(16384); while (true) { val n = input.read(bytes); if (n < 0) break; hash.update(bytes, 0, n) } }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
    /** Interim progress uses entries already verified in this run; only deep validation can declare ready. */
    fun status(key: String, deepValidation: Boolean = true): OfflineSpeechStatus {
        val plan = (if (deepValidation) plan(key) else cachedPlan?.takeIf { it.mediaKey == key } ?: plan(key)) ?: return OfflineSpeechStatus(mediaKey = key, availableBytes = directory.usableSpace,
            message = "Conservation incomplète : textes ou index absents/incohérents ; relance la préparation")
        return statusFor(plan, deepValidation)
    }
    private fun statusFor(plan: Plan, deepValidation: Boolean, certify: Boolean = true): OfflineSpeechStatus {
        val key = plan.mediaKey
        if (cachedPlan != plan) { cachedPlan = plan; cachedSegments = plan.segments }
        val required = cachedSegments
        val measured = required.associate { segment -> segment.key to if (deepValidation) get(key, segment.key)
            else verifiedEntries["$key/${segment.key}"]?.takeIf { it.file.isFile } }
        val ready = measured.filterValues { it != null }.keys
        val firstMissing = plan.cues.firstOrNull { cue -> SpeechTools.segments(cue.text, plan.languageTag).any { SpeechTools.key(plan.configuration, it) !in ready } }
        val readyThrough = firstMissing?.startMs ?: if (plan.sourceComplete) plan.totalDurationMs else plan.cues.maxOfOrNull { it.endMs } ?: 0
        val knownBytes = measured.values.filterNotNull().sumOf { it.file.length() }
        val knownDuration = measured.values.filterNotNull().sumOf { it.durationMs }
        val bytesPerMs = if (knownDuration > 0) maxOf(48.0, knownBytes.toDouble() / knownDuration) else 96.0
        val durationModel = SpeechDurationModel()
        val missingEstimate = required.filter { it.key !in ready }.sumOf { durationModel.estimate(it.text, 1f) }
        val additional = (missingEstimate * bytesPerMs * 1.15 + (required.size - ready.size) * 512.0).toLong()
        val estimate = knownBytes + additional
        val complete = certify && deepValidation && plan.sourceComplete && ready.size == required.size
        return OfflineSpeechStatus(key, plan.title, required.size, ready.size, readyThrough, plan.totalDurationMs,
            complete, estimate, if (directory.exists()) directory.usableSpace else directory.parentFile?.usableSpace ?: 0, plan.sourceComplete,
            if (complete) "Prêt hors ligne : toutes les voix requises ont été vérifiées"
            else if (!plan.sourceComplete) "Source/traduction partielle : prêt hors ligne impossible avant validation complète"
            else "${ready.size}/${required.size} segments vérifiés ; préparé jusqu’à ${readyThrough / 60_000} min sur ${plan.totalDurationMs / 60_000}", additional)
    }
    fun list(): List<OfflineSpeechWork> = directory.listFiles()?.filter { it.isDirectory && safeKey.matches(it.name) }
        ?.take(256)?.mapNotNull { dir ->
            runCatching {
                val meta = File(dir, "plan.bin"); require(meta.length() in 1..262_144)
                DataInputStream(BufferedInputStream(meta.inputStream())).use { input ->
                    require(input.readInt() == VERSION); input.string(64)
                    val title = input.string(4096); val duration = input.readLong(); require(duration in 1..MAX_TOTAL_MS)
                    val complete = input.readBoolean(); val configuration = input.string(131_072); input.string(256)
                    val count = input.readInt(); require(count in 0..MAX_CUES && input.read() == -1)
                    OfflineSpeechWork(dir.name, title, duration, complete, SpeechTools.key("voice-configuration", configuration),
                        count, dir.walkTopDown().filter { it.isFile }.sumOf { it.length() })
                }
            }.getOrNull()
        }?.sortedBy { it.title } ?: emptyList()
    fun removeVoices(key: String) { audioDirectory(key).deleteRecursively(); verifiedEntries.keys.removeAll { it.startsWith("$key/") } }
    fun removeTexts(key: String) { File(workDirectory(key), "texts.bin").delete(); if (cachedPlan?.mediaKey == key) cachedPlan = null }
    fun removeIndex(key: String) { File(workDirectory(key), "index.bin").delete(); if (cachedPlan?.mediaKey == key) cachedPlan = null }
}

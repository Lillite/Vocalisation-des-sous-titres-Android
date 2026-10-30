package fr.soustitresvoix.player

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

/** Publisher header facts only. These fixtures contain no usable model weights. */
class LocalGgufProfilesTest {
    private fun header(fields: List<Pair<String, String>>): ByteArray {
        val output = ByteArrayOutputStream()
        fun number(value: Long, width: Int) { output.write(ByteBuffer.allocate(width).order(ByteOrder.LITTLE_ENDIAN)
            .also { if (width == 4) it.putInt(value.toInt()) else it.putLong(value) }.array()) }
        fun text(value: String) { val bytes = value.toByteArray(Charsets.UTF_8); number(bytes.size.toLong(), 8); output.write(bytes) }
        output.write(byteArrayOf(71, 71, 85, 70)); number(3, 4); number(354, 8); number(fields.size.toLong(), 8)
        fields.forEach { (key, value) -> text(key); number(8, 4); text(value) }
        return output.toByteArray()
    }
    private fun publisherHeader() = header(listOf("general.architecture" to "hunyuan-dense",
        "general.type" to "model", "general.name" to "Global_Step_560", "general.size_label" to "7.5B"))
    private fun rejects(reason: TranslationFailure.Reason = TranslationFailure.Reason.LOCAL_PROFILE, block: () -> Unit) {
        try { block(); fail("Expected an explicit profile/template refusal") }
        catch (failure: TranslationFailure) { assertEquals(reason, failure.reason) }
    }

    @Test fun actualOfficialHyMt2NameIsRecognizedByVerifiedPublisherIdentityAndArchitecture() {
        val metadata = LocalGgufMetadataReader.parse(publisherHeader())
        assertEquals("Global_Step_560", metadata.name)
        assertEquals("hunyuan-dense", metadata.architecture)
        for (digest in listOf(LocalGgufProfiles.OFFICIAL_HY_MT2_Q8, LocalGgufProfiles.OFFICIAL_HY_MT2_Q6,
            LocalGgufProfiles.OFFICIAL_HY_MT2_Q4))
            assertEquals(LocalGgufPrompts.Family.HY_MT,
                LocalGgufProfiles.select(LocalGgufProfile.AUTO, metadata.architecture, metadata.name, digest, metadata))
    }

    @Test fun identicalGenericHunyuanHeaderWithoutKnownIdentityNeedsExplicitProfile() {
        val metadata = LocalGgufMetadataReader.parse(publisherHeader())
        rejects(TranslationFailure.Reason.LOCAL_TEMPLATE) { LocalGgufProfiles.select(LocalGgufProfile.AUTO, metadata.architecture, metadata.name, "0".repeat(64), metadata) }
        assertEquals(LocalGgufPrompts.Family.HY_MT,
            LocalGgufProfiles.select(LocalGgufProfile.HY_MT, metadata.architecture, metadata.name, "0".repeat(64), metadata))
    }

    @Test fun explicitProfileAndKnownDigestStillCannotOverrideIncompatibleArchitecture() {
        rejects { LocalGgufProfiles.select(LocalGgufProfile.HY_MT, "gemma3", "Global_Step_560", LocalGgufProfiles.OFFICIAL_HY_MT2_Q8) }
        rejects { LocalGgufProfiles.select(LocalGgufProfile.TRANSLATE_GEMMA, "hunyuan-dense", "TranslateGemma", "0".repeat(64)) }
        rejects { LocalGgufProfiles.select(LocalGgufProfile.AUTO, "llama", "Global_Step_560", LocalGgufProfiles.OFFICIAL_HY_MT2_Q8) }
    }

    @Test fun verifiedTranslationMetadataHintsWorkWithoutAnInformativeDisplayName() {
        assertEquals(LocalGgufPrompts.Family.HY_MT,
            LocalGgufProfiles.select(LocalGgufProfile.AUTO, "hunyuan-dense", "Global_Step_560", "0".repeat(64),
                LocalGgufMetadata(basename = "Tencent HY-MT2")))
        assertEquals(LocalGgufPrompts.Family.TRANSLATE_GEMMA,
            LocalGgufProfiles.select(LocalGgufProfile.AUTO, "gemma3", "Checkpoint", "0".repeat(64),
                LocalGgufMetadata(finetune = "TranslateGemma IT")))
        rejects(TranslationFailure.Reason.LOCAL_TEMPLATE) { LocalGgufProfiles.select(LocalGgufProfile.AUTO, "hunyuan-dense", "Hunyuan general chat", "0".repeat(64)) }
    }

    @Test fun boundedMetadataParserKeepsEarlyHintsWhenTokenizerDataExceedsItsBudget() {
        val fields = listOf("general.architecture" to "hunyuan-dense", "general.name" to "Global_Step_560")
        val valid = header(fields)
        val withExtraEntry = valid.copyOf().also {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putLong(16, 3)
        }
        val tokenizerKey = "tokenizer.ggml.tokens".toByteArray(Charsets.UTF_8)
        val suffix = ByteBuffer.allocate(8 + tokenizerKey.size + 4 + 4 + 8).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(tokenizerKey.size.toLong()).put(tokenizerKey).putInt(9).putInt(8).putLong(Long.MAX_VALUE).array()
        val metadata = LocalGgufMetadataReader.parse(withExtraEntry + suffix)
        assertEquals("hunyuan-dense", metadata.architecture)
        assertEquals("Global_Step_560", metadata.name)
    }

    @Test fun corruptOrUnsupportedHeadersDoNotProduceTrustedMetadata() {
        val wrongMagic = publisherHeader().also { it[0] = 0 }
        val wrongVersion = publisherHeader().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 4) }
        for (bytes in listOf(ByteArray(0), ByteArray(23), wrongMagic, wrongVersion))
            assertEquals(LocalGgufMetadata(), LocalGgufMetadataReader.parse(bytes))
    }
    @Test fun officialIndexGenericNameIsRecognizedByEachVerifiedQuantizationIdentity() {
        val metadata = LocalGgufMetadata(architecture = "qwen35", name = "9b", finetune = "9b")
        for (digest in listOf(LocalGgufProfiles.OFFICIAL_INDEX_Q4, LocalGgufProfiles.OFFICIAL_INDEX_Q5,
            LocalGgufProfiles.OFFICIAL_INDEX_Q6, LocalGgufProfiles.OFFICIAL_INDEX_Q8)) {
            assertEquals(LocalGgufPrompts.Family.INDEX_TRANSLATE,
                LocalGgufProfiles.select(LocalGgufProfile.AUTO, "qwen35", "9b", digest, metadata))
            rejects { LocalGgufProfiles.select(LocalGgufProfile.AUTO, "llama", "9b", digest, metadata) }
        }
        assertEquals("5e47c636c75de6c5065f8550869a744e049451ac591a001968872d5c8b4b7898", LocalGgufProfiles.OFFICIAL_INDEX_Q6)
    }

    @Test fun architectureAloneNeverMisidentifiesAnotherQwenAsIndex() {
        val digest = "0".repeat(64)
        assertEquals(LocalGgufPrompts.Family.MODEL_TEMPLATE,
            LocalGgufProfiles.select(LocalGgufProfile.AUTO, "qwen35", "9b", digest, chatTemplateAvailable = true))
        assertEquals(LocalGgufPrompts.Family.INDEX_TRANSLATE,
            LocalGgufProfiles.select(LocalGgufProfile.AUTO, "qwen35", "Index-Translate-9B", digest))
        assertEquals(LocalGgufPrompts.Family.INDEX_TRANSLATE,
            LocalGgufProfiles.select(LocalGgufProfile.INDEX_TRANSLATE, "qwen35", "9b", digest))
        rejects { LocalGgufProfiles.select(LocalGgufProfile.INDEX_TRANSLATE, "gemma3", "Index-Translate", digest) }
    }

    @Test fun absentHeaderHintsDeferTheDecisionAndRuntimeCapabilityOverridesHeaderHints() {
        fun model(metadata: LocalGgufMetadata) = LocalGgufModel(java.io.File("synthetic-no-weights.gguf"), "0".repeat(64), 24, 0, metadata)
        assertNull(LocalGgufProfiles.preflight(model(LocalGgufMetadata()), LocalGgufProfile.AUTO))
        assertNull(LocalGgufProfiles.preflight(model(LocalGgufMetadata(architecture = "qwen35", name = "9b")), LocalGgufProfile.AUTO))
        assertNull(LocalGgufProfiles.preflight(model(LocalGgufMetadata(architecture = "llama")), LocalGgufProfile.MODEL_TEMPLATE))
        val hint = LocalGgufMetadata(architecture = "llama", chatTemplateAvailable = true)
        assertEquals(LocalGgufPrompts.Family.MODEL_TEMPLATE,
            LocalGgufProfiles.preflight(model(hint), LocalGgufProfile.AUTO))
        rejects(TranslationFailure.Reason.LOCAL_TEMPLATE) {
            LocalGgufProfiles.select(LocalGgufProfile.AUTO, "llama", "A chat model", "0".repeat(64), hint, chatTemplateAvailable = false)
        }
        assertEquals(LocalGgufPrompts.Family.MODEL_TEMPLATE,
            LocalGgufProfiles.select(LocalGgufProfile.AUTO, "llama", "A chat model", "0".repeat(64), chatTemplateAvailable = true))
    }

    @Test fun specializedProfilesStayPreferredOverGenericConversationTemplates() {
        for ((architecture, name, expected) in listOf(
            Triple("gemma3", "TranslateGemma", LocalGgufPrompts.Family.TRANSLATE_GEMMA),
            Triple("hunyuan-dense", "HY-MT2", LocalGgufPrompts.Family.HY_MT))) {
            val profile = if (expected == LocalGgufPrompts.Family.TRANSLATE_GEMMA)
                LocalGgufProfile.TRANSLATE_GEMMA else LocalGgufProfile.HY_MT
            assertEquals(expected, LocalGgufProfiles.select(profile, architecture,
                name, "0".repeat(64), chatTemplateAvailable = true))
            assertEquals(expected, LocalGgufProfiles.select(LocalGgufProfile.AUTO, architecture,
                name, "0".repeat(64), chatTemplateAvailable = true))
        }
    }

    @Test fun verifiedQwen35QuantizationIdentitySelectsNonThinkingWithoutAnInformativeName() {
        assertEquals("91898433cf5ce0a8f45516a4cc3e9343b6e01d052d01f684309098c66a326c59",
            LocalGgufProfiles.VERIFIED_QWEN35_9B_Q6)
        assertEquals(LocalGgufPrompts.Family.QWEN35_INSTRUCT,
            LocalGgufProfiles.select(LocalGgufProfile.AUTO, "qwen35", "9b", LocalGgufProfiles.VERIFIED_QWEN35_9B_Q6))
        rejects {
            LocalGgufProfiles.select(LocalGgufProfile.AUTO, "llama", "9b", LocalGgufProfiles.VERIFIED_QWEN35_9B_Q6)
        }
        assertEquals(LocalGgufPrompts.Family.QWEN35_INSTRUCT,
            LocalGgufProfiles.select(LocalGgufProfile.QWEN35_INSTRUCT, "qwen35", "A custom display name", "0".repeat(64)))
        rejects {
            LocalGgufProfiles.select(LocalGgufProfile.QWEN35_INSTRUCT, "gemma3", "Qwen3.5-9B", "0".repeat(64))
        }
    }

    @Test fun namedPostTrainedQwen35ModelsUseTheirOwnRawFramingWithoutNativeTemplateCapability() {
        for (name in listOf("Qwen3.5-0.8B", "Qwen3.5 2B", "Qwen3.5-4B-Instruct", "Qwen3.5-9B", "Qwen3.5-27B-IT"))
            assertEquals(name, LocalGgufPrompts.Family.QWEN35_INSTRUCT,
                LocalGgufProfiles.select(LocalGgufProfile.AUTO, "qwen35", name, "0".repeat(64)))
        assertEquals(LocalGgufPrompts.Family.QWEN35_INSTRUCT,
            LocalGgufProfiles.select(LocalGgufProfile.AUTO, "qwen35", "Checkpoint", "0".repeat(64),
                LocalGgufMetadata(basename = "Qwen3.5", finetune = "Instruct")))
    }

    @Test fun baseCustomAndUnidentifiedQwen35ModelsDoNotAcquireAnInstructProfileFromArchitectureAlone() {
        for ((name, hints) in listOf(
            "9b" to LocalGgufMetadata(),
            "Qwen3.5-9B-Base" to LocalGgufMetadata(),
            "Qwen3.5-9B-Custom" to LocalGgufMetadata(),
            "Qwen3.5-9B" to LocalGgufMetadata(finetune = "Custom"),
            "Qwen3.5-9B" to LocalGgufMetadata(basename = "Qwen3.5", finetune = "Base")))
            assertEquals(name, LocalGgufPrompts.Family.MODEL_TEMPLATE,
                LocalGgufProfiles.select(LocalGgufProfile.AUTO, "qwen35", name, "0".repeat(64), hints,
                    chatTemplateAvailable = true))
    }

    @Test fun indexIdentityAndNamesRetainPriorityOverGeneralQwen35Hints() {
        val hints = LocalGgufMetadata(architecture = "qwen35", basename = "Qwen3.5-9B", finetune = "Instruct")
        assertEquals(LocalGgufPrompts.Family.INDEX_TRANSLATE,
            LocalGgufProfiles.select(LocalGgufProfile.AUTO, "qwen35", "9b", LocalGgufProfiles.OFFICIAL_INDEX_Q6, hints))
        assertEquals(LocalGgufPrompts.Family.INDEX_TRANSLATE,
            LocalGgufProfiles.select(LocalGgufProfile.AUTO, "qwen35", "Index-Translate-9B", "0".repeat(64), hints))
    }

    @Test fun qwen35PreflightAndCacheIdentityRecognizeTheChangedAutomaticFramingOnly() {
        fun model(digest: String, metadata: LocalGgufMetadata) =
            LocalGgufModel(java.io.File("synthetic-no-weights.gguf"), digest, 24, 0, metadata)
        val named = model("0".repeat(64), LocalGgufMetadata(architecture = "qwen35", name = "Qwen3.5-9B"))
        val verified = model(LocalGgufProfiles.VERIFIED_QWEN35_9B_Q6, LocalGgufMetadata(architecture = "qwen35", name = "9b"))
        val unknown = model("0".repeat(64), LocalGgufMetadata(architecture = "qwen35", name = "9b"))
        val index = model(LocalGgufProfiles.OFFICIAL_INDEX_Q6, LocalGgufMetadata(architecture = "qwen35", name = "9b"))
        for (candidate in listOf(named, verified)) {
            assertEquals(LocalGgufPrompts.Family.QWEN35_INSTRUCT, LocalGgufProfiles.preflight(candidate, LocalGgufProfile.AUTO))
            assertTrue(LocalGgufProfiles.usesQwen35PromptIdentity(candidate))
            assertTrue(LocalGgufTranslationEngine.identity(candidate).startsWith("local-gguf-v5-"))
        }
        assertFalse(LocalGgufProfiles.usesQwen35PromptIdentity(unknown))
        assertFalse(LocalGgufProfiles.usesQwen35PromptIdentity(index))
        assertTrue(LocalGgufTranslationEngine.identity(index).startsWith("local-gguf-v4-"))
        assertTrue(LocalGgufTranslationEngine.identity(unknown, profile = LocalGgufProfile.QWEN35_INSTRUCT)
            .startsWith("local-gguf-v5-"))
        assertTrue(LocalGgufTranslationEngine.identity(named, profile = LocalGgufProfile.MODEL_TEMPLATE)
            .startsWith("local-gguf-v4-"))
    }

    @Test fun unknownAutomaticMetadataCannotReuseVersionFourBeforeRuntimeQwenDiscovery() {
        val digest = "0".repeat(64)
        fun model(metadata: LocalGgufMetadata, sha: String = digest) =
            LocalGgufModel(java.io.File("synthetic-no-weights.gguf"), sha, 24, 0, metadata)
        val oldInput = "local-gguf-text-v4\n$digest\n${LocalModelProbe.REVISION}\n4\n2048\n512\nauto\nAUTO\nrecent-two\n"
        val oldDigest = java.security.MessageDigest.getInstance("SHA-256").digest(oldInput.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 255) }
        for (metadata in listOf(LocalGgufMetadata(), LocalGgufMetadata(name = "Qwen3.5-9B"),
            LocalGgufMetadata(architecture = "qwen35", name = "9b"),
            LocalGgufMetadata(architecture = "qwen35", name = "Checkpoint", chatTemplateAvailable = true))) {
            val identity = LocalGgufTranslationEngine.identity(model(metadata))
            assertTrue(identity.startsWith("local-gguf-v5-"))
            assertNotEquals("local-gguf-v4-$oldDigest", identity)
        }
        // Complete non-Qwen architecture keeps the old key even when its
        // conversation template can only be inspected by the native runtime.
        assertEquals("local-gguf-v4-$oldDigest",
            LocalGgufTranslationEngine.identity(model(LocalGgufMetadata(architecture = "llama"))))
        for (known in listOf(LocalGgufProfiles.OFFICIAL_HY_MT2_Q8, LocalGgufProfiles.OFFICIAL_INDEX_Q6))
            assertTrue(LocalGgufTranslationEngine.identity(model(LocalGgufMetadata(), known)).startsWith("local-gguf-v4-"))
    }

}

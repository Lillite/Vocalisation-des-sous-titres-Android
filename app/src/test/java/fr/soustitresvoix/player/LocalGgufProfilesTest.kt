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

}

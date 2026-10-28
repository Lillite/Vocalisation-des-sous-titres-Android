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
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected an explicit profile refusal") }
        catch (failure: TranslationFailure) { assertEquals(TranslationFailure.Reason.LOCAL_PROFILE, failure.reason) }
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
        rejects { LocalGgufProfiles.select(LocalGgufProfile.AUTO, metadata.architecture, metadata.name, "0".repeat(64), metadata) }
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
        rejects { LocalGgufProfiles.select(LocalGgufProfile.AUTO, "hunyuan-dense", "Hunyuan general chat", "0".repeat(64)) }
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
}

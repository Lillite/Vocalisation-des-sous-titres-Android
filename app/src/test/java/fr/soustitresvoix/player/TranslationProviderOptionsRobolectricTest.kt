package fr.soustitresvoix.player

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TranslationProviderOptionsRobolectricTest {
    private class Transport(val response: TranslationHttpResponse) : TranslationHttpTransport {
        val posts = mutableListOf<TranslationHttpRequest>()
        val gets = mutableListOf<TranslationHttpRequest>()
        var closed = false
        var after: () -> Unit = {}
        override fun post(request: TranslationHttpRequest, cancelled: () -> Boolean): TranslationHttpResponse {
            posts += request; after(); return response
        }
        override fun get(request: TranslationHttpRequest, cancelled: () -> Boolean): TranslationHttpResponse {
            gets += request; after(); return response
        }
        override fun close() { closed = true }
    }
    private fun config(options: TranslationOptions = TranslationOptions(), key: String = "synthetic-test-key") =
        TranslationProviderConfig(TranslationProvider.DEEPL_FREE, key, options = options)
    private fun failure(reason: TranslationFailure.Reason, action: () -> Unit) {
        try { action(); fail("Expected failure") } catch (error: TranslationFailure) { assertEquals(reason, error.reason) }
    }

    @Test fun deepLContextGlossaryAndModelKeepOccurrenceArrayAndCredentialScopeSeparate() {
        val options = TranslationOptions("Two friends meet in a station.", "1234-abcd", "prefer_quality_optimized", "prefer_less", "revision-2")
        val conf = config(options)
        val transport = Transport(TranslationHttpResponse(200, """{"translations":[{"text":"Bonjour"},{"text":"Bonjour"}]}"""))
        val engine = TranslationEngines.create(conf, transport)
        assertEquals(listOf("Bonjour", "Bonjour"), engine.translate(listOf("Hello", "Hello"), "en", "fr") { false })
        val body = JSONObject(transport.posts.single().body)
        assertEquals(2, body.getJSONArray("text").length())
        assertEquals(options.context, body.getString("context"))
        assertEquals("1234-abcd", body.getString("glossary_id"))
        assertEquals("prefer_quality_optimized", body.getString("model_type"))
        assertEquals("prefer_less", body.getString("formality"))
        assertEquals("deepl-free", TranslationEngines.identity(conf))
        assertEquals(TranslationEngines.translationIdentity(conf), engine.id)
        assertNotEquals(TranslationEngines.identity(conf), engine.id)
        assertFalse(conf.toString().contains(options.context))
        assertFalse(options.toString().contains(options.context))
    }

    @Test fun changedContextGlossaryRevisionOrModelCannotReusePriorIdentityButKeyChangeCan() {
        val original = TranslationEngines.translationIdentity(config(TranslationOptions(context = "A")))
        for (options in listOf(TranslationOptions(context = "B"), TranslationOptions(modelType = "latency_optimized"),
            TranslationOptions(glossaryId = "1234", glossaryRevision = "1"), TranslationOptions(glossaryId = "1234", glossaryRevision = "2"))) {
            assertNotEquals(original, TranslationEngines.translationIdentity(config(options)))
        }
        assertEquals(original, TranslationEngines.translationIdentity(config(TranslationOptions(context = "A"), "different-key")))
        assertEquals("deepl-free", TranslationEngines.translationIdentity(config()))
    }

    @Test fun unsupportedOptionsAndInvalidGlossaryDetectionAreRefusedBeforeSending() {
        val transport = Transport(TranslationHttpResponse(200, ""))
        failure(TranslationFailure.Reason.CONFIGURATION) {
            TranslationEngines.create(TranslationProviderConfig(TranslationProvider.AZURE, "test", options = TranslationOptions(context = "A")), transport)
        }
        failure(TranslationFailure.Reason.CONFIGURATION) {
            TranslationEngines.create(config(TranslationOptions(glossaryId = "1234")), transport).translate(listOf("Hello"), "auto", "fr") { false }
        }
        failure(TranslationFailure.Reason.CONFIGURATION) { TranslationEngines.translationIdentity(config(TranslationOptions(context = "\uD800"))) }
        failure(TranslationFailure.Reason.CONFIGURATION) { TranslationEngines.translationIdentity(config(TranslationOptions(modelType = "invented"))) }
        assertTrue(transport.posts.isEmpty())
    }

    @Test fun quotaIsAnExplicitGetWithoutSubtitleBodyOnFixedEndpoint() {
        val transport = Transport(TranslationHttpResponse(200,
            """{"character_count":120,"character_limit":1000,"api_key_character_count":20,"api_key_character_limit":200}"""))
        val usage = TranslationEngines.usage(config(), transport = transport)
        assertEquals(880L, usage.remainingCharacters)
        assertEquals(180L, usage.apiKeyRemainingCharacters)
        val request = transport.gets.single()
        assertEquals("https://api-free.deepl.com/v2/usage", request.uri.toString())
        assertEquals("", request.body)
        assertEquals("DeepL-Auth-Key synthetic-test-key", request.headers["Authorization"])
        assertTrue(transport.posts.isEmpty())
        assertTrue(transport.closed)
    }

    @Test fun quotaRejectsMalformedValuesAndNeverRetriesOrLeaksResponse() {
        for (body in listOf("{}", """{"character_count":-1,"character_limit":1000}""",
            """{"character_count":"20","character_limit":1000}""", """{"character_count":1.5,"character_limit":1000}""")) {
            val transport = Transport(TranslationHttpResponse(200, body))
            failure(TranslationFailure.Reason.RESPONSE) { TranslationEngines.usage(config(), transport = transport) }
            assertEquals(1, transport.gets.size)
        }
        val transport = Transport(TranslationHttpResponse(403, "private response"))
        failure(TranslationFailure.Reason.AUTHENTICATION) { TranslationEngines.usage(config(), transport = transport) }
        assertEquals(1, transport.gets.size)
    }

    @Test fun quotaAndComparisonRejectCancellationAndValidateAllDestinationsBeforeFirstSend() {
        val transport = Transport(TranslationHttpResponse(200, """{"character_count":0,"character_limit":1000}"""))
        try { TranslationEngines.usage(config(), { true }, transport); fail() } catch (_: TranslationCancelledException) {}
        assertTrue(transport.gets.isEmpty())
        failure(TranslationFailure.Reason.CONFIGURATION) {
            TranslationSampleComparator.compareConfirmedSample(listOf(config(), config(key = "bad\nkey")), listOf("Hello"), "en", transportFactory = { transport })
        }
        assertTrue(transport.posts.isEmpty())
    }

    @Test fun comparisonIsBoundedAndPreservesRepeatedSampleResultsWithoutAutomaticFallback() {
        val transports = mutableListOf<Transport>()
        val results = TranslationSampleComparator.compareConfirmedSample(listOf(config(), config(TranslationOptions(context = "Context"))),
            listOf("Hello", "Hello"), "en", transportFactory = {
                Transport(TranslationHttpResponse(200, """{"translations":[{"text":"Bonjour"},{"text":"Bonjour"}]}""")).also { transports += it }
            })
        assertEquals(2, results.size)
        assertTrue(results.all { it.translations == listOf("Bonjour", "Bonjour") && it.failure == null })
        assertTrue(transports.all { it.posts.size == 1 && it.closed })
        failure(TranslationFailure.Reason.CONFIGURATION) {
            TranslationSampleComparator.compareConfirmedSample(listOf(config()), List(21) { "Hello" }, "en", transportFactory = { throw AssertionError("No send") })
        }
    }
}

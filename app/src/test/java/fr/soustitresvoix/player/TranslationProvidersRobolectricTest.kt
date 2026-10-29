package fr.soustitresvoix.player

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TranslationProvidersRobolectricTest {
    private val privateText = "A synthetic subtitle: secret-body-value"
    private val privateKey = "synthetic-credential-not-real"
    private class Transport(var response: TranslationHttpResponse) : TranslationHttpTransport {
        val requests = mutableListOf<TranslationHttpRequest>()
        var after: () -> Unit = { }
        var failure: Exception? = null
        var closed = false
        override fun post(request: TranslationHttpRequest, cancelled: () -> Boolean): TranslationHttpResponse {
            requests += request
            failure?.let { throw it }
            after()
            return response
        }
        override fun close() { closed = true }
    }
    private fun engine(provider: TranslationProvider, transport: Transport, endpoint: String = "https://translate.example.test") =
        TranslationEngines.create(TranslationProviderConfig(provider, privateKey, "westeurope", endpoint), transport)
    private fun assertFailure(reason: TranslationFailure.Reason, action: () -> Unit) {
        try { action(); fail("Expected an application-owned failure") }
        catch (failure: TranslationFailure) {
            assertEquals(reason, failure.reason)
            assertFalse(failure.toString().contains(privateKey))
            assertFalse(failure.toString().contains(privateText))
            assertNull(failure.cause)
        }
    }
    @Test fun azureKeepsOrderedArrayAndExplicitFrenchTargetWithoutFilteringProfanity() {
        val transport = Transport(TranslationHttpResponse(200,
            """[{"translations":[{"text":"Premier","to":"fr"}]},{"translations":[{"text":"Deuxième","to":"fr"}]}]"""))
        val output = engine(TranslationProvider.AZURE, transport).translate(listOf(privateText, "Second"), "sr-Latn", "fr") { false }
        assertEquals(listOf("Premier", "Deuxième"), output)
        val request = transport.requests.single()
        assertEquals("api.cognitive.microsofttranslator.com", request.uri.host)
        assertTrue(request.uri.query.contains("from=sr-Latn"))
        assertTrue(request.uri.query.contains("to=fr"))
        assertTrue(request.uri.query.contains("profanityAction=NoAction"))
        assertEquals(privateKey, request.headers["Ocp-Apim-Subscription-Key"])
        assertEquals("westeurope", request.headers["Ocp-Apim-Subscription-Region"])
        assertEquals(privateText, JSONArray(request.body).getJSONObject(0).getString("Text"))
        assertFalse(request.toString().contains(privateKey))
        assertFalse(request.toString().contains(privateText))
    }
    @Test fun deepLEndpointAndAuthenticationSchemeAreFixedAndUserEndpointCannotRedirectCredentials() {
        for ((provider, host) in listOf(TranslationProvider.DEEPL_FREE to "api-free.deepl.com",
            TranslationProvider.DEEPL_PRO to "api.deepl.com")) {
            val transport = Transport(TranslationHttpResponse(200, """{"translations":[{"text":"Bonjour"}]}"""))
            val result = engine(provider, transport, "https://untrusted.example.test").translate(listOf("Hello"), "en", "fr") { false }
            assertEquals(listOf("Bonjour"), result)
            val request = transport.requests.single()
            assertEquals(host, request.uri.host)
            assertEquals("/v2/translate", request.uri.path)
            assertEquals("DeepL-Auth-Key $privateKey", request.headers["Authorization"])
            val body = JSONObject(request.body)
            assertEquals("EN", body.getString("source_lang"))
            assertEquals("FR", body.getString("target_lang"))
            assertEquals(listOf("Hello"), (0 until body.getJSONArray("text").length()).map { body.getJSONArray("text").getString(it) })
        }
    }
    @Test fun autoDetectionIsOmittedForAzureAndDeepLButExplicitForLibre() {
        for (provider in TranslationProvider.entries.filterNot { it.isGoogle || it.isLocal }) {
            val response = when (provider) {
                TranslationProvider.AZURE -> """[{"translations":[{"text":"Bonjour","to":"fr"}]}]"""
                TranslationProvider.LIBRE -> """{"translatedText":["Bonjour"]}"""
                else -> """{"translations":[{"text":"Bonjour"}]}"""
            }
            val transport = Transport(TranslationHttpResponse(200, response))
            engine(provider, transport).translate(listOf("Hello"), "auto", "fr") { false }
            val request = transport.requests.single()
            if (provider == TranslationProvider.AZURE) assertFalse(request.uri.query.contains("from="))
            else {
                val body = JSONObject(request.body)
                if (provider == TranslationProvider.LIBRE) assertEquals("auto", body.getString("source"))
                else assertFalse(body.has("source_lang"))
            }
        }
    }
    @Test fun libreUsesTextArrayAndPreservesServerPrefixWithOptionalAuthentication() {
        val transport = Transport(TranslationHttpResponse(200, """{"translatedText":["Bonjour","Au revoir"]}"""))
        val config = TranslationProviderConfig(TranslationProvider.LIBRE, endpoint = "https://host.example.test:8443/prefix/")
        assertEquals(listOf("Bonjour", "Au revoir"), TranslationEngines.create(config, transport)
            .translate(listOf("Hello", "Goodbye"), "en", "fr") { false })
        val request = transport.requests.single()
        assertEquals("https://host.example.test:8443/prefix/translate", request.uri.toString())
        val body = JSONObject(request.body)
        assertEquals(2, body.getJSONArray("q").length())
        assertEquals("text", body.getString("format"))
        assertFalse(body.has("api_key"))
        assertEquals("https://host.example.test/prefix/translate",
            TranslationEngines.libreEndpoint("https://host.example.test/prefix/translate").toString())
    }
    @Test fun unsafeServerAddressesAreRefusedBeforeAnyRequest() {
        for (endpoint in listOf("http://host.example.test", "https://user:password@host.example.test",
            "https://host.example.test?api_key=private", "https://host.example.test#fragment", "file:///tmp/subtitles",
            "https://host.example.test/a/../b", "https://host.example.test:0")) {
            val transport = Transport(TranslationHttpResponse(200, """{"translatedText":["Bonjour"]}"""))
            assertFailure(TranslationFailure.Reason.CONFIGURATION) {
                engine(TranslationProvider.LIBRE, transport, endpoint).translate(listOf("Hello"), "en", "fr") { false }
            }
            assertTrue(transport.requests.isEmpty())
        }
    }
    @Test fun missingExtraEmptyAndNonStringTranslationsAreRejectedEntirely() {
        for (body in listOf("""{"translations":[]}""", """{"translations":[{"text":"A"},{"text":"B"}]}""",
            """{"translations":[{"text":"  "}]}""", """{"translations":[{"text":42}]}""",
            """{"translations":[{"text":null}]}""", "broken JSON")) {
            val transport = Transport(TranslationHttpResponse(200, body))
            assertFailure(TranslationFailure.Reason.RESPONSE) {
                engine(TranslationProvider.DEEPL_FREE, transport).translate(listOf(privateText), "en", "fr") { false }
            }
        }
        val transport = Transport(TranslationHttpResponse(200, """{"translatedText":"Bonjour"}"""))
        assertFailure(TranslationFailure.Reason.RESPONSE) {
            engine(TranslationProvider.LIBRE, transport).translate(listOf(privateText), "en", "fr") { false }
        }
    }
    @Test fun azureCannotAcceptWrongTargetOrMultipleTargetTranslations() {
        for (body in listOf("""[{"translations":[{"text":"Hallo","to":"de"}]}]""",
            """[{"translations":[{"text":"Bonjour","to":"fr"},{"text":"Hallo","to":"de"}]}]""")) {
            val transport = Transport(TranslationHttpResponse(200, body))
            assertFailure(TranslationFailure.Reason.RESPONSE) {
                engine(TranslationProvider.AZURE, transport).translate(listOf("Hello"), "en", "fr") { false }
            }
        }
    }
    @Test fun serviceFailuresAreSanitizedAndNeverRetried() {
        for ((status, reason) in listOf(302 to TranslationFailure.Reason.REDIRECT,
            401 to TranslationFailure.Reason.AUTHENTICATION, 403 to TranslationFailure.Reason.AUTHENTICATION,
            456 to TranslationFailure.Reason.QUOTA,
            400 to TranslationFailure.Reason.LANGUAGE, 503 to TranslationFailure.Reason.SERVER)) {
            val transport = Transport(TranslationHttpResponse(status, "$privateText $privateKey"))
            assertFailure(reason) {
                engine(TranslationProvider.DEEPL_FREE, transport).translate(listOf(privateText), "en", "fr") { false }
            }
            assertEquals(1, transport.requests.size)
        }
        val transport = Transport(TranslationHttpResponse(200, "")).apply { failure = IOException("$privateText $privateKey") }
        assertFailure(TranslationFailure.Reason.NETWORK) {
            engine(TranslationProvider.AZURE, transport).translate(listOf(privateText), "en", "fr") { false }
        }
        assertEquals(1, transport.requests.size)
    }
    @Test fun cancellationBeforeAndAfterRequestNeverReturnsATranslation() {
        val transport = Transport(TranslationHttpResponse(200, """{"translations":[{"text":"Bonjour"}]}"""))
        val engine = engine(TranslationProvider.DEEPL_FREE, transport)
        try { engine.translate(listOf("Hello"), "en", "fr") { true }; fail("Expected cancellation") }
        catch (_: TranslationCancelledException) { }
        assertTrue(transport.requests.isEmpty())
        var cancelled = false
        transport.after = { cancelled = true }
        try { engine.translate(listOf("Hello"), "en", "fr") { cancelled }; fail("Expected stale response rejection") }
        catch (_: TranslationCancelledException) { }
        assertEquals(1, transport.requests.size)
        engine.close()
        assertTrue(transport.closed)
        try { engine.translate(listOf("Hello"), "en", "fr") { false }; fail("Closed engine") }
        catch (_: TranslationCancelledException) { }
        assertEquals(1, transport.requests.size)
    }
    @Test fun invalidKeyHeaderLanguageOrBatchIsRefusedBeforeTransport() {
        val transport = Transport(TranslationHttpResponse(200, ""))
        assertFailure(TranslationFailure.Reason.CONFIGURATION) {
            TranslationEngines.create(TranslationProviderConfig(TranslationProvider.AZURE, "bad\r\nheader"), transport)
                .translate(listOf("Hello"), "en", "fr") { false }
        }
        val engine = engine(TranslationProvider.AZURE, transport)
        for ((texts, source) in listOf(emptyList<String>() to "en", listOf(" ") to "en",
            List(51) { "Hello" } to "en", listOf("X".repeat(24_001)) to "en", listOf("\uD800") to "en", listOf("Hello") to "en&to=de")) {
            assertFailure(TranslationFailure.Reason.CONFIGURATION) { engine.translate(texts, source, "fr") { false } }
        }
        assertTrue(transport.requests.isEmpty())
    }
    @Test fun responseAndConfigurationDoNotPrintPrivateContentAndOutputCannotBeMutated() {
        val config = TranslationProviderConfig(TranslationProvider.LIBRE, privateKey, endpoint = "https://private.example.test")
        assertFalse(config.toString().contains(privateKey))
        assertFalse(config.toString().contains("private.example.test"))
        assertFalse(TranslationHttpResponse(200, privateText).toString().contains(privateText))
        val transport = Transport(TranslationHttpResponse(200, """{"translations":[{"text":"Bonjour"}]}"""))
        val output = engine(TranslationProvider.DEEPL_FREE, transport).translate(listOf("Hello"), "en", "fr") { false }
        try { (output as MutableList<String>)[0] = "Original"; fail("Mutable result") }
        catch (_: UnsupportedOperationException) { }
        assertEquals(listOf("Bonjour"), output)
    }
    @Test fun reusableIdentityIncludesLibreServerAndNeverItsCredential() {
        val a = TranslationProviderConfig(TranslationProvider.LIBRE, "key-one", endpoint = "https://one.example.test/")
        val sameEndpoint = TranslationProviderConfig(TranslationProvider.LIBRE, "key-two", endpoint = "https://one.example.test/translate")
        val other = TranslationProviderConfig(TranslationProvider.LIBRE, "key-one", endpoint = "https://two.example.test/")
        assertEquals(TranslationEngines.identity(a), TranslationEngines.identity(sameEndpoint))
        assertNotEquals(TranslationEngines.identity(a), TranslationEngines.identity(other))
        assertEquals(70, TranslationEngines.identity(a).length)
        assertFalse(TranslationEngines.identity(a).contains("key-one"))
        assertFalse(TranslationEngines.identity(a).contains("one.example.test"))
        val transport = Transport(TranslationHttpResponse(200, """{"translatedText":["Bonjour"]}"""))
        assertEquals(TranslationEngines.identity(a), TranslationEngines.create(a, transport).id)
    }
    @Test fun oversizedAndNulResponsesAreRefused() {
        for (body in listOf("x".repeat(HttpsTranslationTransport.MAX_RESPONSE_BYTES + 1),
            """{"translations":[{"text":"x\u0000y"}]}""", """{"translations":[{"text":"\uD800"}]}""", """{"translations":[{"text":"${"X".repeat(24_001)}"}]}""")) {
            val transport = Transport(TranslationHttpResponse(200, body))
            assertFailure(TranslationFailure.Reason.RESPONSE) {
                engine(TranslationProvider.DEEPL_FREE, transport).translate(listOf("Hello"), "en", "fr") { false }
            }
        }
    }

    @Test fun ggufFactoryIsLazyAndClosesUnusedNetworkTransport() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        // No file, service binding or native loading is needed to reuse a
        // retained translation. Loading is deferred until the first new cue.
        val model = LocalGgufModel(java.io.File(context.filesDir, "private-unused-model.gguf"),
            "a".repeat(64), 24L, 0L)
        val config = TranslationProviderConfig(TranslationProvider.LOCAL_GGUF,
            localModel = model, localThreads = 4)
        val transport = Transport(TranslationHttpResponse(200, "Must never be requested"))
        TranslationEngines.create(config, transport, context).use { engine ->
            assertTrue(engine is LocalGgufTranslationEngine)
            assertEquals(TranslationEngines.translationIdentity(config), engine.id)
            assertTrue(transport.closed)
            assertTrue(transport.requests.isEmpty())
            assertFalse(config.toString().contains("private-unused-model"))
        }
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun ggufProfileAndExecutionKeepDistinctResultsAndReachTheLazyFactory() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val model = LocalGgufModel(java.io.File(context.filesDir, "synthetic-profile-fixture.gguf"),
            "b".repeat(64), 24L, 0L)
        val identities = mutableSetOf<String>()
        for (profile in LocalGgufProfile.entries) for (mode in listOf("AUTO", "CPU", "OPENCL")) for (policy in LocalGgufContextPolicy.entries) {
            val config = TranslationProviderConfig(TranslationProvider.LOCAL_GGUF,
                localModel = model, localProfile = profile, localExecution = mode, localContextPolicy = policy)
            val identity = TranslationEngines.translationIdentity(config)
            assertTrue("Different execution/profile choices must not reuse a former result", identities.add(identity))
            val transport = Transport(TranslationHttpResponse(200, "Never requested"))
            TranslationEngines.create(config, transport, context).use { engine ->
                assertEquals(identity, engine.id)
                assertTrue(transport.closed)
                assertTrue(transport.requests.isEmpty())
            }
        }
        assertEquals(LocalGgufProfile.entries.size * 3 * LocalGgufContextPolicy.entries.size, identities.size)
    }
}

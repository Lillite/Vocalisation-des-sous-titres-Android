package fr.soustitresvoix.player

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection

/** A server running LibreTranslate is remote from Android, even if self-hosted. */
enum class TranslationProvider(val id: String, val label: String) {
    AZURE("azure", "Microsoft Azure Translator"),
    DEEPL_FREE("deepl-free", "DeepL — point d’accès API Free"),
    DEEPL_PRO("deepl-pro", "DeepL — point d’accès API payant"),
    LIBRE("libre", "LibreTranslate — serveur personnel HTTPS")
}

/** Never expose credentials in a generated data-class toString(). */
class TranslationProviderConfig(
    val provider: TranslationProvider,
    val apiKey: String = "",
    val region: String = "",
    val endpoint: String = ""
) {
    override fun toString(): String = "TranslationProviderConfig(provider=${provider.id}, credentials=hidden)"
}

/** Runs off the UI thread. Closing invalidates an in-flight response. */
interface TranslationEngine : Closeable {
    val id: String
    fun translate(texts: List<String>, sourceLanguage: String, targetLanguage: String = "fr",
        cancelled: () -> Boolean): List<String>
}

class TranslationCancelledException : IOException("Traduction annulée ; aucune piste incomplète n’a été activée.")

/** Only application-owned messages cross the UI boundary; no cause/body/URI is attached. */
class TranslationFailure(val reason: Reason) : IOException(reason.message) {
    enum class Reason(val message: String) {
        CONFIGURATION("Configuration du traducteur invalide. Vérifie le moteur, sa clé, sa région et les langues."),
        AUTHENTICATION("Accès au traducteur refusé. Vérifie la clé et les autorisations du compte."),
        QUOTA("Quota ou limite du traducteur atteint. Vérifie le compte avant de relancer ; aucune relance automatique."),
        LANGUAGE("Requête ou langue refusée par le traducteur. Vérifie les langues prises en charge par ce moteur."),
        REDIRECT("Redirection du service de traduction refusée. Vérifie l’adresse HTTPS configurée."),
        NETWORK("Connexion au traducteur impossible ou interrompue. Vérifie le réseau puis relance explicitement."),
        RESPONSE("Réponse du traducteur incomplète ou invalide. Aucune piste incomplète n’a été activée."),
        SERVER("Service de traduction indisponible. Réessaie plus tard ; aucune relance automatique.")
    }
}

/** Request objects deliberately do not print text, credentials, headers or URLs. */
class TranslationHttpRequest(val uri: URI, headers: Map<String, String>, val body: String) {
    val headers: Map<String, String> = Collections.unmodifiableMap(HashMap(headers))
    override fun toString(): String = "TranslationHttpRequest(HTTPS POST, content hidden)"
}

class TranslationHttpResponse(val status: Int, val body: String) {
    override fun toString(): String = "TranslationHttpResponse(status=$status, content hidden)"
}

interface TranslationHttpTransport : Closeable {
    fun post(request: TranslationHttpRequest, cancelled: () -> Boolean): TranslationHttpResponse
    override fun close() { }
}

/** HTTPS only; no redirects, no retries, bounded responses, platform certificate validation. */
class HttpsTranslationTransport : TranslationHttpTransport {
    @Volatile private var active: HttpsURLConnection? = null
    private val closed = AtomicBoolean(false)
    override fun post(request: TranslationHttpRequest, cancelled: () -> Boolean): TranslationHttpResponse {
        fun checkCancelled() {
            if (closed.get() || cancelled() || Thread.currentThread().isInterrupted) throw TranslationCancelledException()
        }
        checkCancelled()
        require(request.uri.scheme == "https" && request.uri.host != null)
        val connection = request.uri.toURL().openConnection() as HttpsURLConnection
        active = connection
        try {
            checkCancelled()
            connection.instanceFollowRedirects = false
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.useCaches = false
            connection.doOutput = true
            request.headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            val bytes = request.body.toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { stream -> checkCancelled(); stream.write(bytes) }
            checkCancelled()
            val status = connection.responseCode
            // Do not read error bodies, which may repeat private text or credentials.
            if (status != 200) return TranslationHttpResponse(status, "")
            if (connection.contentLengthLong > MAX_RESPONSE_BYTES) throw TranslationFailure(TranslationFailure.Reason.RESPONSE)
            val data = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    checkCancelled()
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (output.size().toLong() + n > MAX_RESPONSE_BYTES) throw TranslationFailure(TranslationFailure.Reason.RESPONSE)
                    output.write(buffer, 0, n)
                }
                output.toByteArray()
            }
            checkCancelled()
            val body = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString()
            return TranslationHttpResponse(status, body)
        } finally {
            active = null
            connection.disconnect()
        }
    }
    override fun close() { closed.set(true); active?.disconnect() }
    companion object { const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024 }
}

object TranslationEngines {
    fun create(config: TranslationProviderConfig,
        transport: TranslationHttpTransport = HttpsTranslationTransport()): TranslationEngine = HttpEngine(config, transport)

    /** Bind reusable translations to the selected server, without exposing its URL or key. */
    fun identity(config: TranslationProviderConfig): String {
        if (config.provider != TranslationProvider.LIBRE) return config.provider.id
        val endpoint = libreEndpoint(config.endpoint).toASCIIString()
        val digest = MessageDigest.getInstance("SHA-256").digest(endpoint.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
        return "${config.provider.id}-$digest"
    }

    /** Canonical base URL; the /translate path is added by this adapter. */
    fun libreEndpoint(value: String): URI {
        val uri = try { URI(value.trim()) } catch (_: Exception) { throw TranslationFailure(TranslationFailure.Reason.CONFIGURATION) }
        if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.rawUserInfo != null ||
            uri.rawQuery != null || uri.rawFragment != null || uri.port !in -1..65535 || uri.port == 0 ||
            uri.rawPath.orEmpty().contains("\\") || uri.normalize().rawPath != uri.rawPath) {
            throw TranslationFailure(TranslationFailure.Reason.CONFIGURATION)
        }
        val path = uri.rawPath.orEmpty().trimEnd('/')
        // Accept a base URL or the explicit endpoint. Preserve optional server prefix.
        val full = if (path.endsWith("/translate")) path else "$path/translate"
        return URI("https://${uri.rawAuthority}$full")
    }

    private class HttpEngine(private val config: TranslationProviderConfig,
        private val transport: TranslationHttpTransport) : TranslationEngine {
        override val id: String = identity(config)
        private val closed = AtomicBoolean(false)
        override fun close() { closed.set(true); transport.close() }
        override fun translate(texts: List<String>, sourceLanguage: String, targetLanguage: String,
            cancelled: () -> Boolean): List<String> {
            fun checkCancelled() {
                if (closed.get() || cancelled() || Thread.currentThread().isInterrupted) throw TranslationCancelledException()
            }
            try {
                checkCancelled()
                if (texts.isEmpty() || texts.size > 50 || texts.sumOf { it.length.toLong() } > 24_000L ||
                    texts.any { it.isBlank() || !validText(it) } ||
                    !language.matches(sourceLanguage) || !language.matches(targetLanguage) || targetLanguage == "auto")
                    throw TranslationFailure(TranslationFailure.Reason.CONFIGURATION)
                val key = config.apiKey
                if ((config.provider != TranslationProvider.LIBRE && key.isBlank()) ||
                    key.length > 4096 || key.any { it.code !in 33..126 } ||
                    config.region.length > 100 || config.region.any { !(it.isLetterOrDigit() || it == '-') })
                    throw TranslationFailure(TranslationFailure.Reason.CONFIGURATION)
                val headers = linkedMapOf("Content-Type" to "application/json; charset=UTF-8", "Accept" to "application/json")
                val request = when (config.provider) {
                    TranslationProvider.AZURE -> {
                        headers["Ocp-Apim-Subscription-Key"] = key
                        if (config.region.isNotEmpty()) headers["Ocp-Apim-Subscription-Region"] = config.region
                        val query = "api-version=3.0&to=$targetLanguage&textType=plain&profanityAction=NoAction" +
                            if (sourceLanguage == "auto") "" else "&from=$sourceLanguage"
                        TranslationHttpRequest(URI("https://api.cognitive.microsofttranslator.com/translate?$query"),
                            headers, JSONArray().apply { texts.forEach { put(JSONObject().put("Text", it)) } }.toString())
                    }
                    TranslationProvider.DEEPL_FREE, TranslationProvider.DEEPL_PRO -> {
                        headers["Authorization"] = "DeepL-Auth-Key $key"
                        val body = JSONObject().put("text", JSONArray(texts)).put("target_lang", targetLanguage.uppercase(Locale.ROOT))
                            .put("preserve_formatting", true)
                        if (sourceLanguage != "auto") body.put("source_lang", sourceLanguage.uppercase(Locale.ROOT))
                        val host = if (config.provider == TranslationProvider.DEEPL_FREE) "api-free.deepl.com" else "api.deepl.com"
                        TranslationHttpRequest(URI("https://$host/v2/translate"), headers, body.toString())
                    }
                    TranslationProvider.LIBRE -> {
                        val body = JSONObject().put("q", JSONArray(texts)).put("source", sourceLanguage)
                            .put("target", targetLanguage).put("format", "text")
                        if (key.isNotEmpty()) body.put("api_key", key)
                        TranslationHttpRequest(libreEndpoint(config.endpoint), headers, body.toString())
                    }
                }
                if (request.body.toByteArray(Charsets.UTF_8).size > 120 * 1024)
                    throw TranslationFailure(TranslationFailure.Reason.CONFIGURATION)
                checkCancelled()
                val response = transport.post(request) { closed.get() || cancelled() || Thread.currentThread().isInterrupted }
                checkCancelled()
                when (response.status) {
                    200 -> Unit
                    in 300..399 -> throw TranslationFailure(TranslationFailure.Reason.REDIRECT)
                    401, 403 -> throw TranslationFailure(TranslationFailure.Reason.AUTHENTICATION)
                    429, 456 -> throw TranslationFailure(TranslationFailure.Reason.QUOTA)
                    400, 404, 413, 422 -> throw TranslationFailure(TranslationFailure.Reason.LANGUAGE)
                    else -> throw TranslationFailure(TranslationFailure.Reason.SERVER)
                }
                if (response.body.toByteArray(Charsets.UTF_8).size > HttpsTranslationTransport.MAX_RESPONSE_BYTES)
                    throw TranslationFailure(TranslationFailure.Reason.RESPONSE)
                val output = when (config.provider) {
                    TranslationProvider.AZURE -> {
                        val result = JSONArray(response.body)
                        if (result.length() != texts.size) throw TranslationFailure(TranslationFailure.Reason.RESPONSE)
                        List(result.length()) { i ->
                            val translations = result.getJSONObject(i).getJSONArray("translations")
                            if (translations.length() != 1) throw TranslationFailure(TranslationFailure.Reason.RESPONSE)
                            val item = translations.getJSONObject(0)
                            if (!strictString(item, "to").equals(targetLanguage, ignoreCase = true))
                                throw TranslationFailure(TranslationFailure.Reason.RESPONSE)
                            strictString(item, "text")
                        }
                    }
                    TranslationProvider.DEEPL_FREE, TranslationProvider.DEEPL_PRO -> {
                        val result = JSONObject(response.body).getJSONArray("translations")
                        if (result.length() != texts.size) throw TranslationFailure(TranslationFailure.Reason.RESPONSE)
                        List(result.length()) { i -> strictString(result.getJSONObject(i), "text") }
                    }
                    TranslationProvider.LIBRE -> {
                        val result = JSONObject(response.body).get("translatedText")
                        // Array input must produce an array even for one subtitle.
                        if (result !is JSONArray || result.length() != texts.size)
                            throw TranslationFailure(TranslationFailure.Reason.RESPONSE)
                        List(result.length()) { i -> result.get(i) as? String ?: throw TranslationFailure(TranslationFailure.Reason.RESPONSE) }
                    }
                }
                if (output.any { it.isBlank() || it.length > 24_000 || !validText(it) })
                    throw TranslationFailure(TranslationFailure.Reason.RESPONSE)
                checkCancelled()
                return Collections.unmodifiableList(ArrayList(output))
            } catch (cancel: TranslationCancelledException) { throw cancel }
            catch (failure: TranslationFailure) { throw failure }
            catch (_: org.json.JSONException) { throw TranslationFailure(TranslationFailure.Reason.RESPONSE) }
            catch (_: java.nio.charset.CharacterCodingException) { throw TranslationFailure(TranslationFailure.Reason.RESPONSE) }
            catch (_: Exception) {
                checkCancelled()
                throw TranslationFailure(TranslationFailure.Reason.NETWORK)
            }
        }
    }
    private fun strictString(objectValue: JSONObject, name: String): String =
        objectValue.get(name) as? String ?: throw TranslationFailure(TranslationFailure.Reason.RESPONSE)
    private fun validText(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val character = text[i]
            if (character == '\u0000' || Character.isLowSurrogate(character)) return false
            if (Character.isHighSurrogate(character)) {
                if (i + 1 >= text.length || !Character.isLowSurrogate(text[i + 1])) return false
                i++
            }
            i++
        }
        return true
    }
    private val language = Regex("(?:auto|[A-Za-z]{2,3}(?:-[A-Za-z]{2,8})?)")
}

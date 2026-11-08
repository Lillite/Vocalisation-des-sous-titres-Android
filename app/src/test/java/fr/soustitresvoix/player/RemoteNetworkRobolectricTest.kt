package fr.soustitresvoix.player

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.common.util.UriUtil
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import androidx.test.core.app.ApplicationProvider
import java.net.URI
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RemoteNetworkRobolectricTest {
    private lateinit var context: Context
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("remote-connections-private-v1", Context.MODE_PRIVATE).edit().clear().commit()
    }
    /** Exercises real AES-GCM/store plumbing; Android Keystore has a separate device test. */
    private fun store(): RemoteConnectionStore {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return RemoteConnectionStore.forTest(context, object : RemoteSecretCipher {
            override fun encrypt(plaintext: ByteArray, identity: String): String {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key); cipher.updateAAD(identity.toByteArray())
                return Base64.encodeToString(cipher.iv + cipher.doFinal(plaintext), Base64.NO_WRAP)
            }
            override fun decrypt(value: String, identity: String): ByteArray {
                val bytes = Base64.decode(value, Base64.NO_WRAP)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12))); cipher.updateAAD(identity.toByteArray())
                return cipher.doFinal(bytes, 12, bytes.size - 12)
            }
        })
    }
    private fun server() = SyntheticHttpServer()
    private fun base(server: SyntheticHttpServer) = "http://127.0.0.1:${server.port}"

    @Test fun encryptedConnectionsAndDirectUrlsNeverPutSecretsInPlaybackIdentity() {
        val store = store()
        val connection = RemoteConnection(label = "Synthétique", protocol = RemoteProtocol.SFTP, host = "example.invalid",
            username = "synthetic-user", password = "synthetic-password", hostKeySha256 = "SHA256:" + "A".repeat(43))
        store.save(connection)
        assertEquals(connection, store.get(connection.id))
        val firstRevision = store.sourceRevision(RemoteMediaUris.create(connection.id, "/film.mkv"))
        store.save(connection.copy(host = "changed.invalid"))
        assertNotEquals(firstRevision, store.sourceRevision(RemoteMediaUris.create(connection.id, "/film.mkv")))
        val direct = store.saveDirectUrl("https://synthetic-user:synthetic-password@example.invalid/secret-token/film.m3u8?token=synthetic-query")
        assertEquals(RemoteMediaUris.DIRECT_SCHEME, direct.scheme)
        assertEquals("/media.m3u8", direct.path)
        assertNull(direct.query)
        assertEquals(listOf(direct), store.listDirectUrls())
        assertFalse(direct.toString().contains("synthetic-"))
        val disk = context.getSharedPreferences("remote-connections-private-v1", Context.MODE_PRIVATE).all.toString()
        listOf("synthetic-password", "synthetic-user", "synthetic-query", "example.invalid", "secret-token").forEach { assertFalse(disk.contains(it)) }
        assertFalse(connection.toString().contains("synthetic-password"))
        store.deleteDirectUrl(direct)
        assertEquals("missing", store.sourceRevision(direct))
        assertTrue(store.listDirectUrls().isEmpty())
    }

    @Test fun pathsRoundTripUtf8AndRejectTraversal() {
        val uri = RemoteMediaUris.create("server1", "/Cinéma/film 100% #1.mkv")
        assertEquals("/Cinéma/film 100% #1.mkv", uri.path)
        assertTrue(uri.toString().contains("%20"))
        assertEquals("/data/Cinéma/film.mkv", RemotePaths.resolve("/data", "/Cinéma/film.mkv"))
        for (path in listOf("/../private", "/a/./b", "/a\\b", "/a\n")) {
            assertThrows(IllegalArgumentException::class.java) { RemotePaths.normalize(path) }
        }
        assertEquals("/a", RemotePaths.parent("/a/b"))
        assertEquals("/", RemotePaths.parent("/a"))
        assertEquals("/a/%2e%2e", RemotePaths.join("/a", "%2e%2e"))
    }

    @Test fun actualHttpRangePreservesSignedOctetsAndSeekBytes() {
        val request = AtomicReference<String>()
        val range = AtomicReference<String>()
        val server = server()
        val payload = "0123456789".toByteArray()
        server.createContext("/") { exchange ->
            request.set(exchange.requestURI.toASCIIString()); range.set(exchange.requestHeaders.getFirst("Range"))
            exchange.responseHeaders.add("Content-Range", "bytes 3-6/10")
            exchange.sendResponseHeaders(206, 4)
            exchange.responseBody.use { it.write(payload, 3, 4) }
        }
        server.start()
        val store = store()
        try {
            val uri = store.saveDirectUrl(base(server) + "/a%2Fb%20c.mp4?sig=one%2Ftwo%2Bthree%3D&x=%252F")
            val source = NetworkDataSourceFactory(context, store).createDataSource()
            try {
                assertEquals(4L, source.open(DataSpec.Builder().setUri(uri).setPosition(3).setLength(4).build()))
                val buffer = ByteArray(20)
                assertEquals(4, source.read(buffer, 0, 20)); assertEquals("3456", buffer.copyOf(4).toString(Charsets.UTF_8))
                assertEquals(-1, source.read(buffer, 0, 20))
                assertEquals("bytes=3-6", range.get())
                assertEquals("/a%2Fb%20c.mp4?sig=one%2Ftwo%2Bthree%3D&x=%252F", request.get())
            } finally { source.close() }
        } finally { server.close() }
    }

    @Test fun serverIgnoringRangesStillSeeksCorrectlyAndExactEof416IsReadable() {
        val server = server()
        server.createContext("/ignored") { exchange ->
            exchange.sendResponseHeaders(200, 10); exchange.responseBody.use { it.write("0123456789".toByteArray()) }
        }
        server.createContext("/end") { exchange ->
            exchange.responseHeaders.add("Content-Range", "bytes */10")
            exchange.sendResponseHeaders(416, -1); exchange.close()
        }
        server.start()
        try {
            val cancellation = RemoteCancellation()
            RemoteHttp.open(base(server) + "/ignored", 7, -1, cancellation).use {
                val buffer = ByteArray(10)
                assertEquals(10L, it.length); assertEquals(3, it.read(buffer, 0, 10))
                assertEquals("789", buffer.copyOf(3).toString(Charsets.UTF_8))
            }
            RemoteHttp.open(base(server) + "/end", 10, -1, cancellation).use {
                assertEquals(10L, it.length); assertEquals(-1, it.read(ByteArray(1), 0, 1))
            }
            cancellation.close()
        } finally { server.close() }
    }

    @Test fun crossOriginRedirectDropsBasicAuthorizationAndKeepsRelativeManifestBase() {
        val received = AtomicReference<String?>()
        val target = server(); val origin = server()
        target.createContext("/final/playlist.m3u8") { exchange ->
            received.set(exchange.requestHeaders.getFirst("Authorization"))
            val bytes = "#EXTM3U\n#EXT-X-ENDLIST\n".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        origin.createContext("/") { exchange ->
            assertNotNull(exchange.requestHeaders.getFirst("Authorization"))
            exchange.responseHeaders.add("Location", base(target) + "/final/playlist.m3u8")
            exchange.sendResponseHeaders(302, -1); exchange.close()
        }
        origin.start(); target.start()
        try {
            val cancel = RemoteCancellation()
            RemoteHttp.open(base(origin).replace("http://", "http://user:password@") + "/playlist.m3u8", 0, -1, cancel).use {
                assertNull(received.get())
                assertEquals(base(target) + "/final/playlist.m3u8", it.resolvedUri.toString())
            }
            cancel.close()
        } finally { origin.close(); target.close() }
    }

    @Test fun hlsChildrenShareOnlyTheirMediaSameOriginBasicScopeAndTransferCallbacks() {
        val origin = server(); val cdn = server()
        val expected = "Basic " + Base64.encodeToString("manifest-user:manifest-password".toByteArray(), Base64.NO_WRAP)
        val requests = mutableListOf<Pair<String, String?>>()
        val cdnHeaders = mutableListOf<String?>()
        val manifest = "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:0\n" +
            "#EXTINF:1,\nsegment%20one.ts?sig=segment%2Fone%3D\n" +
            "#EXTINF:1,\n${base(origin)}/protected/absolute.ts?token=%2F\n" +
            "#EXTINF:1,\n${base(cdn)}/cross.ts\n" +
            "#EXTINF:1,\nredirect.ts\n#EXT-X-ENDLIST\n"
        origin.createContext("/") { exchange ->
            val request = exchange.requestURI.toASCIIString()
            val authorization = exchange.requestHeaders.getFirst("Authorization")
            requests += request to authorization
            if (exchange.requestURI.path.startsWith("/protected/") && authorization != expected) {
                exchange.sendResponseHeaders(401, -1); exchange.close()
            } else when (exchange.requestURI.rawPath) {
                "/protected/start.m3u8" -> {
                    exchange.responseHeaders.add("Location", "/protected/a%2Fb.m3u8?sig=one%2Ftwo%2Bthree%3D")
                    exchange.sendResponseHeaders(302, -1); exchange.close()
                }
                "/protected/a%2Fb.m3u8" -> {
                    val bytes = manifest.toByteArray(); exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                "/protected/redirect.ts" -> {
                    exchange.responseHeaders.add("Location", base(cdn) + "/redirected.ts")
                    exchange.sendResponseHeaders(302, -1); exchange.close()
                }
                else -> {
                    val bytes = "synthetic-segment".toByteArray(); exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
            }
        }
        cdn.createContext("/") { exchange ->
            cdnHeaders += exchange.requestHeaders.getFirst("Authorization")
            val bytes = "synthetic-cdn-segment".toByteArray(); exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        origin.start(); cdn.start()
        try {
            val store = store()
            val opaque = store.saveDirectUrl(base(origin).replace("http://", "http://manifest-user:manifest-password@") + "/protected/start.m3u8")
            val plainFactory = NetworkDataSourceFactory(context, store)
            val mediaFactory = plainFactory.forMediaUri(opaque)
            val starts = AtomicInteger(); val ends = AtomicInteger(); val transferred = AtomicInteger()
            val listener = object : TransferListener {
                override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) { assertTrue(isNetwork) }
                override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) { assertTrue(isNetwork); starts.incrementAndGet() }
                override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) { transferred.addAndGet(bytesTransferred) }
                override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) { ends.incrementAndGet() }
            }
            fun read(factory: NetworkDataSourceFactory, uri: Uri): Pair<Uri, ByteArray> {
                val source = factory.createDataSource(); source.addTransferListener(listener)
                try {
                    source.open(DataSpec.Builder().setUri(uri).build())
                    val resolved = requireNotNull(source.uri)
                    val output = ByteArrayOutputStream(); val buffer = ByteArray(128)
                    while (true) { val count = source.read(buffer, 0, buffer.size); if (count < 0) break; output.write(buffer, 0, count) }
                    return resolved to output.toByteArray()
                } finally { source.close() }
            }
            val (resolved, bytes) = read(mediaFactory, opaque)
            assertEquals(base(origin) + "/protected/a%2Fb.m3u8?sig=one%2Ftwo%2Bthree%3D", resolved.toString())
            assertFalse(resolved.toString().contains("manifest-password"))
            val playlist = HlsPlaylistParser().parse(resolved, ByteArrayInputStream(bytes)) as HlsMediaPlaylist
            assertEquals(4, playlist.segments.size)
            playlist.segments.forEach { segment ->
                val (_, payload) = read(mediaFactory, UriUtil.resolveToUri(playlist.baseUri, segment.url))
                assertTrue(payload.toString(Charsets.UTF_8).startsWith("synthetic-"))
            }
            // Reusing either the base factory or a new media scope on the same
            // origin must not inherit the preceding media's credentials.
            read(plainFactory, Uri.parse(base(origin) + "/public.ts"))
            read(mediaFactory.forMediaUri(Uri.parse(base(origin) + "/other-public.ts")), Uri.parse(base(origin) + "/other-public.ts"))
            assertEquals(7, starts.get()); assertEquals(starts.get(), ends.get()); assertTrue(transferred.get() > bytes.size)
            assertTrue(requests.filter { it.first.startsWith("/protected/") }.all { it.second == expected })
            assertTrue(requests.any { it.first == "/protected/segment%20one.ts?sig=segment%2Fone%3D" })
            assertTrue(requests.any { it.first == "/protected/absolute.ts?token=%2F" })
            assertEquals(2, cdnHeaders.size); assertTrue(cdnHeaders.all { it == null })
            assertTrue(requests.filter { it.first.endsWith("public.ts") }.all { it.second == null })
            assertNull(opaque.query); assertFalse(opaque.toString().contains("manifest-user"))
        } finally { origin.close(); cdn.close() }
    }

    @Test fun webDavPropfindUsesRealCustomMethodAndExcludesOutsidePaths() {
        val method = AtomicReference<String>(); val depth = AtomicReference<String>()
        val server = server()
        server.createContext("/") { exchange ->
            method.set(exchange.requestMethod); depth.set(exchange.requestHeaders.getFirst("Depth")); exchange.requestBody.close()
            val bytes = ("<d:multistatus xmlns:d=\"DAV:\">" +
                "<d:response><d:href>/seed/Films/</d:href></d:response>" +
                "<d:response><d:href>/seed/Films/Cin%C3%A9ma%20%231.mkv</d:href><d:propstat><d:prop><d:getcontentlength>1234</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>" +
                "<d:response><d:href>/seed/Films/Saison/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>" +
                "<d:response><d:href>https://other.invalid/seed/Films/stolen.mkv</d:href></d:response>" +
                "<d:response><d:href>/seed/Films/%2e%2e/private.mkv</d:href></d:response>" +
                "</d:multistatus>").toByteArray()
            exchange.sendResponseHeaders(207, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val connection = RemoteConnection(label = "Fixture", protocol = RemoteProtocol.WEBDAV, host = "127.0.0.1", port = server.port, rootPath = "/seed", tls = false)
            val cancel = RemoteCancellation()
            val entries = RemoteWebDav.list(connection, "/Films", cancel)
            assertEquals("PROPFIND", method.get()); assertEquals("1", depth.get())
            assertEquals(2, entries.size)
            assertEquals(1234L, entries.first { !it.isDirectory }.sizeBytes)
            assertEquals("/Films/Cinéma #1.mkv", entries.first { !it.isDirectory }.path)
            assertEquals("/Films/Saison", entries.first { it.isDirectory }.path)
            cancel.close()
        } finally { server.close() }
    }

    @Test fun webDavXmlRejectsEntitiesAndListingOverflow() {
        val endpoint = URI("https://example.invalid/seed/")
        val dangerous = "<!DOCTYPE d [<!ENTITY private SYSTEM 'file:///etc/passwd'>]><d:multistatus xmlns:d=\"DAV:\"><d:response><d:href>&private;</d:href></d:response></d:multistatus>".toByteArray()
        assertThrows(Exception::class.java) { RemoteWebDav.parse(dangerous, endpoint, "/seed", "/", RemoteCancellation()) }
        val oversized = buildString {
            append("<d:multistatus xmlns:d=\"DAV:\">")
            repeat(5001) { append("<d:response><d:href>/seed/file$it.mkv</d:href></d:response>") }
            append("</d:multistatus>")
        }.toByteArray()
        assertThrows(RemoteAccessException::class.java) { RemoteWebDav.parse(oversized, endpoint, "/seed", "/", RemoteCancellation()) }
    }

    @Test fun cancellationClosesRegisteredResourcesAndNeverBecomesSuccess() {
        val cancel = RemoteCancellation(); var closed = false
        cancel.register(java.io.Closeable { closed = true }); cancel.close()
        assertTrue(closed)
        assertThrows(RemoteAccessException::class.java) { cancel.ensureActive() }
    }

    @Test fun completeSyntheticMkvIndexOverHttpPreservesEveryLocalOccurrenceAndRepeat() {
        val bytes = context.resources.openRawResource(R.raw.demonstration).use { it.readBytes() }
        val server = server()
        server.createContext("/synthetic.mkv") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            val start = range?.substringAfter("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
            val count = bytes.size - start
            if (range != null) exchange.responseHeaders.add("Content-Range", "bytes $start-${bytes.lastIndex}/${bytes.size}")
            exchange.sendResponseHeaders(if (range == null) 200 else 206, count.toLong())
            exchange.responseBody.use { it.write(bytes, start, count) }
        }
        server.start()
        try {
            val local = FullSubtitleIndexer(context).scan(SavedMedia("android.resource://${context.packageName}/${R.raw.demonstration}", "Synthétique local")) {}
            val remote = FullSubtitleIndexer(context).scan(SavedMedia(base(server) + "/synthetic.mkv", "Synthétique HTTP")) {}
            assertFalse(remote.truncated)
            assertEquals(8, remote.count)
            assertEquals(local.count, remote.count)
            val localItems = (0..9).flatMap { local.selected(it.toString()) }
            val remoteItems = (0..9).flatMap { remote.selected(it.toString()) }
            assertEquals(8, remoteItems.size)
            assertEquals(localItems.map { it.identity }, remoteItems.map { it.identity })
            val repeats = remoteItems.filter { it.raw == "Oui." }
            assertEquals(2, repeats.size)
            assertNotEquals(repeats[0].identity, repeats[1].identity)
        } finally { server.close() }
    }

    @Test fun adaptiveOrLiveScanFailsExplicitlyInsteadOfReturningAnEmptyCompleteIndex() {
        for (uri in listOf("https://example.invalid/playlist.m3u8", "https://example.invalid/manifest.mpd", "rtsp://example.invalid/live")) {
            val error = assertThrows(java.io.IOException::class.java) { FullSubtitleIndexer(context).scan(SavedMedia(uri, "Synthétique")) {} }
            assertTrue(FullSubtitleIndexer.safeFailureMessage(error).contains("traduction intégrale n’est pas disponible"))
        }
    }
}

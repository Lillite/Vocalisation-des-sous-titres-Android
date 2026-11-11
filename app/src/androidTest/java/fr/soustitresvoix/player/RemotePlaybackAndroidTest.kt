package fr.soustitresvoix.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.PlayerMessage
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.soustitresvoix.core.NarrationMachine
import fr.soustitresvoix.core.NarrationLedger
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real Android Keystore -> authenticated loopback HTTP -> Media3 demux ->
 * buffered source occurrences -> PlaybackSession -> Android TTS IPC. Only the generated
 * demonstration and the existing silence-producing test voice are used. These
 * tests cover HTTP and WebDAV GET; they do not certify FTP/SFTP/SMB servers. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class RemotePlaybackAndroidTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrument.targetContext

    @Before fun resetPreferences() {
        context.getSharedPreferences("player", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("cache", false).putBoolean("narration", true).putInt("orientation", 0)
            .putInt("resumePolicy", NarrationMachine.ResumePolicy.REPLICA.ordinal).commit()
        context.getSharedPreferences("playback-measurements", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun waitFor(message: String, timeoutMs: Long = 25_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            instrument.runOnMainSync { ready = condition() }
            if (ready) return
            Thread.sleep(40)
        }
        fail(message)
    }

    @Test fun opaqueAuthenticatedHttpPauseAndResumeSpeaksEverySourceOccurrenceThroughRealTts() {
        val bytes = context.resources.openRawResource(R.raw.demonstration).use { it.readBytes() }
        val expectedRequests = listOf("Bonjour.", "Ceci est un essai des sous-titres vocalisés.", SOURCE_TEXTS[1],
            "La vidéo reprend ensuite.", "Tu peux régler le volume du film séparément.", SOURCE_TEXTS[3],
            "Oui.", "Oui.", SOURCE_TEXTS[6], SOURCE_TEXTS[7])
        val requests = Collections.synchronizedList(mutableListOf<String>())
        fun observed() = synchronized(requests) { requests.toList() }
        val errors = Collections.synchronizedList(mutableListOf<String>())
        val sessionReference = AtomicReference<PlaybackSession>()
        val observation = RemoteNarrationObservation()
        val maintenance = Handler(Looper.getMainLooper())
        var lastSavedAt = 0L
        val maintenanceTick = object : Runnable {
            override fun run() {
                sessionReference.get()?.let { session ->
                    session.prepareTick()
                    if (SystemClock.elapsedRealtime() - lastSavedAt > 5_000) {
                        session.savePosition(); lastSavedAt = SystemClock.elapsedRealtime()
                    }
                }
                maintenance.postDelayed(this, 500)
            }
        }
        val firstUtterance = AtomicReference<String>()
        val pauseOnce = AtomicBoolean()
        val pauseBarrier = AtomicReference<PlayerMessage>()
        val receiverFailure = AtomicReference<Throwable>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != FixtureSpeechService.ALL_REQUESTS_ACTION) return
                val text = intent.getStringExtra("text") ?: return
                // Record every service request, including unexpected duplicates and preparations.
                requests += text
                observation.event("service.received(text=$text)")
                if (text == expectedRequests.first() && pauseOnce.compareAndSet(false, true)) {
                    try {
                        val session = requireNotNull(sessionReference.get())
                        firstUtterance.set(requireNotNull(session.machine.snapshot().speakingId))
                        session.pause()
                        pauseBarrier.set((session.playback as Media3PlaybackBackend).exoPlayer.createMessage { _, _ -> }.send())
                    } catch (failure: Throwable) { receiverFailure.set(failure) }
                }
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(FixtureSpeechService.ALL_REQUESTS_ACTION), ContextCompat.RECEIVER_EXPORTED)
        val preferences = PlayerPreferences(context)
        val store = RemoteConnectionStore(context)
        var opaque: Uri? = null
        try {
            preferences.apply {
                enginePackage = instrument.context.packageName; voiceName = "fixture-fr"
                narration = true; putBool("cache", false); setMode(NarrationMachine.Mode.COMFORT)
            }
            HttpFixture(bytes, "/media/demonstration.mkv?token=synthetic-signed-token").use { server ->
                val raw = "http://synthetic-user:synthetic-password@127.0.0.1:${server.port}${server.target}"
                val uri = store.saveDirectUrl(raw).also { opaque = it }
                assertEquals(RemoteMediaUris.DIRECT_SCHEME, uri.scheme)
                assertFalse(uri.toString().contains("synthetic"))
                assertFalse(context.getSharedPreferences("remote-connections-private-v1", Context.MODE_PRIVATE).all.toString().contains("synthetic-signed-token"))
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    var surface: PlayerView? = null
                    try {
                        scenario.onActivity { current ->
                            // MainActivity's own empty session makes no audio request.
                            // This real session differs only by transparent observers.
                            val session = PlaybackSession(current, preferences, onChanged = {},
                                onMessage = { text, error -> observation.event("message(error=$error,$text)") },
                                onCaption = {}, components = observation.components)
                            sessionReference.set(session)
                            observation.attach(session); observation.observePlayer(session)
                            surface = PlayerView(current).apply { useController = false; player = session.player }
                            current.setContentView(requireNotNull(surface))
                            maintenance.post(maintenanceTick)
                            session.player.addListener(object : Player.Listener {
                                override fun onPlayerError(error: PlaybackException) { errors += error.errorCodeName }
                            })
                            session.open(SavedMedia(uri.toString(), "Synthetic authenticated HTTP fixture"))
                        }
                        waitFor("The authenticated real player, selected text track and test TTS engine did not become ready") {
                            val session = sessionReference.get()
                            session.player.playbackState == Player.STATE_READY && session.selectedTextId() != null && session.speech.isReady
                        }
                        scenario.onActivity {
                            assertFalse(sessionReference.get().hasCompleteSubtitleIndex)
                            assertEquals(uri.toString(), sessionReference.get().media?.uri)
                            assertEquals(uri.toString(), sessionReference.get().prefs.lastMedia()?.uri)
                            assertTrue("Paused demux/index preparation must not request TTS", observed().isEmpty())
                            sessionReference.get().play()
                        }
                        waitFor("Ordinary authenticated network Play did not advance without complete indexing") {
                            sessionReference.get().player.currentPosition > 0 || pauseBarrier.get() != null
                        }
                        waitFor("The first real TTS request was not interrupted by an explicit user pause") {
                            pauseBarrier.get() != null || receiverFailure.get() != null
                        }
                        receiverFailure.get()?.let { throw AssertionError("Could not pause the observed real utterance", it) }
                        assertTrue(requireNotNull(pauseBarrier.get()).blockUntilDelivered(20_000))
                        instrument.waitForIdleSync()
                        scenario.onActivity {
                            val state = sessionReference.get().machine.snapshot()
                            assertFalse(state.wantsPlayback); assertFalse(state.videoRequested); assertNull(state.speakingId)
                            assertFalse(sessionReference.get().player.playWhenReady)
                            assertEquals(listOf("Bonjour."), observed())
                        }
                        // Let the fixture's cancelled/completed callback arrive while user intent stays paused.
                        Thread.sleep(1_200)
                        scenario.onActivity {
                            assertFalse("An old TTS completion must not resume a user-paused network film", sessionReference.get().machine.snapshot().wantsPlayback)
                            assertFalse(sessionReference.get().player.playWhenReady)
                            assertEquals(listOf("Bonjour."), observed())
                            sessionReference.get().play()
                            assertNotNull(sessionReference.get().machine.snapshot().speakingId)
                            assertNotEquals(firstUtterance.get(), sessionReference.get().machine.snapshot().speakingId)
                        }
                        waitFor("The authenticated film did not end with every narration occurrence drained", 55_000) {
                            val session = sessionReference.get(); val state = session.machine.snapshot()
                            session.player.playbackState == Player.STATE_ENDED && state.speakingId == null && state.pendingCount == 0 && !state.wantsPlayback
                        }
                        instrument.waitForIdleSync()
                        assertTrue("Real network playback failed: $errors", errors.isEmpty())
                        scenario.onActivity {
                            val session = sessionReference.get()
                            val items = (0..9).flatMap { session.ahead.selected(it.toString()) }.sortedBy { it.startUs }
                            observation.assertComplete(expectedRequests, listOf(0, 0, 1, 2, 2, 3, 4, 5, 6, 7),
                                items.map { NarrationLedger.hashIdentity(it.identity) }, observed())
                        }
                        scenario.onActivity {
                            assertCompleteDemoIndex(sessionReference.get().ahead)
                            assertFalse("Ordinary network playback must not silently require a whole-file inventory", sessionReference.get().hasCompleteSubtitleIndex)
                            assertTrue("The real network film did not advance", sessionReference.get().player.currentPosition > 0)
                            assertEquals(0, sessionReference.get().machine.snapshot().recoverableCount)
                            assertFalse(sessionReference.get().player.playWhenReady)
                            assertEquals(uri.toString(), sessionReference.get().prefs.lastMedia()?.uri)
                        }
                    } finally {
                        maintenance.removeCallbacks(maintenanceTick)
                        scenario.onActivity {
                            surface?.player = null
                            sessionReference.getAndSet(null)?.close()
                            observation.detach()
                        }
                    }
                }
                assertTrue("The fixture was never read with its expected HTTP Basic authorization", server.authorizedReads.get() > 0)
                assertEquals("The client sent an unauthenticated request", 0, server.rejectedReads.get())
                assertTrue("The loopback HTTP fixture failed: ${server.failure.get()}", server.failure.get() == null)
            }
        } finally {
            maintenance.removeCallbacks(maintenanceTick)
            observation.writeDiagnostics()
            context.unregisterReceiver(receiver)
            preferences.enginePackage = ""; preferences.clearMedia()
            opaque?.let(store::deleteDirectUrl)
        }
    }

    @Test fun opaqueWebDavCredentialsReachRealByteRangeTransportAndCompleteMkvIndexer() {
        val bytes = context.resources.openRawResource(R.raw.demonstration).use { it.readBytes() }
        val store = RemoteConnectionStore(context)
        HttpFixture(bytes, "/media/demonstration.mkv").use { server ->
            val connection = RemoteConnection(label = "Synthetic authenticated WebDAV", protocol = RemoteProtocol.WEBDAV,
                host = "127.0.0.1", port = server.port, username = "synthetic-user", password = "synthetic-password", rootPath = "/media", tls = false)
            try {
                store.save(connection)
                val uri = RemoteMediaUris.create(connection.id, "/demonstration.mkv")
                assertFalse(uri.toString().contains("synthetic"))
                val source = NetworkDataSourceFactory(context).createDataSource()
                try {
                    assertEquals(64L, source.open(DataSpec.Builder().setUri(uri).setPosition(97).setLength(64).build()))
                    val result = ByteArray(64); var offset = 0
                    while (offset < result.size) {
                        val count = source.read(result, offset, result.size - offset)
                        assertTrue("Range transport stopped before the requested bytes", count > 0); offset += count
                    }
                    assertArrayEquals(bytes.copyOfRange(97, 161), result)
                    assertEquals(-1, source.read(ByteArray(1), 0, 1))
                } finally { source.close() }
                assertCompleteDemoIndex(FullSubtitleIndexer(context).scan(SavedMedia(uri.toString(), "Synthetic WebDAV index")) {})
                assertTrue("The server never received the explicit nonzero byte range", server.rangedReads.get() > 0)
                assertTrue(server.authorizedReads.get() > 0)
                assertEquals(0, server.rejectedReads.get())
                assertTrue("The loopback HTTP fixture failed: ${server.failure.get()}", server.failure.get() == null)
            } finally { store.delete(connection.id) }
        }
    }

    private fun assertCompleteDemoIndex(index: CueAheadIndex) {
        assertFalse(index.truncated)
        assertEquals("The generated demonstration has exactly eight distinct timed occurrences", 8, index.count)
        // This three-stream generated Matroska fixture uses numeric track IDs.
        val items = (0..9).flatMap { index.selected(it.toString()) }.sortedBy { it.startUs }
        assertEquals(SOURCE_TEXTS, items.map { it.raw })
        assertEquals(8, items.map { it.identity }.toSet().size)
        val repeated = items.filter { it.raw == "Oui." }
        assertEquals(2, repeated.size)
        assertNotEquals(repeated[0].identity, repeated[1].identity)
        assertEquals(2_000_000L, repeated[1].startUs - repeated[0].startUs)
    }

    /** Tiny real HTTP server: exact synthetic request target, Basic authentication,
     * correct byte ranges and connection close. It never contacts the internet. */
    private class HttpFixture(private val bytes: ByteArray, val target: String) : Closeable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = server.localPort
        val authorizedReads = AtomicInteger()
        val rejectedReads = AtomicInteger()
        val rangedReads = AtomicInteger()
        val failure = AtomicReference<String>()
        private val closed = AtomicBoolean()
        private val activeSocket = AtomicReference<Socket?>()
        private val authorization = "Basic " + Base64.encodeToString("synthetic-user:synthetic-password".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        private val thread = Thread({
            while (!closed.get()) {
                try {
                    server.accept().use { socket ->
                        activeSocket.set(socket)
                        try { serve(socket) }
                        // Media3 may close an earlier request when it seeks or is released.
                        catch (_: SocketException) { }
                        finally { activeSocket.compareAndSet(socket, null) }
                    }
                }
                catch (_: SocketException) { if (!closed.get()) failure.compareAndSet(null, "AcceptSocketException") }
                catch (error: Exception) { if (!closed.get()) failure.compareAndSet(null, error.javaClass.simpleName) }
            }
        }, "synthetic-loopback-http").apply { isDaemon = true; start() }

        private fun serve(socket: Socket) {
            socket.soTimeout = 10_000
            val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val request = input.readLine()?.split(' ') ?: return
            val headers = HashMap<String, String>()
            repeat(100) {
                val line = input.readLine() ?: return
                if (line.isEmpty()) { respond(socket, request, headers); return }
                val colon = line.indexOf(':')
                if (colon > 0) headers[line.substring(0, colon).lowercase(java.util.Locale.ROOT)] = line.substring(colon + 1).trim()
            }
            throw java.io.IOException("Too many fixture request headers")
        }

        private fun respond(socket: Socket, request: List<String>, headers: Map<String, String>) {
            val output = socket.getOutputStream()
            if (headers["authorization"] != authorization) {
                rejectedReads.incrementAndGet()
                output.write("HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1)); return
            }
            if (request.getOrNull(0) != "GET" || request.getOrNull(1) != target) {
                output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1)); return
            }
            val range = headers["range"]?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }
            val start = range?.groupValues?.get(1)?.toInt() ?: 0
            val end = range?.groupValues?.get(2)?.takeIf { it.isNotEmpty() }?.toInt()?.coerceAtMost(bytes.lastIndex) ?: bytes.lastIndex
            if (start >= bytes.size) {
                output.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */${bytes.size}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1)); return
            }
            require(start <= end)
            authorizedReads.incrementAndGet()
            if (range != null) rangedReads.incrementAndGet()
            val response = "HTTP/1.1 ${if (range == null) "200 OK" else "206 Partial Content"}\r\n" +
                "Content-Type: video/x-matroska\r\nAccept-Ranges: bytes\r\nContent-Length: ${end - start + 1}\r\n" +
                (if (range != null) "Content-Range: bytes $start-$end/${bytes.size}\r\n" else "") + "Connection: close\r\n\r\n"
            output.write(response.toByteArray(Charsets.ISO_8859_1)); output.write(bytes, start, end - start + 1); output.flush()
        }

        override fun close() { closed.set(true); activeSocket.getAndSet(null)?.close(); server.close(); thread.join(5_000) }
    }

    companion object {
        // Literal expectations from examples/demonstration.srt, never generated by the implementation under test.
        private val SOURCE_TEXTS = listOf(
            "Bonjour. Ceci est un essai des sous-titres vocalisés.",
            "Cette phrase est volontairement longue : en mode confort, la vidéo doit attendre si la voix n’a pas encore terminé de la lire.",
            "La vidéo reprend ensuite. Tu peux régler le volume du film séparément.",
            "[Musique douce] Les indications entre crochets peuvent être conservées ou ignorées.",
            "Oui.", "Oui.",
            "Tu peux maintenant mettre en pause, revenir en arrière et essayer de répéter cette phrase.",
            "Dernier essai : cette dernière phrase dépasse la fin de la vidéo et doit tout de même être lue jusqu’au bout en mode confort."
        )
    }
}

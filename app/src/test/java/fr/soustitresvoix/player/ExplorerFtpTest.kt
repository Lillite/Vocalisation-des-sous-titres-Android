package fr.soustitresvoix.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ExplorerFtpTest {
    @Test fun ftpRequestsHiddenEntriesAndFallsBackForServersWithoutListFlags() {
        val (hidden, hiddenCommands) = browse(acceptFlag = true)
        assertEquals(listOf(".dossier", "Film sans extension"), hidden.map { it.name })
        assertTrue(hidden.first().isDirectory)
        assertEquals("/.dossier", hidden.first().path)
        assertEquals(listOf("MLSD /", "LIST -a /"), hiddenCommands)

        val (regular, regularCommands) = browse(acceptFlag = false)
        assertEquals(listOf("Film sans extension"), regular.map { it.name })
        assertEquals(listOf("MLSD /", "LIST -a /", "LIST /"), regularCommands)
    }

    private fun browse(acceptFlag: Boolean): Pair<List<RemoteEntry>, List<String>> {
        val loopback = InetAddress.getByName("127.0.0.1")
        val worker = Executors.newSingleThreadExecutor()
        try {
            return ServerSocket(0, 1, loopback).use { listener ->
                listener.soTimeout = 5_000
                val server = worker.submit<List<String>> {
                    listener.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val input = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                        val output = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
                        fun reply(value: String) { output.write("$value\r\n"); output.flush() }
                        val commands = mutableListOf<String>()
                        var dataListener: ServerSocket? = null
                        reply("220 Synthetic FTP")
                        try {
                            while (true) {
                                val command = input.readLine() ?: break
                                when (command.substringBefore(' ')) {
                                    "USER", "PASS" -> reply("230 Signed in")
                                    "FEAT" -> reply("211 No features")
                                    "TYPE" -> reply("200 Binary")
                                    "SYST" -> reply("215 UNIX Type: L8")
                                    "PASV" -> {
                                        dataListener?.close()
                                        val passive = ServerSocket(0, 1, loopback).apply { soTimeout = 5_000 }
                                        dataListener = passive
                                        reply("227 Entering Passive Mode (127,0,0,1,${passive.localPort / 256},${passive.localPort % 256})")
                                    }
                                    "MLSD", "LIST" -> {
                                        commands += command
                                        val passive = requireNotNull(dataListener)
                                        dataListener = null
                                        passive.use {
                                            when {
                                                command.startsWith("MLSD") -> reply("502 MLSD unsupported")
                                                !acceptFlag && command.startsWith("LIST -a") -> reply("501 Option unsupported")
                                                else -> {
                                                    reply("150 Directory contents")
                                                    passive.accept().use { data ->
                                                        data.getOutputStream().bufferedWriter(Charsets.UTF_8).use { listing ->
                                                            if (command.startsWith("LIST -a")) listing.write("drwxr-xr-x 2 demo demo 4096 Nov 10 2026 .dossier\r\n")
                                                            listing.write("-rw-r--r-- 1 demo demo 256 Nov 10 2026 Film sans extension\r\n")
                                                        }
                                                    }
                                                    reply("226 Listing complete")
                                                }
                                            }
                                        }
                                    }
                                    else -> reply("502 Unsupported")
                                }
                            }
                        } finally { dataListener?.close() }
                        commands
                    }
                }
                val connection = RemoteConnection(label = "Synthétique", protocol = RemoteProtocol.FTP,
                    host = loopback.hostAddress, port = listener.localPort, username = "anonymous")
                val entries = RemoteCancellation().use { RemoteProtocolClients.list(connection, "/", it) }
                entries to server.get(5, TimeUnit.SECONDS)
            }
        } finally { worker.shutdownNow() }
    }
}

package fr.soustitresvoix.core

import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Bounded, read-only look-ahead for uncompressed MKV UTF8/ASS subtitle tracks.
 * Video/audio blocks are skipped by seeking, never decoded or loaded in memory.
 * Unsupported encodings/lacing/unknown timings simply have no index entry.
 */
class MkvTextIndex(private val channel: FileChannel, private val offset: Long = 0,
    length: Long = channel.size() - offset, private val cancelled: () -> Boolean = { false }) {
    private data class Element(val id: Long, val start: Long, val data: Long, val end: Long, val unknown: Boolean)
    private data class Track(val number: Long, val codec: String, val encoded: Boolean)
    private val end = Math.addExact(offset, length)
    private val deadline = System.nanoTime() + 15_000_000_000L
    private val tracks = mutableMapOf<Long, Track>()
    private val result = mutableMapOf<String, MutableList<TimedCaption>>()
    private var scale = 1_000_000L
    private var total = 0
    private var chars = 0
    private val one = ByteBuffer.allocate(1)
    private fun checkWork() {
        check(!cancelled() && !Thread.currentThread().isInterrupted && System.nanoTime() < deadline) { "Indexation interrompue" }
        check(total <= 50_000 && chars <= 8 * 1024 * 1024) { "Index trop volumineux" }
    }
    private fun byte(): Int {
        checkWork(); require(channel.position() < end)
        one.clear(); require(channel.read(one) == 1); return one.array()[0].toInt() and 255
    }
    private fun vint(id: Boolean): Pair<Long, Boolean> {
        val b = byte(); require(b != 0)
        val n = Integer.numberOfLeadingZeros(b) - 24 + 1
        require(n in 1..if (id) 4 else 8)
        var v = if (id) b.toLong() else (b and (255 ushr n)).toLong()
        repeat(n - 1) { v = (v shl 8) or byte().toLong() }
        val unknown = !id && v == (1L shl (7 * n)) - 1
        return v to unknown
    }
    private fun element(limit: Long): Element? {
        checkWork(); val start = channel.position(); if (start >= limit) return null
        val id = vint(true).first; val (size, unknown) = vint(false); val data = channel.position()
        val stop = if (unknown) limit else Math.addExact(data, size)
        require(stop in data..limit); return Element(id, start, data, stop, unknown)
    }
    private fun uint(e: Element): Long {
        require(e.end - e.data in 1..8); var n = 0L
        while (channel.position() < e.end) n = (n shl 8) or byte().toLong()
        require(n >= 0); return n
    }
    private fun string(e: Element): String {
        val n = e.end - channel.position(); require(n in 0..262_144)
        val b = ByteBuffer.allocate(n.toInt())
        while (b.hasRemaining()) { checkWork(); require(channel.read(b) > 0) }
        return Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(b.array())).toString().trimEnd('\u0000')
    }
    fun read(): Map<String, List<TimedCaption>> {
        require(offset >= 0 && end > offset); channel.position(offset)
        val ebml = element(end) ?: return emptyMap()
        if (ebml.id != 0x1A45DFA3L) return emptyMap()
        channel.position(ebml.end)
        while (channel.position() < end) {
            val s = element(end) ?: break
            if (s.id == 0x18538067L) segment(s.end)
            channel.position(s.end)
        }
        return result.mapValues { it.value.sortedBy(TimedCaption::startMs) }
    }
    private fun segment(limit: Long) {
        while (true) {
            val e = element(limit) ?: break
            when (e.id) {
                0x1549A966L -> info(e.end)
                0x1654AE6BL -> trackList(e.end)
                0x1F43B675L -> { cluster(e.end, e.unknown); if (e.unknown) continue }
            }
            channel.position(e.end)
        }
    }
    private fun info(limit: Long) {
        while (true) {
            val e = element(limit) ?: break
            if (e.id == 0x2AD7B1L) scale = uint(e).also { require(it in 1..1_000_000_000L) }
            channel.position(e.end)
        }
    }
    private fun trackList(limit: Long) {
        while (true) {
            val e = element(limit) ?: break
            if (e.id == 0xAEL) {
                var number = -1L; var type = 0L; var codec = ""; var encoded = false
                while (true) {
                    val t = element(e.end) ?: break
                    when (t.id) {
                        0xD7L -> number = uint(t)
                        0x83L -> type = uint(t)
                        0x86L -> codec = string(t)
                        0x6D80L -> encoded = true
                    }
                    channel.position(t.end)
                }
                if (type == 17L && number >= 0 && codec in setOf("S_TEXT/UTF8", "S_TEXT/ASS", "S_TEXT/SSA") && tracks.size < 32)
                    tracks[number] = Track(number, codec, encoded)
            }
            channel.position(e.end)
        }
    }
    private fun cluster(limit: Long, unknown: Boolean) {
        var timestamp = 0L
        while (true) {
            val e = element(limit) ?: break
            if (unknown && e.id in setOf(0x1F43B675L, 0x1C53BB6BL, 0x1254C367L, 0x114D9B74L, 0x1654AE6BL, 0x1549A966L)) {
                channel.position(e.start); return
            }
            if (e.id == 0xE7L) timestamp = uint(e)
            if (e.id == 0xA0L) blockGroup(e.end, timestamp)
            channel.position(e.end)
        }
    }
    private fun blockGroup(limit: Long, clusterTime: Long) {
        var block: Element? = null; var duration: Long? = null
        while (true) {
            val e = element(limit) ?: break
            if (e.id == 0xA1L) block = e
            if (e.id == 0x9BL) duration = uint(e)
            channel.position(e.end)
        }
        val b = block ?: return; val d = duration ?: return
        if (d <= 0 || b.end - b.data < 4) return
        channel.position(b.data)
        val number = vint(false).first
        val track = tracks[number] ?: return
        if (track.encoded) return
        val relative = ((byte() shl 8) or byte()).toShort().toLong()
        val flags = byte(); if (flags and 6 != 0) return
        val start = Math.multiplyExact(Math.addExact(clusterTime, relative), scale) / 1_000_000
        val stop = Math.addExact(start, Math.multiplyExact(d, scale) / 1_000_000)
        if (start < 0 || stop <= start) return
        var raw = string(b)
        if (track.codec != "S_TEXT/UTF8") {
            val parts = raw.split(',', limit = 9)
            if (parts.size != 9) return
            raw = parts[8]
        }
        total++; chars += raw.length; checkWork()
        result.getOrPut(number.toString()) { mutableListOf() }.add(TimedCaption(start, stop, raw))
    }
}

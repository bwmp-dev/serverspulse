package com.serverspulse.agent.core.geo

import java.io.BufferedReader
import java.net.InetAddress

/**
 * An in-memory IP-to-country index built from the DB-IP IP-to-Country Lite CSV.
 *
 * The CSV is held as sorted primitive range arrays and searched with a binary
 * search rather than being loaded through a GeoIP library. A library would pull
 * a reader, a database format and their transitive dependencies into every
 * shaded platform jar to answer one question — which country an address is in —
 * that a sorted array already answers in a few hundred nanoseconds.
 *
 * IPv4 and IPv6 are indexed separately because they are separate address
 * spaces; interleaving them would make the ordering meaningless.
 *
 * Instances are immutable once built and safe to read from any thread.
 */
class CountryDatabase private constructor(
    private val v4Start: LongArray,
    private val v4End: LongArray,
    private val v4Code: IntArray,
    private val v6StartHi: LongArray,
    private val v6StartLo: LongArray,
    private val v6EndHi: LongArray,
    private val v6EndLo: LongArray,
    private val v6Code: IntArray
) {
    val rangeCount: Int
        get() = v4Code.size + v6Code.size

    /** Returns an uppercase ISO-3166-1 alpha-2 code, or null if unlisted. */
    fun lookup(address: InetAddress): String? {
        val bytes = address.address ?: return null

        val v4 = toIpv4(bytes)
        if (v4 != null) {
            val index = searchV4(v4)
            if (index < 0 || v4 > v4End[index]) return null
            return decode(v4Code[index])
        }

        if (bytes.size != 16) return null
        val hi = readLong(bytes, 0)
        val lo = readLong(bytes, 8)
        val index = searchV6(hi, lo)
        if (index < 0 || compare128(hi, lo, v6EndHi[index], v6EndLo[index]) > 0) return null
        return decode(v6Code[index])
    }

    private fun searchV4(key: Long): Int {
        var low = 0
        var high = v4Start.size - 1
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (v4Start[mid] <= key) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }

    private fun searchV6(hi: Long, lo: Long): Int {
        var low = 0
        var high = v6StartHi.size - 1
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (compare128(v6StartHi[mid], v6StartLo[mid], hi, lo) <= 0) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }

    companion object {
        /**
         * Rows whose start address is below the previous row's are skipped.
         *
         * The published file is ascending within each address family and the
         * binary search depends on that. Skipping a stray row costs a handful
         * of unresolved addresses; sorting a file that should already be sorted
         * would cost a second copy of it in memory on every server that starts.
         */
        fun parse(reader: BufferedReader): CountryDatabase {
            val v4Start = LongBuffer()
            val v4End = LongBuffer()
            val v4Code = IntBuffer()
            val v6StartHi = LongBuffer()
            val v6StartLo = LongBuffer()
            val v6EndHi = LongBuffer()
            val v6EndLo = LongBuffer()
            val v6Code = IntBuffer()

            var lastV4 = -1L
            var lastV6Hi = 0L
            var lastV6Lo = 0L
            var haveV6 = false

            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty() || line[0] == '#') continue

                val first = line.indexOf(',')
                if (first <= 0) continue
                val second = line.indexOf(',', first + 1)
                if (second <= first) continue

                val code = encode(unquote(line, second + 1, line.length)) ?: continue
                val startText = unquote(line, 0, first)
                val endText = unquote(line, first + 1, second)

                if (startText.indexOf(':') < 0) {
                    val start = parseIpv4(startText) ?: continue
                    val end = parseIpv4(endText) ?: continue
                    if (start < lastV4 || end < start) continue
                    lastV4 = start
                    v4Start.add(start)
                    v4End.add(end)
                    v4Code.add(code)
                } else {
                    val start = parseIpv6(startText) ?: continue
                    val end = parseIpv6(endText) ?: continue
                    if (haveV6 && compare128(start[0], start[1], lastV6Hi, lastV6Lo) < 0) continue
                    if (compare128(end[0], end[1], start[0], start[1]) < 0) continue
                    haveV6 = true
                    lastV6Hi = start[0]
                    lastV6Lo = start[1]
                    v6StartHi.add(start[0])
                    v6StartLo.add(start[1])
                    v6EndHi.add(end[0])
                    v6EndLo.add(end[1])
                    v6Code.add(code)
                }
            }

            return CountryDatabase(
                v4Start.toArray(), v4End.toArray(), v4Code.toArray(),
                v6StartHi.toArray(), v6StartLo.toArray(),
                v6EndHi.toArray(), v6EndLo.toArray(), v6Code.toArray()
            )
        }

        /** IPv4-mapped IPv6 addresses answer from the IPv4 index, not the IPv6 one. */
        private val V4_MAPPED_PREFIX = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, -1)

        private fun toIpv4(bytes: ByteArray): Long? {
            if (bytes.size == 4) return readUnsignedInt(bytes, 0)
            if (bytes.size != 16) return null
            for (i in V4_MAPPED_PREFIX.indices) {
                if (bytes[i] != V4_MAPPED_PREFIX[i]) return null
            }
            return readUnsignedInt(bytes, 12)
        }

        private fun readUnsignedInt(bytes: ByteArray, offset: Int): Long {
            var value = 0L
            for (i in 0 until 4) {
                value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
            }
            return value
        }

        private fun readLong(bytes: ByteArray, offset: Int): Long {
            var value = 0L
            for (i in 0 until 8) {
                value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
            }
            return value
        }

        private fun compare128(aHi: Long, aLo: Long, bHi: Long, bLo: Long): Int {
            val high = java.lang.Long.compareUnsigned(aHi, bHi)
            if (high != 0) return high
            return java.lang.Long.compareUnsigned(aLo, bLo)
        }

        private fun unquote(line: String, from: Int, to: Int): String {
            var start = from
            var end = to
            while (start < end && (line[start] == '"' || line[start] == ' ')) start++
            while (end > start && (line[end - 1] == '"' || line[end - 1] == ' ' || line[end - 1] == '\r')) end--
            return line.substring(start, end)
        }

        private fun encode(code: String): Int? {
            if (code.length != 2) return null
            val first = code[0].uppercaseChar()
            val second = code[1].uppercaseChar()
            if (first < 'A' || first > 'Z' || second < 'A' || second > 'Z') return null
            return (first.code shl 8) or second.code
        }

        private fun decode(packed: Int): String {
            return charArrayOf(((packed shr 8) and 0xFF).toChar(), (packed and 0xFF).toChar())
                .concatToString()
        }

        private fun parseIpv4(text: String): Long? {
            var value = 0L
            var octet = -1
            var parts = 0

            for (index in text.indices) {
                val ch = text[index]
                if (ch == '.') {
                    if (octet < 0) return null
                    value = (value shl 8) or octet.toLong()
                    parts++
                    octet = -1
                } else {
                    val digit = ch - '0'
                    if (digit < 0 || digit > 9) return null
                    octet = (if (octet < 0) 0 else octet) * 10 + digit
                    if (octet > 255) return null
                }
            }

            if (octet < 0 || parts != 3) return null
            return (value shl 8) or octet.toLong()
        }

        /**
         * Parses a textual IPv6 address into `[high 64 bits, low 64 bits]`,
         * including `::` compression and a trailing dotted quad.
         */
        private fun parseIpv6(text: String): LongArray? {
            val groups = ShortArray(8)
            val tailGroups = ShortArray(8)
            var head = 0
            var tail = 0
            var compressed = false

            var index = 0
            val length = text.length
            if (length >= 2 && text[0] == ':' && text[1] == ':') {
                compressed = true
                index = 2
            } else if (length >= 1 && text[0] == ':') {
                return null
            }

            while (index < length) {
                if (text[index] == ':') {
                    if (compressed) return null
                    compressed = true
                    index++
                    continue
                }

                var end = index
                while (end < length && text[end] != ':') end++
                val token = text.substring(index, end)

                if (token.indexOf('.') >= 0) {
                    val packed = parseIpv4(token) ?: return null
                    val highGroup = ((packed shr 16) and 0xFFFF).toShort()
                    val lowGroup = (packed and 0xFFFF).toShort()
                    if (compressed) {
                        if (tail + 2 > 8) return null
                        tailGroups[tail++] = highGroup
                        tailGroups[tail++] = lowGroup
                    } else {
                        if (head + 2 > 8) return null
                        groups[head++] = highGroup
                        groups[head++] = lowGroup
                    }
                } else {
                    if (token.isEmpty() || token.length > 4) return null
                    var group = 0
                    for (ch in token) {
                        val digit = Character.digit(ch, 16)
                        if (digit < 0) return null
                        group = (group shl 4) or digit
                    }
                    if (compressed) {
                        if (tail >= 8) return null
                        tailGroups[tail++] = group.toShort()
                    } else {
                        if (head >= 8) return null
                        groups[head++] = group.toShort()
                    }
                }

                index = end
                if (index < length) index++
            }

            if (compressed) {
                if (head + tail > 7) return null
                for (i in head until 8 - tail) {
                    groups[i] = 0
                }
                for (i in 0 until tail) {
                    groups[8 - tail + i] = tailGroups[i]
                }
            } else if (head != 8) {
                return null
            }

            var hi = 0L
            var lo = 0L
            for (i in 0 until 4) {
                hi = (hi shl 16) or (groups[i].toLong() and 0xFFFF)
            }
            for (i in 4 until 8) {
                lo = (lo shl 16) or (groups[i].toLong() and 0xFFFF)
            }
            return longArrayOf(hi, lo)
        }
    }

    private class LongBuffer {
        private var data = LongArray(4096)
        private var size = 0

        fun add(value: Long) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = value
        }

        fun toArray(): LongArray = data.copyOf(size)
    }

    private class IntBuffer {
        private var data = IntArray(4096)
        private var size = 0

        fun add(value: Int) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = value
        }

        fun toArray(): IntArray = data.copyOf(size)
    }
}

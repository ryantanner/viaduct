package viaduct.graphql.schema.binary

import java.io.Closeable
import java.io.InputStream
import java.nio.charset.StandardCharsets

private const val LEN = 128 * 1024

internal class BInputStream(
    private val input: InputStream,
    /** Max length _including_ trailing delimiter. */
    private val maxStringLength: Int,
    /** Buffer size, defaults to LEN. Exposed for testing. */
    private val bufLen: Int = LEN
) : Closeable {
    init {
        if (maxStringLength < 0) {
            throw IllegalArgumentException("maxStringLength must be at least $0 ($maxStringLength).")
        } else if (bufLen < maxStringLength) {
            throw IllegalArgumentException("maxStringLength may not be larger than bufLen ($maxStringLength > $bufLen).")
        }
    }

    private val buf = ByteArray(bufLen)
    private var pos = 0
    private var lim = 0
    private var mOffset = 0

    private fun refill() {
        val remaining = lim - pos
        System.arraycopy(buf, pos, buf, 0, remaining)
        val newBytes = input.read(buf, remaining, bufLen - remaining)
        pos = 0
        lim =
            if (newBytes < 0) {
                if (remaining == 0) {
                    throw InvalidFileFormatException("Reached EOF.")
                } else {
                    remaining
                }
            } else {
                remaining + newBytes
            }
    }

    fun read(): Int {
        if (pos == lim) refill()
        mOffset++
        return buf[pos++].toInt() and 0xFF
    }

    fun readInt(): Int {
        if (lim < pos + 4) refill()
        mOffset += WORD_SIZE
        var result = buf[pos++].toInt() and 0xFF // LSB first for little-endian
        result = result or ((buf[pos++].toInt() and 0xFF) shl 8)
        result = result or ((buf[pos++].toInt() and 0xFF) shl 16)
        result = result or ((buf[pos++].toInt() and 0xFF) shl 24) // MSB last
        return result
    }

    fun readLong(): Long {
        if (lim < pos + 8) refill()
        mOffset += 2 * WORD_SIZE
        var result = (buf[pos++].toInt() and 0xFF).toLong() // LSB first for little-endian
        result = result or ((buf[pos++].toInt() and 0xFF).toLong() shl 8)
        result = result or ((buf[pos++].toInt() and 0xFF).toLong() shl 16)
        result = result or ((buf[pos++].toInt() and 0xFF).toLong() shl 24)
        result = result or ((buf[pos++].toInt() and 0xFF).toLong() shl 32)
        result = result or ((buf[pos++].toInt() and 0xFF).toLong() shl 40)
        result = result or ((buf[pos++].toInt() and 0xFF).toLong() shl 48)
        result = result or ((buf[pos++].toInt() and 0xFF).toLong() shl 56) // MSB last
        return result
    }

    /**
     * Reads the next identifier but does _not_ consume the byte
     * terminating the identifier.  This allows the reading code
     * to read the metadata contain in that terminating byte.
     */
    fun readIdentifier(): String {
        if (lim < (pos + 11)) refill() // Need 11 bytes: 10 for fast path + 1 for terminator check
        var len = 0
        while (0 < buf[pos + len] && len < 10) len++
        if (0 < buf[pos + len]) {
            if (lim < (pos + maxStringLength)) refill()
            do {
                len++
            } while (0 < buf[pos + len])
        }
        // encoding.md says that identifiers are from the ASCII, not Latin-1
        // character set, so one might expect `US_ASCII` here.  However,
        // the only difference between the two is that `US_ASCII` checks
        // that bit 7 is zero -- this check is overhead -- and our code elsewhere
        // ensures that bit 7 is zero, so we're using the cheaper latin-1 encoder
        val result = String(buf, pos, len, StandardCharsets.ISO_8859_1)
        pos += len
        mOffset += len
        return result
    }

    /*
     * Reads the entire string and (unlike [readIdentifier] consumes the
     * terminating byte.
     */
    fun readUTF8String(): String {
        if (lim < (pos + 21)) refill() // Need 21 bytes: 20 for fast path + 1 for terminator check
        var len = 0
        while (buf[pos + len] != 0.toByte() && len < 20) len++
        if (buf[pos + len] != 0.toByte()) {
            if (lim < (pos + maxStringLength)) refill()
            do {
                len++
            } while (buf[pos + len] != 0.toByte())
        }
        val result = String(buf, pos, len, StandardCharsets.UTF_8)
        pos += len + 1 // Skip past the null terminator
        mOffset += len + 1
        return result
    }

    fun skipPadding() {
        val remainder = mOffset.rem(WORD_SIZE)
        val toRead = if (remainder == 0) 0 else WORD_SIZE - remainder
        repeat(toRead) { read() }
    }

    fun validateMagicNumber(
        expected: Int,
        sectionName: String
    ) {
        val actual = readInt()
        require(expected == actual) {
            "Invalid magic number for $sectionName section: expected 0x${expected.toString(16)}, got 0x${actual.toString(16)}"
        }
    }

    override fun close() {
        input.close()
    }
}

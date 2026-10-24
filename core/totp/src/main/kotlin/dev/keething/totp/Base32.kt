package dev.keething.totp

import java.io.ByteArrayOutputStream

public object Base32 {
    /** RFC 4648, case-insensitive; ignores spaces and `=` padding. Null on other characters. */
    public fun decode(text: String): ByteArray? {
        val out = ByteArrayOutputStream(text.length * 5 / 8)
        var buffer = 0
        var bits = 0
        for (c in text) {
            val value = when (c) {
                in 'A'..'Z' -> c - 'A'
                in 'a'..'z' -> c - 'a'
                in '2'..'7' -> c - '2' + 26
                ' ', '\t', '\n', '\r', '=' -> continue
                else -> return null
            }
            buffer = (buffer shl 5) or value
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.write((buffer ushr bits) and 0xFF)
                buffer = buffer and ((1 shl bits) - 1)
            }
        }
        return out.toByteArray()
    }

    /** RFC 4648 without padding, as otpauth links carry it. */
    public fun encode(bytes: ByteArray): String = buildString {
        var buffer = 0
        var bits = 0
        for (byte in bytes) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                append(ALPHABET[(buffer ushr bits) and 31])
            }
            buffer = buffer and ((1 shl bits) - 1)
        }
        if (bits > 0) append(ALPHABET[(buffer shl (5 - bits)) and 31])
    }

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
}

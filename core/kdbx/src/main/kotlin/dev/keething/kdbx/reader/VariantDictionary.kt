package dev.keething.kdbx.reader

import dev.keething.kdbx.crypto.Bytes
import java.io.ByteArrayOutputStream

/** KeePass's typed key-value map (the KDF parameters), in the order it was read. */
internal class VariantDictionary private constructor(private val values: LinkedHashMap<String, Value>) {
    private class Value(val type: Int, val data: ByteArray)

    fun bytes(key: String): ByteArray? = values[key]?.takeIf { it.type == BYTES }?.data?.copyOf()

    fun uint32(key: String): Long? =
        values[key]?.takeIf { it.type == UINT32 && it.data.size == 4 }?.let { Bytes.intLe(it.data, 0).toLong() and 0xFFFF_FFFFL }

    /** An unsigned 64-bit number; null when it does not fit a Long (no real parameter is that large). */
    fun uint64(key: String): Long? = uint64Bits(key)?.takeIf { it >= 0 }

    /** An unsigned 64-bit number's bits: above `Long.MAX_VALUE` it is negative (the KDF's costs, checked as unsigned). */
    fun uint64Bits(key: String): Long? =
        values[key]?.takeIf { it.type == UINT64 && it.data.size == 8 }?.let { Bytes.longLe(it.data, 0) }

    fun contains(key: String): Boolean = key in values

    /** The keys in the order they were read. */
    fun keys(): List<String> = values.keys.toList()

    /** Writes one in the order of the calls, as KeePass's `VariantDictionary.Serialize` does (version 1.0). */
    class Writer {
        private val out = ByteArrayOutputStream().apply { write(0x00); write(0x01) }

        fun bytes(key: String, value: ByteArray): Writer = item(BYTES, key, value)

        fun uint32(key: String, value: Long): Writer = item(UINT32, key, Bytes.le32(value.toInt()))

        fun uint64(key: String, value: Long): Writer = item(UINT64, key, Bytes.le64(value))

        fun finish(): ByteArray = out.apply { write(0) }.toByteArray()

        private fun item(type: Int, key: String, value: ByteArray): Writer = apply {
            val name = key.encodeToByteArray()
            out.write(type)
            out.write(Bytes.le32(name.size))
            out.write(name)
            out.write(Bytes.le32(value.size))
            out.write(value)
        }
    }

    companion object {
        private const val UINT32 = 0x04
        private const val UINT64 = 0x05
        private const val BYTES = 0x42

        fun parse(bytes: ByteArray): VariantDictionary {
            val r = ByteReader(bytes)
            val version = r.uint16() ?: throw KdbxException.Corrupt("KDF parameters version")
            if (version shr 8 != 1) throw KdbxException.Corrupt("KDF parameters version")
            val values = LinkedHashMap<String, Value>()
            while (true) {
                val type = r.uint8() ?: throw KdbxException.Corrupt("KDF parameters")
                if (type == 0) return VariantDictionary(values)
                val key = r.size()?.let(r::bytes) ?: throw KdbxException.Corrupt("KDF parameters")
                val value = r.size()?.let(r::bytes) ?: throw KdbxException.Corrupt("KDF parameters")
                values[key.decodeToString()] = Value(type, value)
            }
        }
    }
}

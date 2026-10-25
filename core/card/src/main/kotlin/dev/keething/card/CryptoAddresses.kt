package dev.keething.card

import java.math.BigInteger
import java.security.MessageDigest

/** The networks whose addresses the wallet's face recognises by their format (SPEC §6.2). */
public enum class CryptoNetwork(public val displayName: String) {
    BITCOIN("Bitcoin"),
    BITCOIN_TESTNET("Bitcoin testnet"),
    LITECOIN("Litecoin"),
    DOGECOIN("Dogecoin"),

    /** Also any other EVM network (Polygon, BNB Smart Chain, Arbitrum…): their addresses are the same. */
    ETHEREUM("Ethereum"),
    TRON("Tron"),
    SOLANA("Solana"),
}

/** Whether an address's own checksum was checked. */
public enum class AddressCheck {
    PASSES,

    /** Its format has none (Solana; an Ethereum address in one case), so a typo is not caught. */
    NONE,

    /** It fails: a typo. */
    FAILS,
}

/** A receiving address, its network read from its format ("bc1q…", "0x…"), and its checksum. */
public data class CryptoAddress(val text: String, val network: CryptoNetwork, val kind: String, val check: AddressCheck) {
    public companion object {
        /** The address [text] is (outer spaces aside); null when it has no format known here. */
        public fun read(text: String): CryptoAddress? {
            val t = text.trim()
            if (t.isEmpty() || t.any(Char::isWhitespace)) return null
            return segwit(t) ?: ethereum(t) ?: base58(t)
        }

        private val SEGWIT_PREFIXES = mapOf("bc" to CryptoNetwork.BITCOIN, "tb" to CryptoNetwork.BITCOIN_TESTNET, "ltc" to CryptoNetwork.LITECOIN)

        private fun segwit(t: String): CryptoAddress? {
            val lower = t.lowercase()
            val separator = lower.lastIndexOf('1')
            if (separator < 1) return null
            val network = SEGWIT_PREFIXES[lower.substring(0, separator)] ?: return null
            val decoded = Bech32.decodeSegwit(lower.substring(0, separator), t)
            val prefix = lower.substring(0, separator) + "1"
            if (decoded == null) {
                // Its prefix and alphabet say what it is; the rest fails.
                if (lower.length < 14 || lower.drop(separator + 1).any { it !in Bech32.CHARSET }) return null
                return CryptoAddress(t, network, "SegWit ($prefix…)", AddressCheck.FAILS)
            }
            val (version, program) = decoded
            val kind = when {
                version == 0 && program.size == 20 -> "Native SegWit (${prefix}q…)"
                version == 0 -> "Native SegWit script (${prefix}q…)"
                version == 1 && program.size == 32 -> "Taproot (${prefix}p…)"
                else -> "SegWit v$version"
            }
            return CryptoAddress(t, network, kind, AddressCheck.PASSES)
        }

        private val HEX40 = Regex("^0x[0-9a-fA-F]{40}$")

        /** EIP-55: a letter is upper case where the Keccak-256 of the lower-case address has a nibble of 8 or more. */
        private fun ethereum(t: String): CryptoAddress? {
            if (!HEX40.matches(t)) return null
            val hex = t.substring(2)
            val kind = "or another EVM network: the same address"
            if (hex == hex.lowercase() || hex == hex.uppercase()) return CryptoAddress(t, CryptoNetwork.ETHEREUM, kind, AddressCheck.NONE)
            return CryptoAddress(t, CryptoNetwork.ETHEREUM, kind, if (eip55(hex.lowercase()) == hex) AddressCheck.PASSES else AddressCheck.FAILS)
        }

        /** The address in EIP-55's mixed case. */
        internal fun eip55(lowerHex: String): String {
            val hash = Keccak.keccak256(lowerHex.toByteArray())
            return buildString {
                for ((i, c) in lowerHex.withIndex()) {
                    val nibble = (hash[i / 2].toInt() shr (if (i % 2 == 0) 4 else 0)) and 0xF
                    append(if (c in 'a'..'f' && nibble >= 8) c.uppercaseChar() else c)
                }
            }
        }

        /** Base58Check by the version byte; Solana's 32 bytes without a checksum, by their shape. */
        private fun base58(t: String): CryptoAddress? {
            if (t.length !in 25..44) return null
            val bytes = Base58.decode(t) ?: return null
            if (bytes.size == 25) {
                val kind = VERSIONS[bytes[0].toInt() and 0xFF]
                if (kind != null && t.length in 26..35) {
                    return CryptoAddress(t, kind.first, kind.second, if (Base58.checks(bytes)) AddressCheck.PASSES else AddressCheck.FAILS)
                }
            }
            if (bytes.size == 32 && t.length >= 32) return CryptoAddress(t, CryptoNetwork.SOLANA, "no checksum in its addresses", AddressCheck.NONE)
            return null
        }

        private val VERSIONS: Map<Int, Pair<CryptoNetwork, String>> = mapOf(
            0x00 to (CryptoNetwork.BITCOIN to "Legacy (1…)"),
            0x05 to (CryptoNetwork.BITCOIN to "Script (3…)"),
            0x6F to (CryptoNetwork.BITCOIN_TESTNET to "Legacy (m… / n…)"),
            0xC4 to (CryptoNetwork.BITCOIN_TESTNET to "Script (2…)"),
            0x30 to (CryptoNetwork.LITECOIN to "Legacy (L…)"),
            0x32 to (CryptoNetwork.LITECOIN to "Script (M…)"),
            0x1E to (CryptoNetwork.DOGECOIN to "Legacy (D…)"),
            0x16 to (CryptoNetwork.DOGECOIN to "Script (9… / A…)"),
            0x41 to (CryptoNetwork.TRON to "Tron (T…)"),
        )
    }
}

/**
 * An extended key as BIP-32 writes it (`xpub…`, `zpub…`): the public one lets anyone see every address and balance of the
 * wallet; a private one (`xprv…`) is the wallet itself, and is treated as the recovery phrase is.
 */
public data class ExtendedKey(val text: String, val prefix: String, val isPrivate: Boolean, val kind: String, val network: CryptoNetwork, val passes: Boolean) {
    public companion object {
        public fun read(text: String): ExtendedKey? {
            val t = text.trim()
            val prefix = t.take(4)
            val (network, private, kind) = PREFIXES[prefix] ?: return null
            if (t.length !in 100..115) return null
            val bytes = Base58.decode(t) ?: return ExtendedKey(t, prefix, private, kind, network, passes = false)
            return ExtendedKey(t, prefix, private, kind, network, passes = bytes.size == 82 && Base58.checks(bytes))
        }

        private val PREFIXES: Map<String, Triple<CryptoNetwork, Boolean, String>> = buildMap {
            for ((pub, prv, kind) in listOf(
                Triple("xpub", "xprv", "BIP-44, or any"),
                Triple("ypub", "yprv", "nested SegWit"),
                Triple("zpub", "zprv", "native SegWit"),
                Triple("Ypub", "Yprv", "nested SegWit multisig"),
                Triple("Zpub", "Zprv", "native SegWit multisig"),
            )) {
                put(pub, Triple(CryptoNetwork.BITCOIN, false, kind))
                put(prv, Triple(CryptoNetwork.BITCOIN, true, kind))
            }
            for ((pub, prv, kind) in listOf(Triple("tpub", "tprv", "BIP-44, or any"), Triple("upub", "uprv", "nested SegWit"), Triple("vpub", "vprv", "native SegWit"))) {
                put(pub, Triple(CryptoNetwork.BITCOIN_TESTNET, false, kind))
                put(prv, Triple(CryptoNetwork.BITCOIN_TESTNET, true, kind))
            }
            put("Ltub", Triple(CryptoNetwork.LITECOIN, false, "BIP-44"))
            put("Mtub", Triple(CryptoNetwork.LITECOIN, false, "SegWit"))
        }
    }
}

/** A private key in Wallet Import Format (`5…`, `K…`, `L…`; `9…`, `c…` on testnet): one address's coins. */
public object WifKey {
    /** Base58Check of version 0x80 (0xEF on testnet), 32 bytes of key and maybe the 0x01 of a compressed one. */
    public fun isKey(text: String): Boolean {
        val t = text.trim()
        if (t.length !in 51..52) return false
        val bytes = Base58.decode(t) ?: return false
        val version = bytes.firstOrNull()?.toInt()?.and(0xFF)
        val shaped = bytes.size == 37 || (bytes.size == 38 && bytes[33] == 1.toByte())
        return shaped && (version == 0x80 || version == 0xEF) && Base58.checks(bytes)
    }
}

/** A BIP-32 derivation path (`m/84'/0'/0'`) and what its purpose, coin and account say. */
public object DerivationPath {
    private val PATH = Regex("^[mM](/\\d+['hH]?)+$")

    /** Written as a path: `m`, then numbers, hardened ones with `'` or `h`. */
    public fun isPath(text: String): Boolean = PATH.matches(text.trim())

    /** "BIP-84 · Bitcoin, native SegWit · account 1"; null when it is no path, or says nothing known. */
    public fun describe(text: String): String? {
        val t = text.trim()
        if (!PATH.matches(t)) return null
        val levels = t.split('/').drop(1).map { it.trimEnd('\'', 'h', 'H').toLongOrNull() ?: return null }
        val purpose = PURPOSES[levels[0]] ?: return null
        val coin = levels.getOrNull(1)?.let { COINS[it] }
        val account = levels.getOrNull(2)?.let { "account ${it + 1}" }
        return listOfNotNull("BIP-${levels[0]}", listOfNotNull(coin, purpose.takeIf { it.isNotEmpty() }).joinToString(", ").takeIf { it.isNotEmpty() }, account).joinToString(" · ")
    }

    /** The network the path's coin type names; null for none known here. */
    public fun network(text: String): CryptoNetwork? {
        val t = text.trim()
        if (!PATH.matches(t)) return null
        val coin = t.split('/').getOrNull(2)?.trimEnd('\'', 'h', 'H')?.toLongOrNull() ?: return null
        return NETWORKS[coin]
    }

    private val NETWORKS = mapOf(
        0L to CryptoNetwork.BITCOIN, 1L to CryptoNetwork.BITCOIN_TESTNET, 2L to CryptoNetwork.LITECOIN, 3L to CryptoNetwork.DOGECOIN,
        60L to CryptoNetwork.ETHEREUM, 195L to CryptoNetwork.TRON, 501L to CryptoNetwork.SOLANA,
    )
    private val PURPOSES = mapOf(44L to "", 49L to "nested SegWit", 84L to "native SegWit", 86L to "Taproot", 48L to "multisig")
    private val COINS = mapOf(0L to "Bitcoin", 1L to "testnet", 2L to "Litecoin", 3L to "Dogecoin", 60L to "Ethereum", 195L to "Tron", 501L to "Solana")
}

/** Base58 as Bitcoin writes it, and its check: the last 4 bytes are the first of the double SHA-256 of the rest. */
internal object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    fun decode(text: String): ByteArray? {
        var value = BigInteger.ZERO
        for (c in text) {
            val digit = ALPHABET.indexOf(c).takeIf { it >= 0 } ?: return null
            value = value.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(digit.toLong()))
        }
        val body = value.toByteArray().let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }.takeIf { value.signum() != 0 } ?: ByteArray(0)
        val zeros = text.takeWhile { it == '1' }.length
        return ByteArray(zeros) + body
    }

    fun checks(bytes: ByteArray): Boolean {
        if (bytes.size < 5) return false
        val sha = MessageDigest.getInstance("SHA-256")
        val hash = sha.digest(sha.digest(bytes.copyOfRange(0, bytes.size - 4)))
        return (0 until 4).all { hash[it] == bytes[bytes.size - 4 + it] }
    }
}

/** BIP-173 / BIP-350: Bech32 for SegWit version 0, Bech32m for the later ones. */
internal object Bech32 {
    const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private const val BECH32 = 1
    private const val BECH32M = 0x2bc830a3

    private fun polymod(values: IntArray): Int {
        val generator = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        var chk = 1
        for (v in values) {
            val top = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0 until 5) if ((top ushr i) and 1 == 1) chk = chk xor generator[i]
        }
        return chk
    }

    /** The witness version and program of a SegWit address with prefix [hrp]; null when it fails. Mixed case fails. */
    fun decodeSegwit(hrp: String, address: String): Pair<Int, ByteArray>? {
        if (address.length > 90 || (address != address.lowercase() && address != address.uppercase())) return null
        val lower = address.lowercase()
        val separator = lower.lastIndexOf('1')
        if (lower.substring(0, separator) != hrp || lower.length - separator - 1 < 7) return null
        val data = lower.substring(separator + 1).map { c -> CHARSET.indexOf(c).takeIf { it >= 0 } ?: return null }
        val expanded = hrp.map { it.code ushr 5 } + 0 + hrp.map { it.code and 31 } + data
        val constant = polymod(expanded.toIntArray())
        if (constant != BECH32 && constant != BECH32M) return null
        val values = data.dropLast(6)
        if (values.isEmpty()) return null
        val version = values[0]
        if (version > 16) return null
        if ((version == 0) != (constant == BECH32)) return null
        val program = convertBits(values.drop(1)) ?: return null
        if (program.size !in 2..40 || (version == 0 && program.size != 20 && program.size != 32)) return null
        return version to program
    }

    /** 5-bit groups to bytes, padding of at most 4 zero bits. */
    private fun convertBits(values: List<Int>): ByteArray? {
        var acc = 0
        var bits = 0
        val out = mutableListOf<Byte>()
        for (v in values) {
            acc = (acc shl 5) or v
            bits += 5
            while (bits >= 8) {
                bits -= 8
                out += ((acc ushr bits) and 0xFF).toByte()
            }
        }
        if (bits >= 5 || ((acc shl (8 - bits)) and 0xFF) != 0) return null
        return out.toByteArray()
    }
}

/** Keccak-256 as Ethereum uses it (the original padding, not SHA3-256's), for EIP-55's checksum. */
internal object Keccak {
    private val ROUND_CONSTANTS = listOf(
        "0000000000000001", "0000000000008082", "800000000000808A", "8000000080008000", "000000000000808B", "0000000080000001",
        "8000000080008081", "8000000000008009", "000000000000008A", "0000000000000088", "0000000080008009", "000000008000000A",
        "000000008000808B", "800000000000008B", "8000000000008089", "8000000000008003", "8000000000008002", "8000000000000080",
        "000000000000800A", "800000008000000A", "8000000080008081", "8000000000008080", "0000000080000001", "8000000080008008",
    ).map { it.toULong(16).toLong() }
    private val ROTATIONS = intArrayOf(0, 1, 62, 28, 27, 36, 44, 6, 55, 20, 3, 10, 43, 25, 39, 41, 45, 15, 21, 8, 18, 2, 61, 56, 14)
    private const val RATE = 136

    fun keccak256(input: ByteArray): ByteArray {
        val state = LongArray(25)
        val padded = ByteArray(((input.size / RATE) + 1) * RATE)
        input.copyInto(padded)
        padded[input.size] = (padded[input.size].toInt() xor 0x01).toByte()
        padded[padded.size - 1] = (padded[padded.size - 1].toInt() xor 0x80).toByte()
        for (block in 0 until padded.size / RATE) {
            for (i in 0 until RATE / 8) {
                var lane = 0L
                for (b in 0 until 8) lane = lane or ((padded[block * RATE + i * 8 + b].toLong() and 0xFF) shl (8 * b))
                state[i] = state[i] xor lane
            }
            permute(state)
        }
        return ByteArray(32) { i -> (state[i / 8] ushr (8 * (i % 8))).toByte() }
    }

    private fun permute(a: LongArray) {
        val c = LongArray(5)
        val b = LongArray(25)
        for (round in 0 until 24) {
            for (x in 0 until 5) c[x] = a[x] xor a[x + 5] xor a[x + 10] xor a[x + 15] xor a[x + 20]
            for (x in 0 until 5) {
                val d = c[(x + 4) % 5] xor java.lang.Long.rotateLeft(c[(x + 1) % 5], 1)
                for (y in 0 until 5) a[x + 5 * y] = a[x + 5 * y] xor d
            }
            for (x in 0 until 5) for (y in 0 until 5) {
                b[y + 5 * ((2 * x + 3 * y) % 5)] = java.lang.Long.rotateLeft(a[x + 5 * y], ROTATIONS[x + 5 * y])
            }
            for (x in 0 until 5) for (y in 0 until 5) {
                a[x + 5 * y] = b[x + 5 * y] xor (b[(x + 1) % 5 + 5 * y].inv() and b[(x + 2) % 5 + 5 * y])
            }
            a[0] = a[0] xor ROUND_CONSTANTS[round]
        }
    }
}

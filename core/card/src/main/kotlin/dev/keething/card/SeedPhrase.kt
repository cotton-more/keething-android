package dev.keething.card

import java.security.MessageDigest
import java.text.Normalizer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A wallet's recovery phrase as written (SPEC §6.2): its words, and what they are. A BIP-39 phrase is checked by its
 * checksum (the last word carries it, so a typo in any word is caught), in whichever of BIP-39's ten word lists its words
 * come from; an Electrum seed, whose words come from the same lists but which checks itself otherwise, is named as one.
 * Numbers and punctuation written between the words ("1. abandon 2. ability") are left out.
 */
public data class SeedPhrase(val words: List<String>, val check: Check) {
    public sealed interface Check {
        /** A BIP-39 phrase whose checksum is right. */
        public data class Bip39(val language: Bip39Language) : Check

        /** An Electrum seed ("Standard", "SegWit", "2FA", "2FA SegWit"): not BIP-39, restored in Electrum. */
        public data class Electrum(val kind: String) : Check

        /** A word, by its index, that is in no list the others are in. */
        public data class UnknownWord(val index: Int, val language: Bip39Language) : Check

        /** Every word in the list, but the checksum fails: a word is wrong, or two are swapped. */
        public data class ChecksumFails(val language: Bip39Language) : Check

        /** Every word in the list, but a phrase has 12, 15, 18, 21 or 24 of them. */
        public data class WordCount(val language: Bip39Language) : Check

        /** Too few of the words are in any list to be a phrase. */
        public data object NotAPhrase : Check
    }

    /** Right as a BIP-39 phrase or an Electrum seed. */
    val passes: Boolean get() = check is Check.Bip39 || check is Check.Electrum

    /** Most of its words are from a list: what the offer to type a field reads as a phrase. */
    val isWords: Boolean get() = check != Check.NotAPhrase

    /** What the check says, for the face and the editor. */
    public fun summary(): String = when (val c = check) {
        is Check.Bip39 -> "The checksum is right: BIP-39, ${c.language.displayName}"
        is Check.Electrum -> "An Electrum seed (${c.kind}), not BIP-39: restore it in Electrum"
        is Check.UnknownWord -> "Word ${c.index + 1}, “${words[c.index]}”, is not a BIP-39 word. A typo?"
        is Check.ChecksumFails -> "The checksum fails: a word is wrong, or two are swapped"
        is Check.WordCount -> "A BIP-39 phrase has 12, 15, 18, 21 or 24 words; this one has ${words.size}"
        Check.NotAPhrase -> "Not a BIP-39 phrase: most of its words are in no BIP-39 list"
    }

    public companion object {
        /** The words of [text] and what they are; null when it has no words. */
        public fun read(text: String): SeedPhrase? {
            val tokens = tokens(text)
            if (tokens.isEmpty()) return null
            val keys = tokens.map(::key)
            val known = Bip39Language.entries.associateWith { language -> keys.map(language::indexOf) }
            val best = Bip39Language.entries.maxBy { language -> known.getValue(language).count { it >= 0 } }
            val bestIndices = known.getValue(best)
            // The list's own spelling where a word is in it (accents and all), else the word as written.
            val words = tokens.mapIndexed { i, token -> if (bestIndices[i] >= 0) best.word(bestIndices[i]) else Normalizer.normalize(token, Normalizer.Form.NFC) }
            val electrum = electrumKind(text, tokens.size)
            val full = Bip39Language.entries.filter { language -> known.getValue(language).all { it >= 0 } }
            val check = when {
                full.isNotEmpty() && tokens.size in LENGTHS -> {
                    full.firstOrNull { checksumPasses(known.getValue(it)) }?.let(Check::Bip39)
                        ?: electrum?.let(Check::Electrum)
                        ?: Check.ChecksumFails(full.first())
                }
                electrum != null -> Check.Electrum(electrum)
                full.isNotEmpty() -> Check.WordCount(full.first())
                bestIndices.count { it >= 0 } * 2 >= tokens.size -> Check.UnknownWord(bestIndices.indexOfFirst { it < 0 }, best)
                else -> Check.NotAPhrase
            }
            // All words in a list: spelt as that list spells them.
            val language = when (check) {
                is Check.Bip39 -> check.language
                is Check.ChecksumFails -> check.language
                is Check.WordCount -> check.language
                else -> null
            }
            return SeedPhrase(language?.let { known.getValue(it).map(it::word) } ?: words, check)
        }

        private val LENGTHS = setOf(12, 15, 18, 21, 24)

        /** The words, without numbers ("1.", "12)") and the commas between them; any space separates (Japanese's too). */
        private fun tokens(text: String): List<String> = buildList {
            val word = StringBuilder()
            fun end() {
                val token = word.toString().trim(',', ';', '.', ')', '(', '"', '\'').replace(NUMBERED, "")
                if (token.isNotEmpty() && !token.all(Char::isDigit)) add(token)
                word.clear()
            }
            for (char in text) if (char.isWhitespace() || char == ',' || char == ';') end() else word.append(char)
            end()
        }

        private val NUMBERED = Regex("^\\d+[.)]")

        /** A word as compared: lower case, NFKD, as the lists are written. */
        internal fun key(word: String): String = Normalizer.normalize(word.lowercase(), Normalizer.Form.NFKD)

        /** BIP-39: the words' 11-bit indices make the entropy and, last, the first bits of its SHA-256. */
        private fun checksumPasses(indices: List<Int>): Boolean {
            val bits = indices.size * 11
            val checksumBits = bits / 33
            val entropy = ByteArray((bits - checksumBits) / 8)
            fun bit(n: Int): Int = (indices[n / 11] shr (10 - n % 11)) and 1
            for (n in 0 until entropy.size * 8) {
                if (bit(n) == 1) entropy[n / 8] = (entropy[n / 8].toInt() or (0x80 ushr (n % 8))).toByte()
            }
            val hash = MessageDigest.getInstance("SHA-256").digest(entropy)
            entropy.fill(0)
            for (k in 0 until checksumBits) {
                val expected = (hash[0].toInt() shr (7 - k)) and 1
                if (bit(entropy.size * 8 + k) != expected) return false
            }
            return true
        }

        /**
         * Electrum's seed versions (Electrum `mnemonic.calc_seed_type`): the HMAC-SHA512 of the text, normalized, keyed
         * "Seed version", starts with the version in hex. Only for the lengths Electrum makes, so that a BIP-39 phrase with
         * a typo is seldom taken for one.
         */
        private fun electrumKind(text: String, count: Int): String? {
            val mac = Mac.getInstance("HmacSHA512")
            mac.init(SecretKeySpec("Seed version".toByteArray(), "HmacSHA512"))
            val hex = mac.doFinal(electrumNormalized(text).toByteArray()).joinToString("") { "%02x".format(it) }
            return when {
                count !in 12..13 && !(count >= 20 && hex.startsWith("101")) && !hex.startsWith("102") -> null
                hex.startsWith("01") -> "Standard"
                hex.startsWith("100") -> "SegWit"
                hex.startsWith("101") && (count == 12 || count >= 20) -> "2FA"
                hex.startsWith("102") -> "2FA SegWit"
                else -> null
            }
        }

        /** Electrum `normalize_text`: NFKD, lower case, no accents, one space between words, none between CJK characters. */
        private fun electrumNormalized(text: String): String {
            val folded = Normalizer.normalize(text, Normalizer.Form.NFKD).lowercase()
                .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
                .split(Regex("[\\s\\u3000]+")).filter { it.isNotEmpty() }.joinToString(" ")
            return buildString {
                for ((i, char) in folded.withIndex()) {
                    if (char == ' ' && i > 0 && i < folded.length - 1 && isCjk(folded[i - 1]) && isCjk(folded[i + 1])) continue
                    append(char)
                }
            }
        }

        private fun isCjk(char: Char): Boolean = Character.UnicodeScript.of(char.code) == Character.UnicodeScript.HAN ||
            char.code in 0x3040..0x30FF || char.code in 0xAC00..0xD7AF || char.code in 0x3100..0x312F
    }
}

/** BIP-39's word lists (MIT, `resources/…/bip39`), in the BIP's order. */
public enum class Bip39Language(public val displayName: String, private val file: String) {
    ENGLISH("English", "english"),
    JAPANESE("Japanese", "japanese"),
    KOREAN("Korean", "korean"),
    SPANISH("Spanish", "spanish"),
    CHINESE_SIMPLIFIED("Chinese (simplified)", "chinese_simplified"),
    CHINESE_TRADITIONAL("Chinese (traditional)", "chinese_traditional"),
    FRENCH("French", "french"),
    ITALIAN("Italian", "italian"),
    CZECH("Czech", "czech"),
    PORTUGUESE("Portuguese", "portuguese"),
    ;

    /** The 2048 words, NFKD as the BIP writes them; read once, when first needed. */
    private val words: List<String> by lazy {
        val stream = checkNotNull(Bip39Language::class.java.getResourceAsStream("/dev/keething/card/bip39/$file.txt")) { "word list $file missing" }
        stream.bufferedReader().useLines { lines -> lines.map(String::trim).filter(String::isNotEmpty).toList() }.also { check(it.size == 2048) }
    }

    private val index: Map<String, Int> by lazy { words.withIndex().associate { (i, w) -> w to i } }

    /**
     * Without accents, for a word typed without them (the Latin lists' words are told apart without). Not for Japanese,
     * whose voicing marks tell words apart.
     */
    private val plainIndex: Map<String, Int> by lazy {
        if (this in NON_LATIN) return@lazy emptyMap()
        words.withIndex().groupBy { stripMarks(it.value) }.filterValues { it.size == 1 }.mapValues { it.value.single().index }
    }

    /** The word's index; -1 when it is not in the list. [key] is lower case and NFKD. */
    public fun indexOf(key: String): Int = index[key] ?: plainIndex[stripMarks(key)] ?: -1

    /** The word at [index], as shown (NFC). */
    public fun word(index: Int): String = Normalizer.normalize(words[index], Normalizer.Form.NFC)

    private companion object {
        val NON_LATIN = setOf(JAPANESE, KOREAN, CHINESE_SIMPLIFIED, CHINESE_TRADITIONAL)

        fun stripMarks(word: String): String = word.filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
    }
}

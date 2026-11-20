package dev.keething.card

import dev.keething.domain.text.UserText
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

        /** An Electrum seed ("Standard", "SegWit", "2FA", "2FA SegWit", Electrum's names for them): not BIP-39, restored in Electrum. */
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
    public fun summary(): UserText = when (val c = check) {
        is Check.Bip39 -> UserText.of(CardText.SEED_BIP39, c.language.displayName)
        is Check.Electrum -> UserText.of(CardText.SEED_ELECTRUM, c.kind)
        is Check.UnknownWord -> UserText.of(CardText.SEED_UNKNOWN_WORD, c.index + 1, words[c.index])
        is Check.ChecksumFails -> UserText.of(CardText.SEED_CHECKSUM_FAILS)
        is Check.WordCount -> UserText.of(CardText.SEED_WORD_COUNT, words.size)
        Check.NotAPhrase -> UserText.of(CardText.SEED_NOT_A_PHRASE)
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
            val full = Bip39Language.entries.filter { language -> known.getValue(language).all { it >= 0 } }
            // Electrum's seeds are words of these lists too: anything else is never read as one.
            val electrum = if (full.isEmpty()) null else electrumKind(text, tokens.size)
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
         * "Seed version", starts with the version in hex. Only for words of a list and the lengths Electrum makes (12 or
         * 13 words, a 2FA seed's 20 and more), so that a BIP-39 phrase with a typo, or a password, is seldom taken for one.
         */
        private fun electrumKind(text: String, count: Int): String? {
            val mac = Mac.getInstance("HmacSHA512") // not user text
            mac.init(SecretKeySpec("Seed version".toByteArray(), "HmacSHA512")) // not user text
            val hex = mac.doFinal(electrumNormalized(text).toByteArray()).joinToString("") { "%02x".format(it) }
            return when {
                count !in 12..13 && !(count >= 20 && (hex.startsWith("101") || hex.startsWith("102"))) -> null
                // Electrum's own names of its seeds, as Electrum shows them.
                hex.startsWith("01") -> "Standard" // not user text
                hex.startsWith("100") -> "SegWit" // not user text
                hex.startsWith("101") && (count == 12 || count >= 20) -> "2FA"
                hex.startsWith("102") -> "2FA SegWit" // not user text
                else -> null
            }
        }

        /**
         * Electrum `normalize_text`: NFKD, lower case, no character of a combining class other than 0, one space between
         * words (any Unicode space), none between CJK characters (Electrum's `CJK_INTERVALS`; the Mac's `ElectrumSeed`).
         */
        internal fun electrumNormalized(text: String): String {
            val folded = Normalizer.normalize(text, Normalizer.Form.NFKD).lowercase().codePoints().toArray().filter { !isCombining(it) }
            val words = mutableListOf<List<Int>>()
            var word = mutableListOf<Int>()
            for (point in folded) {
                if (Character.isWhitespace(point) || Character.isSpaceChar(point)) {
                    if (word.isNotEmpty()) words += word
                    word = mutableListOf()
                } else {
                    word += point
                }
            }
            if (word.isNotEmpty()) words += word
            val joined = words.flatMapIndexed { i, w -> if (i == 0) w else listOf(' '.code) + w }
            return buildString {
                for ((i, point) in joined.withIndex()) {
                    if (point == ' '.code && i > 0 && i < joined.size - 1 && isCjk(joined[i - 1]) && isCjk(joined[i + 1])) continue
                    appendCodePoint(point)
                }
            }
        }

        /**
         * A combining class other than 0 (Python's `unicodedata.combining`), which the JDK does not say: such a mark is put
         * before a mark of a lower class by canonical ordering, so one probe below it (U+0301, 230) and one above it (U+0334,
         * 1) tell. Every such character is a mark (Mn or Mc), so nothing else is probed.
         */
        private fun isCombining(point: Int): Boolean {
            val type = Character.getType(point)
            if (type != Character.NON_SPACING_MARK.toInt() && type != Character.COMBINING_SPACING_MARK.toInt()) return false
            val mark = String(Character.toChars(point))
            return Normalizer.normalize("a\u0301$mark", Normalizer.Form.NFD) != "a\u0301$mark" ||
                Normalizer.normalize("a$mark\u0334", Normalizer.Form.NFD) != "a$mark\u0334"
        }

        private fun isCjk(point: Int): Boolean = CJK_INTERVALS.any { point in it }

        /** Electrum's `CJK_INTERVALS`, in its order. */
        private val CJK_INTERVALS: List<IntRange> = listOf(
            0x4E00..0x9FFF, 0x3400..0x4DBF, 0x20000..0x2A6DF, 0x2A700..0x2B73F, 0x2B740..0x2B81F, // CJK unified ideographs
            0xF900..0xFAFF, 0x2F800..0x2FA1D, // CJK compatibility ideographs
            0x3190..0x319F, // Kanbun
            0x2E80..0x2EFF, 0x2F00..0x2FDF, 0x31C0..0x31EF, 0x2FF0..0x2FFF, // radicals, strokes, ideographic description
            0xE0100..0xE01EF, // variation selectors supplement
            0x3100..0x312F, 0x31A0..0x31BF, // Bopomofo
            0xFF00..0xFFEF, // halfwidth and fullwidth forms
            0x3040..0x309F, 0x30A0..0x30FF, 0x31F0..0x31FF, 0x1B000..0x1B0FF, // kana
            0xAC00..0xD7AF, 0x1100..0x11FF, 0xA960..0xA97F, 0xD7B0..0xD7FF, 0x3130..0x318F, // Hangul
            0xA4D0..0xA4FF, 0x16F00..0x16F9F, 0xA000..0xA48F, 0xA490..0xA4CF, // Lisu, Miao, Yi
        )
    }
}

/** BIP-39's word lists (MIT, `resources/…/bip39`), in the BIP's order. */
public enum class Bip39Language(private val key: CardText, private val file: String) {
    ENGLISH(CardText.LANGUAGE_ENGLISH, "english"),
    JAPANESE(CardText.LANGUAGE_JAPANESE, "japanese"),
    KOREAN(CardText.LANGUAGE_KOREAN, "korean"),
    SPANISH(CardText.LANGUAGE_SPANISH, "spanish"),
    CHINESE_SIMPLIFIED(CardText.LANGUAGE_CHINESE_SIMPLIFIED, "chinese_simplified"),
    CHINESE_TRADITIONAL(CardText.LANGUAGE_CHINESE_TRADITIONAL, "chinese_traditional"),
    FRENCH(CardText.LANGUAGE_FRENCH, "french"),
    ITALIAN(CardText.LANGUAGE_ITALIAN, "italian"),
    CZECH(CardText.LANGUAGE_CZECH, "czech"),
    PORTUGUESE(CardText.LANGUAGE_PORTUGUESE, "portuguese"),
    ;

    public val displayName: UserText get() = UserText.of(key)

    /** The 2048 words, NFKD as the BIP writes them; read once, when first needed. */
    private val words: List<String> by lazy {
        val stream = checkNotNull(Bip39Language::class.java.getResourceAsStream("/dev/keething/card/bip39/$file.txt")) { "word list $file missing" } // not user text
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

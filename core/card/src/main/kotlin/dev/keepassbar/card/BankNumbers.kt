package dev.keepassbar.card

import java.math.BigInteger
import java.util.Locale

/**
 * International bank account numbers (ISO 13616): a country, two check digits and the national number, as long as
 * the country's registry entry says. One with the right country, length and mod-97 check is unmistakable, so an
 * untyped field holding one is read as one, as dates and links are (SPEC §6.3).
 */
public object Iban {
    /** The text without spaces, upper case; null when it is not shaped as one (letters, digits and spaces only). */
    public fun compact(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.any { !it.isLetterOrDigit() && it != ' ' } || trimmed.any { it.code >= 128 }) return null
        return trimmed.filter { it != ' ' }.uppercase(Locale.ROOT)
    }

    /** A known country, its length, digits after the country: shaped as an IBAN, checked or not. */
    public fun isShaped(compact: String): Boolean {
        if (compact.length < 15 || !compact[0].isLetter() || !compact[1].isLetter() || !compact[2].isDigit() || !compact[3].isDigit()) return false
        return LENGTHS[compact.substring(0, 2)] == compact.length
    }

    /** The mod-97 check (ISO 7064): the first four characters moved to the end, letters as 10–35. */
    public fun checks(compact: String): Boolean {
        val moved = compact.substring(4) + compact.substring(0, 4)
        val digits = buildString { for (c in moved) if (c.isDigit()) append(c) else append(c - 'A' + 10) }
        return BigInteger(digits).mod(BigInteger.valueOf(97)) == BigInteger.ONE
    }

    /** An IBAN the text is, checked: its compact form; null otherwise. */
    public fun of(text: String): String? = compact(text)?.takeIf { isShaped(it) && checks(it) }

    /** In groups of four, as printed (`DE89 3704 0044 0532 0130 00`). */
    public fun grouped(compact: String): String = compact.chunked(4).joinToString(" ")

    /** Its country (ISO 3166 alpha-2). */
    public fun country(compact: String): String = compact.substring(0, 2)

    /** IBAN lengths by country, from SWIFT's IBAN registry. */
    private val LENGTHS: Map<String, Int> = mapOf(
        "AD" to 24, "AE" to 23, "AL" to 28, "AT" to 20, "AZ" to 28, "BA" to 20, "BE" to 16, "BG" to 22, "BH" to 22,
        "BI" to 27, "BR" to 29, "BY" to 28, "CH" to 21, "CR" to 22, "CY" to 28, "CZ" to 24, "DE" to 22, "DJ" to 27,
        "DK" to 18, "DO" to 28, "EE" to 20, "EG" to 29, "ES" to 24, "FI" to 18, "FK" to 18, "FO" to 18, "FR" to 27,
        "GB" to 22, "GE" to 22, "GI" to 23, "GL" to 18, "GR" to 27, "GT" to 28, "HN" to 28, "HR" to 21, "HU" to 28,
        "IE" to 22, "IL" to 23, "IQ" to 23, "IS" to 26, "IT" to 27, "JO" to 30, "KW" to 30, "KZ" to 20, "LB" to 28,
        "LC" to 32, "LI" to 21, "LT" to 20, "LU" to 20, "LV" to 21, "LY" to 25, "MC" to 27, "MD" to 24, "ME" to 22,
        "MK" to 19, "MN" to 20, "MR" to 27, "MT" to 31, "MU" to 30, "NI" to 28, "NL" to 18, "NO" to 15, "OM" to 23,
        "PK" to 24, "PL" to 28, "PS" to 29, "PT" to 25, "QA" to 29, "RO" to 24, "RS" to 22, "RU" to 33, "SA" to 24,
        "SC" to 31, "SD" to 18, "SE" to 24, "SI" to 19, "SK" to 24, "SM" to 27, "SO" to 23, "ST" to 25, "SV" to 28,
        "TL" to 23, "TN" to 24, "TR" to 26, "UA" to 29, "VA" to 22, "VG" to 24, "XK" to 20, "YE" to 30,
    )
}

/** A BIC (SWIFT code): `COBADEFFXXX`, 8 or 11 characters, its country in the fifth and sixth. */
public object Bic {
    private val SHAPE = Regex("^[A-Z]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?$")

    /** The country of a BIC; null when the text is not one (written in capitals, as BICs are: "Commerzbank" is a name). */
    public fun country(text: String): String? {
        val compact = text.trim()
        return if (SHAPE.matches(compact) && Countries.flag(compact.substring(4, 6)) != null) compact.substring(4, 6) else null
    }
}

/**
 * Russian bank details (банковские реквизиты): a 20-digit account (расчётный счёт) and the bank's 9-digit БИК, whose
 * last three digits take part in the account's check digit (the ninth); a correspondent account checks against the
 * БИК's fifth and sixth digits after a 0. Weights 7, 1, 3 over the 23 digits, the sum a multiple of 10 (the Bank of
 * Russia's rules of 1997).
 */
public object RussianAccount {
    /** A БИК of the Russian Federation: nine digits starting `04`. */
    public fun isBik(text: String): Boolean {
        val t = text.trim()
        return t.length == 9 && t.all { it in '0'..'9' } && t.startsWith("04")
    }

    /** The 20 digits of an account written with spaces or dashes; null when it is not 20 digits. */
    public fun digits(text: String): String? {
        val t = text.trim()
        if (t.any { it !in '0'..'9' && it != ' ' && it != '-' }) return null
        return t.filter { it in '0'..'9' }.takeIf { it.length == 20 }
    }

    /** The account checks against the БИК. */
    public fun accountChecks(account: String, bik: String): Boolean = keyed(bik.trim().takeLast(3) + account)

    /** The correspondent account checks against the БИК. */
    public fun correspondentChecks(account: String, bik: String): Boolean = keyed("0" + bik.trim().substring(4, 6) + account)

    /** As printed: the balance account, the currency, the check digit, the branch, the number (`40817 810 5 0000 1234567`). */
    public fun grouped(account: String): String =
        listOf(account.substring(0, 5), account.substring(5, 8), account.substring(8, 9), account.substring(9, 13), account.substring(13)).joinToString(" ")

    private fun keyed(digits: String): Boolean {
        if (digits.length != 23 || digits.any { it !in '0'..'9' }) return false
        val weights = intArrayOf(7, 1, 3)
        return digits.withIndex().sumOf { (i, c) -> (c - '0') * weights[i % 3] } % 10 == 0
    }
}

/** Countries as the faces show them: a flag and a name from an ISO 3166 code, and a code from what a field says. */
public object Countries {
    private val CODES: Set<String> = Locale.getISOCountries().toSet()

    /** The flag (two regional indicator letters, which the system font draws); null for a code that is none. */
    public fun flag(code: String): String? {
        val c = code.uppercase(Locale.ROOT)
        if (c !in CODES && c != "XK") return null
        return c.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
    }

    /** The flag shown where nothing says the country for sure: Antarctica's, no one's in particular. */
    public val UNKNOWN_FLAG: String = flag("AQ")!!

    /** The English name ("Germany"). */
    public fun name(code: String): String =
        if (code.uppercase(Locale.ROOT) == "XK") "Kosovo" else region(code).getDisplayCountry(Locale.ENGLISH)

    /**
     * The country a field names: an ISO 3166 alpha-2 or alpha-3 code, a passport's ICAO code (`D` for Germany), or a
     * name in English, German, Russian, French, Spanish or the phone's language; null when it names none.
     */
    public fun parse(text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val upper = t.uppercase(Locale.ROOT)
        if (upper == "D") return "DE"
        if (upper.length == 2 && upper in CODES) return upper
        if (upper.length == 3) ISO3[upper]?.let { return it }
        return NAMES[fold(t)]
    }

    private fun region(code: String): Locale = Locale.Builder().setRegion(code.uppercase(Locale.ROOT)).build()

    private fun fold(text: String): String = java.text.Normalizer.normalize(text.trim().lowercase(Locale.ROOT), java.text.Normalizer.Form.NFD)
        .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }.replace('ё', 'е')

    private val ISO3: Map<String, String> by lazy {
        CODES.mapNotNull { code -> runCatching { region(code).isO3Country }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { it to code } }.toMap()
    }

    private val NAMES: Map<String, String> by lazy {
        val languages = listOf(Locale.ENGLISH, Locale.GERMAN, Locale.forLanguageTag("ru"), Locale.FRENCH, Locale.forLanguageTag("es"), Locale.getDefault())
        buildMap {
            for (code in CODES) for (language in languages) {
                val name = region(code).getDisplayCountry(language)
                if (name.isNotEmpty() && name != code) putIfAbsent(fold(name), code)
            }
            // What documents and people commonly write besides the official names.
            putAll(mapOf("usa" to "US", "united states of america" to "US", "uk" to "GB", "great britain" to "GB", "england" to "GB", "russia" to "RU", "россия" to "RU", "рф" to "RU", "deutschland" to "DE", "germany" to "DE"))
        }
    }
}

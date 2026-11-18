package dev.keething.autofill.form

/** What a field asks for, as far as filling is concerned. */
internal enum class FieldKind {
    USERNAME,

    /** A phone number: the username when the form names nothing else. */
    PHONE,
    PASSWORD,

    /** A new password (sign-up, password change): not filled with the entry's password. */
    NEW_PASSWORD,

    /** A one-time code. */
    CODE,

    /** A text field that says nothing about itself: the username when it comes right before a password. */
    PLAIN,

    /** Not a login field (search, address, payment card, a checkbox, hidden). */
    IGNORED,
}

/**
 * Tells what a field asks for (SPEC §7): the autofill hints first, then the HTML `autocomplete` and `type`,
 * the input type, and last the words in the field's id, hint and HTML names.
 */
internal object FieldClassifier {
    fun kind(field: FormField): FieldKind {
        if (!field.isText || !field.isVisible || !field.isEnabled) return FieldKind.IGNORED
        val words = Words.of(field)
        fromHints(field.autofillHints, words)?.let { return it }
        fromHtml(field, words)?.let { return it }
        fromInputType(field.inputType, words)?.let { return it }
        return fromWords(words)
    }

    private fun fromHints(hints: List<String>, words: Words): FieldKind? {
        for (raw in hints) {
            val hint = raw.lowercase().filter(Char::isLetterOrDigit)
            when {
                hint == "newpassword" -> return FieldKind.NEW_PASSWORD
                hint == "password" || hint == "currentpassword" -> return words.passwordKind()
                hint == "username" || hint == "emailaddress" || hint == "email" -> return FieldKind.USERNAME
                hint.startsWith("phone") || hint == "tel" -> return FieldKind.PHONE
                "otp" in hint || "onetimecode" in hint -> return FieldKind.CODE
                // A new account's name, or anything a login form does not ask for.
                hint == "newusername" || OTHER_HINTS.any(hint::startsWith) -> return FieldKind.IGNORED
            }
        }
        return null
    }

    private fun fromHtml(field: FormField, words: Words): FieldKind? {
        if (field.htmlTag != null && !field.htmlTag.equals("input", ignoreCase = true)) return null
        val attributes = field.htmlAttributes.mapKeys { it.key.lowercase() }
        val autocomplete = attributes["autocomplete"].orEmpty().lowercase().split(' ').filter { it.isNotEmpty() }
        for (token in autocomplete) {
            when {
                token == "new-password" -> return FieldKind.NEW_PASSWORD
                token == "current-password" -> return FieldKind.PASSWORD
                token == "username" || token == "email" -> return FieldKind.USERNAME
                token == "one-time-code" -> return FieldKind.CODE
                token.startsWith("tel") -> return FieldKind.PHONE
                token.startsWith("cc-") || OTHER_AUTOCOMPLETE.any(token::startsWith) -> return FieldKind.IGNORED
            }
        }
        return when (attributes["type"]?.lowercase()) {
            "password" -> words.passwordKind()
            "email" -> FieldKind.USERNAME
            "tel" -> FieldKind.PHONE
            in IGNORED_HTML_TYPES -> FieldKind.IGNORED
            else -> null
        }
    }

    private fun fromInputType(inputType: Int, words: Words): FieldKind? {
        val variation = inputType and TYPE_MASK_VARIATION
        return when (inputType and TYPE_MASK_CLASS) {
            TYPE_CLASS_TEXT -> when (variation) {
                TYPE_TEXT_VARIATION_PASSWORD, TYPE_TEXT_VARIATION_WEB_PASSWORD -> words.passwordKind()
                // Also used to keep the keyboard from suggesting words in an ordinary field: a password only by its words.
                TYPE_TEXT_VARIATION_VISIBLE_PASSWORD -> if (words.sayPassword) words.passwordKind() else null
                TYPE_TEXT_VARIATION_EMAIL_ADDRESS, TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> FieldKind.USERNAME
                TYPE_TEXT_VARIATION_FILTER -> FieldKind.IGNORED
                else -> null
            }
            TYPE_CLASS_NUMBER -> if (variation == TYPE_NUMBER_VARIATION_PASSWORD) words.passwordKind() else null
            // A phone keypad alone is no phone number: apps ask for an amount or a quantity on it. Its words decide.
            TYPE_CLASS_PHONE -> if (words.sayPhone) FieldKind.PHONE else null
            else -> null
        }
    }

    private fun fromWords(words: Words): FieldKind = when {
        words.saySearch -> FieldKind.IGNORED
        words.sayCode -> FieldKind.CODE
        words.sayPassword -> words.passwordKind()
        words.sayUsername -> FieldKind.USERNAME
        words.sayPhone -> FieldKind.PHONE
        else -> FieldKind.PLAIN
    }

    private const val TYPE_MASK_CLASS = 0x0000000f
    private const val TYPE_MASK_VARIATION = 0x00000ff0
    private const val TYPE_CLASS_TEXT = 1
    private const val TYPE_CLASS_NUMBER = 2
    private const val TYPE_CLASS_PHONE = 3
    private const val TYPE_TEXT_VARIATION_EMAIL_ADDRESS = 0x20
    private const val TYPE_TEXT_VARIATION_PASSWORD = 0x80
    private const val TYPE_TEXT_VARIATION_VISIBLE_PASSWORD = 0x90
    private const val TYPE_TEXT_VARIATION_FILTER = 0xb0
    private const val TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS = 0xd0
    private const val TYPE_TEXT_VARIATION_WEB_PASSWORD = 0xe0
    private const val TYPE_NUMBER_VARIATION_PASSWORD = 0x10

    private val OTHER_HINTS = listOf(
        "creditcard", "giftcard", "postal", "address", "street", "personname", "name", "given", "family", "birth",
        "gender", "country", "city", "organization", "promo", "upi", "iban", "search",
    )
    private val OTHER_AUTOCOMPLETE = listOf(
        "name", "given-name", "family-name", "additional-name", "honorific", "nickname", "organization", "street",
        "address", "country", "postal-code", "bday", "sex", "url", "photo", "transaction", "language",
    )
    private val IGNORED_HTML_TYPES = setOf(
        "hidden", "search", "checkbox", "radio", "submit", "button", "image", "file", "reset", "range", "color",
        "date", "datetime-local", "month", "week", "time", "url",
    )
}

/** The words a field is named with: its id, hint and HTML names, split at camel case and punctuation, lowercased. */
internal class Words private constructor(private val text: String) {
    val saySearch: Boolean get() = SEARCH.containsMatchIn(text)
    val sayCode: Boolean get() = CODE.containsMatchIn(text) || (CODE_WORD.containsMatchIn(text) && !OTHER_CODE.containsMatchIn(text))
    val sayPassword: Boolean get() = PASSWORD.containsMatchIn(text)
    val sayUsername: Boolean get() = USERNAME.containsMatchIn(text)
    val sayPhone: Boolean get() = PHONE.containsMatchIn(text)

    /** A password field named "new", "confirm", "repeat" asks for a new password. */
    fun passwordKind(): FieldKind = if (NEW.containsMatchIn(text)) FieldKind.NEW_PASSWORD else FieldKind.PASSWORD

    companion object {
        fun of(field: FormField): Words {
            val attributes = field.htmlAttributes.mapKeys { it.key.lowercase() }
            val parts = listOfNotNull(field.idEntry, field.hint) +
                listOf("name", "id", "placeholder", "aria-label", "label", "title").mapNotNull(attributes::get)
            val text = parts.joinToString(" ") { part ->
                part.replace(CAMEL, "$1 $2").replace(SEPARATORS, " ")
            }.lowercase()
            return Words(" $text ")
        }

        private val CAMEL = Regex("([\\p{Ll}\\d])(\\p{Lu})") // not user text
        private val SEPARATORS = Regex("[^\\p{L}\\d]+")

        /**
         * `\b` spelled out with Unicode letters: the JDK's `\b` is ASCII-only (since JDK 19) without `(?U)`,
         * and Android's regex engine does not take `(?U)`. So `\bкод\b` finds "Код" on both.
         */
        private const val BOUNDARY = "(?:(?<![\\p{L}\\p{N}])(?=[\\p{L}\\p{N}])|(?<=[\\p{L}\\p{N}])(?![\\p{L}\\p{N}]))"

        private fun words(vararg patterns: String) = Regex(patterns.joinToString("|").replace("\\b", BOUNDARY))

        private val SEARCH = words("search", "suche", "поиск", "\\bquery\\b", "captcha", "капча")
        private val CODE = words(
            "\\botp\\b", "\\btotp\\b", "\\b2fa\\b", "\\bmfa\\b", "one ?time", "onetime", "einmal", "verification ?code",
            "verify ?code", "auth ?code", "authenticator", "security ?token", "bestätigungscode", "одноразов", "код подтвержд",
        )
        /** "Code" alone, when it is not a postal, promo or voucher code. */
        private val CODE_WORD = words("\\bcode\\b", "\\bкод\\b")
        private val OTHER_CODE = words(
            "postal", "\\bzip\\b", "\\bpost", "\\bplz\\b", "promo", "voucher", "coupon", "gutschein", "rabatt", "discount",
            "country", "area", "referral", "invit", "промо", "индекс", "купон",
        )
        private val PASSWORD = words(
            "password", "passwort", "passwd", "kennwort", "passcode", "\\bpass\\b", "\\bpwd\\b", "\\bpw\\b", "\\bpin\\b",
            "пароль", "contraseña", "mot de passe", // not user text
        )
        private val NEW = words(
            "\\bnew\\b", "\\bneu", "confirm", "repeat", "retype", "re ?enter", "wiederhol", "bestätig", "\\bнов", "повтор",
            "подтвержд",
        )
        private val USERNAME = words(
            "user", "login", "log ?on", "e ?mail", "\\bmail\\b", "account", "benutzer", "anmeldename", "kunden", "customer",
            "member", "identifier", "логин", "почт", "пользовател", "учетн", "учётн",
        )
        private val PHONE = words("phone", "mobile", "handy", "rufnummer", "telefon", "\\btel\\b", "телефон")
    }
}

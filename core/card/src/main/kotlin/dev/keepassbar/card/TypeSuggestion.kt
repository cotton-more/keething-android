package dev.keepassbar.card

import dev.keepassbar.domain.vault.CustomField
import dev.keepassbar.domain.vault.Entry
import dev.keepassbar.domain.vault.EntryChanges
import dev.keepassbar.search.SearchNormalizer

/**
 * An entry whose fields without a type are named as a payment card's, a Wi-Fi network's, a bank account's, an identity
 * document's or an SSH login's parts (SPEC §6.3), as 1Password's import and hand-made entries name them: the card offers
 * to give them those types, which makes the entry's face here (and the Mac's view of cards and networks). Nothing is
 * typed without the user's tap. Read by the names, and by the values where a value says what it is: a card number must
 * look like one, an IBAN is one by its check, a host must be a host.
 */
public data class TypeSuggestion(val kind: Kind, val types: Map<String, FieldType>) {
    public enum class Kind(public val noun: String) {
        PAYMENT_CARD("a payment card"),
        WIFI("a Wi-Fi network"),
        BANK_ACCOUNT("a bank account"),
        DOCUMENT("an identity document"),
        SSH("an SSH login"),
        CRYPTO("a crypto wallet"),
    }

    /** The entry's types with these added (Mac SPEC §3.12): saved as any edit. */
    public fun changes(entry: Entry): EntryChanges =
        EntryChanges(fieldTypes = entry.fieldTypes + types.mapValues { it.value.typeName })

    public companion object {
        public fun of(entry: Entry): TypeSuggestion? = card(entry) ?: wifi(entry) ?: bank(entry) ?: document(entry) ?: ssh(entry) ?: crypto(entry)

        private fun card(entry: Entry): TypeSuggestion? {
            val interpreter = FieldInterpreter(entry)
            if (entry.customFields.any { interpreter.type(it) == FieldType.CARD_NUMBER }) return null
            val free = entry.customFields.filter { interpreter.type(it) == null }
            val number = free.firstOrNull { name(it) in NUMBER && looksLikeNumber(it) } ?: return null
            val types = linkedMapOf(number.name to FieldType.CARD_NUMBER)
            for ((type, names) in CARD_PARTS) {
                free.firstOrNull { it !== number && it.name !in types && name(it) in names }?.let { types[it.name] = type }
            }
            // A number alone could be anything's: a card has more.
            if (types.values.none { it == FieldType.CARD_EXPIRY || it == FieldType.CARD_CVC || it == FieldType.CARD_HOLDER }) return null
            return TypeSuggestion(Kind.PAYMENT_CARD, types)
        }

        private fun wifi(entry: Entry): TypeSuggestion? {
            val interpreter = FieldInterpreter(entry)
            if (entry.customFields.any { interpreter.type(it) == FieldType.WIFI_SSID }) return null
            val free = entry.customFields.filter { interpreter.type(it) == null }
            val ssid = free.firstOrNull { name(it) in SSID && !it.plainText.isNullOrBlank() } ?: return null
            val password = free.firstOrNull { name(it) in WIFI_PASSWORD && !it.isEmpty }
            val security = free.firstOrNull { name(it) in SECURITY }
            // Without a password field the face says the network is open: only when it says so itself.
            val open = security?.plainText?.let { SearchNormalizer.normalize(it).trim() in OPEN } == true
            if (password == null && !open) return null
            val types = linkedMapOf(ssid.name to FieldType.WIFI_SSID)
            password?.let { types[it.name] = FieldType.WIFI_PASSWORD }
            security?.let { types[it.name] = FieldType.WIFI_SECURITY }
            return TypeSuggestion(Kind.WIFI, types)
        }

        private fun bank(entry: Entry): TypeSuggestion? {
            val interpreter = FieldInterpreter(entry)
            if (entry.customFields.any { interpreter.type(it) == FieldType.BANK_IBAN || interpreter.type(it) == FieldType.BANK_ACCOUNT }) return null
            val free = entry.customFields.filter { interpreter.type(it) == null }
            // An IBAN by its value (unprotected) or its name (protected); else a Russian account with its БИК.
            val iban = free.firstOrNull { field -> field.plainText?.let(Iban::of) != null } ?: free.firstOrNull { it.plainText == null && name(it) in IBAN }
            val code = free.firstOrNull { name(it) in BANK_CODE && it.plainText?.let(RussianAccount::isBik) == true }
            val account = if (iban == null && code != null) free.firstOrNull { name(it) in ACCOUNT && it.plainText?.let(RussianAccount::digits) != null } else null
            val number = iban ?: account ?: return null
            val types = linkedMapOf(number.name to if (iban != null) FieldType.BANK_IBAN else FieldType.BANK_ACCOUNT)
            code?.let { types[it.name] = FieldType.BANK_CODE }
            for ((type, names) in BANK_PARTS) {
                free.firstOrNull { it.name !in types && name(it) in names && !it.plainText.isNullOrBlank() }?.let { types[it.name] = type }
            }
            return TypeSuggestion(Kind.BANK_ACCOUNT, types)
        }

        private fun document(entry: Entry): TypeSuggestion? {
            val interpreter = FieldInterpreter(entry)
            if (entry.customFields.any { interpreter.type(it) == FieldType.DOC_NUMBER }) return null
            val free = entry.customFields.filter { interpreter.type(it) == null }
            val number = free.firstOrNull { name(it) in DOC_NUMBER && !it.isEmpty } ?: return null
            val types = linkedMapOf(number.name to FieldType.DOC_NUMBER)
            for ((type, names) in DOC_PARTS) {
                free.firstOrNull { it.name !in types && name(it) in names && !it.isEmpty }?.let { types[it.name] = type }
            }
            // A number alone could be anything's: a document has dates or a country. A bare "Number" (a short card's
            // too, with its expiry) needs two parts only a document has.
            val dated = types.values.count { it == FieldType.DOC_EXPIRY || it == FieldType.DOC_ISSUED || it == FieldType.DOC_BIRTH || it == FieldType.DOC_COUNTRY }
            val documentOnly = types.values.count { it == FieldType.DOC_ISSUED || it == FieldType.DOC_BIRTH || it == FieldType.DOC_COUNTRY || it == FieldType.DOC_TYPE }
            val enough = if (name(number) in GENERIC_NUMBER) documentOnly >= 2 else dated >= 1
            return if (enough) TypeSuggestion(Kind.DOCUMENT, types) else null
        }

        private fun ssh(entry: Entry): TypeSuggestion? {
            val interpreter = FieldInterpreter(entry)
            if (entry.customFields.any { interpreter.type(it) == FieldType.SSH_HOST }) return null
            val free = entry.customFields.filter { interpreter.type(it) == null }
            val host = free.firstOrNull { name(it) in SSH_HOST && it.plainText?.let(SshTarget::parse) != null } ?: return null
            val port = free.firstOrNull { name(it) in SSH_PORT && it.plainText?.trim()?.toIntOrNull() in 1..65535 }
            val user = free.firstOrNull { name(it) in SSH_USER && !it.plainText.isNullOrBlank() }
            // A host alone could be a website's: an SSH login says so, by a port, a user, a key or "SSH" in its names.
            val says = port != null || user != null || SshKeyLocation.hasSettings(entry.attachments) ||
                "ssh" in name(host) || SearchNormalizer.normalize(entry.displayTitle).contains("ssh")
            if (!says) return null
            val types = linkedMapOf(host.name to FieldType.SSH_HOST)
            port?.let { types[it.name] = FieldType.SSH_PORT }
            user?.let { types[it.name] = FieldType.SSH_USER }
            return TypeSuggestion(Kind.SSH, types)
        }

        private fun crypto(entry: Entry): TypeSuggestion? {
            val interpreter = FieldInterpreter(entry)
            if (entry.customFields.any { interpreter.type(it)?.isCryptoPart == true }) return null
            val free = entry.customFields.filter { interpreter.type(it) == null }
            // A phrase by its value (its checksum, or words of a list under a phrase's name), or by its name when protected.
            val seed = free.firstOrNull { field -> field.plainText?.let(SeedPhrase::read)?.passes == true }
                ?: free.firstOrNull { name(it) in SEED && (it.plainText?.let(SeedPhrase::read)?.isWords ?: !it.isEmpty) }
            // Addresses and keys by a checksum that passes; one without (Solana's) only under an address's name.
            val addresses = free.filter { field ->
                val read = field.plainText?.let(CryptoAddress::read) ?: return@filter false
                read.check == AddressCheck.PASSES || (read.check == AddressCheck.NONE && name(field) in ADDRESS)
            }
            val xpub = free.firstOrNull { field -> field.plainText?.let(ExtendedKey::read)?.let { it.passes && !it.isPrivate } == true }
            if (seed == null && addresses.isEmpty() && xpub == null) return null
            val types = linkedMapOf<String, FieldType>()
            seed?.let { types[it.name] = FieldType.CRYPTO_SEED }
            if (seed != null) free.firstOrNull { it.name !in types && name(it) in PASSPHRASE && !it.isEmpty }?.let { types[it.name] = FieldType.CRYPTO_PASSPHRASE }
            addresses.forEach { types[it.name] = FieldType.CRYPTO_ADDRESS }
            xpub?.let { types[it.name] = FieldType.CRYPTO_XPUB }
            free.firstOrNull { it.name !in types && it.plainText?.let(DerivationPath::describe) != null }?.let { types[it.name] = FieldType.CRYPTO_PATH }
            return TypeSuggestion(Kind.CRYPTO, types)
        }

        /** A field's name as compared: without its 1Password section, folded, words split on anything else. */
        internal fun name(field: CustomField): String {
            val label = ImportedFieldName.split(field.name)?.second ?: field.name
            return SearchNormalizer.normalize(label).replace(NON_WORD, " ").trim()
        }

        /** 12 to 19 digits, maybe grouped; a protected number is never read and counts by its name. */
        private fun looksLikeNumber(field: CustomField): Boolean {
            val text = field.plainText ?: return true
            if (text.any { !it.isDigit() && it != ' ' && it != '-' }) return false
            return text.count(Char::isDigit) in 12..19
        }

        private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

        // Folded as [name] folds them (lower case, no diacritics: "gultig bis").
        private val NUMBER = setOf(
            "number", "card number", "cardnumber", "credit card number", "card no", "ccnum", "pan",
            "kartennummer", "nummer", "номер", "номер карты",
        )
        private val CARD_PARTS: List<Pair<FieldType, Set<String>>> = listOf(
            FieldType.CARD_HOLDER to setOf(
                "cardholder", "cardholder name", "card holder", "holder", "name on card", "name",
                "karteninhaber", "inhaber", "владелец", "владелец карты", "держатель", "держатель карты",
            ),
            FieldType.CARD_EXPIRY to setOf(
                "expiry", "expiry date", "expires", "expiration", "expiration date", "exp", "valid thru", "valid through",
                "valid until", "gultig bis", "ablaufdatum", "срок", "срок действия", "действует до",
            ),
            FieldType.CARD_CVC to setOf(
                "cvc", "cvv", "cvc2", "cvv2", "cvn", "csc", "security code", "verification number", "verification code",
                "card verification number", "prufnummer", "kartenprufnummer", "sicherheitscode", "код", "код cvc",
            ),
            FieldType.CARD_PIN to setOf("pin", "pin code", "pincode", "пин", "пин код"),
            FieldType.CARD_TYPE to setOf("type", "card type", "brand", "network", "kartentyp", "тип", "тип карты"),
        )

        private val SSID = setOf(
            "ssid", "network name", "network", "wifi name", "wi fi name", "wifi network", "wi fi network", "wlan name",
            "wlan ssid", "netzwerkname", "netzwerk", "wlan", "имя сети", "сеть",
        )
        private val WIFI_PASSWORD = setOf(
            "wifi password", "wi fi password", "wireless password", "wireless network password", "network password",
            "network key", "wpa key", "wpa password", "wlan key", "wlan password", "wlan passwort", "wlan schlussel",
            "netzwerkschlussel", "passwort", "key", "пароль", "пароль wifi", "пароль wi fi", "ключ сети",
        )
        private val SECURITY = setOf(
            "security", "security type", "wireless security", "encryption", "verschlusselung", "sicherheit",
            "безопасность", "тип безопасности", "шифрование",
        )

        private val IBAN = setOf("iban", "iban number", "international bank account number")
        private val ACCOUNT = setOf(
            "account", "account number", "account no", "accountno", "kontonummer", "расчетный счет", "р с", "счет",
            "номер счета", "лицевой счет", "счет получателя",
        )
        private val BANK_CODE = setOf("bik", "бик", "бик банка", "bank code", "routing number", "routing", "sort code", "blz", "bankleitzahl")
        private val BANK_PARTS: List<Pair<FieldType, Set<String>>> = listOf(
            FieldType.BANK_BIC to setOf("bic", "swift", "swift code", "swift bic", "bic swift", "bic code", "swift bic code"),
            FieldType.BANK_HOLDER to setOf(
                "account holder", "holder", "owner", "name on account", "account name", "beneficiary", "kontoinhaber", "inhaber",
                "empfanger", "получатель", "владелец", "владелец счета",
            ),
            FieldType.BANK_NAME to setOf("bank", "bank name", "bankname", "name of bank", "kreditinstitut", "банк", "банк получателя", "наименование банка"),
            FieldType.BANK_CORR to setOf("корр счет", "к с", "корреспондентский счет", "correspondent account", "corr account"),
        )

        private val DOC_NUMBER = setOf(
            "passport number", "passport no", "document number", "document no", "id number", "id card number", "license number",
            "licence number", "driver license number", "number", "reisepassnummer", "passnummer", "ausweisnummer",
            "dokumentennummer", "fuhrerscheinnummer", "номер паспорта", "номер документа", "серия и номер", "номер",
        )
        private val GENERIC_NUMBER = setOf("number", "номер")
        private val DOC_PARTS: List<Pair<FieldType, Set<String>>> = listOf(
            FieldType.DOC_TYPE to setOf("type", "document type", "dokumententyp", "тип", "тип документа", "вид документа"),
            FieldType.DOC_NAME to setOf("full name", "fullname", "name", "name on document", "vollstandiger name", "фио", "имя"),
            FieldType.DOC_COUNTRY to setOf("issuing country", "country", "country of issue", "ausstellungsland", "land", "страна", "страна выдачи"),
            FieldType.DOC_BIRTH to setOf("date of birth", "birthdate", "birth date", "dob", "born", "geburtsdatum", "дата рождения"),
            FieldType.DOC_ISSUED to setOf("issued on", "issue date", "date of issue", "issued", "ausstellungsdatum", "ausgestellt am", "дата выдачи"),
            FieldType.DOC_EXPIRY to setOf(
                "expiry date", "expiration date", "expires", "expiry", "date of expiry", "valid until", "gultig bis", "ablaufdatum",
                "срок действия", "действителен до",
            ),
        )

        private val SSH_HOST = setOf(
            "host", "hostname", "host name", "server", "server address", "ssh host", "ssh server", "ip", "ip address", "хост",
            "сервер", "адрес сервера",
        )
        private val SSH_PORT = setOf("port", "ssh port", "порт")
        private val SSH_USER = setOf("user", "ssh user", "login", "benutzer", "пользователь", "логин")

        private val SEED = setOf(
            "seed", "seed phrase", "seedphrase", "seed words", "wallet seed", "recovery seed", "recovery phrase", "secret recovery phrase",
            "recovery words", "backup phrase", "mnemonic", "mnemonic phrase", "mnemonic words", "bip39", "bip 39", "secret phrase",
            "wiederherstellungsphrase", "seed satz", "сид", "сид фраза", "seed фраза", "мнемоническая фраза", "мнемоника",
            "фраза восстановления", "секретная фраза", "кодовые слова",
        )
        private val PASSPHRASE = setOf(
            "passphrase", "seed passphrase", "bip39 passphrase", "bip 39 passphrase", "25th word", "13th word", "extra word",
            "парольная фраза", "кодовая фраза", "25 е слово", "25 слово",
        )
        private val ADDRESS = setOf(
            "address", "wallet address", "receiving address", "receive address", "deposit address", "public address",
            "adresse", "empfangsadresse", "адрес", "адрес кошелька",
        )

        /** What the face reads as an open network (as [WiFiNetwork] does). */
        private val OPEN = setOf("none", "open", "offen", "нет", "открытая")
    }
}

package dev.keepassbar.card

import dev.keepassbar.domain.vault.CustomField
import dev.keepassbar.domain.vault.Entry
import dev.keepassbar.domain.vault.EntryChanges
import dev.keepassbar.search.SearchNormalizer

/**
 * An entry whose fields without a type are named as a payment card's or a Wi-Fi network's parts (SPEC §6.3), as
 * 1Password's import and hand-made entries name them: the card offers to give them those types, which makes the
 * entry's face here and its view on the Mac. Nothing is typed without the user's tap, so the phone never shows a
 * face the Mac does not. Read by the names alone, except an unprotected card number, which must look like one.
 */
public data class TypeSuggestion(val kind: Kind, val types: Map<String, FieldType>) {
    public enum class Kind { PAYMENT_CARD, WIFI }

    /** The entry's types with these added (Mac SPEC §3.12): saved as any edit. */
    public fun changes(entry: Entry): EntryChanges =
        EntryChanges(fieldTypes = entry.fieldTypes + types.mapValues { it.value.typeName })

    public companion object {
        public fun of(entry: Entry): TypeSuggestion? = card(entry) ?: wifi(entry)

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

        /** What the face reads as an open network (as [WiFiNetwork] does). */
        private val OPEN = setOf("none", "open", "offen", "нет", "открытая")
    }
}

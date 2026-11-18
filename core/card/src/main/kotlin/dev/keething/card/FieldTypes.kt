package dev.keething.card

import dev.keething.domain.text.UserText
import dev.keething.domain.vault.CustomField
import dev.keething.domain.vault.Entry

/** What a custom field holds (Mac SPEC §3.12, Mac `FieldType`), chosen in the editor and kept in the entry. */
public enum class FieldType(public val typeName: String) {
    /** Plain text: nothing is read from the value (an ISO date stays text). */
    TEXT("text"),

    /** A date (`2021-05-03`): shown formatted. */
    DATE("date"),

    /** A date something ends on (`gültig bis`, `Kündigungsfrist`): shown with how it stands. */
    UNTIL("until"),

    /** A web address: opened with a tap, also without its scheme (`db.example.com`). */
    URL("url"),

    /** One of a list of codes (backup codes): a section of them is drawn as a numbered list. */
    CODE("code"),

    /** A password (protected or not): masked on the card until revealed; the editor offers the generator. */
    PASSWORD("password"),

    /** Parts of a payment card: an entry with a `card.number` is drawn as a card. */
    CARD_NUMBER("card.number"),
    CARD_HOLDER("card.holder"),
    CARD_EXPIRY("card.expiry"),
    CARD_CVC("card.cvc"),
    CARD_PIN("card.pin"),
    CARD_TYPE("card.type"),

    /** Parts of a Wi-Fi network: an entry with a `wifi.ssid` offers a QR code to join it. */
    WIFI_SSID("wifi.ssid"),
    WIFI_PASSWORD("wifi.password"),
    WIFI_SECURITY("wifi.security"),

    /** Parts of a bank account: an entry with a `bank.iban` or a `bank.account` is drawn as an account (the phone's own, SPEC §6.3). */
    BANK_IBAN("bank.iban"),
    BANK_BIC("bank.bic"),
    BANK_NAME("bank.name"),
    BANK_HOLDER("bank.holder"),

    /** An account number where there is no IBAN (a Russian расчётный счёт, a US account number). */
    BANK_ACCOUNT("bank.account"),

    /** The bank's national code (a Russian БИК, a routing number, a sort code, a BLZ). */
    BANK_CODE("bank.code"),

    /** A correspondent account (корр. счёт). */
    BANK_CORR("bank.corr"),

    /** Parts of an identity document (passport, ID card, driving licence): an entry with a `doc.number` is drawn as one. */
    DOC_TYPE("doc.type"),
    DOC_NUMBER("doc.number"),
    DOC_NAME("doc.name"),
    DOC_COUNTRY("doc.country"),
    DOC_BIRTH("doc.birth"),
    DOC_ISSUED("doc.issued"),
    DOC_EXPIRY("doc.expiry"),

    /** Parts of an SSH login: an entry with a `ssh.host` is drawn as a terminal with its command. */
    SSH_HOST("ssh.host"),
    SSH_PORT("ssh.port"),
    SSH_USER("ssh.user"),

    /**
     * Parts of a crypto wallet: an entry with a `crypto.seed`, a `crypto.address` or a `crypto.xpub` is drawn as one. The
     * recovery phrase and its passphrase are never copied (SPEC §6.2).
     */
    CRYPTO_SEED("crypto.seed"),
    CRYPTO_PASSPHRASE("crypto.passphrase"),

    /** A receiving address (public); an entry may have several. */
    CRYPTO_ADDRESS("crypto.address"),

    /** An extended public key (`xpub…`, `zpub…`): every address and balance of the wallet. */
    CRYPTO_XPUB("crypto.xpub"),

    /** The derivation path (`m/84'/0'/0'`). */
    CRYPTO_PATH("crypto.path"),
    ;

    /** Read as a date. */
    public val isDate: Boolean get() = this == DATE || this == UNTIL || this == CARD_EXPIRY || this == DOC_BIRTH || this == DOC_ISSUED || this == DOC_EXPIRY

    /** A date something ends on: gets the expiry hint and counts as a deadline. */
    public val endsSomething: Boolean get() = this == UNTIL || this == CARD_EXPIRY || this == DOC_EXPIRY
    public val isCardPart: Boolean get() = typeName.startsWith("card.")
    public val isWiFiPart: Boolean get() = typeName.startsWith("wifi.")
    public val isBankPart: Boolean get() = typeName.startsWith("bank.")
    public val isDocumentPart: Boolean get() = typeName.startsWith("doc.")
    public val isSshPart: Boolean get() = typeName.startsWith("ssh.")
    public val isCryptoPart: Boolean get() = typeName.startsWith("crypto.")

    /** Never copied, nor in a QR code: whoever has it has the wallet (a recovery phrase, its passphrase). */
    public val isNeverCopied: Boolean get() = this == CRYPTO_SEED || this == CRYPTO_PASSPHRASE

    /** A password: the editor offers the generator for it. */
    public val isPassword: Boolean get() = this == PASSWORD || this == WIFI_PASSWORD

    /** The menus' name for it ("Expiry" under *Card*). */
    public val title: UserText
        get() = UserText.of(
            when (this) {
                TEXT -> CardText.TYPE_TEXT
                DATE -> CardText.TYPE_DATE
                UNTIL -> CardText.TYPE_UNTIL
                URL -> CardText.TYPE_URL
                CODE -> CardText.TYPE_CODE
                PASSWORD -> CardText.TYPE_PASSWORD
                CARD_NUMBER -> CardText.TYPE_CARD_NUMBER
                CARD_HOLDER -> CardText.TYPE_CARD_HOLDER
                CARD_EXPIRY -> CardText.TYPE_CARD_EXPIRY
                CARD_CVC -> CardText.TYPE_CARD_CVC
                CARD_PIN -> CardText.TYPE_CARD_PIN
                CARD_TYPE -> CardText.TYPE_CARD_TYPE
                WIFI_SSID -> CardText.TYPE_WIFI_SSID
                WIFI_PASSWORD -> CardText.TYPE_WIFI_PASSWORD
                WIFI_SECURITY -> CardText.TYPE_WIFI_SECURITY
                BANK_IBAN -> CardText.TYPE_BANK_IBAN
                BANK_BIC -> CardText.TYPE_BANK_BIC
                BANK_NAME -> CardText.TYPE_BANK_NAME
                BANK_HOLDER -> CardText.TYPE_BANK_HOLDER
                BANK_ACCOUNT -> CardText.TYPE_BANK_ACCOUNT
                BANK_CODE -> CardText.TYPE_BANK_CODE
                BANK_CORR -> CardText.TYPE_BANK_CORR
                DOC_TYPE -> CardText.TYPE_DOC_TYPE
                DOC_NUMBER -> CardText.TYPE_DOC_NUMBER
                DOC_NAME -> CardText.TYPE_DOC_NAME
                DOC_COUNTRY -> CardText.TYPE_DOC_COUNTRY
                DOC_BIRTH -> CardText.TYPE_DOC_BIRTH
                DOC_ISSUED -> CardText.TYPE_DOC_ISSUED
                DOC_EXPIRY -> CardText.TYPE_DOC_EXPIRY
                SSH_HOST -> CardText.TYPE_SSH_HOST
                SSH_PORT -> CardText.TYPE_SSH_PORT
                SSH_USER -> CardText.TYPE_SSH_USER
                CRYPTO_SEED -> CardText.TYPE_CRYPTO_SEED
                CRYPTO_PASSPHRASE -> CardText.TYPE_CRYPTO_PASSPHRASE
                CRYPTO_ADDRESS -> CardText.TYPE_CRYPTO_ADDRESS
                CRYPTO_XPUB -> CardText.TYPE_CRYPTO_XPUB
                CRYPTO_PATH -> CardText.TYPE_CRYPTO_PATH
            },
        )

    /** The name where it stands alone ("Card expiry"). */
    public val fullTitle: UserText
        get() = when (this) {
            CARD_NUMBER -> UserText.of(CardText.FULL_CARD_NUMBER)
            CARD_HOLDER -> UserText.of(CardText.FULL_CARD_HOLDER)
            CARD_EXPIRY -> UserText.of(CardText.FULL_CARD_EXPIRY)
            CARD_CVC -> UserText.of(CardText.FULL_CARD_CVC)
            CARD_PIN -> UserText.of(CardText.FULL_CARD_PIN)
            CARD_TYPE -> UserText.of(CardText.FULL_CARD_TYPE)
            WIFI_SSID -> UserText.of(CardText.FULL_WIFI_SSID)
            WIFI_PASSWORD -> UserText.of(CardText.FULL_WIFI_PASSWORD)
            WIFI_SECURITY -> UserText.of(CardText.FULL_WIFI_SECURITY)
            BANK_NAME -> UserText.of(CardText.FULL_BANK_NAME)
            BANK_CODE -> UserText.of(CardText.FULL_BANK_CODE)
            DOC_TYPE -> UserText.of(CardText.FULL_DOC_TYPE)
            DOC_NUMBER -> UserText.of(CardText.FULL_DOC_NUMBER)
            DOC_NAME -> UserText.of(CardText.FULL_DOC_NAME)
            DOC_COUNTRY -> UserText.of(CardText.FULL_DOC_COUNTRY)
            DOC_ISSUED -> UserText.of(CardText.FULL_DOC_ISSUED)
            DOC_EXPIRY -> UserText.of(CardText.FULL_DOC_EXPIRY)
            SSH_HOST -> UserText.of(CardText.FULL_SSH_HOST)
            SSH_PORT -> UserText.of(CardText.FULL_SSH_PORT)
            SSH_USER -> UserText.of(CardText.FULL_SSH_USER)
            CRYPTO_PASSPHRASE -> UserText.of(CardText.FULL_CRYPTO_PASSPHRASE)
            else -> title
        }

    /**
     * The name a new field of this type starts with. The Mac names only a card's and a Wi-Fi network's parts; the
     * phone names every type, so that a new field starts in its value (typing a name is slow on a phone). A name in
     * the file, as the Mac's: English whatever the phone's language (keething-spec plans T13b).
     */
    public val defaultName: String
        get() = when (this) {
            TEXT -> "Text" // not user text
            DATE -> "Date" // not user text
            UNTIL -> "Valid until" // not user text
            URL -> "Website" // not user text
            CODE -> "Code" // not user text
            PASSWORD -> "Password" // not user text
            CARD_NUMBER -> "Card number" // not user text
            CARD_HOLDER -> "Cardholder" // not user text
            CARD_EXPIRY -> "Expiry date" // not user text
            CARD_CVC -> "CVC" // not user text
            CARD_PIN -> "PIN" // not user text
            CARD_TYPE -> "Card type" // not user text
            WIFI_SSID -> "Network name" // not user text
            WIFI_PASSWORD -> "Wi-Fi password" // not user text
            WIFI_SECURITY -> "Security" // not user text
            BANK_IBAN -> "IBAN" // not user text
            BANK_BIC -> "BIC" // not user text
            BANK_NAME -> "Bank" // not user text
            BANK_HOLDER -> "Account holder" // not user text
            BANK_ACCOUNT -> "Account number" // not user text
            BANK_CODE -> "Bank code" // not user text
            BANK_CORR -> "Correspondent account" // not user text
            DOC_TYPE -> "Document type" // not user text
            DOC_NUMBER -> "Document number" // not user text
            DOC_NAME -> "Full name" // not user text
            DOC_COUNTRY -> "Issuing country" // not user text
            DOC_BIRTH -> "Date of birth" // not user text
            DOC_ISSUED -> "Date of issue" // not user text
            DOC_EXPIRY -> "Valid until" // not user text
            SSH_HOST -> "Host" // not user text
            SSH_PORT -> "Port" // not user text
            SSH_USER -> "SSH user" // not user text
            CRYPTO_SEED -> "Recovery phrase" // not user text
            CRYPTO_PASSPHRASE -> "Passphrase" // not user text
            CRYPTO_ADDRESS -> "Address" // not user text
            CRYPTO_XPUB -> "xpub" // not user text
            CRYPTO_PATH -> "Derivation path" // not user text
        }

    public companion object {
        /** The types of their own, then the parts of each kind of entry (the editor's menus). */
        public val BASIC: List<FieldType> = listOf(TEXT, DATE, UNTIL, URL, CODE, PASSWORD)
        public val CARD: List<FieldType> = entries.filter { it.isCardPart }
        public val WIFI: List<FieldType> = entries.filter { it.isWiFiPart }
        public val BANK: List<FieldType> = entries.filter { it.isBankPart }
        public val DOCUMENT: List<FieldType> = entries.filter { it.isDocumentPart }
        public val SSH: List<FieldType> = entries.filter { it.isSshPart }
        public val CRYPTO: List<FieldType> = entries.filter { it.isCryptoPart }

        /** The kinds of entries whose parts the editor's menus offer, by their menu title. */
        public val KINDS: List<Pair<UserText, List<FieldType>>> = listOf(
            UserText.of(CardText.KIND_CARD) to CARD,
            UserText.of(CardText.KIND_WIFI) to WIFI,
            UserText.of(CardText.KIND_BANK) to BANK,
            UserText.of(CardText.KIND_DOCUMENT) to DOCUMENT,
            UserText.of(CardText.KIND_SSH) to SSH,
            UserText.of(CardText.KIND_CRYPTO) to CRYPTO,
        )

        /** A stored type name (case and outer spaces ignored); null when it is none this version knows. */
        public fun of(typeName: String): FieldType? {
            val name = typeName.trim().lowercase()
            return entries.firstOrNull { it.typeName == name }
        }
    }
}

/**
 * The types of one entry's custom fields (Mac `FieldTypes`), as the entry keeps them ([Entry.fieldTypes]). A
 * field without one is read by its value (Mac SPEC §3.6.2); a type this version does not know counts as none.
 */
public class FieldTypes(stored: Map<String, String>) {
    public constructor(entry: Entry) : this(entry.fieldTypes)

    private val byName: Map<String, FieldType> = stored.mapNotNull { (name, type) -> FieldType.of(type)?.let { name to it } }.toMap()

    /** Types that are not a [FieldType] (field name → type as stored). */
    public val unknown: Map<String, String> = stored.filterValues { FieldType.of(it) == null }

    /** The field's declared type (its whole name, exactly). */
    public fun type(fieldName: String): FieldType? = byName[fieldName]

    public companion object {
        public val NONE: FieldTypes = FieldTypes(emptyMap())
    }
}

/**
 * How custom fields are read, shared by the card, the list, the deadlines and the editor so they never disagree
 * (Mac `FieldInterpreter`). A field's type in its entry decides; a field without one is read by its value. A
 * protected value is never read.
 */
internal class FieldInterpreter(val types: FieldTypes) {
    constructor(entry: Entry) : this(FieldTypes(entry))

    fun type(field: CustomField): FieldType? = types.type(field.name)

    /**
     * The date in an unprotected field and whether it ends something. Typed: only date types (any accepted form);
     * untyped: the unmistakable forms, ending something when the name says so.
     */
    fun date(field: CustomField): Pair<FieldDate, Boolean>? {
        val value = field.plainText?.takeIf { it.isNotEmpty() } ?: return null
        type(field)?.let { type ->
            if (!type.isDate) return null
            return FieldDate.parse(value, typed = true)?.let { it to type.endsSomething }
        }
        return FieldDate.parse(value, typed = false)?.let { it to FieldNameHint(field.name).isExpiry }
    }

    /**
     * The address an unprotected field opens. Typed `url`: any address [OpenableUrl] takes, with or without its
     * scheme; untyped: an explicit http(s) address, or a bare host in a field named like one; other types: none.
     */
    fun link(field: CustomField): String? {
        val value = field.plainText?.trim() ?: return null
        return when (type(field)) {
            FieldType.URL -> OpenableUrl.of(value)
            null -> {
                val lower = value.lowercase()
                if (!lower.startsWith("http://") && !lower.startsWith("https://") && !FieldNameHint(field.name).isWebAddress) return null
                OpenableUrl.of(value)
            }
            else -> null
        }
    }
}

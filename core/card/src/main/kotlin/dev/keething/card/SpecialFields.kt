package dev.keething.card

import dev.keething.domain.text.UserText
import dev.keething.domain.vault.CustomField
import dev.keething.domain.vault.FieldValue
import java.time.Instant
import java.time.ZoneId

/** Nothing in it; a protected value is asked without being revealed. */
internal val CustomField.isEmpty: Boolean
    get() = when (val v = value) {
        is FieldValue.Plain -> v.text.isEmpty()
        is FieldValue.Protected -> v.secret.isEmpty()
    }

/**
 * A view of a kind of entry, made from its typed fields. It claims its fields, which then do not appear again
 * among the custom fields; null when the entry is not of its kind.
 */
internal interface TypedSection {
    fun make(context: CardContext): Pair<CardSection, Set<Int>>?
}

/**
 * An entry with a field typed `card.number`: all its `card.*` fields as one section, drawn as a card. The
 * number, CVC and PIN are masked until revealed even when the database does not protect them.
 */
internal object PaymentCardSection : TypedSection {
    override fun make(context: CardContext): Pair<CardSection, Set<Int>>? {
        val number = context.typed(FieldType.CARD_NUMBER).firstOrNull() ?: return null
        val parts = context.parts { it.isCardPart }
        val expiryField = context.typed(FieldType.CARD_EXPIRY).firstOrNull()
        val expiry = expiryField?.let { context.interpreter.date(it.value)?.first }
        val holder = context.typed(FieldType.CARD_HOLDER).firstOrNull()
        val type = context.typed(FieldType.CARD_TYPE).firstOrNull()
        val cvc = context.typed(FieldType.CARD_CVC).firstOrNull()?.takeIf { !it.value.isEmpty }
        val card = PaymentCard.make(
            number.value, RowIds.custom(number.index), holder?.value, expiry, type?.value, context.now, context.zone,
        ).copy(
            // On the face only what it can show: an unprotected holder, a date, a type it names the card by.
            holderRowId = holder?.takeIf { !it.value.plainText.isNullOrEmpty() }?.let { RowIds.custom(it.index) },
            expiryRowId = expiryField?.takeIf { expiry != null }?.let { RowIds.custom(it.index) },
            cvcRowId = cvc?.let { RowIds.custom(it.index) },
            typeRowId = type?.takeIf { field ->
                val text = field.value.plainText?.trim().orEmpty()
                text.isNotEmpty() && CardBrand.detectIn(number.value.value) == null || CardBrand.known(text) != null
            }?.let { RowIds.custom(it.index) },
        )
        val (title, labels) = context.sectionLabels(parts)
        val rows = parts.zip(labels).map { (part, label) ->
            val row = context.row(part.index, part.value, label, ImportedFieldName.displayName(part.value.name))
            when {
                row.isProtected || row.isEmpty -> row
                row.id == card.numberRowId -> row.concealed(card.groupedNumber, card.maskedNumber)
                row.type == FieldType.CARD_CVC || row.type == FieldType.CARD_PIN -> row.concealed()
                else -> row
            }
        }
        return CardSection("payment-card", CardSection.Kind.PaymentCard(card), title, rows) to parts.map { it.index }.toSet()
    }
}

/** An entry with an unprotected `wifi.ssid`: its `wifi.*` fields as one section with a QR code to join. */
internal object WiFiSection : TypedSection {
    override fun make(context: CardContext): Pair<CardSection, Set<Int>>? {
        val ssid = context.typed(FieldType.WIFI_SSID).firstOrNull() ?: return null
        val password = context.typed(FieldType.WIFI_PASSWORD).firstOrNull()
        val security = context.typed(FieldType.WIFI_SECURITY).firstOrNull()
        val network = WiFiNetwork.make(ssid.value, password?.let { it.value to RowIds.custom(it.index) }, security?.value)
            ?.copy(
                ssidRowId = RowIds.custom(ssid.index),
                securityRowId = security?.takeIf { !it.value.plainText.isNullOrBlank() }?.let { RowIds.custom(it.index) },
            )
            ?: return null
        val parts = context.parts { it.isWiFiPart }
        val (title, labels) = context.sectionLabels(parts)
        val rows = parts.zip(labels).map { (part, label) ->
            context.row(part.index, part.value, label, ImportedFieldName.displayName(part.value.name))
        }
        return CardSection("wifi", CardSection.Kind.WiFi(network), title, rows) to parts.map { it.index }.toSet()
    }
}

/**
 * What the card view draws for an entry with a `card.number` field (Mac `PaymentCard`). The number is shown
 * grouped and masked down to its last four digits until revealed. A protected number is not read at all: no
 * brand from it, no last digits.
 */
public data class PaymentCard(
    val numberRowId: String,
    /** "Visa", from the number's first digits, else from the `card.type` field (its text when unknown). */
    val brand: String?,
    /** The card network, when it is a known one (the card art follows it). */
    val network: CardBrand?,
    /** The unprotected number grouped as printed (`4111 1111 1111 1111`, Amex 4-6-5); null when protected. */
    val groupedNumber: String?,
    /** `•••• •••• •••• 1111`; the plain mask when protected. */
    val maskedNumber: String,
    /** False when the digits fail the Luhn check (a typo?); null when not checked. */
    val checkDigitOk: Boolean?,
    val holder: String?,
    val expiry: FieldDate?,
    val expiryHint: ExpiryHint?,
    /** The rows the face shows (SPEC §6.2), besides the number: the card shows the other rows under it. */
    val holderRowId: String? = null,
    val expiryRowId: String? = null,
    /** On the face's back. */
    val cvcRowId: String? = null,
    /** The card type field, when the face names the card by it. */
    val typeRowId: String? = null,
) {
    /** The rows drawn on the face, front and back; the others are rows under it. */
    val faceRowIds: Set<String> get() = setOfNotNull(numberRowId, holderRowId, expiryRowId, cvcRowId, typeRowId)

    internal companion object {
        fun make(number: CustomField, numberRowId: String, holder: CustomField?, expiry: FieldDate?, type: CustomField?, now: Instant, zone: ZoneId): PaymentCard {
            val typeText = type?.plainText?.takeIf { it.isNotEmpty() }
            val typeBrand = typeText?.let(CardBrand::known)
            val typeName = typeText?.let { typeBrand?.displayName ?: it.trim() }
            val holderName = holder?.plainText?.takeIf { it.isNotEmpty() }
            val hint = expiry?.expiry(now, zone)
            val text = number.plainText
                ?: return PaymentCard(numberRowId, typeName, typeBrand, null, RevealState.MASK, null, holderName, expiry, hint)
            val digits = text.filter { it in '0'..'9' }
            val brand = CardBrand.detect(digits)
            val groups = CardBrand.grouping(digits.length, brand)
            val masked = CardBrand.group("•".repeat(maxOf(digits.length - 4, 0)) + digits.takeLast(4), groups)
            return PaymentCard(
                numberRowId = numberRowId,
                brand = brand?.displayName ?: typeName,
                network = brand ?: typeBrand,
                groupedNumber = if (digits.isEmpty()) text else CardBrand.group(digits, groups),
                maskedNumber = if (digits.length > 4) masked else RevealState.MASK,
                checkDigitOk = if (digits.length in 12..19) CardBrand.luhn(digits) else null,
                holder = holderName,
                expiry = expiry,
                expiryHint = hint,
            )
        }
    }
}

/** Card networks, by their leading digits (IIN ranges), most specific first (Mac `CardBrand`). */
public enum class CardBrand(public val displayName: String) {
    VISA("Visa"), // not user text
    MASTERCARD("Mastercard"), // not user text
    AMEX("American Express"), // not user text
    MIR("Mir"), // not user text
    MAESTRO("Maestro"), // not user text
    DISCOVER("Discover"), // not user text
    UNION_PAY("UnionPay"), // not user text
    JCB("JCB"),
    DINERS("Diners Club"), // not user text
    ;

    public companion object {
        public fun detect(digits: String): CardBrand? {
            fun prefix(n: Int) = if (digits.length >= n) digits.substring(0, n).toInt() else -1
            if (digits.isEmpty()) return null
            val p1 = prefix(1)
            val p2 = prefix(2)
            val p3 = prefix(3)
            val p4 = prefix(4)
            return when {
                p2 == 34 || p2 == 37 -> AMEX
                p4 in 2200..2204 -> MIR
                p2 in 51..55 || p4 in 2221..2720 -> MASTERCARD
                p4 == 6011 || p2 == 65 || p3 in 644..649 -> DISCOVER
                p4 in 3528..3589 -> JCB
                p2 == 36 || p2 == 38 || p2 == 39 || p3 in 300..305 -> DINERS
                p2 == 62 -> UNION_PAY
                p2 == 50 || p2 in 56..58 || p2 == 63 || p2 == 67 -> MAESTRO
                p1 == 4 -> VISA
                else -> null
            }
        }

        /** The network of a number as stored; null when it is protected (never read) or names none. */
        internal fun detectIn(value: FieldValue): CardBrand? = (value as? FieldValue.Plain)?.text?.filter { it in '0'..'9' }?.let(::detect)

        /** 1Password's card type field (`visa`, `mc`, `amex`, `mir`…) as a brand name; else the text itself. */
        public fun name(typeText: String): String = known(typeText)?.displayName ?: typeText.trim()

        /** The network a card type field names, when it is a known one. */
        public fun known(typeText: String): CardBrand? = when (typeText.trim().lowercase()) {
            "visa" -> VISA
            "mc", "mastercard", "master card" -> MASTERCARD // not user text
            "amex", "american express" -> AMEX // not user text
            "mir", "мир" -> MIR
            "maestro" -> MAESTRO
            "discover" -> DISCOVER
            "unionpay" -> UNION_PAY
            "jcb" -> JCB
            "diners", "diners club" -> DINERS // not user text
            else -> null
        }

        internal fun grouping(count: Int, brand: CardBrand?): List<Int> = when {
            brand == AMEX && count == 15 -> listOf(4, 6, 5)
            brand == DINERS && count == 14 -> listOf(4, 6, 4)
            else -> List((count + 3) / 4) { 4 }
        }

        internal fun group(text: String, sizes: List<Int>): String {
            val parts = mutableListOf<String>()
            var rest = text
            for (size in sizes) {
                if (rest.isEmpty()) break
                parts += rest.take(size)
                rest = rest.drop(size)
            }
            if (rest.isNotEmpty()) parts += rest
            return parts.joinToString(" ")
        }

        internal fun luhn(digits: String): Boolean {
            var sum = 0
            for ((i, c) in digits.reversed().withIndex()) {
                if (c !in '0'..'9') return false
                var d = c - '0'
                if (i % 2 == 1) {
                    d *= 2
                    if (d > 9) d -= 9
                }
                sum += d
            }
            return sum % 10 == 0
        }
    }
}

/**
 * An entry with a `wifi.ssid` field (Mac `WiFiNetwork`): the card offers a QR code that phones read to join
 * the network. The code contains the password, so it is drawn only while the password row is revealed.
 */
public data class WiFiNetwork(
    val ssid: String,
    val passwordRowId: String?,
    /** `WPA` (also WPA2/WPA3), `WEP`, or `nopass`. */
    val security: String,
    /** How the security field says it ("WPA2"); "Open" for a network without a password, else [security]. */
    val securityLabel: UserText = UserText.plain(security),
    /** The rows the face shows (SPEC §6.2), besides the password: the card shows the other rows under it. */
    val ssidRowId: String? = null,
    val securityRowId: String? = null,
) {
    /** The rows drawn on the face; the others are rows under it. */
    val faceRowIds: Set<String> get() = setOfNotNull(ssidRowId, passwordRowId, securityRowId)

    /** Without a password: anyone may join. */
    val isOpen: Boolean get() = security == "nopass"

    /** The `WIFI:` text of the QR code; `\ ; , : "` are escaped. */
    public fun qrPayload(password: String): String {
        fun esc(s: String) = buildString { for (c in s) { if (c in "\\;,:\"") append('\\'); append(c) } }
        var text = "WIFI:T:$security;S:${esc(ssid)};"
        if (security != "nopass") text += "P:${esc(password)};"
        return "$text;"
    }

    internal companion object {
        fun make(ssid: CustomField, password: Pair<CustomField, String>?, security: CustomField?): WiFiNetwork? {
            val name = ssid.plainText?.takeIf { it.isNotEmpty() } ?: return null
            val text = security?.plainText?.lowercase() ?: ""
            val hasPassword = password?.first?.value?.let {
                when (it) {
                    is FieldValue.Plain -> it.text.isNotEmpty()
                    is FieldValue.Protected -> !it.secret.isEmpty()
                }
            } ?: false
            val kind = when {
                !hasPassword || text.trim() in setOf("none", "open", "offen", "нет", "открытая") -> "nopass"
                "wep" in text -> "WEP"
                else -> "WPA"
            }
            val label = when {
                kind == "nopass" -> UserText.of(CardText.WIFI_OPEN)
                else -> UserText.plain(security?.plainText?.trim()?.takeIf { it.isNotEmpty() && it.length <= 24 } ?: kind)
            }
            return WiFiNetwork(name, if (hasPassword) password?.second else null, kind, label)
        }
    }
}

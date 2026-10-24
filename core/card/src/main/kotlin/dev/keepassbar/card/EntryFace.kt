package dev.keepassbar.card

import dev.keepassbar.domain.vault.Entry
import java.util.Locale

/**
 * What the list shows of an entry its typed fields make a payment card or a Wi-Fi network (SPEC §6.2): a small face
 * instead of the monogram, and a line saying which card or network it is instead of the username and site.
 */
public sealed interface EntryFace {
    /** The line under the title. */
    public val subtitle: String

    /** A payment card: its network, the last digits of an unprotected number, the month it expires. */
    public data class Card(val network: CardBrand?, val brand: String?, val lastDigits: String?, val expiry: CalendarDate?) : EntryFace {
        override val subtitle: String
            get() = listOfNotNull(
                (brand ?: "Card") + (lastDigits?.let { " •• $it" } ?: ""),
                expiry?.let { "%02d/%02d".format(Locale.ROOT, it.month, it.year % 100) },
            ).joinToString(" · ")
    }

    /** A Wi-Fi network: its name and how it is secured ("WPA2", "Open"). */
    public data class WiFi(val ssid: String, val securityLabel: String) : EntryFace {
        override val subtitle: String get() = "$securityLabel · $ssid"
    }

    public companion object {
        /** The face of an entry, as its card draws it; null for any other entry. Reads nothing protected. */
        public fun of(entry: Entry): EntryFace? {
            if (entry.fieldTypes.isEmpty()) return null
            val interpreter = FieldInterpreter(entry)
            fun typed(type: FieldType) = entry.customFields.firstOrNull { interpreter.type(it) == type }

            typed(FieldType.CARD_NUMBER)?.let { number ->
                val digits = number.plainText?.filter { it in '0'..'9' }.orEmpty()
                val typeText = typed(FieldType.CARD_TYPE)?.plainText?.trim()?.takeIf { it.isNotEmpty() }
                val network = CardBrand.detect(digits) ?: typeText?.let(CardBrand::known)
                return Card(
                    network = network,
                    brand = CardBrand.detect(digits)?.displayName ?: typeText?.let(CardBrand::name),
                    lastDigits = digits.takeIf { it.length > 4 }?.takeLast(4),
                    expiry = typed(FieldType.CARD_EXPIRY)?.let { interpreter.date(it)?.first?.date },
                )
            }
            typed(FieldType.WIFI_SSID)?.let { ssid ->
                val password = typed(FieldType.WIFI_PASSWORD)
                val network = WiFiNetwork.make(ssid, password?.let { it to "" }, typed(FieldType.WIFI_SECURITY)) ?: return null
                return WiFi(network.ssid, network.securityLabel)
            }
            return null
        }
    }
}

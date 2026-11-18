package dev.keething.card

import dev.keething.domain.text.UserText
import dev.keething.domain.vault.Entry
import java.util.Locale

/**
 * What the list shows of an entry its typed fields make a payment card, a Wi-Fi network, a bank account, an identity
 * document, an SSH login or a crypto wallet (SPEC §6.2), or of a note (§6.4): a small face instead of the monogram, and a
 * line saying which it is instead of the username and site.
 */
public sealed interface EntryFace {
    /** The line under the title: its parts with " · " between. */
    public val subtitle: UserText

    /** A payment card: its network, the last digits of an unprotected number, the month it expires. */
    public data class Card(val network: CardBrand?, val brand: String?, val lastDigits: String?, val expiry: CalendarDate?) : EntryFace {
        override val subtitle: UserText
            get() = line(
                UserText.Joined(listOfNotNull(brand?.let(UserText::plain) ?: UserText.of(CardText.FACE_CARD), lastDigits?.let { UserText.plain("•• $it") }), " "),
                expiry?.let { UserText.plain("%02d/%02d".format(Locale.ROOT, it.month, it.year % 100)) },
            )
    }

    /** A Wi-Fi network: its name and how it is secured ("WPA2", "Open"). */
    public data class WiFi(val ssid: String, val securityLabel: UserText) : EntryFace {
        override val subtitle: UserText get() = line(securityLabel, UserText.plain(ssid))
    }

    /** A bank account: the number's start and end (`DE89 •• 3000`), the bank; the country for the flag. */
    public data class Bank(val country: String?, val number: String?, val bank: String?) : EntryFace {
        override val subtitle: UserText
            get() = line(number?.let(UserText::plain) ?: UserText.of(CardText.FACE_BANK_ACCOUNT), bank?.let(UserText::plain))
    }

    /** An identity document: its type, the country, the month it ends. */
    public data class Document(val kind: String?, val country: String?, val expiry: CalendarDate?) : EntryFace {
        override val subtitle: UserText
            get() = line(
                kind?.let(UserText::plain) ?: UserText.of(CardText.FACE_DOCUMENT),
                country?.let(UserText::plain),
                expiry?.let { UserText.of(CardText.FACE_UNTIL, "%02d/%04d".format(Locale.ROOT, it.month, it.year)) },
            )
    }

    /** An SSH login: `user@host:port`. */
    public data class Ssh(val target: String) : EntryFace {
        override val subtitle: UserText get() = UserText.plain(target)
    }

    /** A crypto wallet: its network, the first address's start and end, or that it holds a recovery phrase. */
    public data class Crypto(val network: CryptoNetwork?, val address: String?, val hasPhrase: Boolean) : EntryFace {
        override val subtitle: UserText
            get() = line(
                network?.displayName?.let(UserText::plain) ?: UserText.of(CardText.FACE_CRYPTO_WALLET),
                address?.let { UserText.plain(if (it.length > 14) it.take(6) + "…" + it.takeLast(4) else it) }
                    ?: UserText.of(CardText.FACE_RECOVERY_PHRASE).takeIf { hasPhrase },
            )
    }

    /**
     * A note (SPEC §6.4, keething-spec types.note AC7): the note tile in place of its icon or a kind's face, and its
     * [line][NoteLine], `Protected note` when [protected] (nothing of the text read), nothing when it has none.
     */
    public data class Note(val line: UserText?, val protected: Boolean) : EntryFace {
        override val subtitle: UserText get() = line ?: UserText.plain("")
    }

    public companion object {
        private fun line(vararg parts: UserText?): UserText = UserText.Joined(parts.filterNotNull(), " · ")

        /** The face of an entry, as its card draws it; null for any other entry. Reads nothing protected. */
        public fun of(entry: Entry): EntryFace? {
            // A note's tile wins over a kind's face (types.note AC7).
            if (entry.isNote) return Note(NoteLine.of(entry), entry.notesProtected)
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
            fun text(type: FieldType) = typed(type)?.plainText?.trim()?.takeIf { it.isNotEmpty() }
            (typed(FieldType.BANK_IBAN) ?: typed(FieldType.BANK_ACCOUNT))?.let { number ->
                val iban = number.plainText?.let(Iban::compact)?.takeIf { interpreter.type(number) == FieldType.BANK_IBAN && Iban.isShaped(it) }
                val digits = number.plainText?.filter(Char::isLetterOrDigit)
                val shown = when {
                    iban != null -> iban.take(4) + " •• " + iban.takeLast(4)
                    digits != null && digits.length > 8 -> digits.take(5) + " •• " + digits.takeLast(4)
                    else -> null
                }
                val country = iban?.let(Iban::country) ?: text(FieldType.BANK_BIC)?.let(Bic::country)
                    ?: if (text(FieldType.BANK_CODE)?.let(RussianAccount::isBik) == true) "RU" else null
                return Bank(country, shown, text(FieldType.BANK_NAME))
            }
            typed(FieldType.DOC_NUMBER)?.let {
                val country = text(FieldType.DOC_COUNTRY)
                return Document(
                    kind = text(FieldType.DOC_TYPE),
                    country = country?.let { c -> Countries.parse(c)?.takeIf { c.length <= 3 }?.let(Countries::name) ?: c },
                    expiry = typed(FieldType.DOC_EXPIRY)?.let { interpreter.date(it)?.first?.date },
                )
            }
            typed(FieldType.SSH_HOST)?.let { host ->
                val target = host.plainText?.let(SshTarget::parse) ?: return null
                val user = text(FieldType.SSH_USER) ?: target.user ?: entry.username.takeIf { it.isNotEmpty() }
                val port = text(FieldType.SSH_PORT)?.toIntOrNull()?.takeIf { it in 1..65535 } ?: target.port
                return Ssh(SshLogin(target.host, port, user, "", null, null, "", null).target)
            }
            if (entry.customFields.any { interpreter.type(it).let { t -> t == FieldType.CRYPTO_SEED || t == FieldType.CRYPTO_ADDRESS || t == FieldType.CRYPTO_XPUB } }) {
                val wallet = CryptoWallet.make(
                    seed = null, passphrase = null,
                    addresses = entry.customFields.withIndex().filter { interpreter.type(it.value) == FieldType.CRYPTO_ADDRESS },
                    xpub = entry.customFields.withIndex().firstOrNull { interpreter.type(it.value) == FieldType.CRYPTO_XPUB },
                    path = entry.customFields.withIndex().firstOrNull { interpreter.type(it.value) == FieldType.CRYPTO_PATH },
                    label = { UserText.plain("") },
                )
                return Crypto(wallet.network, wallet.addresses.firstOrNull()?.text, typed(FieldType.CRYPTO_SEED) != null)
            }
            return null
        }
    }
}

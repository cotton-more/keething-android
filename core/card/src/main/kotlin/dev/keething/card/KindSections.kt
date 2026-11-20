package dev.keething.card

import dev.keething.domain.text.UserText
import dev.keething.domain.vault.CustomField
import java.util.Locale

/**
 * An entry with a field typed `bank.iban` or `bank.account`: its `bank.*` fields as one section, drawn as an account
 * (SPEC §6.2). The number and a correspondent account copy without spaces.
 */
internal object BankAccountSection : TypedSection {
    override fun make(context: CardContext): Pair<CardSection, Set<Int>>? {
        val iban = context.typed(FieldType.BANK_IBAN).firstOrNull()
        val account = context.typed(FieldType.BANK_ACCOUNT).firstOrNull()
        val number = iban ?: account ?: return null
        val parts = context.parts { it.isBankPart }
        fun plain(type: FieldType) = context.typed(type).firstOrNull()?.takeIf { !it.value.plainText.isNullOrBlank() }
        val bank = BankAccount.make(
            number = number, isIban = number === iban, otherAccount = account?.takeIf { number !== it },
            holder = plain(FieldType.BANK_HOLDER), bic = plain(FieldType.BANK_BIC), code = plain(FieldType.BANK_CODE),
            corr = plain(FieldType.BANK_CORR), name = plain(FieldType.BANK_NAME),
        )
        val compactItems = bank.items.filter { it.compact }.associateBy { it.rowId }
        val (title, labels) = context.sectionLabels(parts)
        val rows = parts.zip(labels).map { (part, label) ->
            val row = context.row(part.index, part.value, label, ImportedFieldName.displayName(part.value.name))
            when {
                row.id == bank.numberRowId && !row.isProtected -> row.copy(shownText = bank.numberShown, copiesCompact = true)
                row.id in compactItems && !row.isProtected -> row.copy(shownText = compactItems.getValue(row.id).value, copiesCompact = true)
                else -> row
            }
        }
        return CardSection("bank-account", CardSection.Kind.BankAccount(bank), title, rows) to parts.map { it.index }.toSet()
    }
}

/**
 * What the account's face shows (SPEC §6.2): the holder, the number large, the rest as small lines, the country's flag.
 * Built from unprotected fields only; a protected number is on the face masked, the other protected parts are rows.
 */
public data class BankAccount(
    /** ISO 3166 alpha-2, from the IBAN, else the BIC, else a Russian БИК; null when nothing says. */
    val country: String?,
    val holder: String?,
    val holderRowId: String?,
    /** [Label.IBAN] or [Label.ACCOUNT]. */
    val numberLabel: Label,
    val numberRowId: String,
    /** Grouped as printed; null when the number is protected. */
    val numberShown: String?,
    /** BIC, bank code (БИК), correspondent account, another account, the bank's name: small lines under the number. */
    val items: List<Item>,
    /** What fails its check ("The IBAN fails its check digits (a typo?)"); null when all pass or nothing is checked. */
    val problem: UserText?,
) {
    /** The face's names of its lines ("IBAN", "BIK", "CORR. ACCOUNT"). */
    public enum class Label(private val key: CardText) {
        IBAN(CardText.BANK_IBAN),
        ACCOUNT(CardText.BANK_ACCOUNT),
        BIC(CardText.BANK_BIC),
        BIK(CardText.BANK_BIK),
        BANK_CODE(CardText.BANK_CODE),
        CORR_ACCOUNT(CardText.BANK_CORR),
        BANK(CardText.BANK_NAME),
        ;

        public val text: UserText get() = UserText.of(key)
    }

    public data class Item(val label: Label, val value: String, val rowId: String, val monospace: Boolean, val compact: Boolean = false)

    val isRussian: Boolean get() = country == "RU" && numberLabel == Label.ACCOUNT

    /** The rows drawn on the face; the others are rows under it. */
    val faceRowIds: Set<String> get() = setOfNotNull(numberRowId, holderRowId) + items.map { it.rowId }

    /**
     * The details as text for someone who sends money (the face's Share): the holder, the number, the codes, the bank;
     * Russian details with their Russian names, as such details are written, whatever the phone's language.
     */
    public fun shareText(): UserText? {
        val number = numberShown ?: return null
        fun line(label: UserText?, value: String) = if (label == null) UserText.plain(value) else UserText.Joined(listOf(label, UserText.plain(value)), " ")
        return UserText.Joined(
            buildList {
                holder?.let { add(UserText.plain(it)) }
                val digits = number.filterNot(Char::isWhitespace).let { if (numberLabel == Label.IBAN) Iban.grouped(it) else it }
                add(line(if (isRussian) UserText.plain("Счёт") else numberLabel.text, digits)) // not user text: Russian details' own name
                for (item in items) {
                    val label = when (item.label) {
                        Label.BANK -> null
                        Label.BIK -> UserText.plain("БИК") // not user text: Russian details' own name
                        Label.CORR_ACCOUNT -> if (isRussian) UserText.plain("К/с") else UserText.of(CardText.BANK_SHARE_CORR) // not user text: Russian details' own name
                        else -> item.label.text
                    }
                    add(line(label, if (item.compact) item.value.filterNot(Char::isWhitespace) else item.value))
                }
            },
            "\n",
        )
    }

    internal companion object {
        fun make(
            number: IndexedValue<CustomField>, isIban: Boolean, otherAccount: IndexedValue<CustomField>?,
            holder: IndexedValue<CustomField>?, bic: IndexedValue<CustomField>?, code: IndexedValue<CustomField>?,
            corr: IndexedValue<CustomField>?, name: IndexedValue<CustomField>?,
        ): BankAccount {
            val text = number.value.plainText?.trim()
            val codeText = code?.value?.plainText?.trim()
            val bik = codeText?.takeIf(RussianAccount::isBik)
            val items = mutableListOf<Item>()
            var shown = text
            var problem: UserText? = null
            var country: String? = null
            if (isIban) {
                val compact = text?.let(Iban::compact)
                if (compact != null && Iban.isShaped(compact)) {
                    shown = Iban.grouped(compact)
                    country = Iban.country(compact)
                    if (!Iban.checks(compact)) problem = UserText.of(CardText.BANK_IBAN_FAILS)
                } else if (text != null) {
                    problem = UserText.of(CardText.BANK_NOT_IBAN)
                }
            } else {
                val digits = text?.let(RussianAccount::digits)
                if (digits != null && bik != null) {
                    shown = RussianAccount.grouped(digits)
                    country = "RU"
                    if (!RussianAccount.accountChecks(digits, bik)) problem = UserText.of(CardText.BANK_ACCOUNT_BIK)
                }
            }
            bic?.let { items += Item(Label.BIC, it.value.plainText!!.trim(), RowIds.custom(it.index), monospace = true) }
            code?.let { items += Item(if (bik != null) Label.BIK else Label.BANK_CODE, codeText!!, RowIds.custom(it.index), monospace = true) }
            corr?.let { field ->
                val t = field.value.plainText!!.trim()
                val digits = RussianAccount.digits(t)
                items += Item(Label.CORR_ACCOUNT, if (digits != null && bik != null) RussianAccount.grouped(digits) else t, RowIds.custom(field.index), monospace = true, compact = true)
                if (digits != null && bik != null && !RussianAccount.correspondentChecks(digits, bik) && problem == null) {
                    problem = UserText.of(CardText.BANK_CORR_BIK)
                }
            }
            otherAccount?.value?.plainText?.trim()?.takeIf { it.isNotEmpty() }?.let {
                items += Item(Label.ACCOUNT, it, RowIds.custom(otherAccount.index), monospace = true, compact = true)
            }
            name?.let { items += Item(Label.BANK, it.value.plainText!!.trim(), RowIds.custom(it.index), monospace = false) }
            if (country == null) country = bic?.value?.plainText?.let(Bic::country) ?: if (bik != null) "RU" else null
            return BankAccount(
                country = country,
                holder = holder?.value?.plainText?.trim(),
                holderRowId = holder?.let { RowIds.custom(it.index) },
                numberLabel = if (isIban) Label.IBAN else Label.ACCOUNT,
                numberRowId = RowIds.custom(number.index),
                numberShown = shown,
                items = items,
                problem = problem,
            )
        }
    }
}

/**
 * An entry with a field typed `doc.number`: its `doc.*` fields as one section, drawn as an identity document (SPEC
 * §6.2). No photo: what such entries keep is a scan of the whole document, a PDF or a picture, not the portrait.
 */
internal object DocumentSection : TypedSection {
    override fun make(context: CardContext): Pair<CardSection, Set<Int>>? {
        val number = context.typed(FieldType.DOC_NUMBER).firstOrNull() ?: return null
        val parts = context.parts { it.isDocumentPart }
        fun plain(type: FieldType) = context.typed(type).firstOrNull()?.takeIf { !it.value.plainText.isNullOrBlank() }
        fun date(type: FieldType) = plain(type)?.let { field -> context.interpreter.date(field.value)?.first?.let { field to it } }
        val country = plain(FieldType.DOC_COUNTRY)
        val expiry = date(FieldType.DOC_EXPIRY)
        val document = IdentityDocument(
            kind = plain(FieldType.DOC_TYPE)?.value?.plainText?.trim(),
            kindRowId = plain(FieldType.DOC_TYPE)?.let { RowIds.custom(it.index) },
            numberRowId = RowIds.custom(number.index),
            name = plain(FieldType.DOC_NAME)?.value?.plainText?.trim(),
            nameRowId = plain(FieldType.DOC_NAME)?.let { RowIds.custom(it.index) },
            country = country?.value?.plainText?.trim()?.let { text -> Countries.parse(text)?.let { code -> if (text.length <= 3) Countries.name(code) else text } ?: text },
            countryCode = country?.value?.plainText?.let(Countries::parse),
            countryRowId = country?.let { RowIds.custom(it.index) },
            birth = date(FieldType.DOC_BIRTH)?.second,
            birthRowId = date(FieldType.DOC_BIRTH)?.first?.let { RowIds.custom(it.index) },
            issued = date(FieldType.DOC_ISSUED)?.second,
            issuedRowId = date(FieldType.DOC_ISSUED)?.first?.let { RowIds.custom(it.index) },
            expiry = expiry?.second,
            expiryRowId = expiry?.first?.let { RowIds.custom(it.index) },
            expiryHint = expiry?.second?.expiry(context.now, context.zone),
        )
        val (title, labels) = context.sectionLabels(parts)
        val rows = parts.zip(labels).map { (part, label) -> context.row(part.index, part.value, label, ImportedFieldName.displayName(part.value.name)) }
        return CardSection("document", CardSection.Kind.Document(document), title, rows) to parts.map { it.index }.toSet()
    }
}

/** What the document's face shows (SPEC §6.2): only unprotected parts, dates that are dates; the number always (masked when protected). */
public data class IdentityDocument(
    /** "Passport", "ID card", … as the field says. */
    val kind: String?,
    val kindRowId: String?,
    val numberRowId: String,
    val name: String?,
    val nameRowId: String?,
    /** The country's name when the field gives a code ("DE" → "Germany"), else its text. */
    val country: String?,
    /** ISO 3166 alpha-2, when the field names a country. */
    val countryCode: String?,
    val countryRowId: String?,
    val birth: FieldDate?,
    val birthRowId: String?,
    val issued: FieldDate?,
    val issuedRowId: String?,
    val expiry: FieldDate?,
    val expiryRowId: String?,
    val expiryHint: ExpiryHint?,
) {
    /** The rows drawn on the face; the others are rows under it. */
    val faceRowIds: Set<String> get() = setOfNotNull(kindRowId, numberRowId, nameRowId, countryRowId, birthRowId, issuedRowId, expiryRowId)

    /** The heading on the face, as the field says it ("Passport"; "Identity document" when no type says); shown in capitals. */
    public val heading: UserText get() = kind?.let(UserText::plain) ?: UserText.of(CardText.DOCUMENT_HEADING)
}

/**
 * An entry with an unprotected `ssh.host`: its `ssh.*` fields as one section, drawn as a terminal with the command that
 * connects (SPEC §6.2). The user is the `ssh.user` field, else the one written in the host, else the entry's username.
 */
internal object SshSection : TypedSection {
    override fun make(context: CardContext): Pair<CardSection, Set<Int>>? {
        val hostField = context.typed(FieldType.SSH_HOST).firstOrNull() ?: return null
        val portField = context.typed(FieldType.SSH_PORT).firstOrNull()?.takeIf { !it.value.plainText.isNullOrBlank() }
        val userField = context.typed(FieldType.SSH_USER).firstOrNull()?.takeIf { !it.value.plainText.isNullOrBlank() }
        val target = SshTarget.parse(hostField.value.plainText ?: return null) ?: return null
        val port = portField?.value?.plainText?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 } ?: target.port
        val user = userField?.value?.plainText?.trim() ?: target.user ?: context.username.takeIf { it.isNotEmpty() }
        val login = SshLogin(
            host = target.host,
            port = port,
            user = user,
            hostRowId = RowIds.custom(hostField.index),
            portRowId = portField?.takeIf { port != null }?.let { RowIds.custom(it.index) },
            userRowId = when {
                userField != null -> RowIds.custom(userField.index)
                target.user == null && user != null -> RowIds.USERNAME
                else -> null
            },
            alias = SshLogin.alias(context.title, target.host),
            keyLocation = if (SshKeyLocation.hasSettings(context.attachments)) SshKeyLocation.of(null, context.attachments) else null,
        )
        val parts = context.parts { it.isSshPart }
        val (title, labels) = context.sectionLabels(parts)
        val rows = parts.zip(labels).map { (part, label) -> context.row(part.index, part.value, label, ImportedFieldName.displayName(part.value.name)) }
        return CardSection("ssh", CardSection.Kind.Ssh(login), title, rows) to parts.map { it.index }.toSet()
    }
}

/** A host as an `ssh.host` field may write it: `host`, `user@host`, `host:port`, `[2001:db8::1]:22`, `ssh://user@host:port`. */
internal data class SshTarget(val host: String, val user: String?, val port: Int?) {
    companion object {
        private val HOST = Regex("^[A-Za-z0-9]([A-Za-z0-9._-]*[A-Za-z0-9])?$") // not user text
        private val IPV6 = Regex("^[0-9A-Fa-f:.]+(%[A-Za-z0-9]+)?$") // not user text

        fun parse(text: String): SshTarget? {
            var t = text.trim()
            if (t.isEmpty() || t.any(Char::isWhitespace)) return null
            if (t.lowercase().startsWith("ssh://")) t = t.substring(6).trimEnd('/')
            var user: String? = null
            t.lastIndexOf('@').takeIf { it >= 0 }?.let { at ->
                user = t.substring(0, at).takeIf { it.isNotEmpty() }
                t = t.substring(at + 1)
            }
            var port: Int? = null
            val host: String
            if (t.startsWith("[")) {
                val close = t.indexOf(']').takeIf { it > 0 } ?: return null
                host = t.substring(1, close)
                val rest = t.substring(close + 1)
                if (rest.isNotEmpty()) port = rest.removePrefix(":").toIntOrNull()?.takeIf { rest.startsWith(":") && it in 1..65535 } ?: return null
            } else if (t.count { it == ':' } == 1) {
                host = t.substringBefore(':')
                port = t.substringAfter(':').toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            } else {
                host = t
            }
            if (!HOST.matches(host) && !(':' in host && IPV6.matches(host))) return null
            return SshTarget(host, user, port)
        }
    }
}

/** What the SSH face shows and copies (SPEC §6.2): the command, the lines of a `~/.ssh/config` block, an `ssh://` link. */
public data class SshLogin(
    val host: String,
    val port: Int?,
    val user: String?,
    val hostRowId: String,
    val portRowId: String?,
    /** The `ssh.user` row, or the username's when the user is the entry's; null when the host writes it. */
    val userRowId: String?,
    /** The `Host` name of the config block, from the entry's title. */
    val alias: String,
    /** The key, when the entry has KeePassXC's SSH agent settings; the card reads the settings for the exact place. */
    val keyLocation: SshKeyLocation?,
) {
    /** The rows drawn on the face; the others are rows under it (the username stays in the credentials). */
    val faceRowIds: Set<String> get() = setOfNotNull(hostRowId, portRowId, userRowId?.takeIf { it != RowIds.USERNAME })

    /** A port other than SSH's own. */
    private val otherPort: Int? get() = port?.takeIf { it != 22 }

    private val hostForUri: String get() = if (':' in host) "[$host]" else host

    /** `user@host:port` as the list's line says it. */
    val target: String get() = (user?.let { "$it@" } ?: "") + hostForUri + (otherPort?.let { ":$it" } ?: "")

    /** The link an SSH app opens (ConnectBot, JuiceSSH): `ssh://user@host:port`. */
    val uri: String get() = "ssh://" + (user?.let { "$it@" } ?: "") + hostForUri + (port?.let { ":$it" } ?: "")

    /** `ssh -i <key file> -p <port> user@host`: `-i` only for a key in a file (an attachment's key comes through the agent). */
    public fun command(key: SshKeyLocation? = keyLocation): String = buildList {
        add("ssh")
        if (key is SshKeyLocation.InFile) {
            add("-i")
            add(shellPath(key.path))
        }
        otherPort?.let { add("-p"); add(it.toString()) }
        add((user?.let { shellWord(it) + "@" } ?: "") + host)
    }.joinToString(" ")

    /** A `Host` block for `~/.ssh/config`; a key in a file as its `IdentityFile`, an attachment's key named in a comment. */
    public fun config(key: SshKeyLocation? = keyLocation): String = buildList {
        add("Host $alias") // not user text
        add("  HostName $host") // not user text
        user?.let { add("  User ${configWord(it)}") } // not user text
        otherPort?.let { add("  Port $it") } // not user text
        when (key) {
            is SshKeyLocation.InFile -> {
                add("  IdentityFile ${configWord(key.path)}") // not user text
                add("  IdentitiesOnly yes") // not user text
            }
            is SshKeyLocation.InAttachment -> add("  # key ${key.name} via the SSH agent") // not user text
            null -> Unit
        }
    }.joinToString("\n")

    public companion object {
        /** A `Host` name from the entry's title: lower case, a dash for anything but letters, digits, dots and dashes. */
        internal fun alias(title: String, host: String): String {
            val slug = title.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9._-]+"), "-").trim('-', '.')
            return slug.ifEmpty { host }
        }

        private val PLAIN = Regex("^[A-Za-z0-9._@%+=:,/-]+$") // not user text

        /** Quoted for a POSIX shell when it needs it. */
        private fun shellWord(word: String): String = if (PLAIN.matches(word)) word else "'" + word.replace("'", "'\\''") + "'"

        /** A path with its `~/` left unquoted, for the shell to expand. */
        private fun shellPath(path: String): String = if (path.startsWith("~/")) "~/" + shellWord(path.substring(2)) else shellWord(path)

        private fun configWord(word: String): String = if (word.any(Char::isWhitespace)) "\"$word\"" else word
    }
}

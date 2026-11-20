package dev.keething.card

import dev.keething.domain.text.UserText
import dev.keething.domain.totp.TotpConfig
import dev.keething.domain.vault.Attachment
import dev.keething.domain.vault.CustomField
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.FieldValue
import dev.keething.totp.TotpParseException
import dev.keething.totp.TotpParser
import dev.keething.totp.TotpResolver
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** A group of rows on the card, drawn as one rounded section. */
public data class CardSection(val id: String, val kind: Kind, val title: String?, val rows: List<FieldRow>) {
    /** A list of codes: [row] is one left to use, not marked used and with a value (a segment of the bar filled). */
    public fun isLeft(row: FieldRow): Boolean = row.usedOn == null && row.isCopyable

    /** A list of codes: how many are left to use ("N of M left", M every row). */
    public val codesLeft: Int get() = rows.count(::isLeft)

    /** A list of codes with two or fewer left: the bar in the error colour. */
    public val fewCodesLeft: Boolean get() = codesLeft <= 2

    /** A list of codes: the one to use next, outlined and copied by Copy next unused; null when none is left. */
    public val nextUnusedCode: FieldRow? get() = if (kind == Kind.Codes) rows.firstOrNull(::isLeft) else null

    public sealed interface Kind {
        /** Username, password, URL and additional URLs, one-time code. */
        public data object Credentials : Kind

        /** A note's text (SPEC §6.4): first, before everything the note has besides. */
        public data object Note : Kind

        public data object Notes : Kind

        /** Custom fields: loose ones (no title) or a 1Password section (titled). */
        public data object Custom : Kind

        /** A titled section whose fields are a list of codes (recovery codes): a numbered grid. */
        public data object Codes : Kind

        /** The `card.*` fields, drawn as a payment card. */
        public data class PaymentCard(val card: dev.keething.card.PaymentCard) : Kind

        /** The `wifi.*` fields, with a QR code to join the network. */
        public data class WiFi(val network: WiFiNetwork) : Kind

        /** The `bank.*` fields, drawn as an account. */
        public data class BankAccount(val account: dev.keething.card.BankAccount) : Kind

        /** The `doc.*` fields, drawn as an identity document. */
        public data class Document(val document: IdentityDocument) : Kind

        /** The `ssh.*` fields, drawn as a terminal with the command. */
        public data class Ssh(val login: SshLogin) : Kind

        /** The `crypto.*` fields, drawn as a wallet: the recovery phrase on a steel plate, the address with its QR code. */
        public data class Crypto(val wallet: CryptoWallet) : Kind

        public data object Attachments : Kind
    }
}

/** KeePass's own expiry of the entry (its banner on the card), [hint] in the words of a field's (card.dates AC8). */
public sealed interface EntryExpiry {
    public val at: Instant
    public val hint: ExpiryHint

    public data class Expired(override val at: Instant, override val hint: ExpiryHint) : EntryExpiry

    public data class Expires(override val at: Instant, override val hint: ExpiryHint) : EntryExpiry
}

public data class EntryCard(
    val id: String,
    val title: UserText,
    /** The group's path from the top-level group; empty in the root group. */
    val groupPath: List<String>,
    /** The innermost group (what the group chip filters by); empty in the root group. */
    val groupName: String,
    val tags: List<String>,
    /** Every row, in the order of [sections]. */
    val rows: List<FieldRow>,
    val sections: List<CardSection>,
    val entryExpiry: EntryExpiry?,
    val avatar: EntryAvatar,
    /** The most pressing date a field says something ends on, when expired or due within 90 days. */
    val deadline: Deadline?,
    val lastModified: Instant?,
    val created: Instant?,
    val customIconId: String?,
    /** The entry's custom fields' names, by index: which field a `custom-<index>` row is. */
    val customFieldNames: List<String> = emptyList(),
) {
    /** The entry is a note (SPEC §6.4): its text comes first, and the note tile stands in for its icon. */
    val isNote: Boolean get() = sections.firstOrNull()?.kind == CardSection.Kind.Note

    /**
     * A part of a text the user selected, to copy through the app (SPEC §6.4; Mac `EntryCardModel.copySelection`): a
     * note's text, or Notes protected in the file. Only from a text shown in clear, and only a part of it; concealed
     * and cleared as its row's Copy. Null for anything else, unprotected Notes too (the system's selection stays).
     */
    public fun selectionToCopy(rowId: String, text: String, reveal: RevealState): NoteSelection? {
        if (text.isEmpty()) return null
        val row = rows.firstOrNull { it.id == rowId } ?: return null
        val isText = row.kind == FieldRow.Kind.NOTE || (row.kind == FieldRow.Kind.NOTES && row.isConcealed)
        if (!isText || (row.isConcealed && !reveal.isRevealed(row.id)) || text !in row.storedText()) return null
        return NoteSelection(text, sensitive = row.isConcealed)
    }

    val paymentCard: PaymentCard? get() = sections.firstNotNullOfOrNull { (it.kind as? CardSection.Kind.PaymentCard)?.card }
    val wifi: WiFiNetwork? get() = sections.firstNotNullOfOrNull { (it.kind as? CardSection.Kind.WiFi)?.network }
    val bankAccount: BankAccount? get() = sections.firstNotNullOfOrNull { (it.kind as? CardSection.Kind.BankAccount)?.account }
    val document: IdentityDocument? get() = sections.firstNotNullOfOrNull { (it.kind as? CardSection.Kind.Document)?.document }
    val ssh: SshLogin? get() = sections.firstNotNullOfOrNull { (it.kind as? CardSection.Kind.Ssh)?.login }
    val crypto: CryptoWallet? get() = sections.firstNotNullOfOrNull { (it.kind as? CardSection.Kind.Crypto)?.wallet }

    /**
     * The text of the Wi-Fi network's QR code (SPEC §3.13; Mac `EntryCardModel.wifiQRPayload`): it holds the
     * password, so only while the password row is [revealed][reveal]; an open network's at any time.
     */
    public fun wifiQrPayload(reveal: RevealState): String? {
        val network = wifi ?: return null
        val rowId = network.passwordRowId ?: return network.qrPayload("")
        if (!reveal.isRevealed(rowId)) return null
        val row = rows.firstOrNull { it.id == rowId } ?: return null
        return network.qrPayload(row.storedText())
    }
}

/**
 * Lays an entry out as card rows (Mac `EntryCardBuilder`): a note's text first (SPEC §6.4); username, password, URL
 * (and additional URLs), the one-time code; a view per kind of entry its typed fields make (payment card, Wi-Fi
 * network, bank account, identity document, SSH login, crypto wallet); notes, unless they are a note's text; custom
 * fields in file order grouped by 1Password section; the attachments.
 */
public object EntryCardBuilder {
    /** The views of kinds of entries, in card order. */
    private val typedSections: List<TypedSection> = listOf(PaymentCardSection, WiFiSection, BankAccountSection, DocumentSection, SshSection, CryptoSection)

    public fun build(entry: Entry, now: Instant, locale: Locale = Locale.getDefault(), zone: ZoneId = ZoneId.systemDefault()): EntryCard {
        fun row(id: String, kind: FieldRow.Kind, label: CardText, value: FieldValue) = FieldRow(id, kind, UserText.of(label), value)
        val context = CardContext(
            entry.customFields.withIndex().toList(), FieldInterpreter(entry), now, zone, locale,
            title = entry.sortTitle, username = entry.username, attachments = entry.attachments,
        )

        val credentials = mutableListOf<FieldRow>()
        if (entry.username.isNotEmpty()) credentials += row(RowIds.USERNAME, FieldRow.Kind.USERNAME, CardText.ROW_USERNAME, FieldValue.Plain(entry.username))
        if (!entry.password.isEmpty()) credentials += row(RowIds.PASSWORD, FieldRow.Kind.PASSWORD, CardText.ROW_PASSWORD, FieldValue.Protected(entry.password))
        if (entry.url.isNotEmpty()) {
            credentials += FieldRow(RowIds.URL, FieldRow.Kind.URL, UserText.of(CardText.ROW_URL), FieldValue.Plain(entry.url), style = addressStyle(entry.url, null))
        }
        // Additional URLs join the credentials.
        var urlNumber = if (entry.url.isEmpty()) 0 else 1
        for ((i, field) in context.fields) {
            if (!field.isAdditionalUrl) continue
            urlNumber++
            val label = if (urlNumber == 1) UserText.of(CardText.ROW_URL) else UserText.of(CardText.ROW_URL_NUMBERED, urlNumber)
            credentials += FieldRow(RowIds.custom(i), FieldRow.Kind.CUSTOM, label, field.value, style = field.plainText?.let { addressStyle(it, null) } ?: ValueStyle.Text)
        }
        if (entry.totp != null) {
            credentials += FieldRow(RowIds.TOTP, FieldRow.Kind.TOTP, UserText.of(CardText.ROW_ONE_TIME_CODE), FieldValue.Plain(""), totp = entry.totp)
        } else {
            // Code fields that give no code (Steam, a counter-based link, a broken secret): the row says why.
            OneTimeCodes.problem(entry)?.let { problem ->
                credentials += FieldRow(
                    RowIds.TOTP, FieldRow.Kind.TOTP, UserText.of(CardText.ROW_ONE_TIME_CODE), FieldValue.Plain(""),
                    codeProblem = problem,
                )
            }
        }

        val sections = mutableListOf<CardSection>()
        // A note's text first, everything added to it under it (keething-spec types.note AC3, AC6).
        val isNote = entry.isNote
        if (isNote) sections += CardSection("note", CardSection.Kind.Note, null, listOf(notesRow(entry, RowIds.NOTE, FieldRow.Kind.NOTE, CardText.ROW_NOTE)))
        if (credentials.isNotEmpty()) sections += CardSection("credentials", CardSection.Kind.Credentials, null, credentials)
        val claimed = mutableSetOf<Int>()
        for (kind in typedSections) {
            val (section, fields) = kind.make(context) ?: continue
            sections += section
            claimed += fields
        }
        if (!isNote && entry.notes.isNotEmpty()) {
            sections += CardSection("notes", CardSection.Kind.Notes, null, listOf(notesRow(entry, RowIds.NOTES, FieldRow.Kind.NOTES, CardText.ROW_NOTES)))
        }
        sections += customSections(context.fields.filter { !it.value.isAdditionalUrl && it.index !in claimed }, context)

        val attachments = entry.attachments.mapIndexed { i, attachment ->
            FieldRow(RowIds.attachment(i), FieldRow.Kind.ATTACHMENT, UserText.plain(attachment.name), FieldValue.Plain(""), size = attachment.size)
        }
        if (attachments.isNotEmpty()) sections += CardSection("attachments", CardSection.Kind.Attachments, null, attachments)

        val expiry = entry.expiryTime?.takeIf { entry.expires }?.let { at ->
            DeadlineList.entryExpiryDate(at, zone)?.let { date ->
                val hint = DeadlineList.entryExpiryHint(date, entry.isExpired(now), now, zone)
                if (entry.isExpired(now)) EntryExpiry.Expired(at, hint) else EntryExpiry.Expires(at, hint)
            }
        }
        // KeePass's own expiry has its banner, a face's own expiry (a card's, a document's) its chip.
        val faceExpiries = sections.mapNotNull { section ->
            when (val kind = section.kind) {
                is CardSection.Kind.PaymentCard -> kind.card.expiryRowId
                is CardSection.Kind.Document -> kind.document.expiryRowId
                else -> null
            }
        }.toSet()
        val pressing = DeadlineList.deadlines(entry, context.interpreter, now, zone).filter { deadline ->
            val source = deadline.source
            source != Deadline.Source.EntryExpiry && deadline.hint.status != ExpiryHint.Status.VALID &&
                !(source is Deadline.Source.Field && RowIds.custom(source.index) in faceExpiries)
        }
        return EntryCard(
            id = entry.id,
            title = entry.displayTitle,
            groupPath = entry.groupPath,
            groupName = entry.groupPath.lastOrNull() ?: "",
            tags = entry.tags,
            rows = sections.flatMap { it.rows },
            sections = sections,
            entryExpiry = expiry,
            avatar = EntryAvatar.of(entry.sortTitle),
            deadline = pressing.minByOrNull { it.date },
            lastModified = entry.lastModified,
            created = entry.created,
            customIconId = entry.customIconId,
            customFieldNames = entry.customFields.map { it.name },
        )
    }

    /**
     * What each field without a type is read as (Mac SPEC §3.6.2), for the editor's type menu: a date (`until` when
     * the name says something ends), a link, an IBAN, one of a list of codes; text otherwise. By index.
     */
    public fun guessedTypes(fields: List<CustomField>): List<FieldType> {
        val interpreter = FieldInterpreter(FieldTypes.NONE)
        val guesses = fields.map { field ->
            val date = interpreter.date(field)
            when {
                date != null -> if (date.second) FieldType.UNTIL else FieldType.DATE
                interpreter.link(field) != null -> FieldType.URL
                field.plainText?.let(Iban::of) != null -> FieldType.BANK_IBAN
                else -> FieldType.TEXT
            }
        }.toMutableList()
        val sections = LinkedHashMap<String, MutableList<Int>>()
        for ((i, field) in fields.withIndex()) {
            ImportedFieldName.split(field.name)?.let { (section, _) -> sections.getOrPut(section) { mutableListOf() } += i }
        }
        for (indices in sections.values) {
            if (indices.size < 2) continue
            val members = indices.map { fields[it] to (ImportedFieldName.split(fields[it].name)?.second ?: "") }
            if (ImportedFieldName.isCodeList(members, interpreter)) indices.forEach { guesses[it] = FieldType.CODE }
        }
        return guesses
    }

    /** The text of Notes, a note's or an entry's: concealed while protected in the file (types.note AC6). */
    private fun notesRow(entry: Entry, id: String, kind: FieldRow.Kind, label: CardText): FieldRow {
        val row = FieldRow(id, kind, UserText.of(label), FieldValue.Plain(entry.notes))
        return if (entry.notesProtected) row.concealed() else row
    }

    /** An address in the URL or a `KP2A_URL…` field: an app's, else a link when the browser can open it. */
    private fun addressStyle(text: String, fieldName: String?): ValueStyle =
        AppAddressStyle.of(text, fieldName) ?: OpenableUrl.of(text)?.let(ValueStyle::Link) ?: ValueStyle.Text

    /**
     * A `Section_` prefix shared by two or more fields makes a titled section, placed where its first field
     * is; other fields are loose, and loose fields with no new section between them share one untitled section.
     */
    private fun customSections(fields: List<IndexedValue<CustomField>>, context: CardContext): List<CardSection> {
        val prefixCounts = fields.mapNotNull { ImportedFieldName.split(it.value.name)?.first }.groupingBy { it }.eachCount()

        class Item(val index: Int, val field: CustomField, val label: String)
        class Block(val title: String?, val items: MutableList<Item> = mutableListOf())
        val blocks = mutableListOf<Block>()
        val blockOfSection = mutableMapOf<String, Block>()
        for ((i, field) in fields) {
            val split = ImportedFieldName.split(field.name)
            if (split != null && (prefixCounts[split.first] ?: 0) >= 2) {
                val block = blockOfSection.getOrPut(split.first) { Block(split.first).also(blocks::add) }
                block.items += Item(i, field, split.second)
            } else {
                if (blocks.isEmpty() || blocks.last().title != null) blocks += Block(null)
                blocks.last().items += Item(i, field, field.name)
            }
        }

        return blocks.map { block ->
            val isCodes = block.title != null && ImportedFieldName.isCodeList(block.items.map { it.field to it.label }, context.interpreter)
            val rows = block.items.mapIndexed { n, item ->
                var label = if (ImportedFieldName.isGeneratedId(item.label)) "" else item.label
                if (isCodes && label.isEmpty()) label = "${n + 1}"
                // A code's notice is its list's name and its number ("Backup codes 3"): the user's words, no sentence.
                val notice = when {
                    label.isNotEmpty() -> UserText.plain(if (isCodes) "${block.title} $label" else label)
                    block.title != null -> UserText.plain(block.title)
                    else -> UserText.of(CardText.ROW_VALUE)
                }
                context.row(item.index, item.field, UserText.plain(label), notice).let { row ->
                    if (isCodes) row.copy(usedOn = UsedCode.parse(row.storedText())?.day) else row
                }
            }
            CardSection(
                "section-${block.items[0].index}",
                if (isCodes) CardSection.Kind.Codes else CardSection.Kind.Custom,
                block.title,
                rows,
            )
        }
    }
}

/** What the parts of the card are built from: the entry's custom fields with their index, how they are read, the time and the phone's zone, the locale. */
internal class CardContext(
    val fields: List<IndexedValue<CustomField>>,
    val interpreter: FieldInterpreter,
    val now: Instant,
    val zone: ZoneId,
    val locale: Locale,
    val title: String = "",
    val username: String = "",
    val attachments: List<Attachment> = emptyList(),
) {
    /** The fields typed [type] in the entry. */
    fun typed(type: FieldType): List<IndexedValue<CustomField>> = fields.filter { interpreter.type(it.value) == type }

    /** The fields whose type passes [test]: the parts of one kind of entry. */
    fun parts(test: (FieldType) -> Boolean): List<IndexedValue<CustomField>> =
        fields.filter { interpreter.type(it.value)?.let(test) == true }

    /**
     * Title and labels for a kind's own section: when all its fields come from one 1Password section, that is
     * the title and the labels go without it; else each field's full name.
     */
    fun sectionLabels(parts: List<IndexedValue<CustomField>>): Pair<String?, List<UserText>> {
        val splits = parts.map { ImportedFieldName.split(it.value.name) }
        val first = splits.firstOrNull()
        if (first != null && splits.all { it?.first == first.first }) {
            return first.first to splits.map { s -> UserText.plain(s?.second?.takeUnless(ImportedFieldName::isGeneratedId) ?: "") }
        }
        return null to parts.map { ImportedFieldName.displayName(it.value.name) }
    }

    /**
     * A custom field's row: an unprotected value may be a date or a link; a protected one is never read as
     * either. A typed field is what its type says (only `until` and `card.expiry` end something); an untyped one
     * is a date in an unmistakable form, ending something when the name says so, or a link (Mac SPEC §3.6.2). An
     * unprotected value typed `password` or as a card's secret, or plainly a secret (a private key, an `otpauth://`
     * link), is masked anyway ([Masking]).
     */
    fun row(index: Int, field: CustomField, label: UserText, notice: UserText): FieldRow {
        val id = RowIds.custom(index)
        val type = interpreter.type(field)
        // A wallet's secret, wherever it stands (also outside a wallet's section): masked, never copied (SPEC §6.2).
        if (CryptoWallet.isNeverCopied(field, type)) {
            return FieldRow(id, FieldRow.Kind.CUSTOM, label, field.value, noticeLabel = notice, type = type, concealed = true, neverCopied = true)
        }
        // Another one-time code (1Password's second and later codes arrive so): a live code.
        OneTimeCodes.config(field)?.let { config ->
            return FieldRow(
                id, FieldRow.Kind.TOTP, if (label.isEmptyLabel) UserText.of(CardText.ROW_ONE_TIME_CODE) else label, FieldValue.Plain(""),
                noticeLabel = if (label.isEmptyLabel) UserText.of(CardText.ROW_ONE_TIME_CODE) else notice, totp = config,
            )
        }
        val text = field.plainText
        if (text == null || text.isEmpty()) {
            return FieldRow(id, FieldRow.Kind.CUSTOM, label, field.value, noticeLabel = notice, type = type)
        }
        interpreter.date(field)?.let { (date, ends) ->
            return FieldRow(
                id, FieldRow.Kind.CUSTOM, label, field.value, style = ValueStyle.Date(date), shownText = date.displayText(locale),
                noticeLabel = notice, expiry = if (ends) date.expiry(now, zone) else null, type = type,
            )
        }
        if (type == null || type == FieldType.URL) {
            AppAddressStyle.of(text, (label as? UserText.Plain)?.text)?.let { app ->
                return FieldRow(id, FieldRow.Kind.CUSTOM, label, field.value, style = app, noticeLabel = notice, type = type)
            }
        }
        interpreter.link(field)?.let { url ->
            return FieldRow(id, FieldRow.Kind.CUSTOM, label, field.value, style = ValueStyle.Link(url), noticeLabel = notice, type = type)
        }
        // An IBAN without a type is unmistakable: grouped, its country's flag in front, copied without spaces.
        if (type == null) {
            Iban.of(text)?.let { iban ->
                val shown = listOfNotNull(Countries.flag(Iban.country(iban)), Iban.grouped(iban)).joinToString("  ")
                return FieldRow(id, FieldRow.Kind.CUSTOM, label, field.value, shownText = shown, noticeLabel = notice, copiesCompact = true)
            }
        }
        return FieldRow(
            id, FieldRow.Kind.CUSTOM, label, field.value, noticeLabel = notice, type = type,
            concealed = Masking.isMasked(type, isProtected = false, text),
        )
    }
}

/** One-time codes beyond the entry's own: custom fields holding an `otpauth://` link (Mac `OneTimeCodes`). */
public object OneTimeCodes {
    public fun config(field: CustomField): TotpConfig? {
        val value = text(field)
        if (!isLink(value)) return null
        return try {
            TotpParser.parseOtpAuthUri(value)
        } catch (_: TotpParseException) {
            null
        }
    }

    /**
     * Why [entry]'s code fields give no code, "This code cannot be used here: Steam codes." (keething-spec card.totp
     * AC6); null when it has a code or no code fields.
     */
    public fun problem(entry: Entry): UserText? {
        if (entry.totp != null || !entry.hasCodeFields) return null
        val texts = entry.codeFields.associate { field ->
            field.name to when (val v = field.value) {
                is FieldValue.Plain -> v.text
                is FieldValue.Protected -> v.secret.reveal()
            }
        }
        return TotpResolver.problem(texts)?.let { UserText.of(CardText.CODE_UNSUPPORTED, it) }
    }

    /** [field] holds a one-time code's link, whether it gives a code or not. */
    public fun holdsLink(field: CustomField): Boolean = isLink(text(field))

    private fun text(field: CustomField): String = when (val v = field.value) {
        is FieldValue.Plain -> v.text
        is FieldValue.Protected -> v.secret.reveal()
    }.trim()

    private fun isLink(value: String): Boolean = value.lowercase().startsWith("otpauth://")
}

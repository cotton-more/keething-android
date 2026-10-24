package dev.keepassbar.card

import dev.keepassbar.domain.totp.TotpConfig
import dev.keepassbar.domain.vault.Attachment
import dev.keepassbar.domain.vault.CustomField
import dev.keepassbar.domain.vault.Entry
import dev.keepassbar.domain.vault.FieldValue
import dev.keepassbar.totp.TotpParseException
import dev.keepassbar.totp.TotpParser
import java.time.Instant
import java.util.Locale

/** A group of rows on the card, drawn as one rounded section. */
public data class CardSection(val id: String, val kind: Kind, val title: String?, val rows: List<FieldRow>) {
    public sealed interface Kind {
        /** Username, password, URL and additional URLs, one-time code. */
        public data object Credentials : Kind

        public data object Notes : Kind

        /** Custom fields: loose ones (no title) or a 1Password section (titled). */
        public data object Custom : Kind

        /** A titled section whose fields are a list of codes (recovery codes): a numbered grid. */
        public data object Codes : Kind

        /** The `card.*` fields, drawn as a payment card. */
        public data class PaymentCard(val card: dev.keepassbar.card.PaymentCard) : Kind

        /** The `wifi.*` fields, with a QR code to join the network. */
        public data class WiFi(val network: WiFiNetwork) : Kind

        /** The `bank.*` fields, drawn as an account. */
        public data class BankAccount(val account: dev.keepassbar.card.BankAccount) : Kind

        /** The `doc.*` fields, drawn as an identity document. */
        public data class Document(val document: IdentityDocument) : Kind

        /** The `ssh.*` fields, drawn as a terminal with the command. */
        public data class Ssh(val login: SshLogin) : Kind

        public data object Attachments : Kind
    }
}

/** KeePass's own expiry of the entry (its banner on the card). */
public sealed interface EntryExpiry {
    public data class Expired(val at: Instant) : EntryExpiry

    public data class Expires(val at: Instant) : EntryExpiry
}

public data class EntryCard(
    val id: String,
    val title: String,
    val groupPath: String,
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
) {
    val paymentCard: PaymentCard? get() = sections.firstNotNullOfOrNull { (it.kind as? CardSection.Kind.PaymentCard)?.card }
    val wifi: WiFiNetwork? get() = sections.firstNotNullOfOrNull { (it.kind as? CardSection.Kind.WiFi)?.network }
    val bankAccount: BankAccount? get() = sections.firstNotNullOfOrNull { (it.kind as? CardSection.Kind.BankAccount)?.account }
    val document: IdentityDocument? get() = sections.firstNotNullOfOrNull { (it.kind as? CardSection.Kind.Document)?.document }
    val ssh: SshLogin? get() = sections.firstNotNullOfOrNull { (it.kind as? CardSection.Kind.Ssh)?.login }

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
 * Lays an entry out as card rows (Mac `EntryCardBuilder`): username, password, URL (and additional URLs), the
 * one-time code; a view per kind of entry its typed fields make (payment card, Wi-Fi network); notes; custom
 * fields in file order grouped by 1Password section; the attachments.
 */
public object EntryCardBuilder {
    /** The views of kinds of entries, in card order. */
    private val typedSections: List<TypedSection> = listOf(PaymentCardSection, WiFiSection, BankAccountSection, DocumentSection, SshSection)

    public fun build(entry: Entry, now: Instant, locale: Locale = Locale.getDefault()): EntryCard {
        fun row(id: String, kind: FieldRow.Kind, label: String, value: FieldValue) = FieldRow(id, kind, label, value)
        val context = CardContext(
            entry.customFields.withIndex().toList(), FieldInterpreter(entry), now, locale,
            title = entry.displayTitle, username = entry.username, attachments = entry.attachments,
        )

        val credentials = mutableListOf<FieldRow>()
        if (entry.username.isNotEmpty()) credentials += row(RowIds.USERNAME, FieldRow.Kind.USERNAME, "Username", FieldValue.Plain(entry.username))
        if (!entry.password.isEmpty()) credentials += row(RowIds.PASSWORD, FieldRow.Kind.PASSWORD, "Password", FieldValue.Protected(entry.password))
        if (entry.url.isNotEmpty()) {
            credentials += FieldRow(RowIds.URL, FieldRow.Kind.URL, "URL", FieldValue.Plain(entry.url), style = linkStyle(OpenableUrl.of(entry.url)))
        }
        // Additional URLs join the credentials.
        var urlNumber = if (entry.url.isEmpty()) 0 else 1
        for ((i, field) in context.fields) {
            if (!field.isAdditionalUrl) continue
            urlNumber++
            val url = field.plainText?.let(OpenableUrl::of)
            credentials += FieldRow(RowIds.custom(i), FieldRow.Kind.CUSTOM, if (urlNumber == 1) "URL" else "URL $urlNumber", field.value, style = linkStyle(url))
        }
        if (entry.totp != null) {
            credentials += FieldRow(RowIds.TOTP, FieldRow.Kind.TOTP, "One-time code", FieldValue.Plain(""), totp = entry.totp)
        }

        val sections = mutableListOf<CardSection>()
        if (credentials.isNotEmpty()) sections += CardSection("credentials", CardSection.Kind.Credentials, null, credentials)
        val claimed = mutableSetOf<Int>()
        for (kind in typedSections) {
            val (section, fields) = kind.make(context) ?: continue
            sections += section
            claimed += fields
        }
        if (entry.notes.isNotEmpty()) {
            sections += CardSection("notes", CardSection.Kind.Notes, null, listOf(row(RowIds.NOTES, FieldRow.Kind.NOTES, "Notes", FieldValue.Plain(entry.notes))))
        }
        sections += customSections(context.fields.filter { !it.value.isAdditionalUrl && it.index !in claimed }, context)

        val attachments = entry.attachments.mapIndexed { i, attachment ->
            FieldRow(RowIds.attachment(i), FieldRow.Kind.ATTACHMENT, attachment.name, FieldValue.Plain(""), size = attachment.size)
        }
        if (attachments.isNotEmpty()) sections += CardSection("attachments", CardSection.Kind.Attachments, null, attachments)

        val expiry = entry.expiryTime?.takeIf { entry.expires }?.let {
            if (entry.isExpired(now)) EntryExpiry.Expired(it) else EntryExpiry.Expires(it)
        }
        // KeePass's own expiry has its banner.
        val pressing = DeadlineList.deadlines(entry, context.interpreter, now)
            .filter { it.source != Deadline.Source.EntryExpiry && it.hint.status != ExpiryHint.Status.VALID }
        return EntryCard(
            id = entry.id,
            title = entry.displayTitle,
            groupPath = GroupPathFormatter.text(entry.groupPath),
            groupName = entry.groupPath.lastOrNull() ?: "",
            tags = entry.tags,
            rows = sections.flatMap { it.rows },
            sections = sections,
            entryExpiry = expiry,
            avatar = EntryAvatar.of(entry.displayTitle),
            deadline = pressing.minByOrNull { it.date },
            lastModified = entry.lastModified,
            created = entry.created,
            customIconId = entry.customIconId,
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

    private fun linkStyle(url: String?): ValueStyle = url?.let(ValueStyle::Link) ?: ValueStyle.Text

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
                val title = block.title ?: "Value"
                val notice = if (label.isEmpty()) title else if (isCodes) "$title $label" else label
                context.row(item.index, item.field, label, notice).let { row ->
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

/** What the parts of the card are built from: the entry's custom fields with their index, how they are read, the time, the locale. */
internal class CardContext(
    val fields: List<IndexedValue<CustomField>>,
    val interpreter: FieldInterpreter,
    val now: Instant,
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
    fun sectionLabels(parts: List<IndexedValue<CustomField>>): Pair<String?, List<String>> {
        val splits = parts.map { ImportedFieldName.split(it.value.name) }
        val first = splits.firstOrNull()
        if (first != null && splits.all { it?.first == first.first }) {
            return first.first to splits.map { s -> s?.second?.takeUnless(ImportedFieldName::isGeneratedId) ?: "" }
        }
        return null to parts.map { ImportedFieldName.displayName(it.value.name) }
    }

    /**
     * A custom field's row: an unprotected value may be a date or a link; a protected one is never read as
     * either. A typed field is what its type says (only `until` and `card.expiry` end something); an untyped one
     * is a date in an unmistakable form, ending something when the name says so, or a link (Mac SPEC §3.6.2). An
     * unprotected value typed `password`, or plainly a secret (a private key, an `otpauth://` link), is masked
     * anyway.
     */
    fun row(index: Int, field: CustomField, label: String, notice: String): FieldRow {
        val id = RowIds.custom(index)
        val type = interpreter.type(field)
        // Another one-time code (1Password's second and later codes arrive so): a live code.
        OneTimeCodes.config(field)?.let { config ->
            return FieldRow(
                id, FieldRow.Kind.TOTP, label.ifEmpty { "One-time code" }, FieldValue.Plain(""),
                noticeLabel = if (label.isEmpty()) "One-time code" else notice, totp = config,
            )
        }
        val text = field.plainText
        if (text == null || text.isEmpty()) {
            return FieldRow(id, FieldRow.Kind.CUSTOM, label, field.value, noticeLabel = notice, type = type)
        }
        interpreter.date(field)?.let { (date, ends) ->
            return FieldRow(
                id, FieldRow.Kind.CUSTOM, label, field.value, style = ValueStyle.Date(date), shownText = date.displayText(locale),
                noticeLabel = notice, expiry = if (ends) date.expiry(now) else null, type = type,
            )
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
            concealed = type == FieldType.PASSWORD || ValueShape.isPlainlySecret(text),
        )
    }
}

/** One-time codes beyond the entry's own: custom fields holding an `otpauth://` link (Mac `OneTimeCodes`). */
public object OneTimeCodes {
    public fun config(field: CustomField): TotpConfig? {
        val value = when (val v = field.value) {
            is FieldValue.Plain -> v.text
            is FieldValue.Protected -> v.secret.reveal()
        }.trim()
        if (!value.lowercase().startsWith("otpauth://")) return null
        return try {
            TotpParser.parseOtpAuthUri(value)
        } catch (_: TotpParseException) {
            null
        }
    }
}

package dev.keething.card.editing

import dev.keething.card.AddressCheck
import dev.keething.card.CardText
import dev.keething.card.CryptoAddress
import dev.keething.card.DerivationPath
import dev.keething.card.EntryCardBuilder
import dev.keething.card.ExtendedKey
import dev.keething.card.FieldDate
import dev.keething.card.FieldInterpreter
import dev.keething.card.FieldType
import dev.keething.card.Masking
import dev.keething.card.FieldTypes
import dev.keething.card.ImportedFieldName
import dev.keething.card.OneTimeCodes
import dev.keething.card.SeedPhrase
import dev.keething.card.SshTarget
import dev.keething.domain.secret.Secret
import dev.keething.domain.text.UserText
import dev.keething.domain.totp.TotpConfig
import dev.keething.domain.totp.TotpFormat
import dev.keething.domain.vault.AttachmentChange
import dev.keething.domain.vault.CustomField
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.EntryChanges
import dev.keething.domain.vault.FieldValue
import dev.keething.domain.vault.GroupDestination
import dev.keething.domain.vault.NewGroup
import dev.keething.domain.vault.EntryType
import dev.keething.domain.vault.EntryTypeChange
import dev.keething.domain.vault.NoteDates
import dev.keething.domain.vault.NoteTitle
import dev.keething.domain.vault.QuickNote
import dev.keething.domain.vault.TotpChange
import dev.keething.domain.vault.Vault
import dev.keething.domain.vault.VaultEdit
import dev.keething.domain.vault.VaultGroup
import dev.keething.totp.TotpResolver
import dev.keething.totp.TotpSetup
import dev.keething.totp.TotpSetupException
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** How adding a block of codes went. */
public sealed interface AddedCodes {
    public data class Added(val draft: EntryDraft, val count: Int) : AddedCodes

    public data class Refused(val problem: UserText) : AddedCodes
}

/** How adding a one-time code went (keething-spec edit.one-time-code AC5). */
public sealed interface AddedCode {
    /** [field]: the code went into a custom field of that name, the entry having its own already; null: it is the entry's own. */
    public data class Added(val draft: EntryDraft, val field: String?) : AddedCode

    public data class Refused(val problem: UserText) : AddedCode
}

/** What the editor offers (SPEC §6.4): an entry's fields, or a note's title, text, site and the rest. */
public enum class EditorLayout { ENTRY, NOTE }

/** Custom fields the card shows as one list of codes (§3.6.2 of the Mac SPEC); the editor folds them into one row. */
public data class CodeList(val title: String, val fieldIds: List<Int>, val allProtected: Boolean)

/**
 * The rules of the entry editor (SPEC §6.2; Mac `EntryEditorModel`, the part the phone edits): what is wrong
 * with a draft, and what saving it changes — only what differs from the entry as loaded, so a save touches
 * nothing else. [start] is the draft the editor opens with.
 */
public class EntryEditor private constructor(
    private val original: Entry,
    public val isNew: Boolean,
    public val start: EntryDraft,
    /** Where a new entry can go: the groups outside the recycle bin. Empty for an existing entry. */
    public val groups: List<VaultGroup>,
    public val layout: EditorLayout = EditorLayout.ENTRY,
    /** When the editor opened: a new note is named by it ("Note, Oct 31, 2026, 7:45 AM"). */
    private val openedAt: Instant = Instant.EPOCH,
    /** How an untitled protected note's time is written: the app's formatter. */
    private val dates: NoteDates? = null,
    /** What the editor was given to start with besides the entry's own values (autofill's save offer); null: nothing. */
    private val given: EntryDraft? = null,
) {
    // A note (SPEC §6.4; keething-spec types.note AC3–AC5)

    /**
     * A note shows the username, the password and custom fields only when it has them (from another app), and keeps them
     * (AC3); a username or password it was [given] shows too, so it is never saved unseen, and stays while edited.
     */
    public val showsUsername: Boolean
        get() = layout == EditorLayout.ENTRY || original.username.isNotBlank() || given?.username?.isNotBlank() == true
    public val showsPassword: Boolean
        get() = layout == EditorLayout.ENTRY || start.password.isNotBlank() || given?.password?.isNotBlank() == true

    /** This editor opened on [draft] rather than on [start] (a save offer's values): they still count as changes. */
    public fun given(draft: EntryDraft): EntryEditor =
        EntryEditor(original, isNew, start, groups, layout, openedAt, dates, given = draft)

    public val showsCustomFields: Boolean get() = layout == EditorLayout.ENTRY || original.customFields.isNotEmpty()

    /** Add field, codes and a kind's set: a note offers none of them (a login is an entry, AC4). */
    public val offersFields: Boolean get() = layout == EditorLayout.ENTRY

    /** A note's Website row: there when it has a site, or once Add website is tapped. */
    public fun showsWebsite(draft: EntryDraft): Boolean =
        layout == EditorLayout.ENTRY || draft.websiteAdded || original.url.isNotBlank() || draft.url.isNotBlank()

    /** When the note was made: the entry's creation time, a new one's editor opening. */
    private val made: Instant get() = original.created ?: openedAt

    /**
     * The title the save writes: as typed; a note's empty one made from its text, or `Note` and when the note was made
     * while it is protected (AC5).
     */
    public fun savedTitle(draft: EntryDraft): String =
        if (layout != EditorLayout.NOTE || draft.title.isNotBlank()) draft.title else NoteTitle.make(draft.notes, draft.notesProtected, made, dates)

    /** What the empty title field says it will become (the agreed mock-up: the first line itself once there is one). */
    public fun titlePlaceholder(draft: EntryDraft): UserText = when {
        layout != EditorLayout.NOTE -> UserText.of(CardText.TITLE)
        draft.notesProtected -> UserText.of(CardText.TITLE_WILL_BE, NoteTitle.make("", protected = true, made = made, dates = dates))
        draft.notes.isBlank() -> UserText.of(CardText.TITLE_FIRST_LINE)
        else -> UserText.of(CardText.TITLE_WILL_BE, NoteTitle.make(draft.notes, protected = false))
    }

    /**
     * Protect text switched: on empties a title made from the text's first line (the text as saved or as typed), so the
     * line does not stay in the unprotected Title; the save names the note by its date instead. A title of the user's own stays.
     */
    public fun protecting(draft: EntryDraft, on: Boolean): EntryDraft {
        if (on == draft.notesProtected) return draft
        val switched = draft.copy(notesProtected = on)
        if (!on || layout != EditorLayout.NOTE) return switched
        val typed = draft.title.trim()
        val made = listOf(original.notes, draft.notes).filter { it.isNotBlank() }.map { NoteTitle.make(it, protected = false) }
        return if (typed in made) switched.copy(title = "") else switched
    }

    public val entryId: String get() = original.id

    /** The fields as loaded, names untrimmed: tidying them is saved with the next change, but is none itself. */
    private val loadedFields = start.fields.map { FieldText(it.name, it.value, it.isProtected) }

    // Validation

    /**
     * What is wrong with a custom field's name, shown at the field; null when nothing. A field left blank (no
     * name, no value) is not saved, so nothing is wrong with it.
     */
    public fun fieldProblem(draft: EntryDraft, field: FieldDraft): UserText? {
        if (field.isBlank) return null
        val name = field.name.trim()
        if (name.isEmpty()) return UserText.of(CardText.FIELD_NO_NAME)
        if (name in EntryChanges.RESERVED_FIELD_NAMES) return UserText.of(CardText.NAME_RESERVED, name)
        val same = draft.fields.count { !it.isBlank && it.name.trim() == name }
        return if (same > 1) UserText.of(CardText.NAME_TAKEN) else null
    }

    /**
     * A field typed as a date whose value is not one: it is saved, and shown as text; null when it is a date,
     * empty, protected (never read) or of another type. The same for an SSH host or port that is not one.
     */
    public fun fieldWarning(field: FieldDraft): UserText? {
        linkWarning(field)?.let { return it }
        if (field.type?.isCryptoPart == true) return cryptoWarning(field)
        sshWarning(field)?.let { return it }
        val type = field.type?.takeIf { it.isDate } ?: return null
        if (field.isProtected || field.value.isBlank() || FieldDate.parse(field.value, typed = true) != null) return null
        return UserText.of(if (type == FieldType.CARD_EXPIRY) CardText.MONTH_WARNING else CardText.DATE_WARNING)
    }

    /**
     * An `otpauth://` link that gives no code, in a field that is not one: saved all the same, masked (the owner's rule,
     * keething-spec edit.one-time-code AC7). One that reads stays text while the editor is open, without a word.
     */
    private fun linkWarning(field: FieldDraft): UserText? {
        if (field.isCode || !field.value.trim().lowercase().startsWith("otpauth://")) return null // not user text
        return TotpResolver.problem(mapOf(TotpFormat.LINK_FIELD to field.value.trim()))?.let { UserText.of(CardText.LINK_NOT_A_CODE, it) }
    }

    /**
     * The field's value is masked as the card masks it (card.masking AC1, AC2): protected, a password or a card's secret,
     * a wallet's secret, or plainly a secret (an `otpauth://` link, a private key).
     */
    public fun isMasked(field: FieldDraft): Boolean = Masking.isMasked(field.type, field.isProtected, field.value)

    /** An `ssh.host` ssh cannot reach, an `ssh.port` outside 1–65535; saved all the same, shown as a plain row. */
    private fun sshWarning(field: FieldDraft): UserText? {
        val value = field.value.trim()
        if (field.isProtected || value.isEmpty()) return null
        return when (field.type) {
            FieldType.SSH_HOST -> if (SshTarget.parse(value) == null) UserText.of(CardText.HOST_WARNING) else null
            FieldType.SSH_PORT -> if (value.toIntOrNull() !in 1..65535) UserText.of(CardText.PORT_WARNING) else null
            else -> null
        }
    }

    /**
     * A recovery phrase that fails its check (read in the editor even when protected: the text is in the form anyway), an
     * address or key whose checksum fails, a path that is none; null when it passes, is empty, or is not checked.
     */
    private fun cryptoWarning(field: FieldDraft): UserText? {
        val value = field.value.trim().takeIf { it.isNotEmpty() } ?: return null
        return when (field.type) {
            FieldType.CRYPTO_SEED -> SeedPhrase.read(value)?.takeIf { !it.passes }?.summary()
            // An address of a network not known here is shown as it is, without a warning.
            FieldType.CRYPTO_ADDRESS -> if (CryptoAddress.read(value)?.check == AddressCheck.FAILS) UserText.of(CardText.ADDRESS_FAILS) else null
            // A private key first: one behind a public prefix (BIP-32's test vector 5) does not pass either.
            FieldType.CRYPTO_XPUB -> when {
                ExtendedKey.isPrivate(value) -> UserText.of(CardText.KEY_PRIVATE, value.take(4))
                ExtendedKey.read(value)?.passes == false -> UserText.of(CardText.KEY_FAILS)
                else -> null
            }
            FieldType.CRYPTO_PATH -> if (DerivationPath.isPath(value)) null else UserText.of(CardText.PATH_WARNING)
            else -> null
        }
    }

    // Field types (Mac SPEC §3.12)

    /** What each field without a type is read as, by id (the type menu shows it as *Automatic*). */
    public fun guessedTypes(draft: EntryDraft): Map<Int, FieldType> =
        draft.fields.map { it.id }.zip(EntryCardBuilder.guessedTypes(draft.fields.map { it.customField() })).toMap()

    /** The field's type, or what it is read as without one. */
    public fun shownType(draft: EntryDraft, field: FieldDraft): FieldType = field.type ?: guessedTypes(draft)[field.id] ?: FieldType.TEXT

    /** The types saved with the entry: every field's that has one, but `text` where nothing else would be read from the field anyway. */
    private fun savedFieldTypes(draft: EntryDraft): Map<String, String> {
        val kept = draft.fields.filterNot { it.isBlank }
        val guesses = EntryCardBuilder.guessedTypes(kept.map { it.customField() })
        return kept.zip(guesses)
            .filter { (field, guess) -> field.typeName != null && !(field.type == FieldType.TEXT && guess == FieldType.TEXT) }
            .associate { (field, _) -> field.name.trim() to field.typeName!! }
    }

    public fun hasFieldProblems(draft: EntryDraft): Boolean = draft.fields.any { fieldProblem(draft, it) != null }

    // Attachments (keething-spec edit.attachments AC3)

    /** A new attachment in the place of one the entry has (the same name): "replaced", not "new" (the Mac's `replaces`). */
    public fun replaces(attachment: AttachmentDraft): Boolean = attachment.isNew && original.attachments.any { it.name == attachment.name }

    /**
     * What is wrong with an attachment's name, shown at its row; null when nothing. The phone names attachments after
     * their files, so only an entry another app gave two of one name, or none, has a problem here, and only once its
     * attachments are changed (a list left as it is is not written): Save stays off until one is removed.
     */
    public fun attachmentProblem(draft: EntryDraft, attachment: AttachmentDraft): UserText? {
        if (shape(draft).attachments == null) return null
        val name = attachment.name.trim()
        if (name.isEmpty()) return UserText.of(CardText.ATTACHMENT_NO_NAME)
        return if (draft.attachments.count { it.name.trim() == name } > 1) UserText.of(CardText.ATTACHMENT_TAKEN) else null
    }

    /** What the attachments' names keep from being saved, for the list under the form: none when nothing. */
    public fun attachmentProblems(draft: EntryDraft): List<UserText> {
        if (shape(draft).attachments == null) return emptyList()
        val names = draft.attachments.map { it.name.trim() }
        val same = names.filter { name -> name.isNotEmpty() && names.count { it == name } > 1 }.distinct()
        return listOfNotNull(
            UserText.of(CardText.ATTACHMENTS_UNNAMED).takeIf { names.any(String::isEmpty) },
            UserText.of(CardText.ATTACHMENTS_SAME, same.joinToString(", ")).takeIf { same.isNotEmpty() },
        )
    }

    /** The group the entry is in as loaded (a new entry's: where it started). */
    public val originalGroupId: String get() = if (isNew) start.groupId else original.groupId

    /** An existing entry going into another group with the save (keething-spec groups.entry-group AC2). */
    public fun isMoved(draft: EntryDraft): Boolean = !isNew && draft.group != GroupChoice(original.groupId)

    /** The ids of the new groups, made once for this editor, so the draft's changes stay the same while it is edited. */
    private val newGroupIds = ArrayList<String>()

    /**
     * Where the entry goes, its new groups' ids made once for this editor (the Mac's `destination`); null when an
     * existing entry stays where it is. A path always names somewhere: a group gone meanwhile is the save's to refuse.
     */
    public fun destination(draft: EntryDraft): GroupDestination? {
        if (!isNew && !isMoved(draft)) return null
        while (newGroupIds.size < draft.newGroups.size) newGroupIds += Entry.newId()
        return GroupDestination(draft.groupId, newGroupIds.zip(draft.newGroups, ::NewGroup))
    }

    /** A new entry needs a title or a website, a note its text (not a problem to list: every new entry starts without). */
    public fun hasName(draft: EntryDraft): Boolean = when (layout) {
        EditorLayout.ENTRY -> draft.title.isNotBlank() || draft.url.isNotBlank()
        EditorLayout.NOTE -> draft.notes.isNotBlank()
    }

    /** Something to lose when the editor closes: a group chosen too, for a new entry as for a move. */
    public fun isDirty(draft: EntryDraft): Boolean = shape(draft) != shape(start) || draft.group != start.group

    public fun canSave(draft: EntryDraft): Boolean {
        // An existing note keeps a text or a title.
        val named = layout == EditorLayout.ENTRY || hasName(draft) || draft.title.isNotBlank()
        return (if (isNew) hasName(draft) else isDirty(draft) && named) && !hasFieldProblems(draft) && attachmentProblems(draft).isEmpty()
    }

    // Saving

    /**
     * Only what differs from the entry as loaded, an existing entry's move too. Its secrets are new: wipe them once
     * the save is done.
     */
    public fun changes(draft: EntryDraft): EntryChanges {
        val shape = shape(draft)
        return EntryChanges(
            title = shape.title,
            username = shape.username,
            password = shape.password?.let(Secret::of),
            url = shape.url,
            notes = shape.notes,
            notesProtected = shape.notesProtected,
            customFields = shape.fields?.map { CustomField(it.name, if (it.isProtected) FieldValue.Protected(Secret.of(it.value)) else FieldValue.Plain(it.value)) },
            fieldTypes = shape.fieldTypes,
            entryType = shape.entryType,
            tags = shape.tags,
            expiry = shape.expiry,
            group = if (isNew) null else destination(draft),
            totp = when {
                shape.codeChanged -> draft.code?.config()?.let { TotpChange.Set(it, TotpSetup.otpauthUri(it, savedTitle(draft), draft.username)) } ?: TotpChange.Remove
                // A code that cannot be used, saved as typed: each field's protection kept.
                shape.codeFields -> draft.codeFields?.let { fields ->
                    TotpChange.Fields(fields.map { CustomField(it.name, if (it.isProtected) FieldValue.Protected(Secret.of(it.value)) else FieldValue.Plain(it.value)) })
                } ?: TotpChange.Remove
                else -> null
            },
            // A new one's content is copied: the change is wiped once saved, the draft keeps its own for another try.
            attachments = shape.attachments?.let {
                draft.attachments.map { attachment ->
                    val name = attachment.name.trim()
                    attachment.content?.let { AttachmentChange.Add(name, it.copy()) } ?: AttachmentChange.Keep(attachment.original ?: name, name)
                }
            },
        )
    }

    /**
     * A new entry moved into another database's [vault] (keething-spec databases.one-list AC5; the Mac's
     * `moveNewEntry`): the editor is that database's, the same new entry; the draft's group goes to its root (another
     * database's groups) and its username to its default unless it was changed; what else was typed stays. Nothing
     * typed: the other database's start, still nothing to discard. An existing entry stays where it is.
     */
    public fun movedTo(vault: Vault, draft: EntryDraft): Pair<EntryEditor, EntryDraft> {
        if (!isNew) return this to draft
        val root = vault.groups.firstOrNull { it.isRoot } ?: vault.groups.firstOrNull()
        // A note has no default username.
        val defaultUsername = if (layout == EditorLayout.NOTE) "" else vault.defaultUsername
        val moved = EntryEditor(
            original, isNew = true, start = start.copy(username = defaultUsername, groupId = root?.id.orEmpty(), newGroups = emptyList()),
            groups = vault.groups, layout = layout, openedAt = openedAt, dates = dates,
        )
        val username = if (draft.username == start.username) defaultUsername else draft.username
        return moved to draft.copy(username = username, groupId = moved.start.groupId, newGroups = emptyList())
    }

    /** The change to save: a new entry in its group, or the entry's fields as loaded. */
    public fun edit(draft: EntryDraft): VaultEdit.EntryEdit =
        if (isNew) {
            VaultEdit.Create(original.id, destination(draft)!!, changes(draft))
        } else {
            VaultEdit.Update(original.id, original.lastModified, changes(draft))
        }

    /** [changes] without the secrets made: compared on every keystroke. */
    private data class Shape(
        val title: String?,
        val username: String?,
        val password: String?,
        val url: String?,
        val notes: String?,
        val notesProtected: Boolean?,
        val fields: List<FieldText>?,
        val fieldTypes: Map<String, String>?,
        val entryType: EntryTypeChange?,
        val tags: List<String>?,
        val expiry: EntryChanges.Expiry?,
        val codeChanged: Boolean,
        val codeFields: Boolean,
        val attachments: List<AttachmentText>?,
    )

    private data class FieldText(val name: String, val value: String, val isProtected: Boolean)

    /** An attachment as saved: its name in the file (null: new) and its name afterwards; a new one is itself ([id]). */
    private data class AttachmentText(val original: String?, val name: String, val id: Int? = null)

    /** The attachments as loaded: each kept under its own name. */
    private val loadedAttachments = original.attachments.map { AttachmentText(it.name, it.name.trim()) }

    private fun shape(draft: EntryDraft): Shape {
        val fields = draft.fields.filterNot { it.isBlank }.map { FieldText(it.name.trim(), it.value, it.isProtected) }
        val tags = draft.savedTags
        // The file keeps seconds: a date less than a second off is the same.
        val moved = draft.expires && Duration.between(draft.expiryTime, original.expiryTime ?: start.expiryTime).abs() >= Duration.ofSeconds(1)
        val note = layout == EditorLayout.NOTE
        return Shape(
            title = savedTitle(draft).takeIf { it != original.title },
            username = draft.username.takeIf { it != original.username },
            password = draft.password.takeIf { it != start.password },
            url = draft.url.takeIf { it != original.url },
            notes = draft.notes.takeIf { it != original.notes },
            // A new note's choice is written either way, so Meta/MemoryProtection does not decide it.
            notesProtected = draft.notesProtected.takeIf { note && (isNew || it != original.notesProtected) },
            fields = fields.takeIf { it != loadedFields },
            fieldTypes = savedFieldTypes(draft).takeIf { it != original.fieldTypes },
            // Edited as a note, it is marked (one read so by its fields alone, AC2); a stored kind stays as stored (AC1).
            entryType = EntryTypeChange.Set(EntryType.NOTE).takeIf { note && original.entryType == null },
            tags = tags.takeIf { it != original.tags },
            expiry = if (draft.expires != original.expires || moved) EntryChanges.Expiry(draft.expires, draft.expiryTime.takeIf { draft.expires }) else null,
            codeChanged = codeChanged(draft),
            codeFields = draft.codeFields != start.codeFields,
            // Only a list that differs from the entry's (AC4).
            attachments = draft.attachments.map { AttachmentText(it.original, it.name.trim(), if (it.isNew) it.id else null) }
                .takeIf { it != loadedAttachments },
        )
    }

    // One-time codes (Mac SPEC §3.23; keething-spec edit.one-time-code)

    /** The entry's own code differs from the one it had (set, changed or removed): "new" or "changed" beside it. */
    public fun codeChanged(draft: EntryDraft): Boolean = draft.code != start.code

    private fun codeTexts(draft: EntryDraft): Map<String, String>? = draft.codeFields?.associate { it.name to it.value }

    /**
     * Why the code fields as typed give no code ("This code cannot be used here: Steam codes.", the card's words); null
     * once they read, or when there are none.
     */
    public fun codeFieldsProblem(draft: EntryDraft): UserText? =
        codeTexts(draft)?.let(TotpResolver::problem)?.let { UserText.of(CardText.CODE_UNSUPPORTED, it) }

    /** The code the code fields give as typed, with a key of its own to wipe; null while they give none. */
    public fun codeFieldsCode(draft: EntryDraft): TotpConfig? = codeTexts(draft)?.let(TotpResolver::resolve)

    /** A code added now goes into a field of its own: the entry has one, or code fields that give none (AC5). */
    public fun offersAnotherCode(draft: EntryDraft): Boolean = draft.code != null || draft.codeFields != null

    /**
     * A code from a secret or an `otpauth://` link (typed, pasted, or read from a QR code): the entry's own when it has
     * none, else another one as a protected custom field "One-time code 2" (3, …) holding its link (AC5).
     */
    public fun addOneTimeCode(draft: EntryDraft, text: String): AddedCode {
        val config = try {
            TotpSetup.parse(text)
        } catch (problem: TotpSetupException) {
            return AddedCode.Refused(codeProblem(problem))
        }
        try {
            if (!offersAnotherCode(draft)) return AddedCode.Added(draft.copy(code = CodeDraft.of(config)), field = null)
            val taken = draft.fields.map { it.name.trim() }.toSet()
            val name = generateSequence(2) { it + 1 }.map { "$ANOTHER_CODE $it" }.first { it !in taken }
            val link = TotpSetup.otpauthUri(config, draft.title, draft.username)
            return AddedCode.Added(draft.copy(fields = draft.fields + FieldDraft(draft.nextFieldId(), name, link, isProtected = true, isCode = true)), field = name)
        } finally {
            config.secret.wipe()
        }
    }

    /**
     * The texts of the QR codes in an image (AC4): the first that is a code is taken, as [addOneTimeCode] takes one;
     * none that is, the first one's problem; no texts at all, "No QR code found in the image."
     */
    public fun addOneTimeCode(draft: EntryDraft, texts: List<String>): AddedCode {
        if (texts.isEmpty()) return AddedCode.Refused(NO_QR_CODE)
        val code = texts.firstOrNull { text -> TotpSetup.parseOrNull(text)?.also { it.secret.wipe() } != null }
        return addOneTimeCode(draft, code ?: texts.first())
    }

    /**
     * The code of a custom field holding an `otpauth://` link (another one-time code), with a key of its own to wipe;
     * null for any other field, and for a link typed into one ([FieldDraft.isCode]). Its link is not edited by hand (AC7):
     * removed, and the code added again.
     */
    public fun oneTimeCode(field: FieldDraft): TotpConfig? =
        if (field.isCode) OneTimeCodes.config(CustomField(field.name, FieldValue.Plain(field.value))) else null

    // A block of codes (Mac SPEC §3.16)

    /**
     * The codes of [text] become protected fields `<prefix>_1`, `_2`, … typed `code` — the card lists such fields
     * as numbered codes. With fields of that list already there the numbers go on, so the new codes join it.
     */
    public fun addCodes(draft: EntryDraft, text: String, prefix: String): AddedCodes {
        prefixProblem(prefix)?.let { return AddedCodes.Refused(it) }
        val list = prefix.trim()
        val codes = codes(text)
        if (codes.isEmpty()) return AddedCodes.Refused(UserText.of(CardText.CODES_NONE))
        if (codes.size > MAXIMUM_PASTED_CODES) {
            return AddedCodes.Refused(UserText.counted(CardText.CODES_TOO_MANY, codes.size, codes.size, MAXIMUM_PASTED_CODES))
        }
        val taken = draft.fields.mapNotNull { field ->
            val name = field.name.trim()
            if (name.startsWith("${list}_")) name.removePrefix("${list}_").toIntOrNull() else null
        }
        val first = (taken.maxOrNull() ?: 0) + 1
        val id = draft.nextFieldId()
        val added = codes.mapIndexed { i, code -> FieldDraft(id + i, "${list}_${first + i}", code, isProtected = true, typeName = FieldType.CODE.typeName) }
        return AddedCodes.Added(draft.copy(fields = draft.fields + added), codes.size)
    }

    /** The lists of codes among the draft's fields, as the card would show them. */
    public fun codeLists(draft: EntryDraft): List<CodeList> {
        val sections = LinkedHashMap<String, MutableList<Pair<FieldDraft, String>>>()
        for (field in draft.fields) {
            val (section, label) = ImportedFieldName.split(field.name) ?: continue
            sections.getOrPut(section) { mutableListOf() } += field to label
        }
        val interpreter = FieldInterpreter(FieldTypes(draft.fields.mapNotNull { field -> field.typeName?.let { field.name to it } }.toMap()))
        return sections.filterValues { members -> ImportedFieldName.isCodeList(members.map { (field, label) -> field.customField() to label }, interpreter) }
            .map { (title, members) -> CodeList(title, members.map { it.first.id }, members.all { it.first.isProtected }) }
    }

    public companion object {
        /**
         * A further one-time code's field is named so, with a number from 2 (the entry's own is the first): a name in the
         * file, English as the Mac's whatever the phone's language.
         */
        public const val ANOTHER_CODE: String = "One-time code" // not user text

        /** An image without a QR code (the Mac's words). */
        public val NO_QR_CODE: UserText = UserText.of(CardText.NO_QR_CODE)

        /** What the editor says when a code is not taken (the Mac's words). */
        internal fun codeProblem(problem: TotpSetupException): UserText = when (problem) {
            is TotpSetupException.Empty -> UserText.of(CardText.CODE_EMPTY)
            is TotpSetupException.Unreadable -> UserText.of(CardText.CODE_UNREADABLE)
            is TotpSetupException.TooShort -> UserText.of(CardText.CODE_TOO_SHORT)
            is TotpSetupException.Unsupported -> UserText.of(CardText.CODE_UNSUPPORTED, problem.reason)
        }

        /** The largest file attached (keething-spec edit.attachments AC2; the Mac's): 64 MiB. */
        public const val MAXIMUM_ATTACHMENT_SIZE: Long = 64L shl 20

        /** The most codes added at once (a page of backup codes has 8–16). */
        public const val MAXIMUM_PASTED_CODES: Int = 50

        /** An existing entry, as loaded. */
        public fun edit(entry: Entry, now: Instant, dates: NoteDates? = null): EntryEditor {
            val fields = entry.customFields.mapIndexed { i, field ->
                val value = field.text()
                val isCode = OneTimeCodes.config(field)?.also { it.secret.wipe() } != null
                FieldDraft(i + 1, field.name, value, field.isProtected, entry.fieldTypes[field.name], isCode)
            }
            val draft = EntryDraft(
                title = entry.title, username = entry.username, password = entry.password.reveal(), url = entry.url,
                notes = entry.notes, notesProtected = entry.notesProtected, tags = entry.tags, fields = fields,
                // An entry that never expired often keeps its creation time there: Expires switched on starts a year ahead.
                expires = entry.expires, expiryTime = entry.expiryTime?.takeIf { entry.expires || it > now } ?: aYearAfter(now), groupId = entry.groupId,
                code = entry.totp?.let(CodeDraft::of),
                // Code fields that give no code are kept as their text (the owner's rule for codes in the editor).
                codeFields = entry.codeFields.takeIf { entry.totp == null && entry.hasCodeFields && it.isNotEmpty() }?.map { field ->
                    CodeFieldDraft(field.name, field.text(), field.isProtected)
                },
                attachments = entry.attachments.mapIndexed { i, attachment -> AttachmentDraft(i + 1, attachment.name, attachment.name, attachment.size) },
            )
            val layout = if (entry.isNote) EditorLayout.NOTE else EditorLayout.ENTRY
            return EntryEditor(entry, isNew = false, start = draft, groups = emptyList(), layout = layout, openedAt = now, dates = dates)
        }

        /**
         * A new entry in [groupId] (the root group when that is none of the vault's), starting with the database's
         * default username (`Meta/DefaultUserName`, as KeePassXC does).
         */
        public fun create(vault: Vault, groupId: String?, now: Instant, id: String = Entry.newId()): EntryEditor {
            val group = vault.groups.firstOrNull { it.id == groupId } ?: vault.groups.firstOrNull { it.isRoot } ?: vault.groups.firstOrNull()
            val draft = EntryDraft(username = vault.defaultUsername, expiryTime = aYearAfter(now), groupId = group?.id.orEmpty())
            return EntryEditor(Entry(id), isNew = true, start = draft, groups = vault.groups)
        }

        /**
         * A new note in [groupId], as [create] places a new entry (SPEC §6.4; types.note AC4): its text protected, no
         * username; untitled, it is named by [now] through [dates].
         */
        public fun createNote(vault: Vault, groupId: String?, now: Instant, dates: NoteDates?, id: String = Entry.newId()): EntryEditor {
            val entry = create(vault, groupId, now, id)
            val draft = entry.start.copy(username = "", notesProtected = true)
            return EntryEditor(Entry(id), isNew = true, start = draft, groups = vault.groups, layout = EditorLayout.NOTE, openedAt = now, dates = dates)
        }

        /**
         * A quick note's editor (SPEC §6.4; keething-spec app.quick-note AC2, AC3; the Mac's `EntryEditorModel(quickNoteIn:)`):
         * [createNote]'s new note, its group the path [QuickNote.INBOX] found as every typed path is (the top-level group
         * of the name, the first, in any case), else made at the top level with the note; the top level itself when the
         * recycle bin or the templates group there has the name (no second one beside it). The text it was given goes
         * into the draft, not the start, so that it counts as a change.
         */
        public fun createQuickNote(vault: Vault, now: Instant, dates: NoteDates?, id: String = Entry.newId()): EntryEditor {
            val note = createNote(vault, groupId = null, now, dates, id)
            val tree = GroupTree(vault.groups, hidden = GroupTree.hidden(vault))
            val found = tree.resolve(listOf(QuickNote.INBOX)) ?: return note
            val inbox = if (found.newNames.isNotEmpty() && tree.hiddenNamed(found.groupId, QuickNote.INBOX) != null) GroupChoice(found.groupId) else found
            return EntryEditor(Entry(id), isNew = true, start = note.start.choosing(inbox), groups = vault.groups, layout = EditorLayout.NOTE, openedAt = now, dates = dates)
        }

        /** The codes in [text]: every line that is not blank (or, on one line, every part between commas or semicolons), numbering and bullets dropped. */
        public fun codes(text: String): List<String> {
            var parts = text.lines().filter { it.isNotBlank() }
            if (parts.size == 1) parts = parts[0].split(',', ';')
            return parts.map(::cleanCode).filter { it.isNotEmpty() }
        }

        /** Why [prefix] cannot name a list of codes; null when it can. */
        public fun prefixProblem(prefix: String): UserText? = when {
            prefix.isBlank() -> UserText.of(CardText.CODES_NO_LIST_NAME)
            '_' in prefix -> UserText.of(CardText.CODES_UNDERSCORE)
            else -> null
        }

        private val numbering = Regex("^\\d{1,2}[.)]\\s+")

        /** `  3. 1234 5678 ` → `1234 5678`; `• abcd-efgh` → `abcd-efgh`. */
        private fun cleanCode(line: String): String {
            var text = line.trim()
            for (bullet in listOf("•", "·", "-", "*")) if (text.startsWith("$bullet ")) text = text.drop(2)
            return text.replaceFirst(numbering, "").trim()
        }

        private fun CustomField.text(): String = when (val v = value) {
            is FieldValue.Plain -> v.text
            is FieldValue.Protected -> v.secret.reveal()
        }

        private fun aYearAfter(now: Instant): Instant = now.atOffset(ZoneOffset.UTC).plusYears(1).toInstant()

        private val FieldDraft.isBlank: Boolean get() = name.isBlank() && value.isEmpty()

        /** The field as the card's rules read it: a protected value is never read, so it goes as an empty secret. */
        private fun FieldDraft.customField(): CustomField =
            CustomField(name.trim(), if (isProtected) FieldValue.Protected(Secret.empty()) else FieldValue.Plain(value))
    }
}

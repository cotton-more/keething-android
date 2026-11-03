package dev.keething.card.editing

import dev.keething.card.AddressCheck
import dev.keething.card.CryptoAddress
import dev.keething.card.DerivationPath
import dev.keething.card.EntryCardBuilder
import dev.keething.card.ExtendedKey
import dev.keething.card.FieldDate
import dev.keething.card.FieldInterpreter
import dev.keething.card.FieldType
import dev.keething.card.FieldTypes
import dev.keething.card.ImportedFieldName
import dev.keething.card.SeedPhrase
import dev.keething.domain.secret.Secret
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
import dev.keething.domain.vault.Vault
import dev.keething.domain.vault.VaultEdit
import dev.keething.domain.vault.VaultGroup
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** How adding a block of codes went. */
public sealed interface AddedCodes {
    public data class Added(val draft: EntryDraft, val count: Int) : AddedCodes

    public data class Refused(val problem: String) : AddedCodes
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
    public fun titlePlaceholder(draft: EntryDraft): String = when {
        layout != EditorLayout.NOTE -> "Title"
        draft.notesProtected -> "Title: ${NoteTitle.make("", protected = true, made = made, dates = dates)}"
        draft.notes.isBlank() -> "Title: the first line"
        else -> "Title: ${NoteTitle.make(draft.notes, protected = false)}"
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
    public fun fieldProblem(draft: EntryDraft, field: FieldDraft): String? {
        if (field.isBlank) return null
        val name = field.name.trim()
        if (name.isEmpty()) return "Give the field a name: KeePass cannot keep a value without one."
        if (name in EntryChanges.RESERVED_FIELD_NAMES) return "“$name” is a name KeePass uses itself."
        val same = draft.fields.count { !it.isBlank && it.name.trim() == name }
        return if (same > 1) "Another field has this name." else null
    }

    /**
     * A field typed as a date whose value is not one: it is saved, and shown as text; null when it is a date,
     * empty, protected (never read) or of another type.
     */
    public fun fieldWarning(field: FieldDraft): String? {
        if (field.type?.isCryptoPart == true) return cryptoWarning(field)
        val type = field.type?.takeIf { it.isDate } ?: return null
        if (field.isProtected || field.value.isBlank() || FieldDate.parse(field.value, typed = true) != null) return null
        return if (type == FieldType.CARD_EXPIRY) {
            "Not a date, shown as text. Write it as 2027-10."
        } else {
            "Not a date, shown as text. Write it as 2027-10-31, or 2027-10 for a month."
        }
    }

    /**
     * A recovery phrase that fails its check (read in the editor even when protected: the text is in the form anyway), an
     * address or key whose checksum fails, a path that is none; null when it passes, is empty, or is not checked.
     */
    private fun cryptoWarning(field: FieldDraft): String? {
        val value = field.value.trim().takeIf { it.isNotEmpty() } ?: return null
        return when (field.type) {
            FieldType.CRYPTO_SEED -> SeedPhrase.read(value)?.takeIf { !it.passes }?.summary()
            // An address of a network not known here is shown as it is, without a warning.
            FieldType.CRYPTO_ADDRESS -> if (CryptoAddress.read(value)?.check == AddressCheck.FAILS) "The address fails its checksum. A typo?" else null
            // A private key first: one behind a public prefix (BIP-32's test vector 5) does not pass either.
            FieldType.CRYPTO_XPUB -> when {
                ExtendedKey.isPrivate(value) -> "A private key (${value.take(4)}): whoever has it has the wallet. It is shown, never copied."
                ExtendedKey.read(value)?.passes == false -> "The key fails its checksum. A typo?"
                else -> null
            }
            FieldType.CRYPTO_PATH -> if (DerivationPath.isPath(value)) null else "Not a derivation path: write it as m/84'/0'/0'."
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
        return (if (isNew) hasName(draft) else isDirty(draft) && named) && !hasFieldProblems(draft)
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
    )

    private data class FieldText(val name: String, val value: String, val isProtected: Boolean)

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
        )
    }

    // A block of codes (Mac SPEC §3.16)

    /**
     * The codes of [text] become protected fields `<prefix>_1`, `_2`, … typed `code` — the card lists such fields
     * as numbered codes. With fields of that list already there the numbers go on, so the new codes join it.
     */
    public fun addCodes(draft: EntryDraft, text: String, prefix: String): AddedCodes {
        prefixProblem(prefix)?.let { return AddedCodes.Refused(it) }
        val list = prefix.trim()
        val codes = codes(text)
        if (codes.isEmpty()) return AddedCodes.Refused("No codes: paste or type them, one per line.")
        if (codes.size > MAXIMUM_PASTED_CODES) {
            return AddedCodes.Refused("That is ${codes.size} codes; at most $MAXIMUM_PASTED_CODES are added at once.")
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
        /** The most codes added at once (a page of backup codes has 8–16). */
        public const val MAXIMUM_PASTED_CODES: Int = 50

        /** An existing entry, as loaded. */
        public fun edit(entry: Entry, now: Instant, dates: NoteDates? = null): EntryEditor {
            val fields = entry.customFields.mapIndexed { i, field ->
                val value = when (val v = field.value) {
                    is FieldValue.Plain -> v.text
                    is FieldValue.Protected -> v.secret.reveal()
                }
                FieldDraft(i + 1, field.name, value, field.isProtected, entry.fieldTypes[field.name])
            }
            val draft = EntryDraft(
                title = entry.title, username = entry.username, password = entry.password.reveal(), url = entry.url,
                notes = entry.notes, notesProtected = entry.notesProtected, tags = entry.tags, fields = fields,
                expires = entry.expires, expiryTime = entry.expiryTime ?: aYearAfter(now), groupId = entry.groupId,
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
         * of the name, the first, in any case), else made at the top level with the note. The text it was given goes
         * into the draft, not the start, so that it counts as a change.
         */
        public fun createQuickNote(vault: Vault, now: Instant, dates: NoteDates?, id: String = Entry.newId()): EntryEditor {
            val note = createNote(vault, groupId = null, now, dates, id)
            val inbox = GroupTree(vault.groups).resolve(listOf(QuickNote.INBOX)) ?: return note
            return EntryEditor(Entry(id), isNew = true, start = note.start.choosing(inbox), groups = vault.groups, layout = EditorLayout.NOTE, openedAt = now, dates = dates)
        }

        /** The codes in [text]: every line (or, on one line, every part between commas or semicolons), numbering and bullets dropped. */
        public fun codes(text: String): List<String> {
            var parts = text.lines()
            if (parts.size == 1) parts = parts[0].split(',', ';')
            return parts.map(::cleanCode).filter { it.isNotEmpty() }
        }

        /** Why [prefix] cannot name a list of codes; null when it can. */
        public fun prefixProblem(prefix: String): String? = when {
            prefix.isBlank() -> "The codes need a name for their list."
            '_' in prefix -> "The list's name cannot contain “_” (it separates the name from the number)."
            else -> null
        }

        private val numbering = Regex("^\\d{1,2}[.)]\\s+")

        /** `  3. 1234 5678 ` → `1234 5678`; `• abcd-efgh` → `abcd-efgh`. */
        private fun cleanCode(line: String): String {
            var text = line.trim()
            for (bullet in listOf("•", "·", "-", "*")) if (text.startsWith("$bullet ")) text = text.drop(2)
            return text.replaceFirst(numbering, "").trim()
        }

        private fun aYearAfter(now: Instant): Instant = now.atOffset(ZoneOffset.UTC).plusYears(1).toInstant()

        private val FieldDraft.isBlank: Boolean get() = name.isBlank() && value.isEmpty()

        /** The field as the card's rules read it: a protected value is never read, so it goes as an empty secret. */
        private fun FieldDraft.customField(): CustomField =
            CustomField(name.trim(), if (isProtected) FieldValue.Protected(Secret.empty()) else FieldValue.Plain(value))
    }
}

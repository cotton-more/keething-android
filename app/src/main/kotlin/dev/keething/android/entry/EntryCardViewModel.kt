package dev.keething.android.entry

import android.util.Log
import dev.keething.android.session.file
import dev.keething.android.groups.GroupTexts
import dev.keething.card.editing.EntryMove
import dev.keething.card.editing.GroupChoice
import dev.keething.card.editing.GroupTree
import dev.keething.vault.session.SaveOutcome
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.net.wifi.WifiNetworkSuggestion
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.keething.android.attachment.AndroidAttachments
import dev.keething.android.attachment.AttachmentFiles
import dev.keething.android.attachment.AttachmentNames
import dev.keething.android.attachment.AttachmentTypes
import dev.keething.android.attachment.SaveRequest
import dev.keething.android.clipboard.SensitiveClipboard
import dev.keething.android.editor.SAVE_LOCKED
import dev.keething.android.editor.problem
import dev.keething.android.list.RecentEntries
import dev.keething.android.recyclebin.RecycleBinMoves
import dev.keething.vault.databases.DatabaseSession
import dev.keething.vault.databases.EntryRef
import dev.keething.android.settings.AppSettings
import dev.keething.android.ui.Notice
import dev.keething.card.CardSection
import dev.keething.card.EntryCard
import dev.keething.card.EntryCardBuilder
import dev.keething.card.EntryFace
import dev.keething.card.FieldRow
import dev.keething.card.FieldType
import dev.keething.card.GroupPathFormatter
import dev.keething.card.RevealState
import dev.keething.card.RowIds
import dev.keething.card.SshKeyLocation
import dev.keething.card.SshPublicKey
import dev.keething.card.TotpDisplay
import dev.keething.card.TypeSuggestion
import dev.keething.card.UsedCode
import dev.keething.domain.vault.Vault
import dev.keething.domain.vault.VaultEdit
import dev.keething.vault.session.SessionState
import java.time.Instant
import java.time.LocalDate
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the SSH face says of the entry's key (SPEC §6.2): where it is, and its public half when it can be read. */
internal data class SshKeyView(val location: SshKeyLocation, val publicKey: SshPublicKey?, val problem: String?)

/** The open card: built from the entry in the vault shown now. */
internal data class CardContent(
    val card: EntryCard,
    val inRecycleBin: Boolean,
    val icon: ImageBitmap?,
    /** Its database has the recycle bin turned off: Delete is for good, asked first (keething-spec edit.delete-for-good AC1). */
    val binTurnedOff: Boolean = false,
    /** The face the list shows for it (a small card, a network), also on the card's header. */
    val face: EntryFace? = null,
    /** Fields named as a card's or a network's, without types: the card offers to type them (SPEC §6.3). */
    val suggestion: TypeSuggestion? = null,
)

internal data class CardUiState(
    /** Null while locked, or when the entry is gone from the vault (deleted elsewhere): the card closes. */
    val content: CardContent?,
    val reveal: RevealState,
)

/**
 * One entry's card (Mac `EntryCardModel`). Every masked value starts masked; what was revealed stays so when
 * the vault is read again, and goes with the card. Copying copies the value as stored, never the mask; a
 * code row copies the current code. An attachment opens in another app or is saved where the user says
 * (SPEC §4, §6.2; Mac `EntryCardModel.previewAttachment` / `saveAttachment`).
 */
internal class EntryCardViewModel(
    /** The entry by its ref: Recent and the declined offer keep it so (the same UUID in another database is another). */
    private val ref: EntryRef,
    /** Its own database's session: the card reads, saves and opens attachments there (keething-spec databases.one-list AC2). */
    private val database: DatabaseSession,
    private val recents: RecentEntries,
    private val clipboard: SensitiveClipboard,
    private val files: AttachmentFiles,
    private val android: AndroidAttachments,
    private val recycleBin: RecycleBinMoves,
    private val settings: AppSettings,
) : ViewModel() {
    private val mutableNotices = Channel<Notice>(Channel.BUFFERED)
    val notices: Flow<Notice> = mutableNotices.receiveAsFlow()

    /** Its own id in its database. */
    private val entryId: String = ref.entry

    private val mutableOpenings = Channel<Intent>(Channel.BUFFERED)

    /** An attachment written for another app to open: the screen starts the intent. */
    val openings: Flow<Intent> = mutableOpenings.receiveAsFlow()

    /** The attachment the save dialog is up for. */
    private var saving: String? = null

    private val reveal = MutableStateFlow(RevealState())

    private val content: StateFlow<CardContent?> = database.session.state
        .map { (it as? SessionState.Unlocked)?.vault }
        .distinctUntilChanged { a, b -> a === b }
        .map { vault -> vault?.let(::timedBuild) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, (database.session.state.value as? SessionState.Unlocked)?.vault?.let(::timedBuild))

    val state: StateFlow<CardUiState> = combine(content, reveal, ::CardUiState)
        .stateIn(viewModelScope, SharingStarted.Eagerly, CardUiState(content.value, RevealState()))

    init {
        content.value?.takeIf { !it.inRecycleBin }?.let { recents.note(ref.text) }
    }

    fun toggleReveal(rowId: String) {
        reveal.value = reveal.value.toggle(rowId)
    }

    /** A payment card's eye: its number and CVC shown together, or masked together. */
    fun toggleRows(rows: List<FieldRow>) {
        reveal.value = reveal.value.toggleAll(rows)
    }

    /** The card's offer to type the fields (SPEC §6.3) was turned down for this entry: it is not made again. */
    var suggestionDismissed: Boolean by mutableStateOf(settings.isSuggestionDismissed(ref.text))
        private set

    /** The offer's types being saved. */
    var suggesting: Boolean by mutableStateOf(false)
        private set

    fun dismissSuggestion() {
        settings.dismissSuggestion(ref.text)
        suggestionDismissed = true
    }

    /** Gives the fields the types offered, saved as any edit (SPEC §3.3): the card then draws the face. */
    fun acceptSuggestion() {
        if (suggesting) return
        val entry = unlockedVault()?.entries?.firstOrNull { it.id == entryId } ?: return
        val suggestion = TypeSuggestion.of(entry) ?: return
        val pending = database.session.save(VaultEdit.Update(entry.id, entry.lastModified, suggestion.changes(entry))) ?: return problem(SAVE_LOCKED)
        suggesting = true
        viewModelScope.launch {
            val failure = pending.await().problem(database.file)
            suggesting = false
            mutableNotices.trySend(
                failure?.let { Notice(it, isProblem = true) }
                    ?: Notice(
                        when (suggestion.kind) {
                            TypeSuggestion.Kind.PAYMENT_CARD -> "Saved · the Mac shows it as a card too"
                            TypeSuggestion.Kind.WIFI -> "Saved · the Mac shows the network too"
                            else -> "Saved"
                        },
                    ),
            )
        }
    }

    /**
     * The system's "Save network?" for the entry's Wi-Fi network (Android 11+, no permission): the network goes to the
     * phone's saved ones only when the user agrees there. Null, with a notice, when the system would refuse it.
     */
    fun addNetworkIntent(): Intent? {
        val card = content.value?.card ?: return null
        val network = card.wifi ?: return null
        val password = network.passwordRowId?.let { id -> card.rows.firstOrNull { it.id == id } }?.storedText()
        val suggestion = try {
            WifiNetworkSuggestion.Builder().setSsid(network.ssid).apply {
                when {
                    network.isOpen || password == null -> Unit
                    network.securityLabel.contains("WPA3", ignoreCase = true) -> setWpa3Passphrase(password)
                    else -> setWpa2Passphrase(password)
                }
            }.build()
        } catch (_: IllegalArgumentException) {
            problem("This phone cannot add the network: its name or key is not one Android accepts")
            return null
        }
        return Intent(Settings.ACTION_WIFI_ADD_NETWORKS).putParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST, arrayListOf(suggestion))
    }

    /** The eye of a list of codes: all of them shown, or all masked. */
    fun toggleSection(section: CardSection) {
        reveal.value = reveal.value.toggleAll(section.rows)
    }

    fun copy(row: FieldRow) {
        if (!row.isCopyable) return
        val text = when {
            row.kind == FieldRow.Kind.TOTP -> row.totp?.let { TotpDisplay.of(it, Instant.now()).code } ?: return
            else -> row.clipboardText()
        }
        clipboard.copy(text, sensitive = row.isConcealed || row.kind == FieldRow.Kind.TOTP)
        if (content.value?.inRecycleBin == false) recents.note(ref.text)
        // A code of a list is copied to be used: the notice offers to mark it so.
        val copied = when {
            row.copiesCompact -> Notice("${row.noticeLabel} copied without spaces · clears in 30 s")
            // Malware swaps addresses on the clipboard: the start and end are what to check.
            row.type == FieldType.CRYPTO_ADDRESS -> Notice("${row.noticeLabel} copied · check its start and end where you paste it")
            else -> Notice.copied(row.noticeLabel)
        }
        mutableNotices.trySend(if (canMarkUsed(row) && row.usedOn == null) copied.copy(undo = { setUsed(row, true) }, actionLabel = "Mark as used") else copied)
    }

    /** A code row's next code (keething-spec card.code-timer): copied as the code is, said with when it starts to work. */
    fun copyNext(row: FieldRow) {
        val display = row.totp?.let { TotpDisplay.of(it, Instant.now()) } ?: return
        clipboard.copy(display.next, sensitive = true)
        if (content.value?.inRecycleBin == false) recents.note(ref.text)
        mutableNotices.trySend(Notice.nextCodeCopied(display.secondsRemaining))
    }

    /**
     * A part of a note's text, or of protected Notes, the user selected and copied (SPEC §6.4; Mac
     * `EntryCardModel.copySelection`): through the app as every copy, concealed and cleared as its row's Copy. Only from
     * a text shown in clear, and only a part of it ([EntryCard.selectionToCopy]).
     */
    fun copySelection(rowId: String, text: String) {
        val selection = content.value?.card?.selectionToCopy(rowId, text, reveal.value) ?: return
        clipboard.copy(selection.text, sensitive = selection.sensitive)
        if (content.value?.inRecycleBin == false) recents.note(ref.text)
        mutableNotices.trySend(Notice("Copied · clears in 30 s"))
    }

    /** Text the card makes rather than a row holds (an ssh command, a config block, a public key): nothing secret. */
    fun copyText(what: String, text: String) {
        clipboard.copy(text, sensitive = false)
        mutableNotices.trySend(Notice.copied(what))
    }

    /** The SSH face's key: read from the entry's KeeAgent settings and key attachment when the card opens or the vault is read again. */
    var sshKey: SshKeyView? by mutableStateOf(null)
        private set

    /** What [sshKey] was read from, so that a vault read again reads it again only when that changed. */
    private var extrasKey: Any? = null

    init {
        viewModelScope.launch { content.collect(::loadSshKey) }
    }

    private fun loadSshKey(content: CardContent?) {
        val card = content?.card
        val entry = unlockedVault()?.let { vault -> (vault.entries + vault.recycleBin).firstOrNull { it.id == entryId } }
        val sshWanted = card?.ssh != null && entry != null && SshKeyLocation.hasSettings(entry.attachments)
        val key = listOf(sshWanted, entry?.attachments)
        if (key == extrasKey) return
        extrasKey = key
        if (!sshWanted) {
            sshKey = null
            return
        }
        val attachments = entry!!.attachments
        viewModelScope.launch { sshKey = withContext(Dispatchers.Default) { readSshKey(attachments) } }
    }

    private fun readSshKey(attachments: List<dev.keething.domain.vault.Attachment>): SshKeyView? {
        val settings = database.session.attachment(entryId, SshKeyLocation.SETTINGS_ATTACHMENT)
        val location = try {
            settings?.useBytes { SshKeyLocation.of(it, attachments) } ?: SshKeyLocation.of(null, attachments)
        } finally {
            settings?.wipe()
        }
        location ?: return null
        if (location !is SshKeyLocation.InAttachment) return SshKeyView(location, null, null)
        val bytes = database.session.attachment(entryId, location.name) ?: return SshKeyView(location, null, "${location.name} is not in the database")
        return try {
            val key = bytes.useBytes(SshPublicKey::ofPrivateKey)
            SshKeyView(location, key, if (key == null) "Only keys in the OpenSSH format can be read" else null)
        } finally {
            bytes.wipe()
        }
    }

    /** The account's details as text to send (holder, number, codes, bank: nothing secret), for the share sheet. */
    fun bankShareIntent(): Intent? {
        val text = content.value?.card?.bankAccount?.shareText() ?: return null
        return Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    }

    /** The code row being marked used (or not); one at a time, while its save runs. */
    var marking: String? by mutableStateOf(null)
        private set

    /** A code of a list (recovery codes), of an entry outside the recycle bin. */
    fun canMarkUsed(row: FieldRow): Boolean {
        val current = content.value ?: return false
        return !current.inRecycleBin && current.card.sections.any { section ->
            section.kind == CardSection.Kind.Codes && section.rows.any { it.id == row.id }
        }
    }

    /**
     * Marks a code of a list used today, or takes the mark off (SPEC §6.2): the mark is written in front of the code
     * ([UsedCode]) and saved as any edit (SPEC §3.3), the old version into the history; the notice says how it went,
     * with Undo.
     */
    fun setUsed(row: FieldRow, used: Boolean) {
        if (marking != null || !canMarkUsed(row)) return
        val entry = unlockedVault()?.entries?.firstOrNull { it.id == entryId } ?: return
        val index = RowIds.customIndex(row.id) ?: return
        val changes = UsedCode.changes(entry, index, used, LocalDate.now()) ?: return
        val pending = database.session.save(VaultEdit.Update(entry.id, entry.lastModified, changes))
        if (pending == null) {
            changes.wipe()
            return problem(SAVE_LOCKED)
        }
        // The session reads the change's secrets until the save is over.
        pending.invokeOnCompletion { changes.wipe() }
        marking = row.id
        viewModelScope.launch {
            val outcome = pending.await()
            marking = null
            val failure = outcome.problem(database.file)
            mutableNotices.trySend(
                when {
                    failure != null -> Notice(failure, isProblem = true)
                    used -> Notice("${row.noticeLabel} marked as used", undo = { setUsed(row, false) })
                    else -> Notice("${row.noticeLabel} marked as not used", undo = { setUsed(row, true) })
                },
            )
        }
    }

    /** Writes the attachment to a file of its own and hands it to the app the user picks. */
    fun openAttachment(row: FieldRow) {
        val name = row.label
        val bytes = database.session.attachment(entryId, name) ?: return problem("Could not read $name")
        val generation = files.generation(database.id)
        viewModelScope.launch {
            val intent = try {
                withContext(Dispatchers.IO) { files.write(database.id, generation, name, bytes)?.let { android.viewIntent(it, bytes) } }
            } catch (_: Exception) {
                return@launch problem("Could not open $name")
            } finally {
                bytes.wipe()
            }
            // Locked meanwhile: the file is gone or was never written.
            if (intent != null && database.session.state.value is SessionState.Unlocked) mutableOpenings.trySend(intent)
        }
    }

    /** What the save dialog starts with for an attachment; [saved] gets the document chosen. */
    fun saveRequest(row: FieldRow): SaveRequest {
        saving = row.label
        return SaveRequest(AttachmentNames.fileName(row.label), android.mimeType(row.label) ?: AttachmentTypes.GENERIC)
    }

    /** The save dialog closed: writes the attachment into the document chosen, if any. */
    fun saved(document: Uri?) {
        val name = saving
        saving = null
        if (document == null) return
        val bytes = name?.let { database.session.attachment(entryId, it) }
        viewModelScope.launch {
            val written = try {
                withContext(Dispatchers.IO) {
                    val written = bytes != null && runCatching { android.writeDocument(document, bytes) }.isSuccess
                    // The dialog made the document, empty: it goes again when nothing could be written into it.
                    if (!written) android.deleteDocument(document)
                    written
                }
            } finally {
                bytes?.wipe()
            }
            mutableNotices.trySend(if (written) Notice("Saved $name") else Notice("Could not save ${name ?: "the attachment"}", isProblem = true))
        }
    }

    /** The card's Move to group… saving: the menu waits. */
    var movingEntry by mutableStateOf(false)
        private set

    /** Move to group…'s picker (plans/android-groups.md G6): the groups as they are now, and where the entry is; null in the bin. */
    fun groupsToMoveTo(): Pair<GroupTree, GroupChoice>? {
        val vault = unlockedVault() ?: return null
        val entry = vault.entries.firstOrNull { it.id == entryId } ?: return null
        return GroupTree.of(vault) to GroupChoice(entry.groupId)
    }

    /**
     * Moves the entry where [choice] says at once, through the checked save (keething-spec groups.entry-group AC2, AC3,
     * AC5–AC7): the snackbar says where it went and names the empty groups the move deleted.
     */
    fun moveTo(choice: GroupChoice) {
        if (movingEntry) return
        val vault = unlockedVault() ?: return
        val entry = vault.entries.firstOrNull { it.id == entryId } ?: return
        val edit = EntryMove.edit(entry, choice) ?: return
        val tree = GroupTree.of(vault)
        val pending = database.session.save(edit) ?: return problem(SAVE_LOCKED)
        movingEntry = true
        viewModelScope.launch {
            val outcome = pending.await()
            movingEntry = false
            val failure = outcome.problem(database.file)
            mutableNotices.trySend(
                failure?.let { Notice(it, isProblem = true) }
                    ?: Notice(GroupTexts.entryMoved(tree, choice, (outcome as? SaveOutcome.Saved)?.deletedGroups.orEmpty())),
            )
        }
    }

    /** A Restore running: the button waits. */
    var restoring by mutableStateOf(false)
        private set

    /** Delete (SPEC §6.2): into the recycle bin at once, the list says how it went, with Undo. False: nothing to delete. */
    fun moveToRecycleBin(): Boolean {
        val entry = unlockedVault()?.entries?.firstOrNull { it.id == entryId } ?: return false
        recycleBin.moveToRecycleBin(database, entry)
        return true
    }

    /**
     * Delete for good (keething-spec edit.delete-for-good), after the card asked: an entry in the bin, or any when its
     * database has the bin turned off. False: nothing to delete.
     */
    fun deleteForGood(): Boolean {
        val vault = unlockedVault() ?: return false
        val entry = vault.recycleBin.firstOrNull { it.id == entryId }
            ?: vault.entries.firstOrNull { it.id == entryId }?.takeIf { !vault.recycleBinEnabled }
            ?: return false
        recycleBin.deleteForGood(database, entry)
        return true
    }

    /** Restore: out of the recycle bin, into the group it came from; the card stays and says where it went. */
    fun restore() {
        if (restoring) return
        restoring = true
        recycleBin.restore(database, entryId) { problem ->
            restoring = false
            val group = unlockedVault()?.entries?.firstOrNull { it.id == entryId }?.groupPath.orEmpty()
            mutableNotices.trySend(
                problem?.let { Notice(it, isProblem = true) }
                    ?: Notice("Restored to " + GroupPathFormatter.text(group).ifEmpty { "the top group" }),
            )
        }
    }

    private fun unlockedVault(): Vault? = (database.session.state.value as? SessionState.Unlocked)?.vault

    /** The password for Share… (SPEC §6.2), as stored; null when there is none. */
    fun passwordToShare(): String? =
        content.value?.card?.rows?.firstOrNull { it.kind == FieldRow.Kind.PASSWORD && !it.isEmpty }?.storedText()

    fun problem(text: String) {
        mutableNotices.trySend(Notice(text, isProblem = true))
    }

    /** [build], its time logged (keething-spec app.large-database): it runs on the main thread. */
    private fun timedBuild(vault: Vault): CardContent? {
        val started = TimeSource.Monotonic.markNow()
        return build(vault).also { Log.i("Card", "built in ${started.elapsedNow().inWholeMilliseconds} ms") }
    }

    private fun build(vault: Vault): CardContent? {
        val inBin = vault.recycleBin.firstOrNull { it.id == entryId }
        val entry = vault.entries.firstOrNull { it.id == entryId } ?: inBin ?: return null
        val card = EntryCardBuilder.build(entry, Instant.now())
        val icon = entry.customIconId?.let(vault.customIcons::get)?.image
            ?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }?.asImageBitmap()
        return CardContent(
            card, inRecycleBin = inBin != null, icon = icon, binTurnedOff = !vault.recycleBinEnabled, face = EntryFace.of(entry),
            suggestion = if (inBin == null) TypeSuggestion.of(entry) else null,
        )
    }
}

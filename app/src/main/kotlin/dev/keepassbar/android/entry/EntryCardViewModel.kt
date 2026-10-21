package dev.keepassbar.android.entry

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.keepassbar.android.attachment.AndroidAttachments
import dev.keepassbar.android.attachment.AttachmentFiles
import dev.keepassbar.android.attachment.AttachmentNames
import dev.keepassbar.android.attachment.AttachmentTypes
import dev.keepassbar.android.attachment.SaveRequest
import dev.keepassbar.android.clipboard.SensitiveClipboard
import dev.keepassbar.android.list.RecentEntries
import dev.keepassbar.android.recyclebin.RecycleBinMoves
import dev.keepassbar.android.session.DatabaseSession
import dev.keepassbar.android.ui.Notice
import dev.keepassbar.card.CardSection
import dev.keepassbar.card.EntryCard
import dev.keepassbar.card.EntryCardBuilder
import dev.keepassbar.card.FieldRow
import dev.keepassbar.card.FieldTypes
import dev.keepassbar.card.GroupPathFormatter
import dev.keepassbar.card.RevealState
import dev.keepassbar.card.TotpDisplay
import dev.keepassbar.domain.vault.Vault
import dev.keepassbar.vault.session.SessionState
import java.time.Instant
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

/** The open card: built from the entry in the vault shown now. */
internal data class CardContent(
    val card: EntryCard,
    val inRecycleBin: Boolean,
    val icon: ImageBitmap?,
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
    private val entryId: String,
    private val database: DatabaseSession,
    private val recents: RecentEntries,
    private val clipboard: SensitiveClipboard,
    private val files: AttachmentFiles,
    private val android: AndroidAttachments,
    private val recycleBin: RecycleBinMoves,
) : ViewModel() {
    private val mutableNotices = Channel<Notice>(Channel.BUFFERED)
    val notices: Flow<Notice> = mutableNotices.receiveAsFlow()

    private val mutableOpenings = Channel<Intent>(Channel.BUFFERED)

    /** An attachment written for another app to open: the screen starts the intent. */
    val openings: Flow<Intent> = mutableOpenings.receiveAsFlow()

    /** The attachment the save dialog is up for. */
    private var saving: String? = null

    private val reveal = MutableStateFlow(RevealState())

    private val content: StateFlow<CardContent?> = database.session.state
        .map { (it as? SessionState.Unlocked)?.vault }
        .distinctUntilChanged { a, b -> a === b }
        .map { vault -> vault?.let(::build) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, (database.session.state.value as? SessionState.Unlocked)?.vault?.let(::build))

    val state: StateFlow<CardUiState> = combine(content, reveal, ::CardUiState)
        .stateIn(viewModelScope, SharingStarted.Eagerly, CardUiState(content.value, RevealState()))

    init {
        content.value?.takeIf { !it.inRecycleBin }?.let { recents.note(entryId) }
    }

    fun toggleReveal(rowId: String) {
        reveal.value = reveal.value.toggle(rowId)
    }

    /** The eye of a list of codes: all of them shown, or all masked. */
    fun toggleSection(section: CardSection) {
        reveal.value = reveal.value.toggleAll(section.rows)
    }

    fun copy(row: FieldRow) {
        if (!row.isCopyable) return
        val text = when {
            row.kind == FieldRow.Kind.TOTP -> row.totp?.let { TotpDisplay.of(it, Instant.now()).code } ?: return
            else -> row.storedText()
        }
        clipboard.copy(text, sensitive = row.isConcealed || row.kind == FieldRow.Kind.TOTP)
        if (content.value?.inRecycleBin == false) recents.note(entryId)
        mutableNotices.trySend(Notice.copied(row.noticeLabel))
    }

    /** Writes the attachment to a file of its own and hands it to the app the user picks. */
    fun openAttachment(row: FieldRow) {
        val name = row.label
        val bytes = database.session.attachment(entryId, name) ?: return problem("Could not read $name")
        val generation = files.generation
        viewModelScope.launch {
            val intent = try {
                withContext(Dispatchers.IO) { files.write(generation, name, bytes)?.let { android.viewIntent(it, bytes) } }
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

    /** A Restore running: the button waits. */
    var restoring by mutableStateOf(false)
        private set

    /** Delete (SPEC §6.2): into the recycle bin at once, the list says how it went, with Undo. False: nothing to delete. */
    fun moveToRecycleBin(): Boolean {
        val entry = unlockedVault()?.entries?.firstOrNull { it.id == entryId } ?: return false
        recycleBin.moveToRecycleBin(database, entry)
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

    private fun build(vault: Vault): CardContent? {
        val inBin = vault.recycleBin.firstOrNull { it.id == entryId }
        val entry = vault.entries.firstOrNull { it.id == entryId } ?: inBin ?: return null
        val card = EntryCardBuilder.build(entry, Instant.now(), types = FieldTypes.from(vault.settings))
        val icon = entry.customIconId?.let(vault.customIcons::get)?.image
            ?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }?.asImageBitmap()
        return CardContent(card, inRecycleBin = inBin != null, icon = icon)
    }
}

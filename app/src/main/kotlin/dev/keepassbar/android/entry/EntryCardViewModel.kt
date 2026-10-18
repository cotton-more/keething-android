package dev.keepassbar.android.entry

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.keepassbar.android.clipboard.SensitiveClipboard
import dev.keepassbar.android.list.RecentEntries
import dev.keepassbar.android.session.DatabaseSession
import dev.keepassbar.android.ui.Notice
import dev.keepassbar.card.CardSection
import dev.keepassbar.card.EntryCard
import dev.keepassbar.card.EntryCardBuilder
import dev.keepassbar.card.FieldRow
import dev.keepassbar.card.FieldTypes
import dev.keepassbar.card.RevealState
import dev.keepassbar.card.TotpDisplay
import dev.keepassbar.domain.vault.FieldValue
import dev.keepassbar.domain.vault.Vault
import dev.keepassbar.vault.session.SessionState
import java.time.Instant
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
 * code row copies the current code.
 */
internal class EntryCardViewModel(
    private val entryId: String,
    database: DatabaseSession,
    private val recents: RecentEntries,
    private val clipboard: SensitiveClipboard,
) : ViewModel() {
    private val mutableNotices = Channel<Notice>(Channel.BUFFERED)
    val notices: Flow<Notice> = mutableNotices.receiveAsFlow()

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
            else -> when (val value = row.value) {
                is FieldValue.Plain -> value.text
                is FieldValue.Protected -> value.secret.reveal()
            }
        }
        clipboard.copy(text, sensitive = row.isConcealed || row.kind == FieldRow.Kind.TOTP)
        if (content.value?.inRecycleBin == false) recents.note(entryId)
        mutableNotices.trySend(Notice.copied(row.noticeLabel))
    }

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

package dev.keething.android.quicknote

import android.app.PendingIntent
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.keething.android.R
import dev.keething.android.ui.UiText
import dev.keething.android.ui.resolve
import dev.keething.android.ui.uiText

/**
 * The Quick Settings tile **Quick note** (SPEC §6.4; keething-spec app.quick-note AC3; plans/done/android-notes.md P1):
 * the quick note's screen with the clipboard's text. Only the app in front may read the clipboard, so the screen reads
 * it itself once it has the focus. From the lock screen Android asks the phone's unlock before it opens the screen
 * (plans/done/android-notes.md AQ0).
 */
class QuickNoteTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        // An action, not a switch: never shown as on.
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            subtitle = getString(R.string.tile_quick_note_subtitle)
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        startActivityAndCollapse(
            PendingIntent.getActivity(this, 0, QuickNoteTile.intent(this), PendingIntent.FLAG_IMMUTABLE),
        )
    }
}

internal object QuickNoteTile {
    /** The quick note's screen in clipboard mode: the tile's and Settings' (the launcher's shortcut is in its XML). */
    fun intent(context: Context): Intent =
        Intent(context, QuickNoteActivity::class.java).setAction(QuickNoteIntent.ACTION_FROM_CLIPBOARD).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Asks Android to add the tile (its own question); [said] gets what to say when Android shows nothing. After the
     * user said no several times Android answers "not added" without asking, and nothing is said.
     */
    fun requestAdd(context: Context, said: (String) -> Unit) {
        val statusBar = context.getSystemService(StatusBarManager::class.java) ?: return said(NOT_ASKED.resolve(context.resources))
        statusBar.requestAddTileService(
            ComponentName(context, QuickNoteTileService::class.java),
            context.getString(R.string.tile_quick_note),
            Icon.createWithResource(context, R.drawable.ic_tile_quick_note),
            context.mainExecutor,
        ) { result -> requestMessage(result)?.let { said(it.resolve(context.resources)) } }
    }

    /** What Settings says after asking Android to add the tile; null when Android's own question said it all. */
    fun requestMessage(result: Int): UiText? = when (result) {
        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> uiText(R.string.quicknote_tile_already_added)
        StatusBarManager.TILE_ADD_REQUEST_ERROR_APP_NOT_IN_FOREGROUND,
        StatusBarManager.TILE_ADD_REQUEST_ERROR_REQUEST_IN_PROGRESS,
        StatusBarManager.TILE_ADD_REQUEST_ERROR_BAD_COMPONENT,
        StatusBarManager.TILE_ADD_REQUEST_ERROR_MISMATCHED_PACKAGE,
        StatusBarManager.TILE_ADD_REQUEST_ERROR_NOT_CURRENT_USER,
        StatusBarManager.TILE_ADD_REQUEST_ERROR_NO_STATUS_BAR_SERVICE,
        -> NOT_ASKED
        // Added, not added, or a value Android does not document: its own question said it.
        else -> null
    }

    private val NOT_ASKED = uiText(R.string.quicknote_tile_not_asked)
}

package dev.keething.android.quicknote

import android.app.StatusBarManager
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.w3c.dom.Element

/**
 * The clipboard's ways in (SPEC §6.4; keething-spec app.quick-note AC3; plans/done/android-notes.md P1, AQ4): the launcher's
 * shortcut **Note from clipboard** and the Quick Settings tile **Quick note** both open the quick note's screen with
 * the clipboard's action; Settings asks Android to add the tile and says what came of it when Android shows nothing.
 */
class QuickNoteWaysInTest {
    private val android = "http://schemas.android.com/apk/res/android"

    private fun parse(path: String) =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(File(path))

    private fun Element.attr(name: String): String = getAttributeNS(android, name)

    private fun Element.children(tag: String): List<Element> =
        getElementsByTagName(tag).let { list -> (0 until list.length).map { list.item(it) as Element } }

    private fun string(reference: String): String {
        val name = reference.removePrefix("@string/")
        return parse("src/main/res/values/strings.xml").documentElement.children("string").first { it.getAttribute("name") == name }.textContent
    }

    @Test
    fun theLaunchersNoteFromClipboardOpensTheQuickNotesScreenWithTheClipboardsAction() {
        val shortcut = parse("src/main/res/xml/shortcuts.xml").documentElement.children("shortcut").single { it.attr("shortcutId") == "note-from-clipboard" }
        assertEquals("Note from clipboard", string(shortcut.attr("shortcutShortLabel")))
        val intent = shortcut.children("intent").single()
        assertEquals(QuickNoteIntent.ACTION_FROM_CLIPBOARD, intent.attr("action"))
        assertEquals(QuickNoteActivity::class.java.name, intent.attr("targetClass"))
    }

    @Test
    fun theTileIsDeclaredForQuickSettingsAsQuickNote() {
        val manifest = parse("src/main/AndroidManifest.xml").documentElement
        val tile = manifest.children("service").single { it.attr("name") == ".quicknote.QuickNoteTileService" }
        assertEquals("android.permission.BIND_QUICK_SETTINGS_TILE", tile.attr("permission"))
        assertEquals("true", tile.attr("exported"))
        assertEquals(listOf("android.service.quicksettings.action.QS_TILE"), tile.children("action").map { it.attr("name") })
        assertEquals("Quick note", string(tile.attr("label")))
    }

    @Test
    fun addingTheTileSaysWhatAndroidDidNotShow() {
        assertNull(QuickNoteTile.requestMessage(StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED), "Android showed the tile added")
        assertNull(QuickNoteTile.requestMessage(StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED), "the user said no in Android's question")
        assertEquals("The Quick note tile is in Quick Settings already", QuickNoteTile.requestMessage(StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED))
        val errors = listOf(
            StatusBarManager.TILE_ADD_REQUEST_ERROR_APP_NOT_IN_FOREGROUND,
            StatusBarManager.TILE_ADD_REQUEST_ERROR_REQUEST_IN_PROGRESS,
            StatusBarManager.TILE_ADD_REQUEST_ERROR_BAD_COMPONENT,
            StatusBarManager.TILE_ADD_REQUEST_ERROR_MISMATCHED_PACKAGE,
            StatusBarManager.TILE_ADD_REQUEST_ERROR_NOT_CURRENT_USER,
            StatusBarManager.TILE_ADD_REQUEST_ERROR_NO_STATUS_BAR_SERVICE,
        )
        for (error in errors) {
            assertEquals("Android did not ask. Add the tile from Quick Settings' edit button.", QuickNoteTile.requestMessage(error), "$error")
        }
        assertNull(QuickNoteTile.requestMessage(3), "a value Android does not document (its question dismissed): its question was shown")
    }
}

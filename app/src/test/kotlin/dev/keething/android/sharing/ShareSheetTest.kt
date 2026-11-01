package dev.keething.android.sharing

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import org.w3c.dom.Element

/**
 * Keething's own share sheets leave out Keething's own targets, "Read with Keething" and Keething note (SPEC §6.2,
 * §10.3; keething-spec plans/android-notes.md P2): every share target the app declares is in the list, and every share
 * sheet the app opens goes through [ShareSheet].
 */
class ShareSheetTest {
    private val android = "http://schemas.android.com/apk/res/android"

    /** The app's activities that take a share (`ACTION_SEND` or `ACTION_SEND_MULTIPLE`), by class name. */
    private fun shareTargets(): Set<String> {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val manifest = factory.newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val activities = manifest.getElementsByTagName("activity")
        return (0 until activities.length).map { activities.item(it) as Element }.filter { activity ->
            val actions = activity.getElementsByTagName("action")
            (0 until actions.length).any { (actions.item(it) as Element).getAttributeNS(android, "name") in SHARES }
        }.map { "dev.keething.android" + it.getAttributeNS(android, "name") }.toSet()
    }

    @Test
    fun everyShareTargetOfTheAppIsLeftOut() {
        assertEquals(shareTargets(), ShareSheet.OWN_TARGETS.toSet())
    }

    @Test
    fun everyShareSheetTheAppOpensGoesThroughIt() {
        val opening = File("src/main/kotlin").walk()
            .filter { it.extension == "kt" && it.name != "ShareSheet.kt" && "createChooser(" in it.readText() }
            .map { it.name }
            .toList()
        assertEquals(emptyList(), opening, "a share sheet opened without leaving out Keething's own targets")
    }

    private companion object {
        val SHARES = setOf("android.intent.action.SEND", "android.intent.action.SEND_MULTIPLE")
    }
}

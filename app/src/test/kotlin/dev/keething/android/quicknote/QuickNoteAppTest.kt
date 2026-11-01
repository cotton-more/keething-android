package dev.keething.android.quicknote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The app a quick note came from (SPEC §6.4; keething-spec app.quick-note-source AC1, AC4): the sending or calling
 * app's label as the launcher shows it, from `Activity.getReferrer()` (plans/done/android-notes.md AQ0); none for Keething
 * itself or what cannot be named.
 */
class QuickNoteAppTest {
    private val labels = mapOf(
        "com.android.chrome" to "Chrome",
        "org.mozilla.firefox" to "Firefox",
        "com.google.android.gm" to "Gmail",
        "dev.keething.android" to "Keething",
        "com.example.unlabelled" to "com.example.unlabelled",
        "com.example.blank" to "  ",
        "android" to "Android System",
        "com.android.intentresolver" to "Intent Resolver",
        "com.android.systemui" to "System UI",
    )

    private fun name(referrer: String?) = QuickNoteApp.name(referrer, own = "dev.keething.android", label = labels::get)

    @Test
    fun theSendersOrCallersLabel() {
        // spec: app.quick-note-source/AC1
        assertEquals("Chrome", name("android-app://com.android.chrome"))
        assertEquals("Firefox", name("android-app://org.mozilla.firefox"))
        assertEquals("Gmail", name("android-app://com.google.android.gm/"))
        assertEquals("Gmail", name("android-app://com.google.android.gm/https/mail.google.com/"), "a referrer with a path")
    }

    @Test
    fun noneForKeethingItselfOrWhatCannotBeNamed() {
        // spec: app.quick-note-source/AC4
        assertNull(name("android-app://dev.keething.android"), "Keething's own (its share sheets leave Keething note out)")
        for (system in listOf("android", "com.android.intentresolver", "com.android.systemui")) {
            assertNull(name("android-app://$system"), "the system's chooser or System UI is not where a text came from ($system)")
        }
        assertNull(name(null), "no referrer")
        assertNull(name("android-app://com.example.notinstalled"), "not installed, or not visible")
        assertNull(name("android-app://com.example.unlabelled"), "an app without a label: its package name is not a name")
        assertNull(name("android-app://com.example.blank"))
        assertNull(name("https://films.example.org/"), "a web page as the referrer (a sender may set EXTRA_REFERRER to one)")
        assertNull(name("android-app://"))
        assertNull(name(""))
    }
}

package dev.keething.android.quicknote

import android.content.Intent
import dev.keething.android.clipboard.SystemClipboard
import dev.keething.android.quicknote.QuickNoteRun.Source
import dev.keething.card.editing.QuickNoteSource

/**
 * What another app hands over to Keething note (SPEC §6.4; keething-spec app.quick-note AC3, app.quick-note-source
 * AC2, AC4, AC5), in the shapes seen in plans/android-notes.md AQ0: a share's text (`ACTION_SEND`, `text/plain`) or the
 * selection menu's (`ACTION_PROCESS_TEXT`), the app, and the page a browser gives with a share.
 *
 * - A lone http(s) address shared is a page: the address goes to the Website, and the text is the subject (Chrome's
 *   page title) or, without one (Firefox), the address too, since a note needs its text.
 * - A text whose last line is a link to a highlight (`#:~:text=`, after a line break and a space: Chrome's, and the
 *   other Chromium browsers', shared selection) gives that link to the Website and the rest as the text, the quotes
 *   the browser put around the selection taken off. Any other subject, and the browser's own line over an address
 *   ("Including link: …", "Share via"), is left out.
 * - Anything else, and the selection menu: the text as it came, no page; nothing is guessed from an address in it.
 */
internal object QuickNoteIntent {
    /** The tile's and the launcher's Note from clipboard: the clipboard's text, read by the screen itself. */
    const val ACTION_FROM_CLIPBOARD: String = "dev.keething.android.action.NOTE_FROM_CLIPBOARD"

    /**
     * Where the quick note's text comes from: the [clipboard] for [ACTION_FROM_CLIPBOARD] (nothing in the intent read,
     * no app: the clipboard does not say), else what a share or the selection menu handed over ([read]).
     */
    fun source(action: String?, extra: (String) -> CharSequence?, app: () -> String?, clipboard: () -> SystemClipboard): Source? =
        if (action == ACTION_FROM_CLIPBOARD) Source.Clipboard(clipboard()) else read(action, extra, app)

    private val LONE_ADDRESS = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)

    /** The selection, a line break, blanks, and an address with a text directive: the last line, nothing after it. */
    private val LINK_TO_HIGHLIGHT = Regex("""^(.*)\n[ \t]*(https?://\S*:~:text=\S*)$""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    /**
     * What came in; null when there is no text. [extra] reads one of the intent's text extras, [app] names the sending
     * or calling app ([QuickNoteApp]) and is asked only when there is a text.
     */
    fun read(action: String?, extra: (String) -> CharSequence?, app: () -> String?): Source.Given? {
        val text = when (action) {
            Intent.ACTION_SEND -> extra(Intent.EXTRA_TEXT)
            Intent.ACTION_PROCESS_TEXT -> extra(Intent.EXTRA_PROCESS_TEXT)
            else -> null
        }?.toString() ?: return null
        val (note, page) = if (action == Intent.ACTION_SEND) shared(text, extra(Intent.EXTRA_SUBJECT)?.toString()) else text to null
        return Source.Given(note, QuickNoteSource(app = app(), page = page))
    }

    /** A share's text and the page it gives; the page null when it gives none. */
    private fun shared(text: String, subject: String?): Pair<String, String?> {
        val trimmed = text.trim()
        if (LONE_ADDRESS.matches(trimmed)) {
            val title = subject?.trim()?.takeIf { it.isNotEmpty() && !isBrowsersOwnLine(it) }
            // An address the Website does not take stays the text's, under its title.
            val page = QuickNoteSource.address(trimmed) ?: return (title?.let { "$it\n$text" } ?: text) to null
            return (title ?: page) to page
        }
        val link = LINK_TO_HIGHLIGHT.find(text.trimEnd()) ?: return text to null
        val page = QuickNoteSource.address(link.groupValues[2]) ?: return text to null
        val selection = link.groupValues[1]
        val inner = selection.trim()
        val unquoted = if (inner.length >= 2 && inner.startsWith('"') && inner.endsWith('"')) inner.substring(1, inner.length - 1) else selection
        return unquoted to page
    }

    /**
     * A subject that is the browser's own line, not a title: Firefox's "Share via" (also over a selected address) and
     * Chrome's "Including link: …" (AQ0, in English; another language's is taken as a title, shown in the editor).
     */
    private fun isBrowsersOwnLine(subject: String): Boolean =
        subject.equals("Share via", ignoreCase = true) || subject.startsWith("Including link:", ignoreCase = true)
}

/**
 * The app a quick note came from (keething-spec app.quick-note-source AC1, AC4): `Activity.getReferrer()`, the sender
 * of a share (not the system's chooser) or the selection menu's caller, which a sender may set itself with
 * `EXTRA_REFERRER` (only a tag; accepted).
 */
internal object QuickNoteApp {
    private const val SCHEME = "android-app://"

    /** The system's own: the chooser and System UI (its relaunch of a screen after an app update, AQ0) are no source. */
    private val SYSTEM = setOf("android", "com.android.intentresolver", "com.android.systemui")

    /**
     * The label of the app [referrer] names, as [label] reads it from the package manager; null for Keething itself
     * ([own] package), the system's chooser and System UI, a referrer that is no app, and an app that cannot be named
     * (not visible, no label of its own: the package name is not a name).
     */
    fun name(referrer: String?, own: String, label: (String) -> String?): String? {
        if (referrer == null || !referrer.startsWith(SCHEME)) return null
        val packageName = referrer.removePrefix(SCHEME).substringBefore('/')
        if (packageName.isEmpty() || packageName == own || packageName in SYSTEM) return null
        return label(packageName)?.trim()?.takeIf { it.isNotEmpty() && it != packageName }
    }
}

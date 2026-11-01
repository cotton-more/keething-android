package dev.keething.android.quicknote

import android.content.Intent

/**
 * What another app hands over to Keething note (SPEC §6.4; keething-spec app.quick-note AC3): a share's text
 * (`ACTION_SEND`, `text/plain`) or the selection menu's (`ACTION_PROCESS_TEXT`). A share's subject becomes the first
 * line only when the text is a lone http(s) address: a page shared from Chrome, whose subject is the page's title. Any
 * other subject is the browser's own line ("Including link: …", "Share via"; plans/android-notes.md AQ0) and is left
 * out.
 */
internal object QuickNoteIntent {
    private val LONE_ADDRESS = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)

    /** The text handed over, as it came; null when there is none. [extra] reads one of the intent's text extras. */
    fun text(action: String?, extra: (String) -> CharSequence?): String? = when (action) {
        Intent.ACTION_SEND -> extra(Intent.EXTRA_TEXT)?.toString()?.let { text ->
            val subject = extra(Intent.EXTRA_SUBJECT)?.toString()?.trim().orEmpty()
            if (subject.isNotEmpty() && LONE_ADDRESS.matches(text.trim())) "$subject\n$text" else text
        }
        Intent.ACTION_PROCESS_TEXT -> extra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        else -> null
    }
}

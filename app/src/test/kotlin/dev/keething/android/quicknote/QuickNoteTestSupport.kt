package dev.keething.android.quicknote

import dev.keething.card.editing.QuickNoteSource

/** What a share or the selection menu hands over: [text] from [source]; none without a text. */
internal fun given(text: String?, source: QuickNoteSource = QuickNoteSource()): QuickNoteRun.Source.Given? =
    text?.let { QuickNoteRun.Source.Given(it, source) }

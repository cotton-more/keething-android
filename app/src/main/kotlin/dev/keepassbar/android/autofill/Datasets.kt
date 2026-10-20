package dev.keepassbar.android.autofill

import android.content.Context
import android.service.autofill.Dataset
import android.service.autofill.Field
import android.service.autofill.FillResponse
import android.service.autofill.Presentations
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.view.inputmethod.InlineSuggestionsRequest
import dev.keepassbar.android.R
import dev.keepassbar.android.session.Sessions
import dev.keepassbar.autofill.form.AutofillForm
import dev.keepassbar.domain.vault.Entry
import dev.keepassbar.vault.session.SessionState

/** A dataset that fills [entry]'s values into [form]'s fields ([ids]); null when the entry has nothing for them. */
internal fun filledDataset(form: AutofillForm, ids: Map<Int, AutofillId>, entry: Entry, code: String?, presentations: Presentations): Dataset? {
    val values = form.valuesFor(entry, code)
    if (values.values.all { it == null }) return null
    val builder = Dataset.Builder(presentations)
    for ((index, value) in values) {
        val field = Field.Builder().apply { if (value != null) setValue(AutofillValue.forText(value)) }.build()
        builder.setField(ids.getValue(index), field)
    }
    return builder.build()
}

/** The answer to a fill request (SPEC §7). */
internal class FillResponses(private val context: Context, private val sessions: Sessions, private val entries: AutofillEntries) {
    fun response(screen: ScreenFields, form: AutofillForm, inline: InlineSuggestionsRequest?): FillResponse? {
        val database = sessions.current.value ?: return null
        // The lock alarm may be late: a fill request checks the time too (SPEC §4).
        sessions.checkBackgroundTime()
        val presentations = AutofillPresentations(context, inline)
        val ids = form.fields.associateWith(screen.ids::get)
        val label = entries.label(form.target)
        val response = FillResponse.Builder()
        val unlocked = database.session.state.value as? SessionState.Unlocked
        if (unlocked == null) {
            val unlock = AutofillRequest(AutofillMode.UNLOCK, form, ids)
            response.addDataset(authenticated(unlock, presentations.action("Unlock KeePassBar", "to fill $label", R.drawable.ic_autofill_lock, 0, pinned = false)))
            return response.build()
        }
        val candidates = entries.candidates(unlocked.vault, form).take(MAX_SUGGESTIONS)
        var shown = 0
        for (candidate in candidates) {
            val entry = candidate.entry
            val presentation = presentations.entry(shown, entry.displayTitle, entry.username.ifEmpty { null })
            val dataset = if (form.codes.isNotEmpty() && entry.totp != null) {
                authenticated(AutofillRequest(AutofillMode.CODE, form, ids, entry.id), presentation)
            } else {
                filledDataset(form, ids, entry, code = null, presentation)
            }
            if (dataset != null) {
                response.addDataset(dataset)
                shown++
            }
        }
        val pick = AutofillRequest(AutofillMode.PICK, form, ids)
        val title = if (shown == 0) "Search KeePassBar" else "Another entry"
        response.addDataset(authenticated(pick, presentations.action(title, "to fill $label", R.drawable.ic_autofill_search, shown, pinned = true)))
        return response.build()
    }

    /** A dataset whose values come from the autofill screen: the fields are named, their values are not known yet. */
    private fun authenticated(request: AutofillRequest, presentations: Presentations): Dataset {
        val builder = Dataset.Builder(presentations).setAuthentication(request.sender(context))
        for (index in request.form.fields) builder.setField(request.ids.getValue(index), Field.Builder().build())
        return builder.build()
    }

    private companion object {
        /** The dropdown stays short; the search finds the rest. */
        const val MAX_SUGGESTIONS = 6
    }
}

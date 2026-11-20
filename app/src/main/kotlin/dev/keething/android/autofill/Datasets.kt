package dev.keething.android.autofill

import android.content.Context
import android.service.autofill.Dataset
import android.service.autofill.Field
import android.service.autofill.FillResponse
import android.service.autofill.Presentations
import android.service.autofill.SaveInfo
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.view.inputmethod.InlineSuggestionsRequest
import dev.keething.android.R
import dev.keething.android.session.Sessions
import dev.keething.android.ui.resolve
import dev.keething.android.ui.ui
import dev.keething.autofill.form.AutofillForm
import dev.keething.domain.vault.Entry

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

/**
 * The answer to a fill request (SPEC §7): the matching entries of every open database (or "Unlock Keething" while none
 * is open; keething-spec autofill.fill AC6), "Generate a password" for a new-password field, and the save offer for the
 * password typed.
 */
internal class FillResponses(
    private val context: Context,
    private val sessions: Sessions,
    private val entries: AutofillEntries,
    private val intents: FillIntents,
) {
    fun response(screen: ScreenFields, form: AutofillForm, inline: InlineSuggestionsRequest?): FillResponse? {
        // No database chosen yet: nothing to offer.
        sessions.current.value ?: return null
        // The lock alarm may be late: a fill request checks the time too (SPEC §4).
        sessions.checkBackgroundTime()
        val presentations = AutofillPresentations(context, inline)
        val ids = form.allFields.associateWith(screen.ids::get)
        val response = FillResponse.Builder()
        // A sign-up gets nothing of an entry: only a new password, and the offer to save it.
        if (!form.isSignUp) addEntries(response, sessions.openNow().map { OpenVault(it.database.id, it.unlocked.vault) }, form, ids, presentations)
        if (form.newPasswords.isNotEmpty()) {
            val generate = AutofillRequest(AutofillMode.GENERATE, form, ids)
            val presentation = presentations.action(context.getString(R.string.autofill_offer_generate), context.getString(R.string.app_name), R.drawable.ic_autofill_generate, 0, pinned = false)
            response.addDataset(authenticated(generate, form.newPasswords, presentation))
        }
        saveInfo(form, ids)?.let(response::setSaveInfo)
        return response.build()
    }

    private fun addEntries(response: FillResponse.Builder, open: List<OpenVault>, form: AutofillForm, ids: Map<Int, AutofillId>, presentations: AutofillPresentations) {
        val label = entries.label(form.target)
        if (open.isEmpty()) {
            val unlock = AutofillRequest(AutofillMode.UNLOCK, form, ids, id = intents.issue())
            response.addDataset(authenticated(unlock, form.fields, presentations.action(context.getString(R.string.autofill_offer_unlock), context.getString(R.string.autofill_offer_to_fill, label), R.drawable.ic_autofill_lock, 0, pinned = false)))
            return
        }
        // Each entry by its ref: the screen a dataset opens finds it in its own database. No value is in the response,
        // which the system keeps for the screen after a lock: the screen hands them over, without being seen while a
        // database is open, after the unlock otherwise (SPEC §7, Unlocked). An entry with a code makes it when tapped:
        // for the code field, or for the clipboard after a login. One with nothing for these fields is left out.
        var shown = 0
        for (candidate in entries.candidates(open, form)) {
            if (shown == MAX_SUGGESTIONS) break
            val entry = candidate.entry
            if (entry.totp == null && form.valuesFor(entry, code = null).values.all { it == null }) continue
            val presentation = presentations.entry(shown, entry.displayTitle.ui().resolve(context.resources), entry.username.ifEmpty { null })
            response.addDataset(authenticated(AutofillRequest(AutofillMode.ENTRY, form, ids, entry.id, intents.issue()), form.fields, presentation))
            shown++
        }
        val pick = AutofillRequest(AutofillMode.PICK, form, ids)
        // Alone it is a chip with its title: pinned, it is an icon only at the keyboard's edge, easily missed.
        val title = context.getString(if (shown == 0) R.string.autofill_offer_search else R.string.autofill_offer_another)
        response.addDataset(authenticated(pick, form.fields, presentations.action(title, context.getString(R.string.autofill_offer_to_fill, label), R.drawable.ic_autofill_search, shown, pinned = shown > 0)))
    }

    /**
     * The system offers to save when the form is sent with one of these fields not as a dataset filled it (SPEC §7,
     * Save): the password (the new one, when there is one) is required, the username optional, and beside a new
     * password the code (a reset: its password generated, the code typed from the SMS). Also when every field has
     * gone (an app that changes screens without leaving the activity).
     */
    private fun saveInfo(form: AutofillForm, ids: Map<Int, AutofillId>): SaveInfo? {
        if (form.savedPasswords.isEmpty()) return null
        val optional = form.usernames + if (form.newPasswords.isNotEmpty()) form.codes else emptyList()
        return SaveInfo.Builder(SaveInfo.SAVE_DATA_TYPE_USERNAME or SaveInfo.SAVE_DATA_TYPE_PASSWORD, form.savedPasswords.map(ids::getValue).toTypedArray())
            .apply { if (optional.isNotEmpty()) setOptionalIds(optional.map(ids::getValue).toTypedArray()) }
            .setFlags(SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE)
            .build()
    }

    /** A dataset whose values come from the autofill screen: [fields] are named, their values are not known yet. */
    private fun authenticated(request: AutofillRequest, fields: List<Int>, presentations: Presentations): Dataset {
        val builder = Dataset.Builder(presentations).setAuthentication(request.sender(context))
        for (index in fields) builder.setField(request.ids.getValue(index), Field.Builder().build())
        return builder.build()
    }

    private companion object {
        /** The dropdown stays short; the search finds the rest. */
        const val MAX_SUGGESTIONS = 6
    }
}

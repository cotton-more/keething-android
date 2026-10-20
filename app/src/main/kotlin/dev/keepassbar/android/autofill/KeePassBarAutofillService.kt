package dev.keepassbar.android.autofill

import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.util.Log
import dev.keepassbar.android.KeePassBarApplication
import dev.keepassbar.autofill.form.AutofillForm
import dev.keepassbar.autofill.target.FillTarget

/**
 * Android's autofill service (SPEC §7): finds the login or code fields of the screen, and offers the
 * matching entries, or "Unlock KeePassBar" while the vault is locked. Filling only: saving comes with
 * writing (ROADMAP step 7), so no `SaveInfo` is set and [onSaveRequest] is never called.
 */
class KeePassBarAutofillService : AutofillService() {
    private val responses: FillResponses get() = (application as KeePassBarApplication).container.fillResponses

    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        val response = runCatching {
            val structure = request.fillContexts.last().structure
            val screen = ScreenFields.read(structure)
            val manual = request.flags and FillRequest.FLAG_MANUAL_REQUEST != 0
            val form = AutofillForm.parse(screen.packageName, screen.fields, packageName, manual)
            // Kinds and counts only: never a value, a domain or an app (SPEC §2).
            Log.i(TAG, "fill request: ${screen.fields.size} fields, " + (form?.describe() ?: "nothing to fill"))
            form?.let { responses.response(screen, it, request.inlineSuggestionsRequest) }
        }.onFailure { Log.e(TAG, "fill request failed", it) }.getOrNull()
        callback.onSuccess(response)
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        callback.onSuccess()
    }

    private fun AutofillForm.describe(): String =
        (if (target is FillTarget.WebPage) "web page" else "app") +
            ", ${usernames.size} username, ${passwords.size} password, ${codes.size} code"

    private companion object {
        const val TAG = "Autofill"
    }
}

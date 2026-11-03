package dev.keething.android.autofill

import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.util.Log
import dev.keething.android.KeethingApplication
import dev.keething.autofill.form.AutofillForm
import dev.keething.autofill.target.FillTarget
import dev.keething.domain.secret.Secret

/**
 * Android's autofill service (SPEC §7): finds the login or code fields of the screen, and offers the
 * matching entries, or "Unlock Keething" while the vault is locked, "Generate a password" for a new one,
 * and the offer to save what was typed.
 */
class KeethingAutofillService : AutofillService() {
    private val container get() = (application as KeethingApplication).container
    private val responses: FillResponses get() = container.fillResponses

    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        val response = runCatching {
            val structure = request.fillContexts.last().structure
            val screen = ScreenFields.read(structure)
            val manual = request.flags and FillRequest.FLAG_MANUAL_REQUEST != 0
            val form = AutofillForm.parse(screen.packageName, screen.fields, packageName, manual)
            // Kinds and counts only: never a value, a domain or an app (SPEC §2).
            Log.i(TAG, "fill request: ${screen.fields.size} fields, " + (form?.describe() ?: "nothing to fill"))
            form?.let { responses.response(screen, it, request.inlineSuggestionsRequest) }
        // The failure's class only: its message could quote what the screen or the database held.
        }.onFailure { Log.e(TAG, "fill request failed: ${it.javaClass.name}") }.getOrNull()
        callback.onSuccess(response)
    }

    /**
     * The user tapped Save in the system's offer: the login typed goes to the save screen, which unlocks if
     * needed and asks how to keep it (SPEC §7, Save). The values stay in this process ([PendingSaves]).
     */
    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        val save = runCatching { typedLogin(request) }.onFailure { Log.e(TAG, "save request failed: ${it.javaClass.name}") }.getOrNull()
        if (save == null) return callback.onSuccess()
        val token = container.pendingSaves.put(save)
        callback.onSuccess(SaveLoginActivity.sender(this, token))
    }

    /** The login typed into the form: the saved password, the username (from an earlier screen of the same form if need be). */
    private fun typedLogin(request: SaveRequest): PendingSave? {
        val screens = request.fillContexts.map { ScreenFields.read(it.structure, withTexts = true) }
        val last = screens.last()
        val form = AutofillForm.parse(last.packageName, last.fields, packageName) ?: return null
        fun ScreenFields.text(index: Int) = texts.getOrNull(index)?.takeIf { it.isNotEmpty() }
        val password = form.savedPasswords.firstNotNullOfOrNull(last::text) ?: return null
        val current = if (form.newPasswords.isNotEmpty()) form.passwords.firstNotNullOfOrNull(last::text) else null
        val username = form.usernames.firstNotNullOfOrNull(last::text) ?: screens.dropLast(1).asReversed().firstNotNullOfOrNull { screen ->
            AutofillForm.parse(screen.packageName, screen.fields, packageName)?.takeIf { it.target == form.target }?.usernames?.firstNotNullOfOrNull(screen::text)
        }
        // Kinds only: never a value, a domain or an app.
        Log.i(TAG, "save request: " + (if (form.target is FillTarget.WebPage) "web page" else "app") +
            ", username ${if (username != null) "typed" else "none"}, " + if (form.newPasswords.isNotEmpty()) "new password" else "password")
        return PendingSave(form.target, username.orEmpty(), Secret.of(password), current?.let(Secret::of))
    }

    private fun AutofillForm.describe(): String =
        (if (target is FillTarget.WebPage) "web page" else "app") +
            ", ${usernames.size} username, ${passwords.size} password, ${codes.size} code"

    private companion object {
        const val TAG = "Autofill"
    }
}

package dev.keepassbar.android.autofill

import android.content.Intent
import android.os.Bundle
import android.service.autofill.Dataset
import android.service.autofill.Field
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import androidx.compose.runtime.remember
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.keepassbar.android.AppContainer
import dev.keepassbar.android.KeePassBarApplication
import dev.keepassbar.android.R
import dev.keepassbar.android.generator.GeneratorUse
import dev.keepassbar.android.generator.PasswordGeneratorSheet
import dev.keepassbar.android.theme.KeePassBarTheme
import dev.keepassbar.android.ui.secureWindow
import dev.keepassbar.domain.vault.Entry
import dev.keepassbar.domain.vault.Vault
import dev.keepassbar.totp.TotpGenerator
import dev.keepassbar.vault.session.SessionState
import java.time.Instant

/**
 * The screen autofill opens over the app being filled (SPEC §7): the unlock when the vault is locked, then
 * the entry to fill. Its answer is the dataset to fill; closing it fills nothing. An entry with a one-time
 * code comes here too, without a screen while the vault is open, so the code is the current one: filled
 * into the code field, or copied when the form has none (the next page asks for it). A new-password field
 * comes here for the password generator.
 */
class AutofillActivity : ComponentActivity() {
    private val container: AppContainer get() = (application as KeePassBarApplication).container

    private lateinit var request: AutofillRequest

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        request = AutofillRequest.from(intent) ?: return finish()
        secureWindow()
        if (request.mode == AutofillMode.GENERATE) {
            enableEdgeToEdge()
            setContent {
                KeePassBarTheme {
                    val generator = remember { container.passwordGenerator() }
                    PasswordGeneratorSheet(generator, GeneratorUse.IntoEntry(::fillGenerated), onDismiss = ::finish)
                }
            }
            return
        }
        val unlocked = container.sessions.current.value?.session?.state?.value as? SessionState.Unlocked
        if (savedInstanceState == null && unlocked != null && answerAtOnce(unlocked.vault)) return
        enableEdgeToEdge()
        val target = container.autofillEntries.label(request.form.target)
        setContent {
            KeePassBarTheme {
                AutofillScreen(container, request, target, answerAtOnce = ::answerAtOnce, onFill = ::fill, onClose = ::finish)
            }
        }
    }

    /**
     * What needs no choice: the entry of a code, or the one entry that matches after an unlock. False when
     * the user chooses.
     */
    private fun answerAtOnce(vault: Vault): Boolean {
        val candidates = container.autofillEntries.candidates(vault, request.form).map { it.entry }
        val entry = when (request.mode) {
            AutofillMode.CODE -> candidates.firstOrNull { it.id == request.entryId }
            AutofillMode.UNLOCK -> candidates.singleOrNull()
            AutofillMode.PICK, AutofillMode.GENERATE -> null
        }
        return entry != null && fill(entry)
    }

    /** A generated password into every new-password field (a sign-up's repetition too); the save offer then has it. */
    private fun fillGenerated(password: String) {
        val presentation = AutofillPresentations(this, inline = null).action("Generated password", null, R.drawable.ic_autofill_generate, 0, pinned = false)
        val builder = Dataset.Builder(presentation)
        for (index in request.form.newPasswords) {
            builder.setField(request.ids.getValue(index), Field.Builder().setValue(AutofillValue.forText(password)).build())
        }
        setResult(RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, builder.build()))
        finish()
    }

    /** Hands [entry]'s values to the system, which fills them in; false when the entry has nothing for these fields. */
    private fun fill(entry: Entry): Boolean {
        val code = entry.totp?.let { TotpGenerator.code(it, Instant.now()) }
        val codeField = request.form.codes.isNotEmpty()
        val presentation = AutofillPresentations(this, inline = null).entry(0, entry.displayTitle, entry.username.ifEmpty { null })
        val dataset = filledDataset(request.form, request.ids, entry, code.takeIf { codeField }, presentation) ?: return false
        if (code != null && !codeField) container.clipboard.copy(code, sensitive = true)
        setResult(RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, dataset))
        finish()
        return true
    }
}

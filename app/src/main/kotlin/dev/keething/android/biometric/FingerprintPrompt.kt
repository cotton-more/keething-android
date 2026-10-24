package dev.keething.android.biometric

import android.content.Context
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import javax.crypto.Cipher
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The system's fingerprint dialog (SPEC §5): strong biometrics only, and "Use password" as the way out,
 * because the lock screen's own password field is the fallback (no screen-lock PIN instead).
 */
internal class FingerprintPrompt(private val context: Context) {
    sealed interface Answer {
        /** The cipher may now do one operation. */
        data object Recognised : Answer

        data object Cancelled : Answer

        data class Failed(val reason: String) : Answer
    }

    /** Shows the dialog for [cipher]; cancelling the caller closes it. Call it on the main thread. */
    suspend fun authenticate(cipher: Cipher): Answer = suspendCancellableCoroutine { continuation ->
        val executor = context.mainExecutor
        // Every callback comes on the main thread; only the first answer counts.
        val answer = { result: Answer -> if (continuation.isActive) continuation.resume(result) }
        val cancel = CancellationSignal()
        continuation.invokeOnCancellation { cancel.cancel() }
        BiometricPrompt.Builder(context)
            .setTitle("Unlock Keething")
            .setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG)
            .setNegativeButton("Use password", executor) { _, _ -> answer(Answer.Cancelled) }
            .setConfirmationRequired(false)
            .build()
            .authenticate(
                BiometricPrompt.CryptoObject(cipher),
                cancel,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = answer(Answer.Recognised)

                    // A finger not recognised keeps the dialog up for another try; only an error ends it.
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) =
                        answer(if (errorCode in CLOSED) Answer.Cancelled else Answer.Failed(errString.toString()))
                },
            )
    }

    private companion object {
        /** Closed by the user, by the system (the app left the screen) or by waiting too long: nothing to report. */
        val CLOSED = setOf(
            BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED,
            BiometricPrompt.BIOMETRIC_ERROR_CANCELED,
            BiometricPrompt.BIOMETRIC_ERROR_TIMEOUT,
        )
    }
}

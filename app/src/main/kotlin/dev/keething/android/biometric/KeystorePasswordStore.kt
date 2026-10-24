package dev.keething.android.biometric

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricManager.Authenticators
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Log
import dev.keething.domain.biometric.BiometricAvailability
import dev.keething.domain.biometric.BiometricPasswordStore
import dev.keething.domain.biometric.StoredPasswordOpening
import dev.keething.domain.secret.Secret
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.UnrecoverableKeyException
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * The master password for the fingerprint unlock (SPEC §5), sealed ([SealedPassword]) for an RSA key pair in
 * the Android Keystore, in StrongBox when the phone has one. The private key works only right after a strong
 * biometric check, for that one operation, and adding or removing a fingerprint invalidates it for good; the
 * public key seals without a prompt. The sealed file is in app-private storage that is never backed up nor
 * moved to another phone (where no key could open it anyway).
 */
internal class KeystorePasswordStore(
    private val context: Context,
    private val prompt: FingerprintPrompt,
    /** The one-at-a-time dispatcher `BiometricUnlock` runs the other calls on. */
    private val io: CoroutineDispatcher,
) : BiometricPasswordStore {
    private val file = File(context.noBackupFilesDir, "biometric-password")
    private val keyStore by lazy { KeyStore.getInstance(KEYSTORE).apply { load(null) } }

    override fun availability(): BiometricAvailability =
        when (context.getSystemService(BiometricManager::class.java).canAuthenticate(Authenticators.BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.Available
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> unavailable("no fingerprint is set up on this phone")
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> unavailable("this phone has no strong fingerprint sensor")
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> unavailable("the fingerprint sensor is not available right now")
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> unavailable("the fingerprint sensor needs a security update")
            else -> unavailable("biometrics are not available")
        }

    override fun hasPassword(): Boolean = file.exists()

    override fun save(password: Secret) {
        val sealed = SealedPassword.seal(password, publicKey())
        val temporary = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(temporary).use { out ->
            out.write(sealed)
            out.fd.sync()
        }
        if (!temporary.renameTo(file)) {
            temporary.delete()
            throw IOException("could not write ${file.name}")
        }
    }

    override suspend fun open(onRecognised: () -> Unit): StoredPasswordOpening {
        val sealed = withContext(io) { file.takeIf(File::exists)?.readBytes() } ?: return StoredPasswordOpening.NothingStored
        val rsa = withContext(io) { decryptingCipher() } ?: return StoredPasswordOpening.KeyInvalidated
        return when (val answer = prompt.authenticate(rsa)) {
            FingerprintPrompt.Answer.Cancelled -> StoredPasswordOpening.Cancelled
            is FingerprintPrompt.Answer.Failed -> StoredPasswordOpening.Failed(answer.reason)
            FingerprintPrompt.Answer.Recognised -> {
                onRecognised()
                openSealed(sealed, rsa)
            }
        }
    }

    /** The RSA decryption in StrongBox: the slow part of a fingerprint unlock after the prompt. */
    private suspend fun openSealed(sealed: ByteArray, rsa: Cipher): StoredPasswordOpening = withContext(io) {
        val started = SystemClock.elapsedRealtime()
        try {
            StoredPasswordOpening.Opened(SealedPassword.open(sealed, rsa)).also {
                Log.i(TAG, "opened in ${SystemClock.elapsedRealtime() - started} ms")
            }
        } catch (unreadable: UnreadableSealException) {
            // Sealed for another key, or changed: nothing can open it any more.
            Log.w(TAG, "stored password unreadable: ${unreadable.message}")
            StoredPasswordOpening.KeyInvalidated
        }
    }

    override fun delete() {
        if (file.exists() && !file.delete()) throw IOException("could not delete ${file.name}")
        keyStore.deleteEntry(ALIAS)
    }

    /** Where the key is kept, for Settings; null when there is none. Blocks on the Keystore. */
    fun keyLocation(): KeyLocation? {
        val key = runCatching { keyStore.getKey(ALIAS, null) }.getOrNull() as? PrivateKey ?: return null
        val info = KeyFactory.getInstance(key.algorithm, KEYSTORE).getKeySpec(key, KeyInfo::class.java)
        return when (info.securityLevel) {
            KeyProperties.SECURITY_LEVEL_STRONGBOX -> KeyLocation.StrongBox
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> KeyLocation.TrustedEnvironment
            KeyProperties.SECURITY_LEVEL_SOFTWARE -> KeyLocation.Software
            else -> KeyLocation.SecureHardware
        }
    }

    enum class KeyLocation { StrongBox, TrustedEnvironment, SecureHardware, Software }

    /**
     * The public key as a copy outside the Keystore: sealing with it needs no fingerprint. A key that a
     * fingerprint change invalidated is replaced first, as a password sealed for it could never be opened.
     */
    private fun publicKey(): PublicKey {
        val current = keyStore.getCertificate(ALIAS)?.publicKey
        val key = if (current != null && decryptingCipher() != null) current else createKey()
        return KeyFactory.getInstance(key.algorithm).generatePublic(X509EncodedKeySpec(key.encoded))
    }

    /** A cipher for the private key, ready for the prompt; null when the key is gone or invalidated. */
    private fun decryptingCipher(): Cipher? {
        val key = try {
            keyStore.getKey(ALIAS, null) as? PrivateKey
        } catch (gone: UnrecoverableKeyException) {
            null
        } ?: return null
        return try {
            Cipher.getInstance(SealedPassword.RSA_TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key, SealedPassword.OAEP) }
        } catch (invalidated: KeyPermanentlyInvalidatedException) {
            null
        }
    }

    private fun createKey(): PublicKey {
        keyStore.deleteEntry(ALIAS)
        val started = SystemClock.elapsedRealtime()
        val strongBox = context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        val key = try {
            generateKey(strongBox)
        } catch (unavailable: StrongBoxUnavailableException) {
            generateKey(strongBox = false)
        }
        Log.i(TAG, "key created in ${SystemClock.elapsedRealtime() - started} ms, ${keyLocation()}")
        return key
    }

    private fun generateKey(strongBox: Boolean): PublicKey {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_DECRYPT)
            // The size every StrongBox must support.
            .setKeySize(2048)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
            .setUserAuthenticationRequired(true)
            // 0: no time window; each use is one operation unlocked by the prompt's CryptoObject.
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            .setInvalidatedByBiometricEnrollment(true)
            .setIsStrongBoxBacked(strongBox)
            .build()
        return KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE).run {
            initialize(spec)
            generateKeyPair().public
        }
    }

    private fun unavailable(reason: String) = BiometricAvailability.Unavailable(reason)

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "biometric-password"
        const val TAG = "Fingerprint"
    }
}

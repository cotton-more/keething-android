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
import dev.keething.domain.biometric.BiometricPasswordStores
import dev.keething.domain.biometric.SeveralOpening
import dev.keething.domain.biometric.StoredPasswordOpening
import dev.keething.domain.secret.Secret
import java.io.File
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
 * Each database's master password for the fingerprint unlock (SPEC §5), sealed for the [Keyring], whose private key is
 * sealed for an RSA key pair in the Android Keystore, in StrongBox when the phone has one ([KeyringFiles]). The
 * Keystore's private key works only right after a strong biometric check, for that one operation, and adding or
 * removing a fingerprint invalidates it for good (and with it the keyring and every database's password); the public
 * keys seal without a prompt. The files are in app-private storage that is never backed up nor moved to another phone
 * (where no key could open them anyway): `biometric/keyring`, and `biometric/databases/<id>` for each database.
 */
internal class KeystorePasswordStores(
    private val context: Context,
    private val prompt: FingerprintPrompt,
    /** The one-at-a-time dispatcher `BiometricSetup` runs the other calls on. */
    private val io: CoroutineDispatcher,
) : BiometricPasswordStores {
    private val keyStore by lazy { KeyStore.getInstance(KEYSTORE).apply { load(null) } }

    /** The one stored password of before each database had its own (plan AD5) is for no database: typed once more. */
    private val files: KeyringFiles by lazy {
        File(context.noBackupFilesDir, OLD_FILE).delete()
        KeyringFiles(File(context.noBackupFilesDir, "biometric"), KeystoreKey())
    }

    override fun availability(): BiometricAvailability =
        when (context.getSystemService(BiometricManager::class.java).canAuthenticate(Authenticators.BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.Available
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> unavailable("no fingerprint is set up on this phone")
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> unavailable("this phone has no strong fingerprint sensor")
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> unavailable("the fingerprint sensor is not available right now")
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> unavailable("the fingerprint sensor needs a security update")
            else -> unavailable("biometrics are not available")
        }

    override fun hasPassword(database: String): Boolean = files.hasPassword(database)

    override fun save(database: String, password: Secret) {
        files.save(database, password)
        Log.i(TAG, "stored for ${database.take(8)}")
    }

    override suspend fun open(database: String, onRecognised: () -> Unit): StoredPasswordOpening {
        val ask = when (val prepared = withContext(io) { files.prepare(database) }) {
            is KeyringFiles.Prepared.Answer -> return prepared.opening.also { Log.w(TAG, "not asked: ${it.describe()}") }
            is KeyringFiles.Prepared.Ask -> prepared
        }
        return when (val answer = prompt.authenticate(ask.cipher)) {
            FingerprintPrompt.Answer.Cancelled -> StoredPasswordOpening.Cancelled
            is FingerprintPrompt.Answer.Failed -> StoredPasswordOpening.Failed(answer.reason)
            FingerprintPrompt.Answer.Recognised -> {
                onRecognised()
                // The RSA decryption in StrongBox is the slow part after the prompt.
                withContext(io) {
                    val started = SystemClock.elapsedRealtime()
                    files.open(ask).also { Log.i(TAG, "${it.describe()} in ${SystemClock.elapsedRealtime() - started} ms") }
                }
            }
        }
    }

    override suspend fun openSeveral(databases: List<String>, onRecognised: () -> Unit, stillWanted: (String) -> Boolean): SeveralOpening {
        val ask = when (val prepared = withContext(io) { files.prepareKeyring() }) {
            is KeyringFiles.PreparedKeyring.Answer -> return prepared.opening.also { Log.w(TAG, "not asked for ${databases.size}: the key or the keyring is gone") }
            is KeyringFiles.PreparedKeyring.Ask -> prepared
        }
        return when (val answer = prompt.authenticate(ask.cipher)) {
            FingerprintPrompt.Answer.Cancelled -> SeveralOpening.Cancelled
            is FingerprintPrompt.Answer.Failed -> SeveralOpening.Failed(answer.reason)
            FingerprintPrompt.Answer.Recognised -> {
                onRecognised()
                val started = SystemClock.elapsedRealtime()
                // The keyring's private key in StrongBox is the slow part; it is dropped when this returns, before any
                // database derives its key.
                val keyring = withContext(io) { files.openKeyring(ask) } ?: return SeveralOpening.KeyInvalidated.also { Log.w(TAG, "the keyring did not open") }
                val each = linkedMapOf<String, StoredPasswordOpening>()
                val unreadable = linkedMapOf<String, String>()
                try {
                    for (database in databases) {
                        // A lock or its closing since: nothing more of it is read.
                        if (!stillWanted(database)) continue
                        // Kept as it is read: a password read there is never dropped unwiped with the result.
                        withContext(io) {
                            try {
                                each[database] = keyring.open(database)
                            } catch (failure: IOException) {
                                unreadable[database] = failure.message ?: failure.javaClass.simpleName
                            }
                        }
                    }
                } catch (failure: Throwable) {
                    // What was read goes with the failure: nobody else would wipe it.
                    each.values.forEach { (it as? StoredPasswordOpening.Opened)?.password?.wipe() }
                    throw failure
                }
                Log.i(TAG, "${each.values.count { it is StoredPasswordOpening.Opened }} of ${databases.size} opened in ${SystemClock.elapsedRealtime() - started} ms")
                SeveralOpening.Opened(each, unreadable)
            }
        }
    }

    override suspend fun confirm(onRecognised: () -> Unit): StoredPasswordOpening = when (val answer = prompt.authenticate(cipher = null)) {
        FingerprintPrompt.Answer.Cancelled -> StoredPasswordOpening.Cancelled
        is FingerprintPrompt.Answer.Failed -> StoredPasswordOpening.Failed(answer.reason)
        FingerprintPrompt.Answer.Recognised -> {
            onRecognised()
            StoredPasswordOpening.Opened(Secret.empty())
        }
    }

    override fun delete(database: String) {
        if (files.delete(database)) Log.i(TAG, "deleted ${database.take(8)}'s")
    }

    override fun deleteAll() {
        files.deleteAll()
        Log.i(TAG, "deleted every stored password, the keyring and the key")
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

    /** The RSA key pair in the Keystore that seals the keyring. */
    private inner class KeystoreKey : FingerprintKey {
        override fun publicKey(): PublicKey? = keyStore.getCertificate(ALIAS)?.publicKey?.let(::copy)

        override fun decryptingCipher(): Cipher? {
            // Null when there is none; an error of the Keystore's (keystore2 says most of them so) is no invalidation.
            val key = try {
                keyStore.getKey(ALIAS, null) as? PrivateKey
            } catch (failed: UnrecoverableKeyException) {
                throw IOException("the Keystore did not give the key (${failed.cause?.javaClass?.simpleName ?: failed.message})", failed)
            } ?: return null
            return try {
                Cipher.getInstance(SealedPassword.RSA_TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key, SealedPassword.OAEP) }
            } catch (invalidated: KeyPermanentlyInvalidatedException) {
                null
            }
        }

        override fun create(): PublicKey {
            keyStore.deleteEntry(ALIAS)
            val started = SystemClock.elapsedRealtime()
            val strongBox = context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
            val key = try {
                generate(strongBox)
            } catch (unavailable: StrongBoxUnavailableException) {
                generate(strongBox = false)
            }
            Log.i(TAG, "key created in ${SystemClock.elapsedRealtime() - started} ms, ${keyLocation()}")
            return copy(key)
        }

        override fun delete() {
            keyStore.deleteEntry(ALIAS)
        }

        /** As a copy outside the Keystore: sealing with it needs no fingerprint. */
        private fun copy(key: PublicKey): PublicKey = KeyFactory.getInstance(key.algorithm).generatePublic(X509EncodedKeySpec(key.encoded))

        private fun generate(strongBox: Boolean): PublicKey {
            val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_DECRYPT)
                // The size every StrongBox must support.
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                .setUserAuthenticationRequired(true)
                // 0: no time window; each use is one operation unlocked by the prompt's CryptoObject. A window would be
                // opened by any strong check, the phone's own unlock too (the AD5 spike).
                .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                .setInvalidatedByBiometricEnrollment(true)
                .setIsStrongBoxBacked(strongBox)
                .build()
            return KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE).run {
                initialize(spec)
                generateKeyPair().public
            }
        }
    }

    private fun unavailable(reason: String) = BiometricAvailability.Unavailable(reason)

    /** For the log, by name: R8 renames the classes. Never the password. */
    private fun StoredPasswordOpening.describe(): String = when (this) {
        is StoredPasswordOpening.Opened -> "opened"
        StoredPasswordOpening.Cancelled -> "cancelled"
        is StoredPasswordOpening.Failed -> "failed"
        StoredPasswordOpening.KeyInvalidated -> "key invalidated"
        StoredPasswordOpening.CopyInvalidated -> "copy invalidated"
        StoredPasswordOpening.NothingStored -> "nothing stored"
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"

        /** The Keystore key's name since before the keyring, when it sealed the one password itself. */
        const val ALIAS = "biometric-password"
        const val OLD_FILE = "biometric-password"
        const val TAG = "Fingerprint"
    }
}

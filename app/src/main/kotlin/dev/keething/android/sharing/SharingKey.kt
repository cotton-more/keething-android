package dev.keething.android.sharing

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import dev.keething.domain.secret.Secret
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The key the sharing's requests are sealed with (SPEC §10.3): 32 random bytes, kept in [file] wrapped by an AES key in
 * the Android Keystore, which never leaves it. No user authentication: the requests are read at every unlock, the
 * fingerprint's or the password's. Not derived from the database's key: KeePassXC gives the file a new key-derivation
 * salt on every save, and the requests waiting for an answer would go with it. Uninstalling the app deletes both.
 * Blocks: call it off the main thread.
 */
internal class SharingKey(private val file: File) {
    /**
     * The key, the caller's to wipe; a new one when there is none or it can never be unwrapped. Throws when the Keystore
     * fails otherwise (perhaps for now): the book stays closed until the next unlock, and is not given up for a key that
     * is still there (keething-spec sharing.book AC3).
     */
    fun load(): Secret {
        unwrap()?.let { return it }
        val key = ByteArray(KEY_SIZE).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, keystoreKey(create = true)) }
        val wrapped = cipher.iv + cipher.doFinal(key)
        val folder = file.parentFile ?: throw IOException("no folder for ${file.name}") // not user text (an exception's detail)
        folder.mkdirs()
        val temporary = File(folder, file.name + ".tmp")
        temporary.writeBytes(wrapped)
        if (!temporary.renameTo(file)) {
            temporary.delete()
            throw IOException("could not keep the sharing key") // not user text (an exception's detail)
        }
        return Secret.adopt(key)
    }

    /**
     * Null only when the key is gone for good: no wrapped key (or not of its size), no Keystore key under [ALIAS], one
     * that does not open it (another key), or one invalidated. Any other failure is thrown.
     */
    private fun unwrap(): Secret? {
        val wrapped = file.takeIf(File::exists)?.readBytes() ?: return null
        if (wrapped.size != IV_SIZE + KEY_SIZE + TAG_SIZE) return null
        val key = keystoreKey(create = false) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_SIZE * 8, wrapped, 0, IV_SIZE))
            }
            Secret.adopt(cipher.doFinal(wrapped, IV_SIZE, wrapped.size - IV_SIZE))
        } catch (_: AEADBadTagException) {
            null
        } catch (_: KeyPermanentlyInvalidatedException) {
            null
        }
    }

    private fun keystoreKey(create: Boolean): SecretKey? {
        val keystore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keystore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) return null
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore" // not user text
        const val ALIAS = "keething-sharing-requests"
        const val TRANSFORMATION = "AES/GCM/NoPadding" // not user text
        const val KEY_SIZE = 32
        const val IV_SIZE = 12
        const val TAG_SIZE = 16
    }
}

package dev.keething.android.storageprobe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Log
import dev.keething.android.biometric.FingerprintPrompt
import dev.keething.android.biometric.SealedPassword
import dev.keething.domain.secret.Secret
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The fingerprint spike (plans/android-databases.md AD5): can one prompt open several stored passwords? Its own keys
 * (`spike-*`) and fictitious passwords only, never the app's key or its stored password:
 * (a) a prompt per database with a key unlocked per operation, as today;
 * (c1) one prompt, then that operation's cipher used again for the next database;
 * (b) a key usable for [WINDOW_SECONDS] after any strong biometric check, in StrongBox and in the TEE, also after
 *     the phone's own unlock by fingerprint;
 * (c5) one prompt opening a software key pair that seals every database's password.
 * Everything is logged under [TAG].
 */
internal class FingerprintProbe(private val context: Context, private val prompt: FingerprintPrompt) {
    /** The probe screen's log while it is open. */
    @Volatile var sink: ((String) -> Unit)? = null

    private val folder = File(context.noBackupFilesDir, "spike")
    private val keyStore by lazy { KeyStore.getInstance(KEYSTORE).apply { load(null) } }
    private var unlockReceiver: BroadcastReceiver? = null

    val watchingUnlocks: Boolean get() = unlockReceiver != null

    suspend fun setUp() = withContext(Dispatchers.IO) {
        remove()
        folder.mkdirs()
        PER_USE.make(0, strongBox = true)
        TIMED_STRONGBOX.make(WINDOW_SECONDS, strongBox = true)
        TIMED_TEE.make(WINDOW_SECONDS, strongBox = false)
        listOf(PER_USE, TIMED_STRONGBOX, TIMED_TEE).forEach { alias ->
            val publicKey = publicKey(alias)
            (1..DATABASES).forEach { seal(alias, it, publicKey) }
        }
        // (c5): a software key pair seals the passwords; the Keystore's per-operation key seals its private key.
        var started = SystemClock.elapsedRealtime()
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        log("setup: software RSA-2048 pair in ${SystemClock.elapsedRealtime() - started} ms")
        started = SystemClock.elapsedRealtime()
        val privateBytes = Secret.adopt(pair.private.encoded)
        File(folder, "$CHAINED-key").writeBytes(SealedPassword.seal(privateBytes, publicKey(PER_USE)))
        privateBytes.wipe()
        File(folder, "$CHAINED-public").writeBytes(pair.public.encoded)
        (1..DATABASES).forEach { seal(CHAINED, it, pair.public) }
        log("setup: private key and ${DATABASES} passwords sealed in ${SystemClock.elapsedRealtime() - started} ms")
    }

    /** (a): one prompt per database, each with its own operation. */
    suspend fun promptEach() {
        val started = SystemClock.elapsedRealtime()
        for (database in 1..DATABASES) {
            val cipher = io { cipher(PER_USE) } ?: return log("(a) no key: set up first")
            val shown = SystemClock.elapsedRealtime()
            if (!recognised("(a) $database", cipher)) return
            val prompted = SystemClock.elapsedRealtime() - shown
            io { log("(a) $database: prompt $prompted ms, ${open(PER_USE, database, cipher)}") }
        }
        log("(a) all $DATABASES in ${SystemClock.elapsedRealtime() - started} ms")
    }

    /** (c1): one prompt, then the same cipher again, and a fresh one for the same key. */
    suspend fun reuseOperation() {
        val cipher = io { cipher(PER_USE) } ?: return log("(c1) no key: set up first")
        if (!recognised("(c1)", cipher)) return
        io {
            (1..DATABASES).forEach { log("(c1) same cipher, database $it: ${open(PER_USE, it, cipher)}") }
            log("(c1) a fresh cipher, database 1: ${attempt(PER_USE, 1)}")
        }
    }

    /** (b): the key's window, before the prompt, inside it, and after it. */
    suspend fun timed(strongBox: Boolean) {
        val alias = if (strongBox) TIMED_STRONGBOX else TIMED_TEE
        io { log("(b) $alias before the prompt: ${attempt(alias, 1)}") }
        if (!recognised("(b) $alias", cipher = null)) return
        val recognisedAt = SystemClock.elapsedRealtime()
        io {
            (1..DATABASES).forEach { log("(b) $alias database $it: ${attempt(alias, it)}") }
            log("(b) $alias all $DATABASES opened within ${SystemClock.elapsedRealtime() - recognisedAt} ms of the finger")
        }
        delay(WINDOW_SECONDS * 1000L + 1500 - (SystemClock.elapsedRealtime() - recognisedAt))
        io { log("(b) $alias after the window (${SystemClock.elapsedRealtime() - recognisedAt} ms): ${attempt(alias, 1)}") }
    }

    /** (c5): one prompt opens the software private key, which opens every database's password. */
    suspend fun chained() {
        val cipher = io { cipher(PER_USE) } ?: return log("(c5) no key: set up first")
        if (!recognised("(c5)", cipher)) return
        io {
            var started = SystemClock.elapsedRealtime()
            val privateBytes = runCatching { SealedPassword.open(File(folder, "$CHAINED-key").readBytes(), cipher) }
                .getOrElse { return@io log("(c5) the private key: ${it.describe()}") }
            val privateKey: PrivateKey = privateBytes.useBytes { KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(it)) }
            privateBytes.wipe()
            log("(c5) private key opened in ${SystemClock.elapsedRealtime() - started} ms")
            started = SystemClock.elapsedRealtime()
            (1..DATABASES).forEach { database ->
                val software = Cipher.getInstance(SealedPassword.RSA_TRANSFORMATION)
                    .apply { init(Cipher.DECRYPT_MODE, privateKey, SealedPassword.OAEP) }
                log("(c5) database $database: ${open(CHAINED, database, software)}")
            }
            log("(c5) all $DATABASES in ${SystemClock.elapsedRealtime() - started} ms after the private key")
        }
    }

    /** (b) after the phone's own unlock: each unlock (fingerprint or PIN) tries the timed keys, with no prompt here. */
    fun watchUnlocks(on: Boolean) {
        unlockReceiver?.let(context::unregisterReceiver)
        unlockReceiver = null
        if (!on) return log("(b) unlocks: no longer watched")
        unlockReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                log("(b) the phone was unlocked")
                thread {
                    listOf(TIMED_STRONGBOX, TIMED_TEE).forEach { log("(b) after the phone's unlock, $it: ${attempt(it, 1)}") }
                }
            }
        }
        // Exported: System UI sends this protected broadcast from its own uid, which a receiver not exported never hears.
        context.registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_USER_PRESENT), Context.RECEIVER_EXPORTED)
        log("(b) unlocks: watched; lock the phone, then unlock it by fingerprint, then by PIN")
    }

    suspend fun remove() = withContext(Dispatchers.IO) {
        ALIASES.forEach(keyStore::deleteEntry)
        folder.deleteRecursively()
        log("spike keys and files removed")
    }

    private suspend fun recognised(what: String, cipher: Cipher?): Boolean =
        when (val answer = prompt.authenticate(cipher)) {
            FingerprintPrompt.Answer.Recognised -> true
            FingerprintPrompt.Answer.Cancelled -> false.also { log("$what: cancelled") }
            is FingerprintPrompt.Answer.Failed -> false.also { log("$what: failed: ${answer.reason}") }
        }

    /** A fresh cipher for [alias] and one database's file: what the Keystore says, timed. */
    private fun attempt(alias: String, database: Int): String {
        val cipher = try {
            cipher(alias) ?: return "no key"
        } catch (refused: Exception) {
            return "init refused: ${refused.describe()}"
        }
        return open(alias, database, cipher)
    }

    private fun open(alias: String, database: Int, cipher: Cipher): String {
        val started = SystemClock.elapsedRealtime()
        return try {
            val opened = SealedPassword.open(File(folder, "$alias-$database").readBytes(), cipher)
            val right = opened.hasSameBytes(fictitious(database))
            opened.wipe()
            "${if (right) "opened" else "WRONG BYTES"} in ${SystemClock.elapsedRealtime() - started} ms"
        } catch (refused: Exception) {
            "refused after ${SystemClock.elapsedRealtime() - started} ms: ${refused.describe()}"
        }
    }

    private fun cipher(alias: String): Cipher? {
        val key = keyStore.getKey(alias, null) as? PrivateKey ?: return null
        return Cipher.getInstance(SealedPassword.RSA_TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key, SealedPassword.OAEP) }
    }

    private fun String.make(windowSeconds: Int, strongBox: Boolean) {
        val started = SystemClock.elapsedRealtime()
        val spec = KeyGenParameterSpec.Builder(this, KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(2048)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(windowSeconds, KeyProperties.AUTH_BIOMETRIC_STRONG)
            .setInvalidatedByBiometricEnrollment(true)
            .setIsStrongBoxBacked(strongBox)
            .build()
        try {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE).run {
                initialize(spec)
                generateKeyPair()
            }
        } catch (failed: Exception) {
            return log("setup: $this NOT made: ${failed.describe()}")
        }
        val key = keyStore.getKey(this, null) as PrivateKey
        val info = KeyFactory.getInstance(key.algorithm, KEYSTORE).getKeySpec(key, KeyInfo::class.java)
        log(
            "setup: $this in ${SystemClock.elapsedRealtime() - started} ms, level ${level(info.securityLevel)}, " +
                "window ${info.userAuthenticationValidityDurationSeconds} s, " +
                "invalidated by enrolment ${info.isInvalidatedByBiometricEnrollment}",
        )
    }

    private fun publicKey(alias: String): PublicKey {
        val key = keyStore.getCertificate(alias).publicKey
        return KeyFactory.getInstance(key.algorithm).generatePublic(X509EncodedKeySpec(key.encoded))
    }

    private fun seal(alias: String, database: Int, publicKey: PublicKey) {
        val password = fictitious(database)
        File(folder, "$alias-$database").writeBytes(SealedPassword.seal(password, publicKey))
        password.wipe()
    }

    private fun fictitious(database: Int) = Secret.of("spike password $database (fictitious)")

    private suspend fun <T> io(work: () -> T): T = withContext(Dispatchers.IO) { work() }

    private fun log(line: String) {
        Log.i(TAG, line)
        sink?.invoke(line)
    }

    private companion object {
        const val TAG = "FingerprintProbe"
        const val KEYSTORE = "AndroidKeyStore"
        const val PER_USE = "spike-per-use"
        const val TIMED_STRONGBOX = "spike-timed-strongbox"
        const val TIMED_TEE = "spike-timed-tee"
        const val CHAINED = "spike-chained"
        val ALIASES = listOf(PER_USE, TIMED_STRONGBOX, TIMED_TEE)
        const val DATABASES = 3
        const val WINDOW_SECONDS = 10

        fun level(level: Int) = when (level) {
            KeyProperties.SECURITY_LEVEL_STRONGBOX -> "StrongBox"
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "TEE"
            KeyProperties.SECURITY_LEVEL_SOFTWARE -> "software"
            else -> "level $level"
        }

        fun Throwable.describe(): String = "${javaClass.simpleName}: $message" + (cause?.let { " ← ${it.javaClass.simpleName}: ${it.message}" } ?: "")
    }
}

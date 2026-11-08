package dev.keething.kdbx.key

import dev.keething.domain.secret.Secret
import java.security.MessageDigest

/**
 * What opens the database: a password, a key file's key, or both (no hardware key). It keeps their composite key,
 * not the parts, and the key derived from it for the last file's KDF parameters: a reload of a file with the same
 * parameters needs no second Argon2 run (SPEC §4). A file with a new salt or new costs is derived again. [wipe] on
 * lock. A wiped or [abandon]ed key is never used: every use throws a [WipedKeyException] (keething-spec kdbx.hardening
 * AC2), a derivation running with it included.
 */
public class DatabaseKey private constructor(private val composite: ByteArray) {
    private var derivedFor: ByteArray? = null
    private var derived: ByteArray? = null

    /** The derived key for [kdf] (a copy: the caller wipes it), and whether it had to be derived now. */
    internal fun transformed(kdf: KdfParameters): Pair<ByteArray, Boolean> = synchronized(this) {
        usable()
        val cached = derived
        if (cached != null && derivedFor.contentEquals(kdf.identity)) return cached.copyOf() to false
        val fresh = kdf.transform(composite, ::usable)
        derived?.fill(0)
        derived = fresh
        derivedFor = kdf.identity.copyOf()
        fresh.copyOf() to true
    }

    private var wiped = false

    /** Set from any thread without waiting for the key's lock: a derivation running with the key holds it. */
    @Volatile
    private var abandoned = false

    /**
     * Gives the key up at once, from any thread, without waiting (keething-spec kdbx.hardening AC6): a derivation
     * running with it stops at its next step, and every use from now on fails as a wiped key's. [wipe] overwrites it;
     * that one waits for a running derivation to stop.
     */
    public fun abandon() {
        abandoned = true
    }

    private fun usable() {
        if (wiped || abandoned) throw WipedKeyException()
    }

    /** A copy with buffers of its own, the derived key included. */
    public fun copy(): DatabaseKey = synchronized(this) {
        usable()
        DatabaseKey(composite.copyOf()).also {
            it.derived = derived?.copyOf()
            it.derivedFor = derivedFor?.copyOf()
        }
    }

    /**
     * Whether [other] is the same master key, its parts the same (keething-spec databases.change-master-key AC1): the
     * composite keys compared in constant time, nothing derived.
     */
    public fun sameAs(other: DatabaseKey): Boolean {
        val theirs = synchronized(other) {
            other.usable()
            other.composite.copyOf()
        }
        try {
            return synchronized(this) {
                usable()
                MessageDigest.isEqual(composite, theirs)
            }
        } finally {
            theirs.fill(0)
        }
    }

    public fun wipe(): Unit = synchronized(this) {
        abandoned = true
        composite.fill(0)
        derived?.fill(0)
        derived = null
        derivedFor = null
        wiped = true
    }

    /** A copy of the composite key, for the tests' vectors. */
    internal fun compositeForTests(): ByteArray = synchronized(this) { composite.copyOf() }

    public companion object {
        /**
         * The composite key as KeePass and KeePassXC make it (keething-spec kdbx.key-files AC6): SHA-256 over the
         * parts' keys in order — SHA-256([password]), then the [keyFile]'s 32 bytes ([KeyFile.key]). At least one
         * of the two; an empty password is a password. The parts stay the caller's to wipe.
         */
        public fun of(password: Secret?, keyFile: Secret? = null): DatabaseKey {
            require(password != null || keyFile != null) { "a key needs a password or a key file" }
            require(keyFile == null || keyFile.size == 32) { "a key file's key is 32 bytes" }
            val parts = MessageDigest.getInstance("SHA-256")
            password?.useBytes { bytes ->
                val inner = MessageDigest.getInstance("SHA-256").digest(bytes)
                parts.update(inner)
                inner.fill(0)
            }
            keyFile?.useBytes { parts.update(it) }
            return DatabaseKey(parts.digest())
        }
    }
}

/** A key used after it was wiped or given up ([DatabaseKey.abandon]): nothing was read or written with it. */
public class WipedKeyException : IllegalStateException("the key was wiped")

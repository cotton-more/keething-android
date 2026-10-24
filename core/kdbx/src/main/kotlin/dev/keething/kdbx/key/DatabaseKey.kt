package dev.keething.kdbx.key

import dev.keething.domain.secret.Secret
import java.security.MessageDigest

/**
 * What opens the database (password only: no key file, no hardware key). It keeps the password's composite
 * key, not the password, and the key derived from it for the last file's KDF parameters: a reload of a file
 * with the same parameters needs no second Argon2 run (SPEC §4). A file with a new salt or new costs is
 * derived again. [wipe] on lock.
 */
public class DatabaseKey private constructor(private val composite: ByteArray) {
    private var derivedFor: ByteArray? = null
    private var derived: ByteArray? = null

    /** The derived key for [kdf] (a copy: the caller wipes it), and whether it had to be derived now. */
    internal fun transformed(kdf: KdfParameters): Pair<ByteArray, Boolean> = synchronized(this) {
        check(!wiped) { "the key was wiped" }
        val cached = derived
        if (cached != null && derivedFor.contentEquals(kdf.identity)) return cached.copyOf() to false
        val fresh = kdf.transform(composite)
        derived?.fill(0)
        derived = fresh
        derivedFor = kdf.identity.copyOf()
        fresh.copyOf() to true
    }

    private var wiped = false

    /** A copy with buffers of its own, the derived key included. */
    public fun copy(): DatabaseKey = synchronized(this) {
        check(!wiped) { "the key was wiped" }
        DatabaseKey(composite.copyOf()).also {
            it.derived = derived?.copyOf()
            it.derivedFor = derivedFor?.copyOf()
        }
    }

    public fun wipe(): Unit = synchronized(this) {
        composite.fill(0)
        derived?.fill(0)
        derived = null
        derivedFor = null
        wiped = true
    }

    public companion object {
        /** A password-only composite key: SHA-256 of the password's SHA-256. */
        public fun fromPassword(password: Secret): DatabaseKey {
            val inner = password.useBytes { MessageDigest.getInstance("SHA-256").digest(it) }
            try {
                return DatabaseKey(MessageDigest.getInstance("SHA-256").digest(inner))
            } finally {
                inner.fill(0)
            }
        }
    }
}

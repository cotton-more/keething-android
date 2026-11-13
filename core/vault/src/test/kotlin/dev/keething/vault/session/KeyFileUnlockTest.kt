package dev.keething.vault.session

import dev.keething.domain.secret.Secret
import dev.keething.domain.storage.DatabaseBackups
import dev.keething.domain.storage.DatabaseStore
import dev.keething.domain.vault.EntryChanges
import dev.keething.domain.vault.VaultEdit
import dev.keething.kdbx.key.DatabaseKey
import dev.keething.kdbx.key.KeyFile
import dev.keething.kdbx.key.WipedKeyException
import dev.keething.kdbx.reader.KdbxException
import dev.keething.kdbx.reader.KdbxReader
import dev.keething.vault.opening.OpenFailure
import dev.keething.vault.opening.OpenResult
import dev.keething.vault.opening.VaultOpener
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** An unlock with a key file (keething-spec kdbx.key-files AC7–AC10, unlock.key-file AC4; the Mac's `KeyFileTests`). */
class KeyFileUnlockTest {
    private class Fixture(scope: TestScope, val store: FakeStore, val keyFile: FakeKeyFile?, opener: VaultOpener = VaultOpener()) {
        val states = mutableListOf<SessionState>()
        val session = VaultSession(
            store = store,
            opener = opener,
            backups = DatabaseBackups { _, _, _ -> },
            clock = FakeClock(scope.testScheduler),
            scope = scope.backgroundScope,
            io = StandardTestDispatcher(scope.testScheduler),
            keyFile = { keyFile },
        )

        init {
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { session.state.collect { states += it } }
        }
    }

    private fun TestScope.fixture(database: ByteArray?, keyFile: String? = "keyx.keyx") =
        Fixture(this, FakeStore(database), keyFile?.let { FakeKeyFile(keyFileBytes(it)) })

    private fun keyfileDatabase(name: String = "keyfile-keyx") = keyFileBytes("$name.kdbx")

    private fun Fixture.unlock(typed: String) {
        val password = Secret.of(typed)
        assertNotNull(session.unlock(password))
        password.wipe() // as the lock screen does at once
    }

    private val Fixture.failure get() = ((session.state.value as SessionState.Locked).reason as LockReason.UnlockFailed).failure

    private fun key(password: String?, keyFile: String = "keyx.keyx") =
        DatabaseKey.of(password?.let(Secret::of), KeyFile.key(keyFileBytes(keyFile)))

    @Test
    fun theUnlockReadsTheKeyFileAndAReloadDoesNot() = runTest {
        val f = fixture(keyfileDatabase())

        f.unlock("pw")
        runCurrent()

        assertIs<SessionState.Unlocked>(f.session.state.value)
        assertEquals(1, f.keyFile!!.reads)
        assertTrue(f.keyFile.handedOut.single().all { it == 0.toByte() }, "the bytes read are wiped")
        val written = KdbxReader.openForEditing(f.store.content!!, key("pw")).write(key("pw")).file
        f.store.change(written, notify = false)
        f.session.reload()
        runCurrent()
        assertIs<SessionState.Unlocked>(f.session.state.value)
        assertEquals(2, f.store.reads, "read again with the kept key")
        assertEquals(1, f.keyFile.reads, "the key file is not read again")

        f.session.lock()
        f.unlock("pw")
        runCurrent()
        assertEquals(2, f.keyFile.reads, "but at every unlock")
    }

    @Test
    fun aKeyFilesKeyIsInMemoryOnlyWhileUnlocked() = runTest {
        // Every key the session tries, as the opener is handed it.
        val tried = mutableListOf<DatabaseKey>()
        val opener = object : VaultOpener() {
            override fun open(store: DatabaseStore, key: DatabaseKey): OpenResult = super.open(store, key).also { tried += key }
        }
        val f = Fixture(this, FakeStore(keyfileDatabase()), FakeKeyFile(keyFileBytes("keyx.keyx")), opener)

        f.unlock("typo")
        runCurrent()
        assertTrue(f.keyFile!!.handedOut.single().all { it == 0.toByte() }, "the bytes read are wiped")
        assertFailsWith<WipedKeyException>("a rejected key is wiped at once") { tried.single().copy() }

        f.unlock("pw")
        runCurrent()
        assertIs<SessionState.Unlocked>(f.session.state.value)
        assertTrue(f.keyFile.handedOut.all { bytes -> bytes.all { it == 0.toByte() } }, "the bytes read are wiped")
        tried.last().copy().wipe() // the session's key, usable while unlocked

        f.session.lock()
        assertFailsWith<WipedKeyException>("the lock wipes it") { tried.last().copy() }
    }

    @Test
    fun aMissingOrUnusableKeyFileFailsBeforeTheDatabaseIsRead() = runTest {
        val missing = fixture(keyfileDatabase())
        missing.keyFile!!.content = null
        missing.unlock("pw")
        runCurrent()
        assertEquals(OpenFailure.KeyFileMissing("work.keyx"), missing.failure)
        assertEquals(0, missing.store.reads)

        val denied = fixture(keyfileDatabase())
        denied.keyFile!!.denied = true
        denied.unlock("pw")
        runCurrent()
        assertEquals(OpenFailure.KeyFileUnusable("Keething may not read work.keyx any more: choose it again"), denied.failure)
        assertEquals(0, denied.store.reads)

        val failing = Fixture(this, FakeStore(keyfileDatabase()), object : FakeKeyFile(null) {
            override fun read() = throw java.io.IOException("I/O error")
        })
        failing.unlock("pw")
        runCurrent()
        assertEquals(OpenFailure.KeyFileUnusable("work.keyx could not be read"), failing.failure, "not stuck in Unlocking")

        val broken = fixture(keyfileDatabase())
        broken.keyFile!!.content = keyFileBytes("keyx.keyx").decodeToString().replace("38D4B997", "00000000").encodeToByteArray()
        broken.unlock("pw")
        runCurrent()
        val failure = assertIs<OpenFailure.KeyFileUnusable>(broken.failure)
        assertEquals("a key file whose key does not match its checksum (changed or damaged)", failure.why)
        assertEquals(0, broken.store.reads)

        val empty = fixture(keyfileDatabase())
        empty.keyFile!!.content = ByteArray(0)
        empty.unlock("")
        runCurrent()
        assertEquals(OpenFailure.KeyFileUnusable("the file is empty"), empty.failure)
    }

    @Test
    fun aRejectedKeyIsCalledByWhatWasGiven() = runTest {
        val passwordAlone = fixture(keyfileDatabase(), keyFile = null)
        passwordAlone.unlock("pw")
        runCurrent()
        assertEquals(OpenFailure.WrongPassword, passwordAlone.failure, "the password alone: it may need a key file")

        val both = fixture(keyfileDatabase())
        both.unlock("typo")
        runCurrent()
        assertEquals(OpenFailure.WrongPasswordOrKeyFile, both.failure)

        val other = Fixture(this, FakeStore(keyfileDatabase()), FakeKeyFile(keyFileBytes("raw32.key")))
        other.unlock("")
        runCurrent()
        assertEquals(OpenFailure.WrongKeyFile, other.failure, "nothing typed, both tries rejected: said for the key file")
        assertEquals(2, other.store.reads, "the key file alone, then the empty password with it")
        assertEquals(1, other.keyFile!!.reads, "the key file read once for both")
        assertEquals(listOf(SessionState.Unlocking), other.states.drop(1).dropLast(1), "one opening")
        assertTrue(listOf(OpenFailure.WrongPassword, OpenFailure.WrongPasswordOrKeyFile, OpenFailure.WrongKeyFile).all { it.isWrongKey })
    }

    @Test
    fun nothingTypedOpensWithTheKeyFileAloneWithoutASecondTry() = runTest {
        val f = fixture(keyfileDatabase("keyfile-only"))

        f.unlock("")
        runCurrent()

        assertIs<SessionState.Unlocked>(f.session.state.value)
        assertEquals(1, f.store.reads)
    }

    @Test
    fun nothingTypedThenTriesTheEmptyPasswordWithTheKeyFileInTheSameOpening() = runTest {
        val emptyPassword = KdbxReader.openForEditing(keyfileDatabase(), key("pw")).write(key("")).file
        val f = fixture(emptyPassword)

        f.unlock("")
        runCurrent()

        assertIs<SessionState.Unlocked>(f.session.state.value)
        assertEquals(2, f.store.reads)
        assertEquals(listOf(SessionState.Unlocking), f.states.drop(1).dropLast(1), "no failure shown between the two")
        assertIs<SessionState.Locked>(f.states.first())
    }

    @Test
    fun aFailureThatIsNotAWrongKeyIsNotTriedTwice() = runTest {
        val f = fixture(null)

        f.unlock("")
        runCurrent()

        assertEquals(OpenFailure.Missing, f.failure)
        assertEquals(1, f.store.reads)
    }

    @Test
    fun aLockDuringTheUnlockEndsItInterrupted() = runTest {
        val f = fixture(keyfileDatabase())
        val outcome = assertNotNull(f.session.unlock(Secret.empty()))

        f.session.lock()
        runCurrent()

        assertEquals(UnlockOutcome.Interrupted, outcome.await())
        assertEquals(SessionState.Locked(LockReason.Manual), f.session.state.value)
        assertEquals(0, f.store.reads, "nothing read after the lock")
    }

    @Test
    fun aLockBetweenTheTwoTriesEndsTheOpening() = runTest {
        val emptyPassword = KdbxReader.openForEditing(keyfileDatabase(), key("pw")).write(key("")).file
        val f = fixture(emptyPassword)
        f.store.beforeRead = { if (it == 1) f.session.lock() } // during the first try, which the database rejects
        val outcome = assertNotNull(f.session.unlock(Secret.empty()))

        runCurrent()

        assertEquals(UnlockOutcome.Interrupted, outcome.await())
        assertEquals(SessionState.Locked(LockReason.Manual), f.session.state.value)
        assertEquals(1, f.store.reads, "the second key is not tried")
    }

    @Test
    fun aKeyFileOfExactly32BytesOpensThroughTheSessionWhichWipesTheBytesItRead() = runTest {
        val f = Fixture(this, FakeStore(keyfileDatabase("keyfile-raw32")), FakeKeyFile(keyFileBytes("raw32.key")))

        f.unlock("pw")
        runCurrent()

        assertIs<SessionState.Unlocked>(f.session.state.value)
        assertTrue(f.keyFile!!.handedOut.single().all { it == 0.toByte() })
    }

    @Test
    fun aReloadRejectedWithAKeyFileLocksAsAChangedPassword() = runTest {
        val f = fixture(keyfileDatabase())
        f.unlock("pw")
        runCurrent()
        f.store.change(KdbxReader.openForEditing(keyfileDatabase(), key("pw")).write(key("other")).file, notify = false)

        f.session.reload()
        runCurrent()

        assertEquals(SessionState.Locked(LockReason.PasswordChanged), f.session.state.value)
    }

    @Test
    fun aSaveIntoAKeyFileDatabaseOpensWithTheSameKey() = runTest {
        for ((database, password) in listOf("keyfile-keyx" to "pw", "keyfile-only" to null)) {
            val f = fixture(keyfileDatabase(database))
            f.unlock(password ?: "")
            runCurrent()
            val entry = (f.session.state.value as SessionState.Unlocked).vault.entries.first()

            val outcome = assertNotNull(f.session.save(VaultEdit.Update(entry.id, entry.lastModified, EntryChanges(notes = "Saved with a key file"))))
            runCurrent()

            assertEquals(SaveOutcome.Saved(), outcome.await(), database)
            val saved = KdbxReader.open(f.store.content!!, key(password))
            assertTrue(saved.document.entries.isNotEmpty(), database)
            assertEquals(1, f.keyFile!!.reads, "the save uses the kept key")
            if (password != null) {
                assertFailsWith<KdbxException.WrongPassword> { KdbxReader.open(f.store.content!!, DatabaseKey.of(Secret.of(password))) }
            }
        }
    }
}

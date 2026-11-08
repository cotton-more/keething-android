package dev.keething.vault.session

import dev.keething.domain.secret.Secret
import dev.keething.domain.storage.DatabaseBackups
import dev.keething.domain.vault.EntryChanges
import dev.keething.domain.vault.VaultEdit
import dev.keething.kdbx.key.DatabaseKey
import dev.keething.kdbx.key.KeyFile
import dev.keething.kdbx.key.WipedKeyException
import dev.keething.kdbx.reader.KdbxException
import dev.keething.kdbx.reader.KdbxReader
import dev.keething.vault.opening.OpenFailure
import dev.keething.vault.opening.VaultOpener
import dev.keething.vault.saving.SaveFailure
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** Change master key… in the session (keething-spec databases.change-master-key AC1, AC4, AC5, AC7). */
class MasterKeyChangeTest {
    private class Fixture(scope: TestScope, val store: FakeStore, val keyFile: FakeKeyFile? = null) {
        val backups = mutableListOf<ByteArray>()
        val session = VaultSession(
            store = store,
            opener = VaultOpener(),
            backups = DatabaseBackups { database, _, _ -> backups += database },
            clock = FakeClock(scope.testScheduler),
            scope = scope.backgroundScope,
            io = StandardTestDispatcher(scope.testScheduler),
            keyFile = { keyFile },
        )
    }

    private fun TestScope.unlocked(store: FakeStore, typed: String = "pw", keyFile: FakeKeyFile? = null): Fixture =
        Fixture(this, store, keyFile).also {
            assertNotNull(it.session.unlock(Secret.of(typed)))
            runCurrent()
            assertIs<SessionState.Unlocked>(it.session.state.value)
        }

    private suspend fun TestScope.confirm(f: Fixture, typed: String): KeyConfirmation {
        val result = backgroundScope.async { f.session.confirmKey(Secret.of(typed)) }
        runCurrent()
        return result.await()
    }

    private fun opens(file: ByteArray, password: String?, keyFile: ByteArray? = null): Boolean = try {
        KdbxReader.open(file, DatabaseKey.of(password?.let(Secret::of), keyFile?.let(KeyFile::key)))
        true
    } catch (_: KdbxException) {
        false
    }

    // spec: databases.change-master-key/AC1
    @Test
    fun theKeyItIsOpenWithIsConfirmedAndAnotherIsNot() = runTest {
        val f = unlocked(FakeStore(fixtureBytes("kdbx4-aes-aeskdf")))

        assertEquals(KeyConfirmation.Confirmed(withPassword = true), confirm(f, "pw"))
        assertEquals(KeyConfirmation.Wrong, confirm(f, "pw "))
        assertEquals(KeyConfirmation.Wrong, confirm(f, ""))
        f.session.lock()
        assertEquals(KeyConfirmation.Locked, confirm(f, "pw"))
    }

    @Test
    fun withAKeyFileTheKeyFileIsReadAgainAndAloneItIsConfirmedWithoutAPassword() = runTest {
        val keyx = keyFileBytes("keyx.keyx")
        val withPassword = unlocked(FakeStore(keyFileBytes("keyfile-keyx.kdbx")), keyFile = FakeKeyFile(keyx))
        assertEquals(KeyConfirmation.Confirmed(withPassword = true), confirm(withPassword, "pw"))
        assertEquals(KeyConfirmation.Wrong, confirm(withPassword, ""))
        withPassword.keyFile!!.content = null
        assertEquals(KeyConfirmation.Failed(OpenFailure.KeyFileMissing("work.keyx")), confirm(withPassword, "pw"))

        val alone = unlocked(FakeStore(keyFileBytes("keyfile-only.kdbx")), typed = "", keyFile = FakeKeyFile(keyx))
        assertEquals(KeyConfirmation.Confirmed(withPassword = false), confirm(alone, ""))
        assertEquals(KeyConfirmation.Wrong, confirm(alone, "pw"))
    }

    // spec: databases.change-master-key/AC4
    @Test
    fun theFileIsWrittenWithTheNewKeyBackedUpAndTheSessionGoesOnWithIt() = runTest {
        val original = fixtureBytes("kdbx4-aes-aeskdf")
        val f = unlocked(FakeStore(original))
        val entry = (f.session.state.value as SessionState.Unlocked).vault.entries.first()

        val outcome = assertNotNull(f.session.changeKey(DatabaseKey.of(Secret.of("a new password"))))
        runCurrent()

        assertIs<SaveOutcome.Saved>(outcome.await())
        assertEquals(true, opens(f.store.content!!, "a new password"))
        assertEquals(false, opens(f.store.content!!, "pw"))
        assertContentEquals(original, f.backups.single(), "the file it replaced is backed up")
        assertIs<SessionState.Unlocked>(f.session.state.value)
        // The next change is written with the new key.
        val next = assertNotNull(f.session.save(VaultEdit.Update(entry.id, entry.lastModified, EntryChanges(notes = "after"))))
        runCurrent()
        assertIs<SaveOutcome.Saved>(next.await())
        assertEquals(true, opens(f.store.content!!, "a new password"))
        assertEquals(KeyConfirmation.Confirmed(withPassword = true), confirm(f, "a new password"))
    }

    // spec: databases.change-master-key/AC5
    @Test
    fun aLockBeforeItIsWrittenLeavesTheFileOpeningWithTheOldKey() = runTest {
        val original = fixtureBytes("kdbx4-aes-aeskdf")
        val f = unlocked(FakeStore(original))

        val outcome = assertNotNull(f.session.changeKey(DatabaseKey.of(Secret.of("new"))))
        f.session.lock()
        runCurrent()

        assertEquals(SaveOutcome.Interrupted, outcome.await())
        assertContentEquals(original, f.store.content)
        assertEquals(0, f.store.replaced)
    }

    // spec: databases.change-master-key/AC5
    @Test
    fun aFileTheOldKeyNoLongerOpensIsNotChangedAndAViewOnlyOneIsRefused() = runTest {
        val f = unlocked(FakeStore(fixtureBytes("kdbx4-aes-aeskdf")))
        val elsewhere = KdbxReader.openForEditing(fixtureBytes("kdbx4-aes-aeskdf"), DatabaseKey.of(Secret.of("pw")))
            .write(DatabaseKey.of(Secret.of("changed elsewhere"))).file
        f.store.change(elsewhere, notify = false)

        val outcome = assertNotNull(f.session.changeKey(DatabaseKey.of(Secret.of("new"))))
        runCurrent()

        assertEquals(SaveOutcome.Failed(SaveFailure.CannotOpen(OpenFailure.WrongPassword)), outcome.await())
        assertContentEquals(elsewhere, f.store.content)
        assertEquals(SessionState.Locked(LockReason.PasswordChanged), f.session.state.value)
    }

    // spec: databases.change-master-key/AC7
    @Test
    fun anotherDeviceStillOnTheOldKeyLocksAtItsNextReloadAsPasswordChanged() = runTest {
        val store = FakeStore(fixtureBytes("kdbx4-aes-aeskdf"))
        val phone = unlocked(store)
        val other = unlocked(store)

        val outcome = assertNotNull(phone.session.changeKey(DatabaseKey.of(Secret.of("new"))))
        runCurrent()
        assertIs<SaveOutcome.Saved>(outcome.await())
        advanceTimeBy(2.seconds)
        runCurrent()

        assertIs<SessionState.Unlocked>(phone.session.state.value, "its own change does not lock it")
        assertEquals(SessionState.Locked(LockReason.PasswordChanged), other.session.state.value)
    }

    @Test
    fun theNewKeyCannotBeUsedOnceGivenToALockedSession() = runTest {
        val f = Fixture(this, FakeStore(fixtureBytes("kdbx4-aes-aeskdf")))
        val newKey = DatabaseKey.of(Secret.of("new"))

        assertEquals(null, f.session.changeKey(newKey))
        assertFailsWith<WipedKeyException> { newKey.copy() }
    }
}

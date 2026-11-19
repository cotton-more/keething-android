package dev.keething.android.databases

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import dev.keething.android.databases.ChangeMasterKeyModel.KeyFileChoice
import dev.keething.android.databases.ChangeMasterKeyModel.PasswordChoice
import dev.keething.android.databases.ChangeMasterKeyModel.Step
import dev.keething.android.ui.english
import dev.keething.domain.secret.Secret
import dev.keething.domain.storage.StoragePlace
import dev.keething.vault.creating.CreateFailure
import dev.keething.vault.databases.CurrentDatabase
import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.databases.FileLocation
import dev.keething.vault.opening.OpenFailure
import dev.keething.vault.saving.SaveFailure
import dev.keething.vault.session.KeyConfirmation
import dev.keething.vault.session.SaveOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/** Change master key…'s form (keething-spec databases.change-master-key AC1–AC3, AC5, AC6). */
class ChangeMasterKeyModelTest {
    private class FakeKeyFiles : KeyChangeFiles {
        val made = mutableListOf<String>()
        val removed = mutableListOf<String>()
        var failure: CreateFailure? = null

        override fun createKeyFile(path: String): Secret {
            failure?.let { throw it }
            made += path
            return Secret.copyOf(ByteArray(32) { 0x5A })
        }

        override fun removeKeyFile(path: String) {
            removed += path
        }

        override fun readKeyFile(at: FileLocation): Secret = Secret.copyOf(ByteArray(32) { 0x33 })

        override fun location(path: String): FileLocation = FileLocation.Granted("grant", StoragePlace.ofPath(path)!!)
    }

    private class FakeLook(val there: MutableSet<String>) : NewDatabaseFiles {
        override fun exists(path: String) = path in there

        override fun isFolder(path: String) = path in there

        override fun syncs(folder: String) = false
    }

    /** What the change was asked: the password as text, the key file and whether its key came. */
    private data class Asked(val password: String?, val keyFile: FileLocation?, val keyFileKey: Boolean)

    private class Fixture(scope: TestScope, keyFile: Boolean = false, val confirmation: KeyConfirmation = KeyConfirmation.Confirmed(withPassword = true)) {
        val files = FakeKeyFiles()
        val look = FakeLook(mutableSetOf("$ROOT/Documents", "$ROOT/Keys"))
        val asked = mutableListOf<Asked>()
        var outcome: SaveOutcome = SaveOutcome.Saved()
        val current = FileLocation.InFolder("grant-keys", StoragePlace("primary", "Keys"), "Work.keyx")
        val model = ChangeMasterKeyModel(
            id = DatabaseId("work"),
            label = "Work",
            fileText = "Sync/KeePass/Work.kdbx",
            fileName = "Work.kdbx",
            databaseFolder = "$ROOT/Sync/KeePass",
            currentKeyFile = if (keyFile) ChangeMasterKeyModel.CurrentKeyFile("Work.keyx", "Keys", current) else null,
            // Its key: "pw", or nothing typed for one that opens with its key file alone.
            confirm = { typed ->
                val key = if ((confirmation as? KeyConfirmation.Confirmed)?.withPassword == false) "" else "pw"
                if (typed.reveal() == key || confirmation !is KeyConfirmation.Confirmed) confirmation else KeyConfirmation.Wrong
            },
            change = { password, location, key ->
                asked += Asked(password?.reveal(), location, key != null)
                CompletableDeferred(CurrentDatabase.KeyChanged(outcome, null))
            },
            files = files,
            look = look,
            root = ROOT,
            scope = scope,
            io = StandardTestDispatcher(scope.testScheduler),
        )

        fun confirmed() {
            model.current.setTextAndPlaceCursorAtEnd("pw")
            model.confirmCurrent()
        }

        fun typeNew(password: String, again: String = password) {
            model.fields.password.setTextAndPlaceCursorAtEnd(password)
            model.fields.again.setTextAndPlaceCursorAtEnd(again)
        }
    }

    // spec: databases.change-master-key/AC1
    @Test
    fun theCurrentKeyComesFirstAndAWrongOneOffersNothingMore() = runTest {
        val f = Fixture(this)

        f.model.current.setTextAndPlaceCursorAtEnd("nope")
        f.model.confirmCurrent()
        advanceUntilIdle()
        assertEquals(Step.CURRENT, f.model.step)
        assertEquals("That is not its master password. Nothing changed.", f.model.problem?.english())
        assertNull(f.model.changeKey(), "nothing more offered")

        f.model.current.setTextAndPlaceCursorAtEnd("pw")
        f.model.confirmCurrent()
        advanceUntilIdle()
        assertEquals(Step.NEW, f.model.step)
        assertEquals("", f.model.current.text.toString(), "what was typed goes")
        assertNull(f.model.problem)
    }

    @Test
    fun aKeyFileThatCannotBeReadIsSaidAtTheFirstStep() = runTest {
        val f = Fixture(this, keyFile = true, confirmation = KeyConfirmation.Failed(OpenFailure.KeyFileMissing("Work.keyx")))

        f.confirmed()
        advanceUntilIdle()

        assertEquals(Step.CURRENT, f.model.step)
        assertNotNull(f.model.problem)
    }

    // spec: databases.change-master-key/AC2
    @Test
    fun aNewPasswordIsTypedTwiceAndMustMatch() = runTest {
        val f = Fixture(this)
        f.confirmed()
        advanceUntilIdle()

        assertEquals(PasswordChoice.NEW, f.model.passwordChoice)
        assertEquals("Type a master password.", f.model.blockers.first().english())
        f.typeNew("correct horse", "correct hors")
        assertEquals("The two passwords differ.", f.model.blockers.first().english())
        f.typeNew("1234")
        assertTrue(f.model.canChange, "a weak one is allowed")
        assertEquals(MasterPasswordFields.Match.MATCHES, f.model.fields.match)

        f.model.changeKey()
        advanceUntilIdle()

        assertEquals(listOf(Asked("1234", null, false)), f.asked)
        assertEquals(Step.DONE, f.model.step)
        assertEquals("", f.model.fields.password.text.toString())
    }

    // spec: databases.change-master-key/AC3
    @Test
    fun nothingChangedIsNothingToSaveAndNoPasswordNeedsAKeyFile() = runTest {
        val f = Fixture(this)
        f.confirmed()
        advanceUntilIdle()

        f.model.choosePassword(PasswordChoice.KEEP)
        assertEquals(listOf("Choose a new password or another key file."), f.model.blockers.map { it.english() })
        assertFalse(f.model.noneAllowed)
        f.model.choosePassword(PasswordChoice.NONE)
        assertEquals(PasswordChoice.KEEP, f.model.passwordChoice, "no password and no key file is no key")

        f.model.chooseKeyFile(KeyFileChoice.New("$ROOT/Keys"))
        assertTrue(f.model.noneAllowed)
        f.model.choosePassword(PasswordChoice.NONE)
        assertTrue(f.model.canChange)
        f.model.chooseKeyFile(KeyFileChoice.None)
        assertEquals(PasswordChoice.NEW, f.model.passwordChoice, "the key file gone, a password is asked for")
    }

    // spec: databases.change-master-key/AC3
    @Test
    fun aNewKeyFileIsMadeUnderAFreeNameAndKeptWithThePasswordAsItIs() = runTest {
        val f = Fixture(this)
        f.look.there += "$ROOT/Keys/Work.keyx"
        f.confirmed()
        advanceUntilIdle()

        f.model.choosePassword(PasswordChoice.KEEP)
        f.model.chooseKeyFile(KeyFileChoice.New("$ROOT/Keys"))
        assertEquals("$ROOT/Keys/Work 2.keyx", f.model.newKeyFilePath, "a key file is never replaced")
        f.model.changeKey()
        advanceUntilIdle()

        assertEquals(listOf("$ROOT/Keys/Work 2.keyx"), f.files.made)
        val location = FileLocation.Granted("grant", StoragePlace.ofPath("$ROOT/Keys/Work 2.keyx")!!)
        assertEquals(listOf(Asked("pw", location, true)), f.asked, "the password kept is the one typed first")
        assertEquals("Work 2.keyx", f.model.keyFileAfter)
        assertTrue(f.model.passwordAfter)
    }

    @Test
    fun aKeyFileAloneIsKeptWithoutAPasswordAndItsKeyFileReadAgain() = runTest {
        val f = Fixture(this, keyFile = true, confirmation = KeyConfirmation.Confirmed(withPassword = false))
        f.model.confirmCurrent()
        advanceUntilIdle()

        assertEquals(PasswordChoice.KEEP, f.model.passwordChoice, "it had no password")
        assertTrue(f.model.keepsNoPassword)
        f.typeNew("now one")
        f.model.choosePassword(PasswordChoice.NEW)
        f.model.changeKey()
        advanceUntilIdle()

        assertEquals(listOf(Asked("now one", f.current, true)), f.asked)
    }

    // spec: databases.change-master-key/AC5
    @Test
    fun aChangeThatFailsRemovesTheKeyFileMadeForItAndSaysWhy() = runTest {
        val f = Fixture(this)
        f.outcome = SaveOutcome.Failed(SaveFailure.FileKeepsChanging)
        f.confirmed()
        advanceUntilIdle()
        f.model.choosePassword(PasswordChoice.KEEP)
        f.model.chooseKeyFile(KeyFileChoice.New("$ROOT/Keys"))

        f.model.changeKey()
        advanceUntilIdle()

        assertEquals(listOf("$ROOT/Keys/Work.keyx"), f.files.removed)
        assertEquals(Step.NEW, f.model.step)
        assertNotNull(f.model.problem)
        assertFalse(f.model.changing)
    }

    // spec: databases.change-master-key/AC6
    @Test
    fun aChangeSavedWhileTheDatabaseLockedKeepsItsNewKeyFileAndSaysItLocked() = runTest {
        val f = Fixture(this)
        f.outcome = SaveOutcome.Saved(lockedMeanwhile = true)
        f.confirmed()
        advanceUntilIdle()
        f.model.choosePassword(PasswordChoice.KEEP)
        f.model.chooseKeyFile(KeyFileChoice.New("$ROOT/Keys"))

        f.model.changeKey()
        advanceUntilIdle()

        assertEquals(Step.DONE, f.model.step)
        assertEquals(listOf("$ROOT/Keys/Work.keyx"), f.files.made)
        assertTrue(f.files.removed.isEmpty(), "the file needs it now")
        assertEquals("Work.keyx", f.model.keyFileAfter)
        assertEquals("It locked while the change was written; the change is in the file. Unlock it with the new key.", f.model.lockedMeanwhile?.english())
    }

    // spec: databases.change-master-key/AC6
    @Test
    fun aFormThatEndedSayingTheDatabaseLockedStaysThroughTheNextLockUntilDone() = runTest {
        val f = Fixture(this)
        f.outcome = SaveOutcome.Saved(lockedMeanwhile = true)
        val changes = MasterKeyChanges { f.model }
        changes.open(DatabaseId("work"))
        f.confirmed()
        advanceUntilIdle()
        f.typeNew("a new one")
        f.model.changeKey()
        advanceUntilIdle()

        changes.locked()
        assertEquals(f.model, changes.form, "nothing typed is left in it, and it says what happened")
        changes.close()
        assertNull(changes.form)
    }

    // spec: databases.change-master-key/AC5
    @Test
    fun aLockBeforeTheFileIsReplacedRemovesTheKeyFileMadeForIt() = runTest {
        val f = Fixture(this)
        f.outcome = SaveOutcome.Interrupted
        f.confirmed()
        advanceUntilIdle()
        f.model.choosePassword(PasswordChoice.KEEP)
        f.model.chooseKeyFile(KeyFileChoice.New("$ROOT/Keys"))

        f.model.changeKey()
        advanceUntilIdle()

        assertEquals(Step.NEW, f.model.step)
        assertEquals(listOf("$ROOT/Keys/Work.keyx"), f.files.removed)
        assertEquals("The database locked before its master key was changed. Nothing changed.", f.model.problem?.english())
    }

    @Test
    fun aKeyFileThatCannotBeMadeChangesNothing() = runTest {
        val f = Fixture(this)
        f.files.failure = CreateFailure.FolderNotFound()
        f.confirmed()
        advanceUntilIdle()
        f.model.choosePassword(PasswordChoice.KEEP)
        f.model.chooseKeyFile(KeyFileChoice.New("$ROOT/Keys"))

        f.model.changeKey()
        advanceUntilIdle()

        assertTrue(f.asked.isEmpty())
        assertEquals("The key file's folder is not there any more. Nothing changed.", f.model.problem?.english())
    }

    private companion object {
        const val ROOT = "/storage/emulated/0"
    }
}

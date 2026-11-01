package dev.keething.android.quicknote

import dev.keething.android.quicknote.QuickNoteModel.Showing
import dev.keething.domain.secret.Secret
import dev.keething.domain.storage.ConflictCopy
import dev.keething.domain.storage.DatabaseRead
import dev.keething.domain.storage.DatabaseStore
import dev.keething.domain.storage.FileVersion
import dev.keething.domain.time.Clock
import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.opening.OpenFailure
import dev.keething.vault.opening.VaultOpener
import dev.keething.vault.session.LockReason
import dev.keething.vault.session.SessionState
import dev.keething.vault.session.VaultSession
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The quick note's screen on a real session (SPEC §6.4; keething-spec app.quick-note AC4): the fixture database's lock
 * screen, a wrong password and the right one typed on it, then a lock while the editor is open.
 */
class QuickNoteSessionsTest {
    /** The fixture database, read only. */
    private class FixtureStore(private val bytes: ByteArray) : DatabaseStore {
        private val version = FileVersion(bytes.size.toLong(), Instant.EPOCH, null)
        override val fileName: String = "Personal.kdbx"

        override fun read(): DatabaseRead = DatabaseRead.Found(bytes.copyOf(), version)

        override fun version(): FileVersion = version

        override fun replace(bytes: ByteArray): Unit = throw IOException("read only")

        override fun conflictCopies(): List<ConflictCopy> = emptyList()

        override fun watch(onChange: () -> Unit): AutoCloseable = AutoCloseable {}
    }

    private fun TestScope.session(): VaultSession {
        val bytes = File(System.getProperty("keething.fixtures"), "kdbx4-aes-aeskdf.kdbx").readBytes()
        val clock = object : Clock {
            override fun now(): Instant = Instant.EPOCH

            override fun elapsed(): Duration = testScheduler.currentTime.milliseconds
        }
        return VaultSession(FixtureStore(bytes), VaultOpener(), { _, _, _ -> }, clock, backgroundScope, StandardTestDispatcher(testScheduler))
    }

    private fun VaultSession.type(password: String) {
        val typed = Secret.of(password)
        unlock(typed)
        typed.wipe()
    }

    @Test
    fun unlockedOnItsLockScreenAfterAWrongPasswordThenLockedWhileTheEditorIsOpen() = runTest {
        val session = session()
        val personal = DatabaseId("P")
        val model = QuickNoteModel(
            target = { personal },
            isOpen = { session.state.value is SessionState.Unlocked },
            state = { session.state },
            intoWindow = {},
            started = MutableStateFlow(0),
            locks = MutableStateFlow(0),
            scope = backgroundScope,
        )
        model.start(recreated = false) { "Dune\nArrival" }
        runCurrent()
        assertEquals(Showing.Unlock(personal), model.showing.value)

        session.type("wrong")
        runCurrent()
        assertEquals(SessionState.Locked(LockReason.UnlockFailed(OpenFailure.WrongPassword)), session.state.value)
        assertEquals(Showing.Unlock(personal), model.showing.value, "the wrong password: the lock screen stays")

        session.type("pw")
        runCurrent()
        assertIs<SessionState.Unlocked>(session.state.value)
        assertEquals("Dune\nArrival", assertIs<Showing.Editor>(model.showing.value).note.text)

        session.lock(LockReason.Manual)
        runCurrent()
        assertEquals(Showing.Gone(null), model.showing.value, "a lock while the editor is open")
    }
}

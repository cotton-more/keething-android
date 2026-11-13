package dev.keething.vault.biometric

import dev.keething.domain.secret.Secret
import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.databases.DatabaseList
import dev.keething.vault.databases.DatabaseRecord
import dev.keething.vault.databases.DatabaseSession
import dev.keething.vault.databases.SessionFactory
import dev.keething.vault.databases.VaultSessions
import dev.keething.vault.databases.add
import dev.keething.vault.opening.VaultOpener
import dev.keething.vault.session.FakeClock
import dev.keething.vault.session.FakeStore
import dev.keething.vault.session.LockReason
import dev.keething.vault.session.SessionState
import dev.keething.vault.session.VaultSession
import dev.keething.vault.session.fixtureBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** Unlocks for a quick task and the reminder (keething-spec unlock.reminder-not-a-lock AC5). */
class QuickTasksTest {
    private class Fixture(private val scope: TestScope) {
        val settings = MapSettings()
        val clock = FakeClock(scope.testScheduler)
        private var ids = 0
        val list = DatabaseList(settings, makeId = { DatabaseId("db-${++ids}") })
        val stores = FakePasswordStores()
        private val io = StandardTestDispatcher(scope.testScheduler)
        val setup = BiometricSetup(stores, list, settings, clock, io)

        private val factory = SessionFactory { record, number, _, watching ->
            val store = FakeStore(fixtureBytes("kdbx4-aes-aeskdf"))
            val session = VaultSession(
                store = store,
                opener = VaultOpener(),
                backups = { _, _, _ -> },
                clock = clock,
                scope = scope.backgroundScope,
                io = io,
                keyFile = { null },
            )
            val biometric = BiometricUnlock(record.id, session, setup, list, scope.backgroundScope, watching, fileExists = { true })
            DatabaseSession(number, record.id, record.file, store, session, biometric)
        }

        val sessions: VaultSessions by lazy { VaultSessions(list, scope.backgroundScope, factory, clock, lockTimeout = { 5.minutes }, now = clock::now) }
        val tasks: QuickTasks by lazy { QuickTasks(sessions) }

        /** A database of the window that opens with "pw", its fingerprint on and its password stored, typed [daysAgo]. */
        fun add(path: String, daysAgo: Long): DatabaseRecord {
            val record = list.add(path, useBiometric = true)
            list.setInWindow(record.id, true)
            stores.stored[record.id.fileName] = Secret.of("pw")
            settings.setString(BiometricSetup.lastTypedKey(record.id), clock.now().minusMillis(daysAgo.days.inWholeMilliseconds).toEpochMilli().toString())
            return record
        }

        fun database(record: DatabaseRecord): DatabaseSession = assertNotNull(sessions.session(record.id))

        fun state(record: DatabaseRecord): SessionState = database(record).session.state.value
    }

    private suspend fun TestScope.fixture(): Fixture = Fixture(this).also {
        it.setup.setEnabled(true)
        it.sessions
    }

    private suspend fun TestScope.openWithFingerprint(f: Fixture, record: DatabaseRecord) {
        val database = f.database(record)
        database.biometric.refresh()
        assertEquals(FingerprintOutcome.Unlocked, database.biometric.unlockWithFingerprint())
        runCurrent()
    }

    @Test
    fun aDatabaseAQuickTaskOpenedWithItsReminderDueAsksNothingAndLocksAgainWhenTheTaskIsDone() = runTest {
        // spec: unlock.reminder-not-a-lock/AC5
        val f = fixture()
        val due = f.add("/db/Due.kdbx", daysAgo = 15)
        val fresh = f.add("/db/Fresh.kdbx", daysAgo = 1)

        f.tasks.begin()
        openWithFingerprint(f, due)
        openWithFingerprint(f, fresh)

        assertEquals(setOf(due.id, fresh.id), f.tasks.found.value, "their questions wait while the task lasts")
        assertTrue(f.database(due).biometric.state.value.reminder, "the question stands, for the next ordinary opening")

        f.tasks.end()
        runCurrent()

        assertEquals(SessionState.Locked(LockReason.AfterQuickTask), f.state(due), "locked again, nothing said")
        assertIs<SessionState.Unlocked>(f.state(fresh), "not due: it stays open")
        assertEquals(emptySet(), f.tasks.found.value)

        openWithFingerprint(f, due)
        assertTrue(f.database(due).biometric.state.value.reminder, "the next ordinary opening asks")
    }

    @Test
    fun aDatabaseOpenBeforeTheTaskIsLeftAsItIs() = runTest {
        // spec: unlock.reminder-not-a-lock/AC5
        val f = fixture()
        val due = f.add("/db/Due.kdbx", daysAgo = 15)
        openWithFingerprint(f, due)
        assertTrue(f.database(due).biometric.state.value.reminder)

        f.tasks.begin()
        f.tasks.end()
        runCurrent()

        assertEquals(emptySet(), f.tasks.found.value)
        assertIs<SessionState.Unlocked>(f.state(due), "opened by the user: the list asks it")
    }
}

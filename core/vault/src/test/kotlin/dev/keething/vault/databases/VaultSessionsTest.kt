package dev.keething.vault.databases

import dev.keething.domain.secret.Secret
import dev.keething.domain.vault.Vault
import dev.keething.domain.vault.VaultEdit
import dev.keething.kdbx.key.DatabaseKey
import dev.keething.kdbx.reader.KdbxReader
import dev.keething.vault.biometric.BiometricSetup
import dev.keething.vault.biometric.BiometricUnlock
import dev.keething.vault.biometric.FakePasswordStores
import dev.keething.vault.biometric.MapSettings
import dev.keething.vault.opening.DatabaseSize
import dev.keething.vault.opening.OpenFailure
import dev.keething.vault.opening.VaultOpener
import dev.keething.vault.session.FakeClock
import dev.keething.vault.session.FakeStore
import dev.keething.vault.session.LockReason
import dev.keething.vault.session.OpenSize
import dev.keething.vault.saving.SaveFailure
import dev.keething.vault.session.SaveRoom
import dev.keething.vault.session.SaveOutcome
import dev.keething.vault.session.SessionState
import dev.keething.vault.session.VaultSession
import dev.keething.vault.session.fixtureBytes
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The coordinator of the window's databases (plan AD8; the Mac's D5 `VaultSessionsTests`): a session each, the app's
 * state over them, lock all, one or close, the window keeping the current database, one background timer for the app.
 */
class VaultSessionsTest {
    /** Holds the work dispatched to it until [release]: a database whose key derivation takes its time. */
    private class Held(private val inner: CoroutineDispatcher) : CoroutineDispatcher() {
        private var held = true
        private val waiting = mutableListOf<Pair<CoroutineContext, Runnable>>()
        val isWaiting: Boolean get() = waiting.isNotEmpty()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (held) waiting += context to block else inner.dispatch(context, block)
        }

        fun release() {
            held = false
            waiting.forEach { (context, block) -> inner.dispatch(context, block) }
            waiting.clear()
        }
    }

    private class Fixture(private val scope: TestScope, val settings: MapSettings = MapSettings()) {
        val list = DatabaseList(settings, makeId = { DatabaseId("db-${++ids}") })
        val clock = FakeClock(scope.testScheduler)
        val files = mutableMapOf<String, FakeStore>()

        /** A database's own file work, by path ([Held]); the others share one. */
        val ios = mutableMapOf<String, CoroutineDispatcher>()
        var timeout = 5.minutes
        private val io = StandardTestDispatcher(scope.testScheduler)
        private var ids = 0
        private val setup = BiometricSetup(FakePasswordStores(), list, settings, clock, io)

        private val factory = SessionFactory { record, number, _, watching ->
            val store = files.getOrPut(record.path!!) { FakeStore(null) }
            val session = VaultSession(store, VaultOpener(), { _, _, _ -> }, clock, scope.backgroundScope, ios[record.path!!] ?: io)
            val biometric = BiometricUnlock(record.id, session, setup, list, scope.backgroundScope, watching, fileExists = { true })
            DatabaseSession(number, record.id, record.file, store, session, biometric)
        }

        /** Every state the app was told, in order (the first is the one it started with). */
        val told = mutableListOf<SessionsState>()

        /** Every Lock and its kin, with its reason. */
        val locks = mutableListOf<LockReason>()

        /** Every start of the background timer, with the time it has (the app's alarm). */
        val timers = mutableListOf<Duration>()

        /** The room each unlock is judged to have (keething-spec app.large-database AC3), and what it was judged with. */
        var room: (DatabaseSize) -> SaveRoom = { SaveRoom.Enough }
        val judged = mutableListOf<Pair<DatabaseSize, List<OpenSize>>>()

        val sessions: VaultSessions by lazy {
            val judge = { size: DatabaseSize, open: List<OpenSize> -> judged += size to open; room(size) }
            VaultSessions(list, scope.backgroundScope, factory, clock, lockTimeout = { timeout }, now = clock::now, judgeRoom = judge).also { sessions ->
                scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { sessions.state.collect { told += it } }
                sessions.observeLocks { locks += it }
                sessions.observeTimer { timers += it }
            }
        }

        /** A database of the list, in the window or not, whose file opens with "pw" unless [bytes] say otherwise. */
        fun add(path: String, inWindow: Boolean = true, bytes: ByteArray? = fixtureBytes("kdbx4-aes-aeskdf")): DatabaseRecord {
            files[path] = FakeStore(bytes)
            val record = list.add(path)
            list.setInWindow(record.id, inWindow)
            return assertNotNull(list.record(record.id))
        }

        fun session(record: DatabaseRecord): VaultSession = assertNotNull(sessions.session(record.id), "no session for ${record.path}").session

        fun state(record: DatabaseRecord): SessionState = session(record).state.value

        fun vault(record: DatabaseRecord): Vault = assertIs<SessionState.Unlocked>(state(record)).vault

        fun open(vararg records: DatabaseRecord): SessionsState = SessionsState.Open(records.map { OpenVault(it.id, vault(it)) })
    }

    private fun TestScope.unlock(f: Fixture, record: DatabaseRecord, password: String = "pw", wait: Boolean = true) {
        val typed = Secret.of(password)
        assertNotNull(f.session(record).unlock(typed), "${record.path} was not locked")
        typed.wipe()
        if (wait) runCurrent()
    }

    /** The fixture's database with another password: its file changed elsewhere. */
    private fun withPassword(password: String): ByteArray =
        KdbxReader.openForEditing(fixtureBytes("kdbx4-aes-aeskdf"), DatabaseKey.of(Secret.of("pw"))).write(DatabaseKey.of(Secret.of(password))).file

    private fun Vault.isWiped(): Boolean = entries.all { it.password.isWiped }

    // A session per database

    @Test
    fun aSessionPerDatabaseOfTheWindowAndAPasswordOpensOnlyItsOwnDatabase() = runTest {
        val f = Fixture(this)
        val p = f.add("/a/p.kdbx")
        val w = f.add("/a/w.kdbx", bytes = withPassword("work-pw"))
        val elsewhere = f.add("/a/elsewhere.kdbx", inWindow = false)
        assertEquals(listOf(p.id, w.id), f.sessions.windowIds)
        assertNotSame(f.session(p), f.session(w), "nothing shared")
        assertNull(f.sessions.session(elsewhere.id), "not in the window: no session")

        unlock(f, w, "work-pw")
        assertEquals(1, f.files.getValue("/a/w.kdbx").reads)
        assertEquals(0, f.files.getValue("/a/p.kdbx").reads, "never tried on another database")
        assertEquals(f.open(w), f.sessions.state.value)
        assertEquals(SessionState.Locked(LockReason.Initial), f.state(p))

        unlock(f, p, "work-pw")
        assertEquals(SessionState.Locked(LockReason.UnlockFailed(OpenFailure.WrongPassword)), f.state(p), "Work's password is Work's")
        unlock(f, p)
        assertEquals(f.open(p, w), f.sessions.state.value, "in the window's order")
        assertEquals(listOf(p.id, w.id), f.sessions.openIds)
    }

    @Test
    fun anEntrysRefFindsItsOwnDatabasesEntryAndSessionWhichSavesIntoThatFileAlone() = runTest {
        // What the screens resolve a ref with (the list's Delete; plan AD9, keething-spec databases.one-list AC2): Work
        // is a copy of Personal, every UUID the same.
        val f = Fixture(this)
        val p = f.add("/a/Personal.kdbx")
        val w = f.add("/b/Work.kdbx")
        unlock(f, p)
        unlock(f, w)
        val same = f.vault(p).entries.first().id
        assertEquals(same, f.vault(w).entries.first().id, "a copy keeps every UUID")

        val found = assertNotNull(f.sessions.entry(EntryRef(w.id, same)))
        assertSame(f.sessions.session(w.id), found.database)
        assertEquals(same, found.entry.id, "its own id, not the ref")
        assertFalse(found.inRecycleBin)
        assertSame(f.sessions.session(p.id), f.sessions.entry(EntryRef(p.id, same))?.database)
        assertNull(f.sessions.entry(EntryRef(p.id, "gone")))
        assertNull(f.sessions.entry(EntryRef(DatabaseId("not in the window"), same)))

        val saved = assertNotNull(found.database.session.save(VaultEdit.MoveToRecycleBin(found.entry.id, found.entry.lastModified)))
        runCurrent()
        assertEquals(SaveOutcome.Saved(), saved.await())
        assertEquals(1, f.files.getValue("/b/Work.kdbx").replaced)
        assertEquals(0, f.files.getValue("/a/Personal.kdbx").replaced, "Personal's file is not touched")
        assertTrue(f.vault(w).recycleBin.any { it.id == same })
        assertTrue(f.vault(p).entries.any { it.id == same }, "Personal's copy stays where it was")
        assertTrue(assertNotNull(f.sessions.entry(EntryRef(w.id, same))).inRecycleBin, "found in its bin too (a card there restores it)")

        f.session(w).lock()
        assertNull(f.sessions.entry(EntryRef(w.id, same)), "locked: not open")
    }

    @Test
    fun theAppsStateSaysWhyNothingIsOpenThenOpeningThenOpenToldOnceEach() = runTest {
        val f = Fixture(this)
        val p = f.add("/a/p.kdbx")
        assertEquals(SessionsState.NothingOpen(LockReason.Initial), f.sessions.state.value)

        unlock(f, p, "typo")
        assertEquals(SessionsState.NothingOpen(LockReason.UnlockFailed(OpenFailure.WrongPassword)), f.sessions.state.value)
        unlock(f, p)

        assertEquals(
            listOf(
                SessionsState.NothingOpen(LockReason.Initial),
                SessionsState.Opening,
                SessionsState.NothingOpen(LockReason.UnlockFailed(OpenFailure.WrongPassword)),
                SessionsState.Opening,
                f.open(p),
            ),
            f.told,
        )
    }

    @Test
    fun severalOpenSideBySide() = runTest {
        val f = Fixture(this)
        val held = Held(StandardTestDispatcher(testScheduler))
        f.ios["/a/p.kdbx"] = held
        val p = f.add("/a/p.kdbx")
        val w = f.add("/a/w.kdbx")

        unlock(f, p, wait = false)
        unlock(f, w, wait = false)
        assertEquals(SessionsState.Opening, f.sessions.state.value, "both deriving their keys")
        runCurrent()
        assertTrue(held.isWaiting)
        assertEquals(f.open(w), f.sessions.state.value)

        held.release()
        runCurrent()
        assertEquals(f.open(p, w), f.sessions.state.value)
    }

    @Test
    fun oneNotFoundAndTheOtherOpenEachOnesStateSaysWhichAndWhy() = runTest {
        val f = Fixture(this)
        val p = f.add("/a/p.kdbx")
        val c = f.add("/a/c.kdbx", bytes = null)

        unlock(f, c)
        unlock(f, p)

        assertEquals(SessionState.Locked(LockReason.UnlockFailed(OpenFailure.Missing)), f.state(c))
        assertEquals(f.open(p), f.sessions.state.value)
    }

    @Test
    fun anotherStartingToOpenOrFailingWhileOneIsOpenIsNotToldAsAChange() = runTest {
        val f = Fixture(this)
        val p = f.add("/a/p.kdbx")
        val w = f.add("/a/w.kdbx")
        unlock(f, p)
        val told = f.told.size

        unlock(f, w, "typo")

        assertEquals(told, f.told.size, "the screens show the same")
        assertEquals(SessionState.Locked(LockReason.UnlockFailed(OpenFailure.WrongPassword)), f.state(w))
    }


    // The memory a save needs (keething-spec app.large-database AC3)

    @Test
    fun aDatabaseTooLargeToSaveOpensReadOnlyAndRefusesEveryChangeWithoutReadingItsFile() = runTest {
        val f = Fixture(this)
        val p = f.add("/a/p.kdbx")
        f.room = { SaveRoom.TooLarge(alone = true) }
        unlock(f, p)
        val unlocked = assertIs<SessionState.Unlocked>(f.state(p))
        assertEquals(SaveRoom.TooLarge(alone = true), unlocked.room)
        assertTrue(unlocked.readOnly)
        assertFalse(unlocked.viewOnly, "not KDBX 3.1: its question is not asked")
        val size = fixtureBytes("kdbx4-aes-aeskdf").size.toLong()
        assertEquals(size, unlocked.size.fileBytes, "the file's size as read")

        val store = f.files.getValue("/a/p.kdbx")
        val reads = store.reads
        val entry = unlocked.vault.entries.first()
        val saved = assertNotNull(f.session(p).save(VaultEdit.MoveToRecycleBin(entry.id, entry.lastModified)))
        runCurrent()
        assertEquals(SaveOutcome.Failed(SaveFailure.TooLarge(alone = true)), saved.await())
        assertEquals(reads, store.reads, "nothing read")
        assertEquals(0, store.replaced, "nothing written")
    }

    @Test
    fun theOneOpenedLaterIsJudgedBesideTheOpenOnesWhichKeepWhatTheyWereJudged() = runTest {
        val f = Fixture(this)
        val p = f.add("/a/p.kdbx")
        val w = f.add("/a/w.kdbx")
        unlock(f, p)
        assertEquals(emptyList(), f.judged.single().second, "nothing else open")

        f.room = { SaveRoom.TooLarge(alone = false) }
        unlock(f, w)
        val pSize = assertIs<SessionState.Unlocked>(f.state(p)).size
        assertEquals(listOf(OpenSize(pSize, saves = true)), f.judged.last().second, "judged beside the open one, which saves")
        assertEquals(SaveRoom.TooLarge(alone = false), assertIs<SessionState.Unlocked>(f.state(w)).room)
        assertEquals(SaveRoom.Enough, assertIs<SessionState.Unlocked>(f.state(p)).room, "the one open first is not changed")

        // A third one counts the read-only one as open but not saving.
        val x = f.add("/a/x.kdbx")
        f.room = { SaveRoom.Enough }
        unlock(f, x)
        assertEquals(listOf(true, false), f.judged.last().second.map { it.saves })
    }

    @Test
    fun aReloadOrASaveKeepsTheRoomOfTheUnlockAndTheNextUnlockJudgesItAgain() = runTest {
        val f = Fixture(this)
        val p = f.add("/a/p.kdbx")
        f.room = { SaveRoom.Near }
        unlock(f, p)
        val entry = f.vault(p).entries.first()
        val saved = assertNotNull(f.session(p).save(VaultEdit.MoveToRecycleBin(entry.id, entry.lastModified)))
        runCurrent()
        assertEquals(SaveOutcome.Saved(), saved.await())
        f.session(p).reload()
        runCurrent()
        assertEquals(SaveRoom.Near, assertIs<SessionState.Unlocked>(f.state(p)).room)
        assertEquals(1, f.judged.size, "judged once, at the unlock")

        f.session(p).nearLimitSaid()
        assertEquals(SaveRoom.Enough, assertIs<SessionState.Unlocked>(f.state(p)).room, "said once")
        f.session(p).lock()
        unlock(f, p)
        assertEquals(SaveRoom.Near, assertIs<SessionState.Unlocked>(f.state(p)).room, "said again at the next unlock")
        assertEquals(2, f.judged.size)
    }

    // Locking

    @Test
    fun lockLocksEveryDatabaseWipesEachOnesSecretsAndIsToldOnce() = runTest {
        val f = Fixture(this)
        val p = f.add("/a/p.kdbx")
        val w = f.add("/a/w.kdbx")
        unlock(f, p)
        unlock(f, w)
        val personal = f.vault(p)
        val work = f.vault(w)
        val told = f.told.size

        f.sessions.lockAll()

        assertEquals(SessionsState.NothingOpen(LockReason.Manual), f.sessions.state.value)
        assertEquals(listOf<SessionsState>(SessionsState.NothingOpen(LockReason.Manual)), f.told.drop(told), "not one database after the other")
        assertEquals(SessionState.Locked(LockReason.Manual), f.state(p))
        assertEquals(SessionState.Locked(LockReason.Manual), f.state(w))
        assertTrue(personal.isWiped() && work.isWiped(), "both vaults wiped in place")
        runCurrent() // the watches close on io
        assertEquals(0, f.files.getValue("/a/p.kdbx").watching + f.files.getValue("/a/w.kdbx").watching, "neither file watched")
        assertEquals(listOf<LockReason>(LockReason.Manual), f.locks)
        assertEquals(listOf(p.id, w.id), f.sessions.windowIds, "locked, still in the window")
    }

    @Test
    fun lockIsToldAlsoWhenNothingWasOpenAndChangesNothingElse() = runTest {
        val f = Fixture(this)
        f.add("/a/p.kdbx")
        assertEquals(SessionsState.NothingOpen(LockReason.Initial), f.sessions.state.value)
        assertEquals(listOf<SessionsState>(SessionsState.NothingOpen(LockReason.Initial)), f.told)

        f.sessions.lockAll()

        assertEquals(listOf<LockReason>(LockReason.Manual), f.locks, "a lock all the same: what was begun before it ends")
        assertEquals(1, f.told.size, "nothing changed")
        assertEquals(SessionsState.NothingOpen(LockReason.Initial), f.sessions.state.value)
    }

    @Test
    fun theAppsLocksAreCountedAlsoOneThatFoundNothingToLockButNotOneDatabaseLockedAlone() = runTest {
        // The Mac's VaultSessions.lockCount: what was begun before a Lock (a stored password read) opens nothing after it.
        val f = Fixture(this)
        val p = f.add("/a/p.kdbx")
        val w = f.add("/a/w.kdbx")
        assertEquals(0, f.sessions.lockCount)

        f.sessions.lockAll()
        assertEquals(1, f.sessions.lockCount, "nothing open: a lock all the same")
        unlock(f, p)
        unlock(f, w)
        f.sessions.lock(p.id)
        f.sessions.close(w.id)
        assertEquals(1, f.sessions.lockCount, "one locked or closed alone is not the app's lock")
        f.sessions.lockAll(LockReason.Timeout)
        assertEquals(2, f.sessions.lockCount)
    }

    @Test
    fun oneDatabaseLockedAloneItsSecretsGoTheOthersStayAndTheAppStaysOpen() = runTest {
        val f = Fixture(this)
        val p = f.add("/a/p.kdbx")
        val w = f.add("/a/w.kdbx")
        unlock(f, p)
        unlock(f, w)
        val personal = f.vault(p)
        val work = f.vault(w)

        f.sessions.lock(p.id)

        assertEquals(f.open(w), f.sessions.state.value)
        assertTrue(personal.isWiped())
        assertFalse(work.isWiped(), "the other database's secrets are its own")
        assertTrue(f.locks.isEmpty(), "not a Lock of the app")
        f.sessions.lock(w.id, LockReason.Manual)
        assertEquals(SessionsState.NothingOpen(LockReason.Manual), f.sessions.state.value)
    }

    @Test
    fun aLockOfOneWhileAnotherOpensLetsItOpenAndLockAllDropsItsLateResult() = runTest {
        val f = Fixture(this)
        val held = Held(StandardTestDispatcher(testScheduler))
        f.ios["/a/w.kdbx"] = held
        val p = f.add("/a/p.kdbx")
        val w = f.add("/a/w.kdbx")
        unlock(f, p)
        unlock(f, w, wait = false)
        runCurrent()
        assertTrue(held.isWaiting, "deriving")

        f.sessions.lock(p.id)
        assertEquals(SessionsState.Opening, f.sessions.state.value)
        held.release()
        runCurrent()
        assertEquals(f.open(w), f.sessions.state.value)

        val again = Held(StandardTestDispatcher(testScheduler))
        f.ios["/a/w.kdbx"] = again
        f.sessions.lockAll()
        f.sessions.close(w.id)
        f.sessions.addToWindow(w.id)
        unlock(f, w, wait = false)
        runCurrent()
        assertTrue(again.isWaiting, "deriving again")
        f.sessions.lockAll()
        again.release()
        runCurrent()
        assertEquals(SessionsState.NothingOpen(LockReason.Manual), f.sessions.state.value, "the vault must not appear after the user locked")
        assertEquals(SessionState.Locked(LockReason.Manual), f.state(w))
    }

    // Closing, and the window

    @Test
    fun closingOneLocksItTakesItOutOfTheWindowAndKeepsItInTheList() = runTest {
        val f = Fixture(this)
        val p = f.add("/a/p.kdbx")
        val w = f.add("/a/w.kdbx")
        unlock(f, p)
        unlock(f, w)
        val session = f.session(w)
        val work = f.vault(w)

        f.sessions.close(w.id)

        assertTrue(work.isWiped())
        assertEquals(listOf(p.id), f.sessions.windowIds)
        assertNull(f.sessions.session(w.id))
        assertEquals(false, f.list.record(w.id)?.inWindow)
        assertEquals(listOf(p.id, w.id), f.list.records.map { it.id }, "still in the list")
        assertEquals(listOf(p.id), f.sessions.openIds)
        assertNull(session.unlock(Secret.of("pw")), "a closed session opens nothing again")

        f.sessions.addToWindow(w.id)
        assertEquals(listOf(p.id, w.id), f.sessions.windowIds, "back in its place, locked")
        assertEquals(SessionState.Locked(LockReason.Initial), f.state(w))
        assertNotSame(session, f.session(w), "a session of its own again")
    }

    @Test
    fun theCurrentDatabaseIsAlwaysInTheWindow() = runTest {
        val f = Fixture(this)
        val p = f.add("/a/p.kdbx", inWindow = false)
        val w = f.add("/a/w.kdbx", inWindow = false)
        assertEquals(listOf(p.id), f.sessions.windowIds, "at the start: the current one goes in")
        assertEquals(p.id, f.sessions.current.value?.id)

        f.list.setCurrent(w.id)
        assertEquals(listOf(p.id, w.id), f.sessions.windowIds, "made current: it joins the window")
        assertEquals(w.id, f.sessions.current.value?.id)

        f.list.remove(w.id)
        assertEquals(listOf(p.id), f.sessions.windowIds)
        assertEquals(p.id, f.sessions.current.value?.id)
        unlock(f, p)
        val personal = f.vault(p)
        val kept = f.sessions.session(p.id)
        f.sessions.close(p.id)
        assertEquals(listOf(p.id), f.sessions.windowIds, "the window keeps the current one: closing it only locks it")
        assertEquals(kept, f.sessions.session(p.id))
        assertEquals(SessionState.Locked(LockReason.Manual), f.state(p))
        assertTrue(personal.isWiped())

        f.list.remove(p.id)
        assertTrue(f.sessions.windowIds.isEmpty())
        assertNull(f.sessions.current.value)
    }

    @Test
    fun aLockAskedOfALockedDatabaseReplacesWhatTheAppSays() = runTest {
        // Its key file chosen on the lock screen: a "not there" said before is gone.
        val f = Fixture(this)
        val p = f.add("/a/p.kdbx", bytes = null)
        unlock(f, p)

        f.sessions.lock(p.id, LockReason.Initial)

        assertEquals(SessionsState.NothingOpen(LockReason.Initial), f.sessions.state.value)
        assertEquals(SessionState.Locked(LockReason.Initial), f.state(p))
    }

    @Test
    fun aDatabaseOutOfTheListLosesItsSessionLockedFirst() = runTest {
        val f = Fixture(this)
        val p = f.add("/a/p.kdbx")
        unlock(f, p)
        val session = f.session(p)
        val personal = f.vault(p)

        f.list.remove(p.id)

        assertTrue(personal.isWiped())
        assertNull(session.unlock(Secret.of("pw")))
        assertTrue(f.sessions.windowIds.isEmpty())
        assertNull(f.sessions.session(p.id))
        assertEquals(SessionsState.NothingOpen(LockReason.Manual), f.sessions.state.value)
    }

    @Test
    fun aDatabasesFileMovedGetsANewSessionAndAnOpenOneLocksFirst() = runTest {
        val f = Fixture(this)
        val p = f.add("/a/p.kdbx")
        unlock(f, p)
        val before = assertNotNull(f.sessions.session(p.id))
        val personal = f.vault(p)
        f.files["/b/p.kdbx"] = FakeStore(fixtureBytes("kdbx4-aes-aeskdf"))

        f.list.update(p.id) { it.copy(file = FileLocation.ByPath("/b/p.kdbx")) }

        val after = assertNotNull(f.sessions.session(p.id))
        assertNotSame(before, after)
        assertTrue(after.number > before.number, "the screens of before go")
        assertEquals("/b/p.kdbx", after.file.path)
        assertTrue(personal.isWiped(), "its vault was the other file's")
        assertEquals(SessionState.Locked(LockReason.Initial), after.session.state.value)
        assertNull(before.session.unlock(Secret.of("pw")))
    }

    @Test
    fun theWindowIsRememberedWithTheListAndASessionIsMadeForEachOfItsDatabasesAtTheStart() = runTest {
        val f = Fixture(this)
        val p = f.add("/a/p.kdbx")
        val w = f.add("/a/w.kdbx")
        f.add("/a/x.kdbx", inWindow = false)
        f.sessions.close(w.id)

        val g = Fixture(this, f.settings)

        assertEquals(listOf(p.id), g.sessions.windowIds)
        assertNotNull(g.sessions.session(p.id))
        assertNull(g.sessions.session(w.id))
    }

    // Reading again

    @Test
    fun aReloadThatFindsADatabasesPasswordChangedLocksThatOneOnly() = runTest {
        val f = Fixture(this)
        val p = f.add("/a/p.kdbx")
        val w = f.add("/a/w.kdbx")
        unlock(f, p)
        unlock(f, w)

        f.files.getValue("/a/w.kdbx").change(withPassword("changed elsewhere"))
        advanceTimeBy(2.seconds)
        runCurrent()

        assertEquals(SessionState.Locked(LockReason.PasswordChanged), f.state(w))
        assertEquals(f.open(p), f.sessions.state.value)
        assertTrue(f.locks.isEmpty(), "not a Lock of the app")

        f.files.getValue("/a/p.kdbx").change(withPassword("changed elsewhere"))
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(SessionsState.NothingOpen(LockReason.PasswordChanged), f.sessions.state.value, "the last one open: the app says why")
    }

    @Test
    fun aNewVaultReadAgainIsTold() = runTest {
        val f = Fixture(this)
        val p = f.add("/a/p.kdbx")
        unlock(f, p)
        val before = f.vault(p)

        f.files.getValue("/a/p.kdbx").change(fixtureBytes("kdbx4-aes-argon2d"))
        advanceTimeBy(2.seconds)
        runCurrent()

        assertNotSame(before, f.vault(p))
        assertEquals(f.open(p), f.sessions.state.value)
    }

    // One background timer for the app (SPEC §4)

    @Test
    fun theBackgroundTimeoutLocksEveryDatabase() = runTest {
        val f = Fixture(this)
        val p = f.add("/a/p.kdbx")
        val w = f.add("/a/w.kdbx")
        unlock(f, p)
        unlock(f, w)
        val personal = f.vault(p)
        val work = f.vault(w)

        f.sessions.movedToBackground()
        advanceTimeBy(4.minutes)
        runCurrent()
        assertEquals(listOf(p.id, w.id), f.sessions.openIds)
        advanceTimeBy(1.minutes)
        runCurrent()

        assertEquals(SessionsState.NothingOpen(LockReason.Timeout), f.sessions.state.value)
        assertEquals(SessionState.Locked(LockReason.Timeout), f.state(p))
        assertEquals(SessionState.Locked(LockReason.Timeout), f.state(w))
        assertTrue(personal.isWiped() && work.isWiped())
        assertEquals(listOf<LockReason>(LockReason.Timeout), f.locks, "one lock for all")
    }

    @Test
    fun comingBackInTimeKeepsEveryOneOpenChecksEachFileAndTheNextStayCountsAfresh() = runTest {
        val f = Fixture(this)
        val p = f.add("/a/p.kdbx")
        val w = f.add("/a/w.kdbx")
        unlock(f, p)
        unlock(f, w)
        val work = f.vault(w)

        f.sessions.movedToBackground()
        advanceTimeBy(4.minutes)
        f.files.getValue("/a/w.kdbx").change(fixtureBytes("kdbx4-aes-argon2d"), notify = false) // the watcher missed it
        f.sessions.movedToForeground()
        runCurrent()
        assertEquals(listOf(p.id, w.id), f.sessions.openIds)
        assertNotSame(work, f.vault(w), "read again")

        advanceTimeBy(10.minutes)
        f.sessions.movedToBackground()
        advanceTimeBy(4.minutes)
        runCurrent()
        assertEquals(listOf(p.id, w.id), f.sessions.openIds, "the foreground was activity for all")
        advanceTimeBy(1.minutes)
        runCurrent()
        assertEquals(SessionsState.NothingOpen(LockReason.Timeout), f.sessions.state.value)
    }

    @Test
    fun comingBackAfterDeepSleepLocksEveryDatabaseAlthoughTheTimerDidNotRun() = runTest {
        val f = Fixture(this)
        val p = f.add("/a/p.kdbx")
        val w = f.add("/a/w.kdbx")
        unlock(f, p)
        unlock(f, w)

        f.sessions.movedToBackground()
        f.clock.sleep(6.minutes)
        f.sessions.movedToForeground()

        assertEquals(SessionsState.NothingOpen(LockReason.Timeout), f.sessions.state.value)
        assertEquals(SessionState.Locked(LockReason.Timeout), f.state(w))
    }

    @Test
    fun aWakeUpInTheBackgroundLocksEveryDatabaseOnceTheTimeoutPassed() = runTest {
        val f = Fixture(this)
        val p = f.add("/a/p.kdbx")
        val w = f.add("/a/w.kdbx")
        unlock(f, p)
        unlock(f, w)
        f.sessions.movedToBackground()

        f.clock.sleep(4.minutes) // a frozen process: no timer runs
        f.sessions.checkBackgroundTime()
        assertEquals(listOf(p.id, w.id), f.sessions.openIds, "not before the timeout")

        f.clock.sleep(1.minutes)
        f.sessions.checkBackgroundTime()
        assertEquals(SessionsState.NothingOpen(LockReason.Timeout), f.sessions.state.value)
        assertEquals(listOf<LockReason>(LockReason.Timeout), f.locks)
    }

    @Test
    fun aWakeUpInTheForegroundChangesNothing() = runTest {
        val f = Fixture(this)
        val p = f.add("/a/p.kdbx")
        unlock(f, p)
        f.sessions.movedToBackground()
        f.sessions.movedToForeground()

        f.clock.sleep(10.minutes)
        f.sessions.checkBackgroundTime()

        assertEquals(f.open(p), f.sessions.state.value)
    }

    @Test
    fun oneLockedAloneLeavesTheTimerToTheOthersAndTheLastLockStopsIt() = runTest {
        val f = Fixture(this)
        val p = f.add("/a/p.kdbx")
        val w = f.add("/a/w.kdbx")
        unlock(f, p)
        unlock(f, w)
        f.sessions.movedToBackground()

        advanceTimeBy(1.minutes)
        f.sessions.lock(p.id)
        advanceTimeBy(4.minutes)
        runCurrent()
        assertEquals(SessionState.Locked(LockReason.Timeout), f.state(w), "five minutes after the app left, as before")
        assertEquals(SessionState.Locked(LockReason.Manual), f.state(p), "its own reason")

        val g = Fixture(this)
        val q = g.add("/a/q.kdbx")
        unlock(g, q)
        g.sessions.movedToBackground()
        g.sessions.lock(q.id, LockReason.PasswordChanged)
        advanceTimeBy(10.minutes)
        runCurrent()
        g.clock.sleep(10.minutes)
        g.sessions.checkBackgroundTime()
        g.sessions.movedToForeground()
        assertTrue(g.locks.isEmpty(), "nothing open: no timeout")
        assertEquals(SessionsState.NothingOpen(LockReason.PasswordChanged), g.sessions.state.value)
    }

    @Test
    fun theTimerStartsWithTheFirstDatabaseOpenedInTheBackground() = runTest {
        // A new database whose Create ended after the app left (plan AD7): its timeout starts when it opens.
        val f = Fixture(this)
        val p = f.add("/a/p.kdbx")
        f.sessions.movedToBackground()
        f.clock.sleep(4.minutes)
        advanceTimeBy(1.minutes)

        unlock(f, p)
        f.sessions.checkBackgroundTime()
        assertEquals(f.open(p), f.sessions.state.value, "not locked at once")
        advanceTimeBy(4.minutes)
        runCurrent()
        assertEquals(f.open(p), f.sessions.state.value)
        advanceTimeBy(1.minutes)
        runCurrent()
        assertEquals(SessionsState.NothingOpen(LockReason.Timeout), f.sessions.state.value)
    }

    @Test
    fun theTimeoutLocksOneStillOpeningSoItDoesNotOpenBehindTheLockScreen() = runTest {
        val f = Fixture(this)
        val held = Held(StandardTestDispatcher(testScheduler))
        f.ios["/a/w.kdbx"] = held
        val p = f.add("/a/p.kdbx")
        val w = f.add("/a/w.kdbx")
        unlock(f, p)
        unlock(f, w, wait = false)
        runCurrent()
        f.sessions.movedToBackground()

        advanceTimeBy(5.minutes)
        runCurrent()
        held.release()
        runCurrent()

        assertEquals(SessionsState.NothingOpen(LockReason.Timeout), f.sessions.state.value)
        assertEquals(SessionState.Locked(LockReason.Timeout), f.state(w))
    }

    @Test
    fun theTimeoutIsTheSettingWhenTheAppLeaves() = runTest {
        val f = Fixture(this)
        val p = f.add("/a/p.kdbx")
        unlock(f, p)
        f.timeout = 1.minutes // changed in Settings while open

        f.sessions.movedToBackground()
        advanceTimeBy(1.minutes)
        runCurrent()

        assertEquals(SessionsState.NothingOpen(LockReason.Timeout), f.sessions.state.value)
        assertEquals(listOf(1.minutes), f.timers)
    }

    @Test
    fun theAppIsToldEachTimeTheTimerStartsForItsAlarm() = runTest {
        // Android freezes the app: the alarm wakes it. A database whose unlock ends after the app left (a fingerprint's
        // stored password read late, a Create) gets one too.
        val f = Fixture(this)
        val p = f.add("/a/p.kdbx")
        val w = f.add("/a/w.kdbx")
        unlock(f, w)
        f.sessions.lock(w.id)
        assertTrue(f.timers.isEmpty(), "in the foreground: no timer, no alarm")

        f.sessions.movedToBackground()
        assertTrue(f.timers.isEmpty(), "nothing open: no timer, no alarm")
        unlock(f, p, wait = false)
        assertEquals(SessionsState.Opening, f.sessions.state.value)
        assertEquals(listOf(5.minutes), f.timers, "the first to begin opening in the background")
        runCurrent()
        assertEquals(listOf(5.minutes), f.timers, "its opening is not a second start")

        f.sessions.movedToForeground()
        f.sessions.movedToBackground()
        assertEquals(listOf(5.minutes, 5.minutes), f.timers)
    }

    @Test
    fun noTimeoutWhileNothingIsOpenAFailedUnlockKeepsItsReason() = runTest {
        val f = Fixture(this)
        val p = f.add("/a/p.kdbx")
        unlock(f, p, "typo")
        val told = f.told.size

        f.sessions.movedToBackground()
        advanceTimeBy(100.minutes)
        runCurrent()
        f.clock.sleep(100.minutes)
        f.sessions.checkBackgroundTime()
        f.sessions.movedToForeground()

        assertEquals(SessionsState.NothingOpen(LockReason.UnlockFailed(OpenFailure.WrongPassword)), f.sessions.state.value, "not changed to the timeout")
        assertEquals(told, f.told.size, "nothing told")
        assertTrue(f.locks.isEmpty())
        unlock(f, p)
        f.sessions.checkBackgroundTime()
        assertEquals(f.open(p), f.sessions.state.value, "in the foreground again: no old stay counts")
    }
}

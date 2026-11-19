package dev.keething.vault.databases

import dev.keething.domain.secret.Secret
import dev.keething.domain.text.UserText
import dev.keething.domain.text.english
import dev.keething.kdbx.key.DatabaseKey
import dev.keething.kdbx.reader.KdbxReader
import dev.keething.vault.VaultText
import dev.keething.vault.biometric.BiometricSetup
import dev.keething.vault.biometric.BiometricUnlock
import dev.keething.vault.biometric.FakePasswordStores
import dev.keething.vault.biometric.MapSettings
import dev.keething.vault.opening.OpenFailure
import dev.keething.vault.opening.VaultOpener
import dev.keething.vault.session.FakeClock
import dev.keething.vault.session.FakeStore
import dev.keething.vault.session.LockReason
import dev.keething.vault.session.SessionState
import dev.keething.vault.session.VaultSession
import dev.keething.vault.session.fixtureBytes
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The chooser's run (plan AD13; keething-spec databases.chooser AC5–AC8): the Mac's `WindowDatabasesTests` for the run,
 * each checked database asked after the one before, each with its own password.
 */
class UnlockRunTest {
    private class Fixture(scope: TestScope) {
        val settings = MapSettings()
        val clock = FakeClock(scope.testScheduler)
        private var ids = 0
        val list = DatabaseList(settings, makeId = { DatabaseId("db-${++ids}") })
        val passwords = FakePasswordStores()
        private val io = StandardTestDispatcher(scope.testScheduler)
        val setup = BiometricSetup(passwords, list, settings, clock, io)
        val files = mutableMapOf<String, FakeStore>()

        /** The files that are not there, by path. */
        val missing = mutableSetOf<String>()

        /** The app's locks, counted (Lock, the timeout, a stay away past it with nothing open). */
        var locks = 0

        private val factory = SessionFactory { record, number, _, watching ->
            val store = files.getOrPut(record.path!!) { FakeStore(null) }
            val session = VaultSession(store, VaultOpener(), { _, _, _ -> }, clock, scope.backgroundScope, io)
            val biometric = BiometricUnlock(record.id, session, setup, list, scope.backgroundScope, watching, fileExists = { true })
            DatabaseSession(number, record.id, record.file, store, session, biometric)
        }

        val sessions: VaultSessions by lazy { VaultSessions(list, scope.backgroundScope, factory, clock, lockTimeout = { 5.minutes }, now = clock::now) }
        val databases: CurrentDatabase by lazy { CurrentDatabase(list, scope.backgroundScope, sessions, setup) }

        /** Every end of a run: what it did, or null when it stopped. */
        val ends = mutableListOf<UnlockRun.Result?>()

        /** A database of the list, in the window, whose file opens with "pw" unless [bytes] say otherwise. */
        fun add(path: String, bytes: ByteArray? = fixtureBytes("kdbx4-aes-aeskdf")): DatabaseRecord {
            files[path] = FakeStore(bytes)
            val record = list.add(path)
            list.setInWindow(record.id, true)
            sessions
            return assertNotNull(list.record(record.id))
        }

        suspend fun run(vararg records: DatabaseRecord): UnlockRun =
            databases.run(records.map { it.id }, locks = { locks }, fileExists = { (it as FileLocation.ByPath).path !in missing }).also { run ->
                run.onEnd = { ends += it }
                run.start()
            }

        fun state(record: DatabaseRecord): SessionState = assertNotNull(sessions.session(record.id)).session.state.value

        fun label(id: DatabaseId): String = assertNotNull(list.record(id)).displayName
    }

    /** What is typed on the database the run asks now. */
    private suspend fun TestScope.type(run: UnlockRun, password: String) {
        run.unlock(assertNotNull(run.current, "the run ended"), Secret.of(password))
        runCurrent()
    }

    /** The fixture's database with another password. */
    private fun withPassword(password: String): ByteArray =
        KdbxReader.openForEditing(fixtureBytes("kdbx4-aes-aeskdf"), DatabaseKey.of(Secret.of("pw"))).write(DatabaseKey.of(Secret.of(password))).file

    // spec: databases.chooser/AC5
    // spec: databases.chooser/AC6
    // spec: databases.chooser/AC7
    @Test
    fun theRunAsksTheCheckedOnesOneAfterAnotherAWrongPasswordStaysSkipAndNotFoundByItself() = runTest {
        val f = Fixture(this)
        val personal = f.add("/db/Personal.kdbx")
        val work = f.add("/db/Work.kdbx", bytes = withPassword("work-pw"))
        val family = f.add("/db/Family Shared.kdbx")
        val crypto = f.add("/stick/Crypto.kdbx")
        val club = f.add("/db/Club.kdbx")

        val run = f.run(personal, work, family, crypto)
        assertEquals(personal.id, run.current)
        assertEquals("Unlocking 1 of 4", run.step.english())
        assertEquals(listOf(UnlockRun.Segment.CURRENT, UnlockRun.Segment.WAITING, UnlockRun.Segment.WAITING, UnlockRun.Segment.WAITING), run.segments)

        type(run, "pw")
        assertEquals(work.id, run.current)
        assertEquals("Unlocking 2 of 4", run.step.english())
        assertEquals(listOf(personal.id), run.opened)
        type(run, "typo")
        assertEquals(work.id, run.current, "a wrong password stays to be typed again")
        assertEquals(OpenFailure.WrongPassword, run.state.value.failure)
        assertEquals(listOf(UnlockRun.Segment.OPENED, UnlockRun.Segment.CURRENT, UnlockRun.Segment.WAITING, UnlockRun.Segment.WAITING), run.segments)
        type(run, "work-pw")
        assertNull(run.state.value.failure)
        assertEquals(1, f.files.getValue("/db/Personal.kdbx").reads, "each typed password tried on its own database only")
        assertEquals(2, f.files.getValue("/db/Work.kdbx").reads)

        f.missing += "/stick/Crypto.kdbx" // the card went meanwhile
        assertEquals(family.id, run.current)
        run.skip(assertNotNull(run.current))

        assertNull(run.current, "Crypto was skipped by itself: the run ended")
        val result = assertNotNull(f.ends.single())
        assertEquals(listOf(personal.id, work.id), result.opened)
        assertEquals(listOf(family.id), result.skipped)
        assertEquals(listOf(UnlockRun.Problem(crypto.id, UserText.of(VaultText.WHY_NOT_FOUND))), result.notOpened)
        assertEquals("2 of 4 open · Family Shared: skipped · Crypto: not found", result.notice(f::label).english())
        assertEquals(listOf(personal.id, work.id), f.sessions.openIds)
        assertEquals(SessionState.Locked(LockReason.Initial), f.state(club), "not checked: not asked")
    }

    // spec: databases.chooser/AC6
    // spec: databases.chooser/AC8
    @Test
    fun whatTypingCannotOpenIsSkippedByItselfAlsoAfterATryAndALockEndsTheRunWithNothingTold() = runTest {
        val f = Fixture(this)
        val personal = f.add("/db/Personal.kdbx", bytes = ByteArray(400) { 7 })
        val work = f.add("/db/Work.kdbx")
        val family = f.add("/db/Family.kdbx")

        val run = f.run(personal, work, family)
        type(run, "pw")
        assertEquals(work.id, run.current, "a damaged file: skipped by itself")
        assertEquals(listOf(UnlockRun.Segment.SKIPPED, UnlockRun.Segment.CURRENT, UnlockRun.Segment.WAITING), run.segments)
        type(run, "pw")
        assertEquals(family.id, run.current)

        f.sessions.lockAll()
        f.locks++
        type(run, "pw")

        assertEquals(listOf<UnlockRun.Result?>(null), f.ends, "nothing to tell: the lock screen comes")
        assertNull(run.current)
        assertEquals(emptyList(), f.sessions.openIds, "nothing opens after the lock")
        assertEquals(0, f.files.getValue("/db/Family.kdbx").reads)
    }

    // spec: databases.chooser/AC8
    @Test
    fun aLockWithNothingOpenYetEndsTheRunAndWhatIsTypedAfterItOpensNothing() = runTest {
        // The phone left past its timeout before the first password: a lock with nothing to lock.
        val f = Fixture(this)
        val personal = f.add("/db/Personal.kdbx")
        f.add("/db/Work.kdbx")
        val run = f.run(personal)

        f.locks++
        type(run, "pw")

        assertEquals(listOf<UnlockRun.Result?>(null), f.ends)
        assertEquals(SessionState.Locked(LockReason.Initial), f.state(personal))
        assertEquals(0, f.files.getValue("/db/Personal.kdbx").reads)
    }

    // spec: databases.chooser/AC6
    @Test
    fun oneOpenedMeanwhileCountsAsOpenedAndOneClosedIsSkipped() = runTest {
        val f = Fixture(this)
        val work = f.add("/db/Work.kdbx")
        val family = f.add("/db/Family.kdbx")
        val club = f.add("/db/Club.kdbx")
        val run = f.run(work, family, club)

        assertNotNull(f.sessions.session(family.id)).session.unlock(Secret.of("pw")) // its fingerprint, say, while Work is asked
        f.databases.close(club.id)
        runCurrent()
        type(run, "pw")

        val result = assertNotNull(f.ends.single())
        assertEquals(listOf(work.id, family.id), result.opened)
        assertEquals("2 of 3 open · Club: closed", result.notice(f::label).english())
    }

    @Test
    fun aDatabaseWhoseFileWentBeforeItsTurnIsSkippedWhenTheRunReachesItAndOneThatGoesAfterATryToo() = runTest {
        val f = Fixture(this)
        val personal = f.add("/db/Personal.kdbx")
        val work = f.add("/db/Work.kdbx")
        val run = f.run(personal, work)

        f.files.getValue("/db/Personal.kdbx").change(null) // gone, though the files were not looked at again
        type(run, "pw")

        assertEquals(work.id, run.current, "not found by the try: skipped by itself")
        type(run, "pw")
        val result = assertNotNull(f.ends.single())
        assertEquals("1 of 2 open · Personal: not found", result.notice(f::label).english())
    }

    @Test
    fun useFingerprintNextTimeInTheRunStoresThatDatabasesPasswordAlone() = runTest {
        val f = Fixture(this)
        val personal = f.add("/db/Personal.kdbx")
        val work = f.add("/db/Work.kdbx")
        f.setup.setEnabled(true)
        f.setup.refresh()
        val run = f.run(work)

        assertNull(assertNotNull(f.sessions.session(work.id)).biometric.useNextTime(true))
        type(run, "pw")
        runCurrent()

        assertEquals(listOf("pw"), f.passwords.saves)
        assertEquals(listOf(work.id.fileName), f.passwords.savedFor)
        assertEquals("1 of 1 open", assertNotNull(f.ends.single()).notice(f::label).english())
        assertEquals(SessionState.Locked(LockReason.Initial), f.state(personal))
    }

    @Test
    fun stopEndsTheRunWhereItIsAndWhatIsTypedAfterOpensNothing() = runTest {
        val f = Fixture(this)
        val personal = f.add("/db/Personal.kdbx")
        val work = f.add("/db/Work.kdbx")
        val run = f.run(personal, work)
        type(run, "pw")

        run.stop()
        run.unlock(work.id, Secret.of("pw"))
        runCurrent()

        assertEquals(listOf<UnlockRun.Result?>(null), f.ends)
        assertNull(run.current)
        assertIs<SessionState.Unlocked>(f.state(personal), "what it opened stays open")
        assertEquals(SessionState.Locked(LockReason.Initial), f.state(work))
    }

    @Test
    fun theTypedPasswordIsWipedBeforeTheKeyIsDerived() = runTest {
        // The session keeps its own copy: the run lets go of the typed one at once, not after the derivation.
        val f = Fixture(this)
        val personal = f.add("/db/Personal.kdbx")
        val run = f.run(personal)
        val typed = Secret.of("pw")
        val wipedWhenRead = mutableListOf<Boolean>()
        f.files.getValue("/db/Personal.kdbx").beforeRead = { wipedWhenRead += typed.isWiped }

        run.unlock(personal.id, typed)

        assertEquals(listOf(true), wipedWhenRead)
        assertIs<SessionState.Unlocked>(f.state(personal))
    }

    @Test
    fun aSkipForADatabaseTheRunHasLeftSkipsNothingMore() = runTest {
        // Found by AD13's second review: a quick second tap on "Skip Personal" while Work is coming on screen.
        val f = Fixture(this)
        val personal = f.add("/db/Personal.kdbx")
        val work = f.add("/db/Work.kdbx")
        val run = f.run(personal, work)

        run.skip(personal.id)
        run.skip(personal.id)

        assertEquals(work.id, run.current, "Work, never seen, is not skipped")
        assertEquals(listOf(UnlockRun.Segment.SKIPPED, UnlockRun.Segment.CURRENT), run.segments)
    }

    // spec: databases.chooser/AC5
    @Test
    fun aPasswordTypedForOneDatabaseNeverGoesToTheNextOneAsked() = runTest {
        // Found by AD13's review: Skip (or the run moving on by itself) between the typing and the try.
        val f = Fixture(this)
        val personal = f.add("/db/Personal.kdbx")
        val work = f.add("/db/Work.kdbx")
        val run = f.run(personal, work)
        run.skip(assertNotNull(run.current))
        val typed = Secret.of("pw")

        run.unlock(personal.id, typed)
        runCurrent()

        assertTrue(typed.isWiped)
        assertEquals(work.id, run.current, "still asked")
        assertEquals(0, f.files.getValue("/db/Work.kdbx").reads, "Personal's password is not tried on Work")
        assertEquals(SessionState.Locked(LockReason.Initial), f.state(work))
    }
}

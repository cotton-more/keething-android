package dev.keething.android.databases

import dev.keething.domain.storage.StoragePlace
import dev.keething.vault.databases.FileLocation
import dev.keething.android.session.FingerprintOffers
import dev.keething.android.session.Visit
import dev.keething.android.settings.InMemorySettingsStore
import dev.keething.domain.secret.Secret
import dev.keething.vault.biometric.BiometricState
import dev.keething.vault.biometric.FingerprintOutcome
import dev.keething.vault.biometric.WindowFingerprintResult
import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.databases.DatabaseList
import dev.keething.vault.databases.UnlockRun
import dev.keething.vault.opening.OpenFailure
import dev.keething.vault.session.LockReason
import dev.keething.vault.session.SessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import dev.keething.android.ui.english

/**
 * The chooser and its run as the app keeps them (plan AD13; keething-spec databases.chooser AC2, AC6–AC8; the Mac's
 * `WindowDatabasesModel` and `WindowScreenModel`): a lock ends the run silently, its end is told or said, Skipped stays
 * on the chips until its database does something else, the fingerprint's answer is said over the chooser.
 */
class WindowChooserTest {
    private val list = DatabaseList(InMemorySettingsStore(), makeId = { DatabaseId("db-${++ids}") })
    private var ids = 0
    private val sync = StoragePlace(StoragePlace.PRIMARY, "Sync")
    private val personal = list.add(FileLocation.InFolder("grant-sync", sync, "Personal.kdbx")).id
    private val work = list.add(FileLocation.InFolder("grant-sync", sync, "Work.kdbx")).id

    private val off = BiometricState(appEnabled = false, databaseEnabled = false)
    private val locked = SessionState.Locked(LockReason.Initial)

    private inner class Fixture(private val scope: TestScope) {
        val opened = mutableSetOf<DatabaseId>()

        /** What a typed password does to each database: opened, or its failure. */
        val failures = mutableMapOf<DatabaseId, OpenFailure>()
        val told = mutableListOf<String>()
        var shownOpened = 0
        var fingerprint: FingerprintOutcome = FingerprintOutcome.Cancelled
        var fingerprints = 0
        var canOpenAny = true
        val locks = MutableStateFlow(0)
        val window = MutableStateFlow(listOf(WindowDatabase(personal, locked, off), WindowDatabase(work, locked, off)))
        val offers = FingerprintOffers()

        /** The databases a typed password was tried on. */
        val tried = mutableListOf<DatabaseId>()

        /** What happens while the fingerprint is asked (a lock, say), told before it answers. */
        var duringFingerprint: suspend () -> Unit = {}

        /** With it, whether one would open is still being asked. */
        var asking: CompletableDeferred<Unit>? = null

        /** The databases each fingerprint and each "would one open" was asked for; null: every one of the window. */
        val fingerprintedFor = mutableListOf<Collection<DatabaseId>?>()
        val askedFor = mutableListOf<Collection<DatabaseId>?>()

        /** What a fingerprint opens of those it is asked for. */
        var fingerprintOpens = emptySet<DatabaseId>()

        val chooser = WindowChooser(
            list = list,
            startRun = { ids ->
                UnlockRun(ids, cannotOpen = { null }, isOpen = { it in opened }) { id, password ->
                    tried += id
                    password.wipe()
                    failures[id]?.let { UnlockRun.Attempt.Failed(it) } ?: UnlockRun.Attempt.Opened.also { opened += id }
                }
            },
            fingerprint = { among ->
                fingerprints++
                fingerprintedFor += among
                duringFingerprint()
                opened += fingerprintOpens.filter { among == null || it in among }
                WindowFingerprintResult(fingerprint, emptyMap())
            },
            canUnlockAny = { among ->
                askedFor += among
                asking?.await()
                canOpenAny
            },
            offers = offers::offer,
            label = { if (it == personal) "Personal" else "Work" },
            showOpened = { shownOpened++ },
            tell = { told += it.english() },
            sessionState = { id -> window.value.firstOrNull { it.id == id }?.session },
            scope = scope.backgroundScope,
            window = window,
            lockCount = locks,
        )

        /** Skip on the database the run asks now; the run moves on. */
        fun skip() {
            chooser.skip(assertNotNull(chooser.run.value?.current))
            scope.runCurrent()
        }

        /** What is typed on the database the run asks now. */
        fun type(password: String = "pw") {
            chooser.type(assertNotNull(chooser.run.value?.current), Secret.of(password))
        }
    }

    // spec: databases.chooser/AC8
    @Test
    fun aLockEndsTheRunWithNothingSaidAlsoWithNothingOpenYet() = runTest {
        val f = Fixture(this)
        f.chooser.start(listOf(personal, work))
        runCurrent()
        assertNotNull(f.chooser.run.value)

        f.locks.value++
        runCurrent()

        assertNull(f.chooser.run.value, "a lock ends the run")
        assertEquals(emptyList(), f.told)
        assertNull(f.chooser.note.value)
        assertEquals(0, f.shownOpened)
    }

    // spec: databases.chooser/AC7
    @Test
    fun theRunsEndIsToldOverTheListOrSaidByTheChooserWhenNoneOpened() = runTest {
        val f = Fixture(this)
        f.chooser.start(listOf(personal, work))
        runCurrent()
        f.type()
        runCurrent()
        f.skip()

        assertNull(f.chooser.run.value)
        assertEquals(listOf("1 of 2 open · Work: skipped"), f.told)
        assertEquals(1, f.shownOpened, "the vault on what opened")
        assertEquals(setOf(work), f.chooser.skipped.value, "Skipped on its chip")

        f.opened.clear()
        f.chooser.start(listOf(personal, work))
        runCurrent()
        assertEquals(emptySet(), f.chooser.skipped.value, "another run")
        f.skip()
        f.skip()
        assertEquals("0 of 2 open · Personal: skipped · Work: skipped", f.chooser.note.value?.english())
        assertEquals(1, f.told.size, "nothing said over a list that is not there")
    }

    // spec: databases.chooser/AC6
    @Test
    fun aWrongPasswordStaysToBeTypedAgainAndStopLeavesWhatOpened() = runTest {
        val f = Fixture(this)
        f.failures[work] = OpenFailure.WrongPassword
        f.chooser.start(listOf(personal, work))
        runCurrent()
        f.type()
        runCurrent()
        f.type("typo")
        runCurrent()

        val run = assertNotNull(f.chooser.run.value)
        assertEquals(work, run.current)
        assertEquals(OpenFailure.WrongPassword, run.state.value.failure)

        f.chooser.stop()
        assertNull(f.chooser.run.value)
        assertEquals(emptyList(), f.told, "stopped: nothing told")
        assertEquals(1, f.shownOpened, "what opened shows")
    }

    // spec: databases.chooser/AC6
    @Test
    fun aChangeOfTheWindowLooksAgainAtTheDatabaseAsked() = runTest {
        val f = Fixture(this)
        f.chooser.start(listOf(personal, work))
        runCurrent()

        f.opened += personal // its fingerprint, say, from elsewhere
        f.window.value = listOf(WindowDatabase(personal, SessionState.Unlocked(dev.keething.domain.vault.Vault(emptyList())), off), WindowDatabase(work, locked, off))
        runCurrent()

        assertEquals(work, assertNotNull(f.chooser.run.value).current, "Personal counts as opened")
        assertEquals(work, f.chooser.asked.value?.id)
    }

    @Test
    fun skippedGivesWayToWhatItsDatabaseDidSince() = runTest {
        val f = Fixture(this)
        f.chooser.start(listOf(personal, work))
        runCurrent()
        f.type()
        runCurrent()
        f.skip()
        runCurrent()
        assertEquals(setOf(work), f.chooser.skipped.value)

        f.window.value = f.window.value.toList() // told again, nothing of Work changed
        runCurrent()
        assertEquals(setOf(work), f.chooser.skipped.value)

        f.window.value = listOf(f.window.value[0], WindowDatabase(work, SessionState.Locked(LockReason.UnlockFailed(OpenFailure.WrongPassword)), off))
        runCurrent()
        assertEquals(emptySet(), f.chooser.skipped.value, "a wrong password on its own lock screen")
    }

    // spec: databases.chooser/AC2
    @Test
    fun theFingerprintsAnswerIsSaidOverTheChooserOnlyWhenNoneOpened() = runTest {
        val f = Fixture(this)

        f.chooser.unlockWithFingerprint()
        runCurrent()
        assertEquals("The fingerprint was cancelled.", f.chooser.note.value?.english())
        assertEquals(0, f.shownOpened)

        f.fingerprint = FingerprintOutcome.Unlocked
        f.chooser.unlockWithFingerprint()
        runCurrent()
        assertNull(f.chooser.note.value)
        assertEquals(1, f.shownOpened, "the vault on the first that opened")
    }

    // spec: databases.chooser/AC1
    @Test
    fun theFingerprintIsOfferedOncePerVisitThatBeganLockedAndOnlyWhenOneWouldOpen() = runTest {
        val f = Fixture(this)
        f.canOpenAny = false
        f.chooser.offer(Visit(1, beganLocked = true))
        runCurrent()
        assertEquals(0, f.fingerprints, "none would open: no prompt")

        f.canOpenAny = true
        f.chooser.offer(Visit(1, beganLocked = true))
        runCurrent()
        assertEquals(0, f.fingerprints, "once per visit")
        f.chooser.offer(Visit(2, beganLocked = true))
        runCurrent()
        assertEquals(1, f.fingerprints)
        f.chooser.offer(Visit(3, beganLocked = false))
        runCurrent()
        assertEquals(1, f.fingerprints, "not after the user's own Lock")
    }

    // spec: databases.chooser/AC4
    @Test
    fun aCheckIsRememberedAtOnceAndARunNeedsOneChecked() = runTest {
        val f = Fixture(this)
        f.chooser.toggle(work)
        assertFalse(list.record(work)!!.checkedInChooser)
        f.chooser.toggle(work)
        assertTrue(list.record(work)!!.checkedInChooser)

        f.chooser.start(emptyList())
        assertNull(f.chooser.run.value)
        f.chooser.start(listOf(work))
        runCurrent()
        val run = f.chooser.run.value
        f.chooser.start(listOf(personal))
        runCurrent()
        assertEquals(run, f.chooser.run.value, "one run at a time")
    }

    // spec: databases.chooser/AC5
    @Test
    fun theRunOpensByTheFingerprintWhatCanAmongTheCheckedAndAsksPasswordsOnlyForTheRest() = runTest {
        val f = Fixture(this)
        f.fingerprint = FingerprintOutcome.Unlocked
        f.fingerprintOpens = setOf(personal)
        f.chooser.start(listOf(personal, work))
        assertTrue(f.chooser.busy.value, "the chooser waits for the prompt")
        assertNull(f.chooser.run.value, "no run under the prompt")
        runCurrent()

        assertEquals(listOf<Collection<DatabaseId>?>(listOf(personal, work)), f.askedFor, "asked for the checked ones only")
        assertEquals(listOf<Collection<DatabaseId>?>(listOf(personal, work)), f.fingerprintedFor)
        val run = assertNotNull(f.chooser.run.value)
        assertEquals(work, run.current, "a password for the one the fingerprint did not open")
        assertEquals(listOf(UnlockRun.Segment.OPENED, UnlockRun.Segment.CURRENT), run.segments)
        f.type()
        runCurrent()

        assertEquals(listOf(work), f.tried, "no password asked for Personal")
        assertEquals(listOf("2 of 2 open"), f.told)
        assertNull(f.chooser.note.value, "what the fingerprint said is not said over the chooser")
    }

    // spec: databases.chooser/AC5
    @Test
    fun whenTheFingerprintOpensEveryCheckedOneTheRunEndsAtOnce() = runTest {
        val f = Fixture(this)
        f.fingerprint = FingerprintOutcome.Unlocked
        f.fingerprintOpens = setOf(personal, work)
        f.chooser.start(listOf(personal, work))
        runCurrent()

        assertNull(f.chooser.run.value)
        assertEquals(emptyList(), f.tried)
        assertEquals(listOf("2 of 2 open"), f.told)
        assertEquals(1, f.shownOpened)
    }

    // spec: databases.chooser/AC5
    @Test
    fun aFingerprintCancelledOrNoneThatWouldOpenLeavesEveryCheckedOneToItsPassword() = runTest {
        val f = Fixture(this)
        f.chooser.start(listOf(personal, work))
        runCurrent()
        assertEquals(1, f.fingerprints)
        assertEquals(personal, f.chooser.run.value?.current, "cancelled: Personal's password")
        assertNull(f.chooser.note.value, "nothing said of the fingerprint")
        assertFalse(f.chooser.busy.value)
        f.chooser.stop()

        f.canOpenAny = false
        f.chooser.start(listOf(personal, work))
        runCurrent()
        assertEquals(1, f.fingerprints, "none would open: no prompt")
        assertEquals(personal, f.chooser.run.value?.current)
    }

    // spec: databases.chooser/AC5
    @Test
    fun aLockWhileTheRunsFingerprintIsAskedStartsNoRun() = runTest {
        val f = Fixture(this)
        f.duringFingerprint = {
            f.locks.value++
            yield()
        }
        f.chooser.start(listOf(personal, work))
        runCurrent()

        assertNull(f.chooser.run.value)
        assertFalse(f.chooser.busy.value)
        assertEquals(emptyList(), f.told)
        assertNull(f.chooser.note.value)
    }

    // Found by AD13's review

    // spec: databases.chooser/AC5
    @Test
    fun theDatabaseAskedFollowsTheRunAndAPasswordTypedForOneNeverGoesToTheNext() = runTest {
        val f = Fixture(this)
        f.chooser.start(listOf(personal, work))
        runCurrent()
        assertEquals(personal, f.chooser.asked.value?.id)

        f.skip()
        runCurrent()
        assertEquals(work, f.chooser.asked.value?.id, "the screen shows Work after Skip")
        assertEquals(f.chooser.run.value, f.chooser.asked.value?.run, "with its run")

        val typed = Secret.of("pw")
        f.chooser.type(personal, typed) // typed on Personal's screen just before
        runCurrent()
        assertTrue(typed.isWiped)
        assertEquals(emptyList(), f.tried, "never tried on Work")
        f.chooser.stop()
        runCurrent()
        assertNull(f.chooser.asked.value)
    }

    @Test
    fun whileTheFingerprintIsOfferedNoRunStartsAndOneStartedMeanwhileStopsTheOffer() = runTest {
        val f = Fixture(this)
        val asking = CompletableDeferred<Unit>()
        f.asking = asking
        f.chooser.offer(Visit(1, beganLocked = true))
        runCurrent()
        assertTrue(f.chooser.busy.value, "the chooser waits")

        f.chooser.start(listOf(personal))
        assertNull(f.chooser.run.value, "no run under the prompt")
        asking.complete(Unit)
        runCurrent()
        assertEquals(1, f.fingerprints)
        assertFalse(f.chooser.busy.value)
    }

    @Test
    fun aVisitThatBeginsOnTheRunUsesItsOfferSoLockAllBringsNoPrompt() = runTest {
        val f = Fixture(this)
        f.chooser.start(listOf(personal, work))
        runCurrent()
        f.chooser.showingRun(Visit(2, beganLocked = true))
        f.chooser.stop()

        f.chooser.offer(Visit(2, beganLocked = true))
        runCurrent()

        assertEquals(listOf<Collection<DatabaseId>?>(listOf(personal, work)), f.fingerprintedFor, "the run's own only")
    }

    @Test
    fun whatTheChooserSaidGoesAtALockAndWhenADatabaseOpens() = runTest {
        val f = Fixture(this)
        f.chooser.unlockWithFingerprint()
        runCurrent()
        assertNotNull(f.chooser.note.value)

        f.locks.value++
        runCurrent()
        assertNull(f.chooser.note.value, "a lock")

        f.chooser.unlockWithFingerprint()
        runCurrent()
        f.window.value = listOf(WindowDatabase(personal, SessionState.Unlocked(dev.keething.domain.vault.Vault(emptyList())), off), f.window.value[1])
        runCurrent()
        assertNull(f.chooser.note.value, "one opened (Open other…, say)")
    }

    // Found by AD13's second review

    @Test
    fun aVisitBeginningWhileTheFingerprintIsAskedUsesItsOfferToo() = runTest {
        val f = Fixture(this)
        val asking = CompletableDeferred<Unit>()
        f.asking = asking
        f.chooser.offer(Visit(1, beganLocked = true))
        runCurrent()
        f.chooser.offer(Visit(2, beganLocked = true)) // back from elsewhere while it is still asked
        asking.complete(Unit)
        runCurrent()
        f.asking = null

        f.chooser.offer(Visit(2, beganLocked = true)) // Lock all later in that visit
        runCurrent()

        assertEquals(1, f.fingerprints)
    }

    @Test
    fun aLockWhileTheFingerprintIsAskedLeavesNothingSaidOverTheChooser() = runTest {
        val f = Fixture(this)
        f.duringFingerprint = {
            f.locks.value++
            yield() // the lock is told while the prompt is still up
        }

        f.chooser.unlockWithFingerprint()
        runCurrent()

        assertNull(f.chooser.note.value)
    }

    // Found on the phone

    @Test
    fun anOfferWhoseChooserGaveWayBeforeItsPromptAsksNothing() = runTest {
        // Open other…: back from the system picker is a new visit; the file picked shows its lock screen in the
        // chooser's place while the offer still asks whether one would open.
        val f = Fixture(this)
        val asking = CompletableDeferred<Unit>()
        f.asking = asking
        f.chooser.offer(Visit(1, beganLocked = true))
        runCurrent()

        f.chooser.left()
        asking.complete(Unit)
        runCurrent()

        assertEquals(0, f.fingerprints)
        assertFalse(f.chooser.busy.value)
    }
}

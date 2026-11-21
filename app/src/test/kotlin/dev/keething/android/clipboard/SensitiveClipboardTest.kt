package dev.keething.android.clipboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class SensitiveClipboardTest {
    private class FakeSystemClipboard : SystemClipboard {
        var text: String? = null
        var sensitive = false
        var token: String? = null

        /** False: the app is in the background and may not look at the clipboard. */
        var readable = true

        override fun put(text: String, sensitive: Boolean, token: String) {
            this.text = text
            this.sensitive = sensitive
            this.token = token
        }

        override fun owner(): ClipOwner = when {
            !readable -> ClipOwner.Unknown
            token != null -> ClipOwner.Ours(token!!)
            else -> ClipOwner.Other
        }

        override fun clear() {
            text = null
            token = null
        }

        override fun text(): String? = text

        override fun hasText(): Boolean = text != null

        fun copiedElsewhere(other: String) {
            text = other
            token = null
        }
    }

    private class FakeAlarm : ClearAlarm {
        var scheduled: Pair<String, Duration>? = null

        override fun schedule(token: String, after: Duration) {
            scheduled = token to after
        }

        override fun cancel() {
            scheduled = null
        }
    }

    private class Fixture(scope: TestScope) {
        val system = FakeSystemClipboard()
        val alarm = FakeAlarm()
        private var next = 0
        val clipboard = SensitiveClipboard(system, alarm, scope.backgroundScope, newToken = { "t${++next}" })
    }

    @Test
    fun aCopyIsClearedAfterThirtySeconds() = runTest {
        val f = Fixture(this)

        f.clipboard.copy("s3cret", sensitive = true)
        assertEquals("s3cret", f.system.text)
        assertEquals(true, f.system.sensitive)
        advanceTimeBy(29.seconds)
        runCurrent()
        assertEquals("s3cret", f.system.text)
        advanceTimeBy(1.seconds)
        runCurrent()

        assertNull(f.system.text)
    }

    @Test
    fun somethingCopiedElsewhereMeanwhileStays() = runTest {
        val f = Fixture(this)

        f.clipboard.copy("s3cret", sensitive = true)
        f.system.copiedElsewhere("a shopping list")
        advanceTimeBy(31.seconds)
        runCurrent()

        assertEquals("a shopping list", f.system.text)
    }

    @Test
    fun aNewerCopyGetsItsOwnThirtySeconds() = runTest {
        val f = Fixture(this)

        f.clipboard.copy("first", sensitive = true)
        advanceTimeBy(20.seconds)
        f.clipboard.copy("second", sensitive = false)
        advanceTimeBy(15.seconds)
        runCurrent()
        assertEquals("second", f.system.text)
        advanceTimeBy(15.seconds)
        runCurrent()

        assertNull(f.system.text)
    }

    @Test
    fun theAlarmIsSetForTheSameTimeWithTheCopysToken() = runTest {
        val f = Fixture(this)

        f.clipboard.copy("s3cret", sensitive = true)

        assertEquals("t1" to 30.seconds, f.alarm.scheduled)
    }

    @Test
    fun theAlarmClearsOnlyItsOwnCopy() = runTest {
        val f = Fixture(this)
        f.clipboard.copy("first", sensitive = true)
        f.clipboard.copy("second", sensitive = true)

        f.clipboard.clearIfStill("t1") // an alarm of the first copy
        assertEquals("second", f.system.text)

        f.clipboard.clearIfStill("t2")
        assertNull(f.system.text)
    }

    @Test
    fun onceTheTimerClearedTheCopyItsAlarmIsCancelledAndDoesNothingIfItStillFires() = runTest {
        val f = Fixture(this)
        f.clipboard.copy("s3cret", sensitive = true)
        advanceTimeBy(31.seconds)
        runCurrent()
        assertNull(f.alarm.scheduled)

        // In the background: the user copied something else, which the app cannot see.
        f.system.copiedElsewhere("a shopping list")
        f.system.readable = false
        f.clipboard.clearIfStill("t1") // the alarm was already on its way

        assertEquals("a shopping list", f.system.text)
    }

    @Test
    fun inTheBackgroundTheClipboardCannotBeSeenSoItIsClearedAnyway() = runTest {
        val f = Fixture(this)
        f.clipboard.copy("s3cret", sensitive = true)
        f.system.copiedElsewhere("a shopping list")
        f.system.readable = false

        f.clipboard.clearIfStill("t1")

        assertNull(f.system.text)
    }

    @Test
    fun anAlarmInANewProcessKnowsNoCopyClearsInTheBackgroundAndKeepsAnotherAppsCopyInFront() = runTest {
        // The process that copied is gone; the alarm starts a new one, whose clipboard knows no copy.
        val background = Fixture(this)
        background.system.put("s3cret", sensitive = true, token = "t1")
        background.system.readable = false
        background.clipboard.clearIfStill("t1")
        assertNull(background.system.text, "in the background the clipboard cannot be seen: cleared")
        assertNull(background.alarm.scheduled)

        val inFront = Fixture(this)
        inFront.system.put("s3cret", sensitive = true, token = "t1")
        inFront.system.copiedElsewhere("a shopping list")
        inFront.clipboard.clearIfStill("t1")
        assertEquals("a shopping list", inFront.system.text, "in front another app's copy is seen and stays")

        val ownInFront = Fixture(this)
        ownInFront.system.put("s3cret", sensitive = true, token = "t1")
        ownInFront.clipboard.clearIfStill("t1")
        assertNull(ownInFront.system.text, "its own copy, by its token: cleared")

        ownInFront.system.put("s3cret", sensitive = true, token = "t1")
        ownInFront.clipboard.clearIfStill("t1")
        assertEquals("s3cret", ownInFront.system.text, "only once: the same alarm again does nothing")
    }
}

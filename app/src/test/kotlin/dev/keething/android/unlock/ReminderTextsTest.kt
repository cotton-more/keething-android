package dev.keething.android.unlock

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The reminder's words (keething-spec unlock.reminder-not-a-lock; the canvas "Password reminder"). */
class ReminderTextsTest {
    @Test
    fun theQuestionAsksAboutWhatTheKeyIsMadeOf() {
        assertEquals(ReminderKind.Password, ReminderKind.of(passwordless = false, keyFile = null))
        assertEquals(ReminderKind.PasswordAndKeyFile("family.keyx"), ReminderKind.of(passwordless = false, keyFile = "family.keyx"))
        assertEquals(ReminderKind.KeyFile("family.keyx"), ReminderKind.of(passwordless = true, keyFile = "family.keyx"))
    }

    @Test
    fun thePasswordsQuestionNamesTheDatabaseAndTheDays() {
        // spec: unlock.reminder-not-a-lock/AC1
        assertEquals("Do you still remember it?", ReminderTexts.title(ReminderKind.Password))
        assertEquals("It has been 14 days since the master password of “Family” was typed.", ReminderTexts.text(ReminderKind.Password, "Family", 14))
        assertEquals("It has been 1 day since the master password of “Family” was typed.", ReminderTexts.text(ReminderKind.Password, "Family", 1))
        assertNull(ReminderTexts.keyFileLine(ReminderKind.Password))
    }

    @Test
    fun aKeyFileAloneIsAskedWhetherItIsKeptSafe() {
        // spec: unlock.reminder-not-a-lock/AC7
        val kind = ReminderKind.KeyFile("family.keyx")
        assertEquals("Is the key file safe?", ReminderTexts.title(kind))
        assertEquals("It has been 30 days. Is the key file family.keyx kept somewhere safe, besides this phone?", ReminderTexts.text(kind, "Family", 30))
        assertNull(ReminderTexts.keyFileLine(kind), "its own question: no line about it")
    }

    @Test
    fun aPasswordAndKeyFileIsThePasswordsQuestionWithOneLineAboutTheKeyFile() {
        // spec: unlock.reminder-not-a-lock/AC8
        val kind = ReminderKind.PasswordAndKeyFile("family.keyx")
        assertEquals("Do you still remember it?", ReminderTexts.title(kind))
        assertEquals("Keep the key file family.keyx somewhere safe too: without it the password opens nothing.", ReminderTexts.keyFileLine(kind))
    }

    @Test
    fun aCheckThatDoesNotMatchSaysHowManyTriesAreLeftThenWhatCanBeDone() {
        // spec: unlock.reminder-not-a-lock/AC3
        assertEquals(3, ReminderQuestionModel.TRIES)
        assertEquals("It does not match. 2 tries left.", ReminderTexts.wrong(2))
        assertEquals("It does not match. 1 try left.", ReminderTexts.wrong(1))
        assertEquals(
            "Family stays open, and nothing has changed. If its master password is saved in Family, look it up there. Or set a new one with Change master key.",
            ReminderTexts.failed("Family"),
        )
        assertEquals("Keething asks again tomorrow.", ReminderTexts.AGAIN_TOMORROW)
    }

    @Test
    fun anAnswerSaysWhenItAsksAgain() {
        assertEquals("Keething asks again in 14 days.", ReminderTexts.answered(matched = false, interval = 14))
        assertEquals("It matches. Keething asks again in 14 days.", ReminderTexts.answered(matched = true, interval = 14))
    }
}

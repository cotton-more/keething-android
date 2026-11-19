package dev.keething.android.autofill

import dev.keething.android.R
import dev.keething.android.ui.english
import dev.keething.android.ui.uiText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** How autofill's save screen says its result (keething-spec databases.saf AC6; the canvas "Backups on Remove and Not found", C5). */
class SavedSayingTest {
    @Test
    fun aSaveWithoutTheWarningIsAShortToast() {
        val said = AutofillSaved.of(uiText(R.string.autofill_save_saved), mayNotSync = false)

        assertEquals("Saved to Keething", said.toast?.english())
        assertNull(said.dialog)
    }

    // spec: databases.saf/AC6
    @Test
    fun withTheSyncWarningItIsADialogUntilOkSoNothingIsCut() {
        val said = AutofillSaved.of(uiText(R.string.autofill_save_password_updated), mayNotSync = true)

        assertNull(said.toast)
        assertEquals("Password updated in Keething", said.dialog?.title?.english())
        assertEquals(
            "A sync app may not send the file: its time came out too close to the one it replaced. Change something and save again.",
            said.dialog?.text?.english(),
        )
    }
}

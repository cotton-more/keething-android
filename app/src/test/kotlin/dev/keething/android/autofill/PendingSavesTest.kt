package dev.keething.android.autofill

import dev.keething.autofill.target.FillTarget
import dev.keething.domain.secret.Secret
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PendingSavesTest {
    private val scope = TestScope()
    private val saves = PendingSaves(scope) { scope.testScheduler.currentTime }

    private fun login() = PendingSave(FillTarget.App("com.example.bank"), "alex", Secret.of("pw"), null)

    @Test
    fun aLoginIsTakenOnceByItsToken() {
        val save = login()
        val token = saves.put(save)
        assertNotEquals(token, saves.put(login()), "each its own token")
        assertNull(saves.take("other"))
        assertSame(save, saves.take(token))
        assertNull(saves.take(token), "once")
        assertNull(saves.take(null))
    }

    @Test
    fun oneNotTakenWithinTenMinutesIsWiped() {
        val save = login()
        saves.put(save)
        scope.advanceTimeBy(10 * 60 * 1000L)
        assertFalse(save.password.isWiped, "kept for 10 minutes")
        scope.advanceTimeBy(1)
        scope.runCurrent()
        assertTrue(save.password.isWiped, "then wiped, with no take or put")
    }

    @Test
    fun oneTakenIsLeftToTheSaveScreen() {
        val save = login()
        val token = saves.put(save)
        assertSame(save, saves.take(token))
        scope.advanceTimeBy(10 * 60 * 1000L + 1)
        scope.runCurrent()
        assertFalse(save.password.isWiped, "the save screen wipes it")
    }
}

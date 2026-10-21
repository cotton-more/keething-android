package dev.keepassbar.android.autofill

import dev.keepassbar.autofill.target.FillTarget
import dev.keepassbar.domain.secret.Secret
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PendingSavesTest {
    private var clock = 0L
    private val saves = PendingSaves { clock }

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
        val token = saves.put(save)
        clock += 10 * 60 * 1000L + 1
        assertNull(saves.take(token))
        assertTrue(save.password.isWiped)
    }
}

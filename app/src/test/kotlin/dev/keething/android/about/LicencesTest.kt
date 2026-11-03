package dev.keething.android.about

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** About's licences (SPEC §6.2 "About"). */
class LicencesTest {
    // spec: app.about/AC5
    @Test
    fun everyLicenceFileIsListedAndEveryListedOneIsThere() {
        val files = File("src/main/assets/licenses").list()!!.toSortedSet()
        assertEquals(files, THIRD_PARTY.map { it.file }.toSortedSet())
    }

    @Test
    fun theAppsLicenceIsTheGplThree() {
        val text = File("../LICENSE").readText()
        assertTrue(text.contains("GNU GENERAL PUBLIC LICENSE") && text.contains("Version 3, 29 June 2007"))
        assertEquals("GPL-3.0-or-later", LICENCE)
    }
}

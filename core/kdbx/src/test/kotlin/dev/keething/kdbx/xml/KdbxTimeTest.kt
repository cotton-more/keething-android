package dev.keething.kdbx.xml

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The times in the XML, both ways (Mac `KDBXTime`). */
class KdbxTimeTest {
    @Test
    fun base64IsTheSecondsSinceYearOneLittleEndian() {
        val time = Instant.parse("2026-10-20T19:22:55Z")

        assertEquals(time, KdbxTime.parse(KdbxTime.base64(time)))
        assertEquals("AAAAAAAAAAA=", KdbxTime.base64(Instant.parse("0001-01-01T00:00:00Z")))
    }

    @Test
    fun aFractionOfASecondIsDropped() {
        assertEquals(Instant.parse("2026-10-20T19:22:55Z"), KdbxTime.parse(KdbxTime.base64(Instant.parse("2026-10-20T19:22:55.999Z"))))
    }

    @Test
    fun isoIsWrittenToTheSecondInUtc() {
        assertEquals("2026-10-20T19:22:55Z", KdbxTime.iso(Instant.parse("2026-10-20T19:22:55.5Z")))
        assertTrue(KdbxTime.isIso("2026-10-20T19:22:55Z"))
        assertFalse(KdbxTime.isIso(KdbxTime.base64(Instant.EPOCH)))
    }
}

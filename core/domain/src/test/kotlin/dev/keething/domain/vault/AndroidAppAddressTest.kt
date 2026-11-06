package dev.keething.domain.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidAppAddressTest {
    @Test
    fun thePackageOfAnAppAddress() {
        assertEquals("com.example.app", AndroidAppAddress.packageOf("androidapp://com.example.app"))
        assertEquals("com.example.app", AndroidAppAddress.packageOf("  AndroidApp://com.example.app/  "), "the scheme in any case, a slash after it")
        assertEquals("de.bank.mobile_2", AndroidAppAddress.packageOf("androidapp://de.bank.mobile_2?x=1#y"))
    }

    @Test
    fun notAnAppAddress() {
        assertNull(AndroidAppAddress.packageOf("androidapp://single"), "a package has two or more parts")
        assertNull(AndroidAppAddress.packageOf("androidapp://1com.example"))
        assertNull(AndroidAppAddress.packageOf("androidapp://"))
        assertNull(AndroidAppAddress.packageOf("https://com.example.app"))
        assertNull(AndroidAppAddress.packageOf("com.example.app"))
    }
}

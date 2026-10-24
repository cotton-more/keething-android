package dev.keething.kdbx

import java.io.File

/** The fictitious databases in `fixtures/` (password `pw`), written by keepassxc-cli 2.7. */
internal object Fixtures {
    const val PASSWORD = "pw"

    fun bytes(name: String): ByteArray = File(System.getProperty("keething.fixtures"), name).readBytes()
}

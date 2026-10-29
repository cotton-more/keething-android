package dev.keething.kdbx

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * KeePassXC's command-line tool, when installed on the Mac (ROADMAP step 7): a second, independent reader of the
 * files the writer makes. Tests that need it pass without checking anything when it is not there.
 */
internal object KeePassXcCli {
    private val executable = File("/Applications/KeePassXC.app/Contents/MacOS/keepassxc-cli")

    val isInstalled: Boolean get() = executable.canExecute()

    class Result(val code: Int, val out: ByteArray, val err: String) {
        val text: String get() = out.decodeToString()
    }

    /** Runs it with [input] on standard input: the fixtures' password unless said otherwise. */
    fun run(vararg arguments: String, input: String = "${Fixtures.PASSWORD}\n"): Result {
        val process = ProcessBuilder(listOf(executable.path) + arguments).start()
        process.outputStream.use { it.write(input.encodeToByteArray()) }
        val out = process.inputStream.use { it.readBytes() }
        val err = process.errorStream.use { it.readBytes() }.decodeToString()
        check(process.waitFor(60, TimeUnit.SECONDS)) { "keepassxc-cli did not finish" }
        return Result(process.exitValue(), out, err)
    }
}

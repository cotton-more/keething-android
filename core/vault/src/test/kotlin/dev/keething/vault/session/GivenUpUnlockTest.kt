package dev.keething.vault.session

import dev.keething.domain.secret.Secret
import dev.keething.domain.storage.DatabaseBackups
import dev.keething.kdbx.editing.HostileFiles
import dev.keething.vault.opening.VaultOpener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** keething-spec kdbx.hardening AC6: a key derivation running when the unlock is given up stops. */
class GivenUpUnlockTest {
    private val io = Executors.newSingleThreadExecutor()

    @AfterTest
    fun stopIo() {
        io.shutdownNow()
    }

    // spec: kdbx.hardening/AC6
    @Test
    fun aLockWhileUnlockingStopsTheKeyDerivation() = runTest {
        // 2⁶⁴−1 AES-KDF rounds: within KeePass's limits, and years of work.
        val endless = HostileFiles.withKdfParameters(fixtureBytes("kdbx4-aes-aeskdf"), HostileFiles.aesKdf(rounds = -1))
        val session = VaultSession(
            store = FakeStore(endless),
            opener = VaultOpener(),
            backups = DatabaseBackups { _, _, _ -> },
            clock = FakeClock(testScheduler),
            scope = backgroundScope,
            io = io.asCoroutineDispatcher(),
        )
        val outcome = session.unlock(Secret.of("pw"))!!
        runCurrent()
        Thread.sleep(300)

        session.lock()
        val free = CountDownLatch(1)
        io.execute(free::countDown)

        assertTrue(free.await(2, TimeUnit.SECONDS), "the derivation stopped: the file work's thread is free again")
        assertEquals(UnlockOutcome.Interrupted, outcome.await())
        assertEquals(SessionState.Locked(LockReason.Manual), session.state.value)
    }
}

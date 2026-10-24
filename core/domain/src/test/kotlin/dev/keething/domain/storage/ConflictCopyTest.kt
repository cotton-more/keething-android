package dev.keething.domain.storage

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConflictCopyTest {
    @Test
    fun recognisesSyncthingConflictCopyOfTheDatabase() {
        val copy = ConflictCopy.of("1Password.kdbx", "1Password.sync-conflict-20261018-143005-ABCDEF2.kdbx")

        assertEquals(
            ConflictCopy(
                fileName = "1Password.sync-conflict-20261018-143005-ABCDEF2.kdbx",
                createdAt = LocalDateTime.of(2026, 10, 18, 14, 30, 5),
                deviceId = "ABCDEF2",
            ),
            copy,
        )
    }

    @Test
    fun ignoresTheDatabaseItself() {
        assertNull(ConflictCopy.of("1Password.kdbx", "1Password.kdbx"))
    }

    @Test
    fun ignoresConflictCopiesOfOtherFiles() {
        assertNull(ConflictCopy.of("1Password.kdbx", "Other.sync-conflict-20261018-143005-ABCDEF2.kdbx"))
        assertNull(ConflictCopy.of("1Password.kdbx", "1Password.sync-conflict-20261018-143005-ABCDEF2.xml"))
        assertNull(ConflictCopy.of("1Password.kdbx", "x1Password.sync-conflict-20261018-143005-ABCDEF2.kdbx"))
    }

    @Test
    fun ignoresNamesThatOnlyLookLikeConflictCopies() {
        assertNull(ConflictCopy.of("1Password.kdbx", "1Password.sync-conflict-2026101-143005-ABCDEF2.kdbx"))
        assertNull(ConflictCopy.of("1Password.kdbx", "1Password.sync-conflict-20261018-143005-abcdef2.kdbx"))
        assertNull(ConflictCopy.of("1Password.kdbx", "1Password.sync-conflict-20261399-143005-ABCDEF2.kdbx"))
    }

    @Test
    fun recognisesConflictCopiesOfAFileWithoutExtension() {
        val copy = ConflictCopy.of("vault", "vault.sync-conflict-20261018-143005-ABCDEF2")

        assertEquals("ABCDEF2", copy?.deviceId)
    }

    @Test
    fun recognisesAConflictOfAConflictCopyAsACopyOfTheDatabase() {
        val name = "1Password.sync-conflict-20261018-143005-ABCDEF2.sync-conflict-20261019-090000-ZYXWVU7.kdbx"

        val copy = ConflictCopy.of("1Password.kdbx", name)

        assertEquals(LocalDateTime.of(2026, 10, 19, 9, 0, 0), copy?.createdAt)
        assertEquals("ZYXWVU7", copy?.deviceId)
    }
}

package dev.keething.android.editor

import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.Vault
import dev.keething.domain.vault.VaultGroup
import dev.keething.vault.session.UnsavedChange
import dev.keething.vault.saving.SaveFailure
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What a save says (SPEC §6.2; the Mac `SaveFailure.message` and the editor's notice). */
class SaveTextsTest {
    private val old = VaultGroup("o", "Old", listOf("Work", "Old"))
    private val deep = VaultGroup("d", "Deep", listOf("Work", "Old", "Deep"))
    private val spare = VaultGroup("s", "Spare", listOf("Spare"))

    @Test
    fun theSaveNamesTheEmptyGroupsItDeletedByTheirPaths() {
        assertEquals("Saved and checked", savedText(emptyList()))
        assertEquals("Saved and checked. The empty group “Work › Old” was deleted.", savedText(listOf(old)))
        assertEquals("Saved and checked. The empty groups “Work › Old › Deep” and “Work › Old” were deleted.", savedText(listOf(deep, old)))
        assertEquals("The empty groups “Work › Old › Deep”, “Work › Old” and “Spare” were deleted.", deletedGroupsNote(listOf(deep, old, spare)))
        assertNull(deletedGroupsNote(emptyList()))
    }

    @Test
    fun aGroupChangeRefusedSaysWhyAndThatNothingWasSaved() {
        val file = File("Work.kdbx")

        assertEquals("A group of that name is already there (made elsewhere?). Nothing was saved.", SaveFailure.GroupNameTaken.describe(file))
        assertEquals("The group is not empty any more (changed elsewhere?). Nothing was deleted.", SaveFailure.GroupNotEmpty.describe(file))
        assertEquals("A group needs a name. Nothing was saved.", SaveFailure.InvalidName.describe(file))
        assertEquals(
            "This group cannot be changed so (the top level, the recycle bin or a group in it, or a move into itself or " +
                "where it is; changed elsewhere?). Nothing was saved.",
            SaveFailure.GroupNotChangeable.describe(file),
        )
    }

    // spec: kdbx.hardening/AC3
    @Test
    fun theUnlockAfterALockSaysWhichChangeWasNotSavedByWhatTheVaultCallsItNow() {
        val vault = Vault(
            entries = listOf(Entry("g", title = "GitHub")),
            groups = listOf(VaultGroup("r", "Root", emptyList()), old),
        )
        fun said(vararg changes: UnsavedChange) = unsavedText(changes.toList(), vault)

        assertNull(said())
        assertEquals(
            "The change to “GitHub” was not saved: the database locked while it was being saved. The file is as it was; make the change again.",
            said(UnsavedChange(UnsavedChange.Kind.ENTRY_CHANGED, "g")),
        )
        assertEquals(
            "A new entry, moving “GitHub” to the recycle bin and renaming the group “Work › Old” were not saved: the database " +
                "locked while it was being saved. The file is as it was; make the change again.",
            said(
                UnsavedChange(UnsavedChange.Kind.ENTRY_CREATED, "new"),
                UnsavedChange(UnsavedChange.Kind.ENTRY_TO_RECYCLE_BIN, "g"),
                UnsavedChange(UnsavedChange.Kind.GROUP_RENAMED, "o"),
            ),
        )
        assertEquals(
            "A new group in the top level was not saved: the database locked while it was being saved. The file is as it was; make the change again.",
            said(UnsavedChange(UnsavedChange.Kind.GROUP_CREATED, "r")),
        )
        assertEquals(
            "Deleting “GitHub” for good was not saved: the database locked while it was being saved. The file is as it was; make the change again.",
            said(UnsavedChange(UnsavedChange.Kind.ENTRY_DELETED, "g")),
        )
        assertEquals(
            "The change to an entry was not saved: the database locked while it was being saved. The file is as it was; make the change again.",
            said(UnsavedChange(UnsavedChange.Kind.ENTRY_CHANGED, "gone")),
            "an entry deleted elsewhere since: never a value kept to name it",
        )
    }
}

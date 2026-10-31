package dev.keething.android.editor

import dev.keething.domain.vault.VaultGroup
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
}

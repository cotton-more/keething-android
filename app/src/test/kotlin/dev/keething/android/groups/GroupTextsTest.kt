package dev.keething.android.groups

import dev.keething.android.ui.english
import dev.keething.card.editing.GroupChanges
import dev.keething.card.editing.GroupChoice
import dev.keething.card.editing.GroupPicker
import dev.keething.card.editing.GroupTree
import dev.keething.domain.vault.VaultGroup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What the group picker and the editor's group row say (plans/android-groups.md G2, G3; the AG3 mock-ups). */
class GroupTextsTest {
    private val tree = GroupTree(
        listOf(
            VaultGroup("R", "Vault", emptyList()),
            VaultGroup("travel", "Travel", listOf("Travel")),
            VaultGroup("japan", "Japan", listOf("Travel", "Japan")),
            VaultGroup("hotels", "Hotels", listOf("Travel", "Japan", "Hotels")),
        ),
    )
    private val picker = GroupPicker(tree)

    private fun view(text: String) = picker.view(GroupPicker.Query(text))

    // spec: groups.entry-group/AC1
    @Test
    fun pathsGoWithArrowsTheRootGroupIsTopLevel() {
        assertEquals("Top level", GroupTexts.path(tree, "R").english())
        assertEquals("Travel › Japan", GroupTexts.path(tree, "japan").english())
        assertEquals("", GroupTexts.path(tree, "gone").english())
        assertEquals("Travel › Korea › Seoul", GroupTexts.path(tree, GroupChoice("travel", listOf("Korea", "Seoul"))).english())
        assertEquals("Top level", GroupTexts.path(tree, GroupChoice("R")).english())
    }

    @Test
    fun thePickerSaysWhatItMakesAndWhereItSearches() {
        val path = view("Travel / Korea / Seoul")
        assertEquals("“Korea” is not a group yet", GroupTexts.hint(path)?.english())
        assertEquals("New groups “Korea › Seoul”", GroupTexts.newGroups(path.offer!!.newNames).english())
        assertEquals("in Travel · made when the entry is saved", GroupTexts.newGroupsPlace(tree, path.offer!!, "made when the entry is saved").english())
        assertEquals("“Korea › Seoul” are not groups yet", GroupTexts.hint(view("Travel / Korea / Seoul / "))?.english())

        val qu = view("qu")
        assertNull(GroupTexts.hint(qu))
        assertEquals("New group “qu”", GroupTexts.newGroups(qu.offer!!.newNames).english())
        assertEquals("in Top level · made when the entries are saved", GroupTexts.newGroupsPlace(tree, qu.offer!!, "made when the entries are saved").english())

        assertNull(GroupTexts.hint(view("travel / japan / ")), "the level is said above the rows")
        assertNull(GroupTexts.empty(tree, view("travel / japan / ")))
        assertEquals(listOf(null, "1 group", "3 groups"), listOf(0, 1, 3).map { GroupTexts.subgroups(it)?.english() })
        assertEquals("No groups in “Travel › Japan › Hotels” yet. Type a name to make one.", GroupTexts.empty(tree, view("Travel / Japan / Hotels / "))?.english())
        assertNull(GroupTexts.empty(tree, view("")))
    }

    @Test
    fun theEditorsRowSaysWhatTheSaveMakesOrWhereTheEntryComesFrom() {
        assertEquals("“Seoul” is made when the entry is saved", GroupTexts.note(tree, GroupChoice("travel", listOf("Seoul")), movedFrom = null)?.english())
        assertEquals(
            "3 new groups are made when the entry is saved",
            GroupTexts.note(tree, GroupChoice("travel", listOf("Korea", "Seoul", "Inns")), movedFrom = "hotels")?.english(),
        )
        assertEquals("“Korea” and “Seoul” are made when the entry is saved", GroupTexts.note(tree, GroupChoice("travel", listOf("Korea", "Seoul")), movedFrom = null)?.english())
        assertEquals("Moves here from Travel › Japan › Hotels when saved", GroupTexts.note(tree, GroupChoice("travel"), movedFrom = "hotels")?.english())
        assertNull(GroupTexts.note(tree, GroupChoice("travel"), movedFrom = null))
        assertEquals("“Seoul” is made when the entries are saved", GroupTexts.note(tree, GroupChoice("travel", listOf("Seoul")), null, entries = true)?.english())
    }

    private val top = VaultGroup("R", "Vault", emptyList())
    private val travel = VaultGroup("travel", "Travel", listOf("Travel"))
    private val japan = VaultGroup("japan", "Japan", listOf("Travel", "Japan"))
    private val hotels = VaultGroup("hotels", "Hotels", listOf("Travel", "Japan", "Hotels"))

    // spec: groups.manage/AC1
    @Test
    fun theNamesDialogSaysWhatItDoesAndWhyANameIsRefused() {
        assertEquals("Rename “Japan”", GroupTexts.renameTitle(japan).english())
        assertEquals("New group in “Japan”", GroupTexts.newGroupTitle(japan).english())
        assertEquals("New group at the top level", GroupTexts.newGroupTitle(top).english())
        assertEquals("A group needs a name.", GroupTexts.nameProblem(GroupChanges.Name.Empty)?.english())
        assertEquals("“Travel” has a group “Japan” already.", GroupTexts.nameProblem(GroupChanges.Name.Taken(travel, japan))?.english())
        assertEquals("Top level has a group “Travel” already.", GroupTexts.nameProblem(GroupChanges.Name.Taken(top, travel))?.english())
        assertNull(GroupTexts.nameProblem(GroupChanges.Name.Ok("Kyoto")))
        assertNull(GroupTexts.nameProblem(GroupChanges.Name.Unchanged))
    }

    // spec: groups.manage/AC5
    @Test
    fun moveToSaysWhatGoesAndWhyATargetIsGreyed() {
        assertEquals("Move “Japan” to…", GroupTexts.moveTitle(japan).english())
        assertEquals("Travel › Japan · 12 entries, all go with it", GroupTexts.moveSubtitle(japan, 12).english())
        assertEquals("Travel › Japan · 1 entry goes with it", GroupTexts.moveSubtitle(japan, 1).english())
        assertEquals("Travel › Japan · empty", GroupTexts.moveSubtitle(japan, 0).english())
        assertEquals("The group itself", GroupTexts.moveRefusal(japan, GroupChanges.MoveRefusal.Itself).english())
        assertEquals("Inside “Japan”", GroupTexts.moveRefusal(japan, GroupChanges.MoveRefusal.Inside).english())
        assertEquals("No groups in “Travel › Japan › Hotels” yet.", GroupTexts.empty(tree, view("Travel / Japan / Hotels / "), canMake = false)?.english())
        assertEquals("No group has “Kyoto”.", GroupTexts.empty(tree, view("Kyoto"), canMake = false)?.english(), "nothing offered to make")
        assertEquals("It is here now", GroupTexts.moveRefusal(japan, GroupChanges.MoveRefusal.HereNow).english())
        assertEquals("“Travel” has a “Hotels” already", GroupTexts.moveRefusal(hotels, GroupChanges.MoveRefusal.NameTaken(travel, VaultGroup("h", "Hotels", listOf("Travel", "Hotels")))).english())
        assertEquals("Top level has a “hotels” already", GroupTexts.moveRefusal(hotels, GroupChanges.MoveRefusal.NameTaken(top, VaultGroup("h", "hotels", listOf("hotels")))).english())
    }

    @Test
    fun aGroupsSheetSaysItsDatabaseAndWhatItHolds() {
        assertEquals("Personal · 12 entries · 3 here", GroupTexts.sheetMeta("Personal", total = 12, here = 3, subgroups = 2).english())
        assertEquals("Personal · 12 entries", GroupTexts.sheetMeta("Personal", total = 12, here = 12, subgroups = 0).english())
        assertEquals("Personal · 1 entry", GroupTexts.sheetMeta("Personal", total = 1, here = 1, subgroups = 0).english())
        assertEquals("Personal · 2 entries · none here", GroupTexts.sheetMeta("Personal", total = 2, here = 0, subgroups = 1).english())
        assertEquals("Personal · empty", GroupTexts.sheetMeta("Personal", total = 0, here = 0, subgroups = 0).english())
        assertEquals("Personal · no entries, 2 groups", GroupTexts.sheetMeta("Personal", total = 0, here = 0, subgroups = 2).english())
    }

    // spec: groups.manage/AC8
    @Test
    fun eachChangeSavedSaysSoInTheSnackbar() {
        assertEquals("Renamed to “Kyoto”", GroupTexts.renamed("Kyoto").english())
        assertEquals("Made “Kyoto” in “Japan”", GroupTexts.made("Kyoto", japan).english())
        assertEquals("Made “Kyoto” at the top level", GroupTexts.made("Kyoto", top).english())
        assertEquals("Moved “Hotels” into “Travel”", GroupTexts.moved(hotels, travel).english())
        assertEquals("Moved “Hotels” to the top level", GroupTexts.moved(hotels, top).english())
        assertEquals("Deleted the empty group “Travel › Japan › Hotels”", GroupTexts.deleted(hotels).english())
    }

    // spec: groups.entry-group/AC7
    @Test
    fun theCardsMoveSaysWhereTheEntryWentAndTheEmptyGroupsDeleted() {
        assertEquals("Moved to “Travel › Japan”", GroupTexts.entryMoved(tree, GroupChoice("japan"), emptyList()).english())
        assertEquals("Moved to “Top level”", GroupTexts.entryMoved(tree, GroupChoice("R"), emptyList()).english())
        assertEquals(
            "Moved to “Travel › Korea”. The empty group “Travel › Japan › Hotels” was deleted.",
            GroupTexts.entryMoved(tree, GroupChoice("travel", listOf("Korea")), listOf(hotels)).english(),
        )
    }
}

package dev.keething.android.groups

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
        assertEquals("Top level", GroupTexts.path(tree, "R"))
        assertEquals("Travel › Japan", GroupTexts.path(tree, "japan"))
        assertEquals("", GroupTexts.path(tree, "gone"))
        assertEquals("Travel › Korea › Seoul", GroupTexts.path(tree, GroupChoice("travel", listOf("Korea", "Seoul"))))
        assertEquals("Top level", GroupTexts.path(tree, GroupChoice("R")))
    }

    @Test
    fun thePickerSaysWhatItMakesAndWhereItSearches() {
        val path = view("Travel / Korea / Seoul")
        assertEquals("“Korea” is not a group yet", GroupTexts.hint(path))
        assertEquals("New groups “Korea › Seoul”", GroupTexts.newGroups(path.offer!!.newNames))
        assertEquals("in Travel · made when the entry is saved", GroupTexts.newGroupsPlace(tree, path.offer!!, "made when the entry is saved"))
        assertEquals("“Korea › Seoul” are not groups yet", GroupTexts.hint(view("Travel / Korea / Seoul / ")))

        val qu = view("qu")
        assertNull(GroupTexts.hint(qu))
        assertEquals("New group “qu”", GroupTexts.newGroups(qu.offer!!.newNames))
        assertEquals("in Top level · made when the entries are saved", GroupTexts.newGroupsPlace(tree, qu.offer!!, "made when the entries are saved"))

        assertNull(GroupTexts.hint(view("travel / japan / ")), "the level is said above the rows")
        assertNull(GroupTexts.empty(tree, view("travel / japan / ")))
        assertEquals(listOf(null, "1 group", "3 groups"), listOf(0, 1, 3).map(GroupTexts::subgroups))
        assertEquals("No groups in “Travel › Japan › Hotels” yet. Type a name to make one.", GroupTexts.empty(tree, view("Travel / Japan / Hotels / ")))
        assertNull(GroupTexts.empty(tree, view("")))
    }

    @Test
    fun theEditorsRowSaysWhatTheSaveMakesOrWhereTheEntryComesFrom() {
        assertEquals("“Seoul” is made when the entry is saved", GroupTexts.note(tree, GroupChoice("travel", listOf("Seoul")), movedFrom = null))
        assertEquals(
            "3 new groups are made when the entry is saved",
            GroupTexts.note(tree, GroupChoice("travel", listOf("Korea", "Seoul", "Inns")), movedFrom = "hotels"),
        )
        assertEquals("“Korea” and “Seoul” are made when the entry is saved", GroupTexts.note(tree, GroupChoice("travel", listOf("Korea", "Seoul")), movedFrom = null))
        assertEquals("Moves here from Travel › Japan › Hotels when saved", GroupTexts.note(tree, GroupChoice("travel"), movedFrom = "hotels"))
        assertNull(GroupTexts.note(tree, GroupChoice("travel"), movedFrom = null))
        assertEquals("“Seoul” is made when the entries are saved", GroupTexts.note(tree, GroupChoice("travel", listOf("Seoul")), null, saved = "the entries are saved"))
    }

    private val top = VaultGroup("R", "Vault", emptyList())
    private val travel = VaultGroup("travel", "Travel", listOf("Travel"))
    private val japan = VaultGroup("japan", "Japan", listOf("Travel", "Japan"))
    private val hotels = VaultGroup("hotels", "Hotels", listOf("Travel", "Japan", "Hotels"))

    // spec: groups.manage/AC1
    @Test
    fun theNamesDialogSaysWhatItDoesAndWhyANameIsRefused() {
        assertEquals("Rename “Japan”", GroupTexts.renameTitle(japan))
        assertEquals("New group in “Japan”", GroupTexts.newGroupTitle(japan))
        assertEquals("New group at the top level", GroupTexts.newGroupTitle(top))
        assertEquals("A group needs a name.", GroupTexts.nameProblem(GroupChanges.Name.Empty))
        assertEquals("“Travel” has a group “Japan” already.", GroupTexts.nameProblem(GroupChanges.Name.Taken(travel, japan)))
        assertEquals("Top level has a group “Travel” already.", GroupTexts.nameProblem(GroupChanges.Name.Taken(top, travel)))
        assertNull(GroupTexts.nameProblem(GroupChanges.Name.Ok("Kyoto")))
        assertNull(GroupTexts.nameProblem(GroupChanges.Name.Unchanged))
    }

    // spec: groups.manage/AC5
    @Test
    fun moveToSaysWhatGoesAndWhyATargetIsGreyed() {
        assertEquals("Move “Japan” to…", GroupTexts.moveTitle(japan))
        assertEquals("Travel › Japan · 12 entries, all go with it", GroupTexts.moveSubtitle(japan, 12))
        assertEquals("Travel › Japan · 1 entry goes with it", GroupTexts.moveSubtitle(japan, 1))
        assertEquals("Travel › Japan · empty", GroupTexts.moveSubtitle(japan, 0))
        assertEquals("The group itself", GroupTexts.moveRefusal(japan, GroupChanges.MoveRefusal.Itself))
        assertEquals("Inside “Japan”", GroupTexts.moveRefusal(japan, GroupChanges.MoveRefusal.Inside))
        assertEquals("No groups in “Travel › Japan › Hotels” yet.", GroupTexts.empty(tree, view("Travel / Japan / Hotels / "), canMake = false))
        assertEquals("No group has “Kyoto”.", GroupTexts.empty(tree, view("Kyoto"), canMake = false), "nothing offered to make")
        assertEquals("It is here now", GroupTexts.moveRefusal(japan, GroupChanges.MoveRefusal.HereNow))
        assertEquals("“Travel” has a “Hotels” already", GroupTexts.moveRefusal(hotels, GroupChanges.MoveRefusal.NameTaken(travel, VaultGroup("h", "Hotels", listOf("Travel", "Hotels")))))
        assertEquals("Top level has a “hotels” already", GroupTexts.moveRefusal(hotels, GroupChanges.MoveRefusal.NameTaken(top, VaultGroup("h", "hotels", listOf("hotels")))))
    }

    @Test
    fun aGroupsSheetSaysItsDatabaseAndWhatItHolds() {
        assertEquals("Personal · 12 entries · 3 here", GroupTexts.sheetMeta("Personal", total = 12, here = 3, subgroups = 2))
        assertEquals("Personal · 12 entries", GroupTexts.sheetMeta("Personal", total = 12, here = 12, subgroups = 0))
        assertEquals("Personal · 1 entry", GroupTexts.sheetMeta("Personal", total = 1, here = 1, subgroups = 0))
        assertEquals("Personal · 2 entries · none here", GroupTexts.sheetMeta("Personal", total = 2, here = 0, subgroups = 1))
        assertEquals("Personal · empty", GroupTexts.sheetMeta("Personal", total = 0, here = 0, subgroups = 0))
        assertEquals("Personal · no entries, 2 groups", GroupTexts.sheetMeta("Personal", total = 0, here = 0, subgroups = 2))
    }

    // spec: groups.manage/AC8
    @Test
    fun eachChangeSavedSaysSoInTheSnackbar() {
        assertEquals("Renamed to “Kyoto”", GroupTexts.renamed("Kyoto"))
        assertEquals("Made “Kyoto” in “Japan”", GroupTexts.made("Kyoto", japan))
        assertEquals("Made “Kyoto” at the top level", GroupTexts.made("Kyoto", top))
        assertEquals("Moved “Hotels” into “Travel”", GroupTexts.moved(hotels, travel))
        assertEquals("Moved “Hotels” to the top level", GroupTexts.moved(hotels, top))
        assertEquals("Deleted the empty group “Travel › Japan › Hotels”", GroupTexts.deleted(hotels))
    }

    // spec: groups.entry-group/AC7
    @Test
    fun theCardsMoveSaysWhereTheEntryWentAndTheEmptyGroupsDeleted() {
        assertEquals("Moved to “Travel › Japan”", GroupTexts.entryMoved(tree, GroupChoice("japan"), emptyList()))
        assertEquals("Moved to “Top level”", GroupTexts.entryMoved(tree, GroupChoice("R"), emptyList()))
        assertEquals(
            "Moved to “Travel › Korea”. The empty group “Travel › Japan › Hotels” was deleted.",
            GroupTexts.entryMoved(tree, GroupChoice("travel", listOf("Korea")), listOf(hotels)),
        )
    }
}

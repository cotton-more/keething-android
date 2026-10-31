package dev.keething.android.groups

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
}

package dev.keething.card.editing

import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.EntryChanges
import dev.keething.domain.vault.GroupDestination
import dev.keething.domain.vault.NewGroup
import dev.keething.domain.vault.Vault
import dev.keething.domain.vault.VaultEdit
import dev.keething.domain.vault.VaultGroup
import java.text.Collator
import java.time.Instant
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What Groups & tags may change (keething-spec groups.manage AC1, AC3, AC5–AC7 on the screen; the Mac's
 * `GroupsAndTagsModel.commitEditing`, `canMove`, `GroupRow.canDelete`; plans/android-groups.md G5), and the card's
 * Move to group… (groups.entry-group AC2 from the card, G6).
 */
class GroupChangesTest {
    // In the file's order. Work holds Mail (an entry) and Old (empty); Archive holds "old" in another case; Conf holds
    // only the Mac app's settings entry; two "Rail" at the top level (another app made them).
    private val groups = listOf(
        VaultGroup("R", "Vault", emptyList()),
        VaultGroup("work", "Work", listOf("Work")),
        VaultGroup("mail", "Mail", listOf("Work", "Mail")),
        VaultGroup("old", "Old", listOf("Work", "Old")),
        VaultGroup("archive", "Archive", listOf("Archive")),
        VaultGroup("archive-old", "old", listOf("Archive", "old")),
        VaultGroup("cafe", "Café", listOf("Café")),
        VaultGroup("conf", "Conf", listOf("Conf")),
        VaultGroup("rail-1", "Rail", listOf("Rail")),
        VaultGroup("rail-2", "Rail", listOf("Rail")),
    )
    private val moved = Instant.parse("2026-10-01T10:00:00Z")
    private val vault = Vault(
        entries = listOf(Entry("1", groupId = "mail", lastModified = moved)),
        settings = Entry("s", title = "Keething", groupId = "conf"),
        groups = groups,
    )
    private val changes = GroupChanges.of(vault, Collator.getInstance(Locale.ENGLISH))

    // spec: groups.manage/AC1
    @Test
    fun aRenameIsTrimmedTheSameNameEndsItAndAnEmptyNameOrOneTheParentHasInAnyCaseOrAccentIsRefused() {
        assertEquals(GroupChanges.Name.Ok("Post"), changes.rename("mail", "  Post "))
        assertEquals(GroupChanges.Name.Unchanged, changes.rename("mail", "Mail"))
        assertEquals(GroupChanges.Name.Unchanged, changes.rename("mail", " Mail  "), "the same name once trimmed")
        assertEquals(GroupChanges.Name.Empty, changes.rename("mail", ""))
        assertEquals(GroupChanges.Name.Empty, changes.rename("mail", "   "))
        assertEquals(GroupChanges.Name.Taken(groups[1], groups[3]), changes.rename("mail", "OLD"), "Work has Old, in another case")
        assertEquals(GroupChanges.Name.Taken(groups[0], groups[6]), changes.rename("archive", "cafe"), "the top level has Café, accents aside")
        assertEquals(GroupChanges.Name.Ok("mail"), changes.rename("mail", "mail"), "its own name in another case is not taken")
        assertEquals(GroupChanges.Name.Ok("Old"), changes.rename("archive-old", "Old"), "Work's Old is in another parent")
        assertEquals(GroupChanges.Name.Taken(groups[0], groups[9]), changes.rename("rail-1", "rail"), "its namesake beside it has the name")
    }

    // spec: groups.manage/AC3
    @Test
    fun aNewGroupHasTheSameNameRulesInAnyGroupTheTopLevelToo() {
        assertEquals(GroupChanges.Name.Ok("Bank"), changes.create("R", " Bank "))
        assertEquals(GroupChanges.Name.Ok("Mail"), changes.create("archive", "Mail"))
        assertEquals(GroupChanges.Name.Empty, changes.create("work", " "))
        assertEquals(GroupChanges.Name.Taken(groups[1], groups[2]), changes.create("work", "MAIL"))
        assertEquals(GroupChanges.Name.Taken(groups[0], groups[6]), changes.create("R", "CAFE"))
        assertNull(changes.create("gone", "Bank"), "a group gone meanwhile")
    }

    // The owner, 2026-11-01: one name under different parents, so different full paths, is allowed however deep.
    @Test
    fun aNameIsTakenOnlyBesideItsNamesakeNotUnderAnotherParent() {
        assertEquals(GroupChanges.Name.Ok("Mail"), changes.create("archive", "Mail"), "Work › Mail; Archive › Mail is another path")
        assertEquals(GroupChanges.Name.Ok("Mail"), changes.create("archive-old", "Mail"), "a level deeper too")
        assertEquals(GroupChanges.Name.Ok("Mail"), changes.create("R", "Mail"), "the top level has no Mail")
        assertEquals(GroupChanges.Name.Ok("Work"), changes.create("work", "Work"), "a group may hold one of its own name")
        assertEquals(GroupChanges.Name.Ok("old"), changes.rename("old", "old"), "Archive's “old” is in another parent")
        assertNull(changes.moveRefusal("mail", "archive"), "Archive has no Mail")
        assertEquals(GroupChanges.MoveRefusal.NameTaken(groups[1], groups[3]), changes.moveRefusal("archive-old", "work"), "beside Work's Old it is taken")
        assertNull(changes.moveRefusal("old", "mail"), "Work › Mail › Old is another path than Archive › old")
    }

    // spec: groups.manage/AC7
    @Test
    fun theTopLevelIsNeverRenamedMovedOrDeletedAndAGroupGoneNotAtAll() {
        assertFalse(changes.canChange("R"))
        assertFalse(changes.canDelete("R"))
        assertNull(changes.rename("R", "Top"))
        assertTrue(changes.canChange("mail"))
        assertFalse(changes.canChange("gone"))
        assertNull(changes.rename("gone", "Post"))
    }

    // spec: groups.manage/AC6
    @Test
    fun onlyAGroupWithNoEntriesAndNoGroupsCanBeDeletedTheSettingsEntryCounted() {
        assertTrue(changes.canDelete("old"))
        assertTrue(changes.canDelete("cafe"))
        assertFalse(changes.canDelete("mail"), "an entry")
        assertFalse(changes.canDelete("work"), "groups, its entry below them")
        assertFalse(changes.canDelete("archive"), "an empty group inside")
        assertFalse(changes.canDelete("conf"), "the Mac app's settings entry is in it")
    }

    // spec: groups.manage/AC5
    @Test
    fun aMoveIsRefusedIntoItselfOrAGroupInsideItWhereItIsAndBesideAGroupOfItsName() {
        assertEquals(GroupChanges.MoveRefusal.Itself, changes.moveRefusal("work", "work"))
        assertEquals(GroupChanges.MoveRefusal.Inside, changes.moveRefusal("work", "mail"))
        assertEquals(GroupChanges.MoveRefusal.HereNow, changes.moveRefusal("mail", "work"))
        assertEquals(GroupChanges.MoveRefusal.HereNow, changes.moveRefusal("work", "R"))
        assertEquals(GroupChanges.MoveRefusal.NameTaken(groups[4], groups[5]), changes.moveRefusal("old", "archive"), "Archive has “old”")
        assertNull(changes.moveRefusal("old", "R"), "the top level is a target")
        assertNull(changes.moveRefusal("mail", "archive"))
        assertNull(changes.moveRefusal("archive-old", "R"), "no “old” at the top level")
        assertTrue(changes.canMove("old", "R"))
        assertFalse(changes.canMove("old", "archive"))
        assertFalse(changes.canMove("R", "work"), "the top level never moves")
        assertFalse(changes.canMove("old", "gone"), "a target gone meanwhile")
    }

    // The owner's example, 2026-11-01: a chain a › b › c › d › e, c holding f and g too. A group moves anywhere up or
    // down its own branch or into another, but never into itself or below itself.
    // spec: groups.manage/AC4
    @Test
    fun aGroupMovesUpOrDownItsBranchWithEverythingInItButNeverBelowItself() {
        val chain = GroupChanges(
            GroupTree(
                listOf(
                    VaultGroup("R", "Vault", emptyList()),
                    VaultGroup("a", "a", listOf("a")),
                    VaultGroup("b", "b", listOf("a", "b")),
                    VaultGroup("c", "c", listOf("a", "b", "c")),
                    VaultGroup("d", "d", listOf("a", "b", "c", "d")),
                    VaultGroup("e", "e", listOf("a", "b", "c", "d", "e")),
                    VaultGroup("f", "f", listOf("a", "b", "c", "f")),
                    VaultGroup("g", "g", listOf("a", "b", "c", "g")),
                ),
            ),
        )
        assertTrue(chain.canMove("g", "e"), "down, into another branch of its parent")
        assertTrue(chain.canMove("g", "d"))
        assertTrue(chain.canMove("g", "f"), "into its sibling")
        assertTrue(chain.canMove("g", "b"), "up")
        assertTrue(chain.canMove("g", "R"))
        assertEquals(GroupChanges.MoveRefusal.HereNow, chain.moveRefusal("g", "c"))
        assertTrue(chain.canMove("c", "a"), "up, with d, e, f and g")
        assertTrue(chain.canMove("c", "R"))
        assertTrue(chain.canMove("d", "f"), "d with e into its sibling f")
        assertTrue(chain.canMove("e", "b"), "e out of d, up two levels")
        assertEquals(GroupChanges.MoveRefusal.Inside, chain.moveRefusal("c", "d"), "below itself: a loop")
        assertEquals(GroupChanges.MoveRefusal.Inside, chain.moveRefusal("c", "e"))
        assertEquals(GroupChanges.MoveRefusal.Inside, chain.moveRefusal("a", "g"))
        assertEquals(GroupChanges.MoveRefusal.HereNow, chain.moveRefusal("c", "b"))
    }

    // spec: groups.entry-group/AC2
    @Test
    fun theCardsMoveIsTheEntrysMoveAloneItsNewGroupsMadeWithItAndNothingWhereItIs() {
        val entry = vault.entries[0]
        assertNull(EntryMove.edit(entry, GroupChoice("mail")))
        assertEquals(
            VaultEdit.Update("1", moved, EntryChanges(group = GroupDestination("archive"))),
            EntryMove.edit(entry, GroupChoice("archive")),
        )
        val ids = listOf("n1", "n2").iterator()
        assertEquals(
            VaultEdit.Update("1", moved, EntryChanges(group = GroupDestination("mail", listOf(NewGroup("n1", "Banks"), NewGroup("n2", "DE"))))),
            EntryMove.edit(entry, GroupChoice("mail", listOf("Banks", "DE")), ids::next),
            "new groups inside its own group are a move",
        )
    }
}

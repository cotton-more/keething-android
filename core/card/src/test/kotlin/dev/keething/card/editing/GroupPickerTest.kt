package dev.keething.card.editing

import dev.keething.card.GroupPathFormatter
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.VaultGroup
import java.text.Collator
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The group picker (keething-spec groups.new-groups AC1, AC2 on the phone, plans/android-groups.md G2, G8; the owner's
 * examples) and the tree it shows; a path typed walked as the Mac's `EntryEditorModel.resolve` (`GroupTests`).
 */
class GroupPickerTest {
    // In the file's order: each group's groups follow it. Two top-level "Mail" (KeePassXC allows it), the second holding
    // Old; two "Rail" in Travel, the second holding Night.
    private val groups = listOf(
        VaultGroup("R", "Vault", emptyList()),
        VaultGroup("foo", "Foo", listOf("Foo")),
        VaultGroup("bar", "Bar", listOf("Foo", "Bar")),
        VaultGroup("bar-qux", "Qux", listOf("Foo", "Bar", "Qux")),
        VaultGroup("travel", "Travel", listOf("Travel")),
        VaultGroup("japan", "Japan", listOf("Travel", "Japan")),
        VaultGroup("japan-hotels", "Hotels", listOf("Travel", "Japan", "Hotels")),
        VaultGroup("rail-1", "Rail", listOf("Travel", "Rail")),
        VaultGroup("rail-2", "Rail", listOf("Travel", "Rail")),
        VaultGroup("night", "Night", listOf("Travel", "Rail", "Night")),
        VaultGroup("qux", "Qux", listOf("Qux")),
        VaultGroup("hotels", "Hotels", listOf("Hotels")),
        VaultGroup("mail-1", "Mail", listOf("Mail")),
        VaultGroup("shopping", "Shopping", listOf("Shopping")),
        VaultGroup("mail-2", "mail", listOf("mail")),
        VaultGroup("old", "Old", listOf("mail", "Old")),
        VaultGroup("cafe", "Café", listOf("Café")),
        VaultGroup("ab", "A/B", listOf("A/B")),
    )
    private val entries = listOf(
        Entry("1", groupId = "R"), Entry("2", groupId = "bar-qux"), Entry("3", groupId = "bar-qux"), Entry("4", groupId = "bar"),
        Entry("5", groupId = "japan-hotels"), Entry("6", groupId = "old"),
    )
    private val tree = GroupTree(groups, entries, Collator.getInstance(Locale.ENGLISH))
    private val picker = GroupPicker(tree)

    private fun view(text: String, entered: GroupPicker.Entered? = null) = picker.view(GroupPicker.Query(text, entered))

    private fun ids(view: GroupPicker.View) = view.rows.map { it.node.group.id }

    private fun bold(row: GroupPicker.Row) = row.match?.let { row.node.group.name.substring(it) }

    @Test
    fun nothingTypedShowsTheTreeTopLevelFirstEachGroupInItsOwnParentWithItsEntries() {
        assertEquals(
            listOf("R", "ab", "cafe", "foo", "bar", "bar-qux", "hotels", "mail-1", "mail-2", "old", "qux", "shopping", "travel", "japan", "japan-hotels", "rail-1", "rail-2", "night"),
            tree.nodes.map { it.group.id },
            "siblings by name, namesakes in the file's order; Old and Night under the second of their parent's name, where the file has them",
        )
        assertEquals(listOf(0, 1, 1, 1, 2, 3, 1, 1, 1, 2, 1, 1, 1, 2, 3, 2, 2, 3), tree.nodes.map { it.depth })
        val totals = tree.nodes.associate { it.group.id to it.total }
        assertEquals(6, totals["R"])
        assertEquals(3, totals["foo"])
        assertEquals(1, totals["mail-2"])
        assertEquals(0, totals["mail-1"])
        assertEquals(listOf("mail-1", "mail-2", "rail-1", "rail-2"), tree.nodes.filter { it.hasNamesake }.map { it.group.id }, "two of a name in one parent, in any case")
        assertEquals("rail-2", tree.parentOf("night")?.id)

        assertEquals(mapOf("R" to 9, "foo" to 1, "travel" to 3, "japan" to 1, "mail-1" to 0, "mail-2" to 1), tree.nodes.associate { it.group.id to it.subgroups }.filterKeys { it in setOf("R", "foo", "travel", "japan", "mail-1", "mail-2") })
    }

    // The owner, 2026-10-31: one level at a time instead of the whole tree, so a deep group keeps the row's width.
    @Test
    fun nothingTypedListsTheTopLevelItselfFirstThenItsGroupsAndTheArrowAndUpWalkTheLevels() {
        val top = view("")
        assertTrue(top.isLevel)
        assertEquals("R", top.level?.id)
        assertEquals(listOf("R", "ab", "cafe", "foo", "hotels", "mail-1", "mail-2", "qux", "shopping", "travel"), ids(top), "by name, as the tree")
        assertTrue(top.rows.all { it.match == null && it.ancestors.isEmpty() })
        assertNull(top.offer)
        assertNull(top.typed)
        assertEquals(top, view("   "))

        val travel = picker.enter("travel")
        val inTravel = picker.view(travel)
        assertTrue(inTravel.isLevel)
        assertEquals(listOf("travel", "japan", "rail-1", "rail-2"), ids(inTravel), "the group itself first, to be chosen")
        assertTrue(inTravel.rows.all { it.ancestors.isEmpty() }, "the path is said above the rows")
        val rail = picker.enter("rail-2")
        assertEquals(listOf("rail-2", "night"), ids(picker.view(rail)))

        assertEquals(travel, picker.up(picker.view(rail)))
        assertEquals(GroupPicker.Query(), picker.up(inTravel))
        assertEquals(GroupPicker.Query(), picker.up(top))
        assertEquals(GroupPicker.Query(), picker.up(view("Foo / baz / ")), "a level not made yet: back to the top")
        assertEquals(picker.enter("foo"), picker.up(view("Foo / Bar / ")), "a path typed goes up the same way")
    }

    // spec: groups.new-groups/AC1
    @Test
    fun typingFindsGroupsAtAnyDepthByAPartOfTheNameThoseDirectlyInsideFirst() {
        // The owner's example: Foo / Bar / Qux and a top-level Qux.
        val qu = view("qu")
        assertFalse(qu.isLevel)
        assertEquals(listOf("qux", "bar-qux"), ids(qu))
        assertEquals(listOf(emptyList(), listOf("Foo", "Bar")), qu.rows.map { it.ancestors }, "each by its whole path")
        assertEquals(listOf("Qu", "Qu"), qu.rows.map(::bold))
        assertEquals(GroupChoice("R", listOf("qu")), qu.offer, "no group directly inside is named qu: + New group")

        // The mock-up's: Hotels and Shopping at the top, then Travel › Japan › Hotels.
        assertEquals(listOf("hotels", "shopping", "japan-hotels"), ids(view("ho")))
        assertEquals("ho", bold(view("ho").rows[1]))

        // Case and diacritics folded; what is bold is the name as written.
        assertEquals(listOf("cafe"), ids(view("CAFE")))
        assertEquals("Café", bold(view("cafe").rows[0]))
        assertEquals("fé", bold(view("FE").rows[0]))
        assertEquals(listOf("mail-1", "mail-2"), ids(view("MAÏL")), "both namesakes, each by its id")

        assertEquals(emptyList(), ids(view("zzz")))
        assertEquals(GroupChoice("R", listOf("zzz")), view("zzz").offer)
    }

    // spec: groups.new-groups/AC1
    @Test
    fun aPathTypedSearchesBelowTheGroupItsLevelsName() {
        val below = view("Foo / Bar / qu")
        assertEquals(listOf("bar-qux"), ids(below), "only below Foo › Bar: not the top-level Qux")
        assertEquals("bar", below.level?.id)
        assertEquals(GroupChoice("bar", listOf("qu")), below.offer)
        assertEquals(listOf("bar"), ids(view("FOO// ba")), "levels in any case, empty ones dropped")

        val inside = view("Foo / ")
        assertTrue(inside.isLevel)
        assertEquals(listOf("foo", "bar"), ids(inside), "an empty last level: that level, as › lists it")
        assertEquals("foo", inside.level?.id)
        assertNull(inside.offer)
        assertEquals(GroupChoice("foo"), inside.typed)

        val none = view("Qux / ")
        assertEquals(listOf("qux"), ids(none), "a group with none inside: itself alone")
        assertEquals("", none.last)
        assertNull(none.offer)

        // An earlier level not a group yet: nothing to search, the levels made.
        val missing = view("Foo / baz / Zed")
        assertEquals(emptyList(), missing.rows)
        assertNull(missing.level)
        assertFalse(missing.isLevel)
        assertEquals(listOf("baz"), missing.missing)
        assertEquals(GroupChoice("foo", listOf("baz", "Zed")), missing.offer)
        assertEquals(GroupChoice("foo", listOf("baz")), view("Foo / baz /").offer)
    }

    // spec: groups.new-groups/AC2
    @Test
    fun whatIsTypedIsANewGroupUnlessAGroupDirectlyInsideHasThatName() {
        assertEquals(GroupChoice("R", listOf("qu")), view("qu").typed)
        val qux = view("qux")
        assertNull(qux.offer, "the top level has a Qux")
        assertEquals(GroupChoice("qux"), qux.typed)
        assertEquals(listOf("qux", "bar-qux"), ids(qux))
        assertEquals(GroupChoice("cafe"), view("cafe").typed, "accents aside")
        assertEquals(GroupChoice("mail-1"), view("mail").typed, "the first of a name")
        assertEquals(GroupChoice("bar-qux"), view("foo / bar / QUX ").typed)
        assertEquals(GroupChoice("japan", listOf("Kyoto")), view(" Travel/Japan /  Kyoto ").typed, "names trimmed")

        // A group with "/" in its name spans two levels (the Mac's GroupTests).
        assertEquals(GroupChoice("ab", listOf("C")), view("A/B / C").typed)
        val ab = view("a/b")
        assertEquals(GroupChoice("ab"), ab.typed)
        assertNull(ab.offer)
        assertEquals(listOf("ab"), ids(ab), "the group the path names is a row")
    }

    // spec: groups.new-groups/AC2
    @Test
    fun aRowsArrowPutsItsPathInTheFieldAndListsItsGroupsTheOneEnteredEvenWithANamesakeFirst() {
        val bar = picker.enter("bar")
        assertEquals(GroupPicker.Query("Foo / Bar / ", GroupPicker.Entered("bar", "Foo / Bar / ")), bar)
        assertEquals(listOf("bar", "bar-qux"), ids(picker.view(bar)))
        assertEquals(GroupChoice("bar", listOf("Zed")), picker.view(bar.copy(text = bar.text + "Zed")).offer)

        // The second Mail, entered: its own groups, not the first one's (G8).
        val second = picker.enter("mail-2")
        assertEquals("mail / ", second.text)
        assertEquals(listOf("mail-2", "old"), ids(picker.view(second)))
        assertEquals("mail-2", picker.view(second).level?.id)
        assertEquals(GroupChoice("mail-2", listOf("New")), picker.view(second.copy(text = "mail / New")).typed)
        assertEquals(GroupChoice("old"), picker.view(second.copy(text = "mail / o")).rows.single().let { GroupChoice(it.node.group.id) })
        // Backed out of a group by editing the text: still below the namesake › went into, as long as its path is typed.
        val night = picker.enter("night")
        assertEquals("Travel / Rail / Night / ", night.text)
        assertEquals("rail-2", picker.view(night.copy(text = "Travel / Rail / ")).level?.id)
        assertEquals("rail-2", picker.view(night.copy(text = "Travel / Rail /")).level?.id, "spaces around / aside")
        assertEquals(GroupChoice("rail-2", listOf("X")), picker.view(night.copy(text = "Travel / Rail / X")).typed)
        assertEquals(GroupChoice("night"), picker.view(night.copy(text = "Travel / Rail / Night")).typed)
        assertEquals("travel", picker.view(night.copy(text = "Travel / ")).level?.id)
        // The text no longer starts with the arrow's: walked from the top again, the first of the name.
        assertEquals(GroupChoice("mail-1", listOf("New")), picker.view(GroupPicker.Query("Mail / New", second.entered)).typed)
        assertEquals(GroupChoice("mail-1"), picker.view(GroupPicker.Query("mail", second.entered)).typed)
    }

    // spec: groups.entry-group/AC1
    @Test
    fun theEditorsRowShowsThePathFromTheTopTheNewLevelsMarked() {
        assertEquals(emptyList(), tree.levels(GroupChoice("R")), "the root group: Top level")
        assertEquals(
            listOf(GroupTree.Level("Travel", false), GroupTree.Level("Japan", false), GroupTree.Level("Kyoto", true), GroupTree.Level("Inns", true)),
            tree.levels(GroupChoice("japan", listOf("Kyoto", "Inns"))),
        )
        assertEquals(listOf(GroupTree.Level("Zed", true)), tree.levels(GroupChoice("R", listOf("Zed"))))
        assertNull(tree.levels(GroupChoice("gone")))
        assertEquals("mail-2", tree.parentOf("old")?.id)
        assertEquals(listOf("mail-2"), tree.children("R").filter { it.name == "mail" }.map { it.id })
        assertEquals(listOf("foo", "travel", "qux", "hotels", "mail-1", "shopping", "mail-2", "cafe", "ab"), tree.children("R").map { it.id }, "the file's order")
    }

    // The owner, 2026-10-31: only the group's own name always whole, the levels above it cut as fish shell cuts them.
    @Test
    fun aLongPathForOneLineKeepsTheGroupsOwnNameAndCutsTheLevelsAboveAsFishDoes() {
        val path = listOf("Archive", "2019", "Client Alpha Corporation", "Infrastructure", "Read only")
        assertEquals(
            listOf(
                "Archive › 2019 › Client Alpha Corporation › Infrastructure › Read only",
                "A › 2019 › Client Alpha Corporation › Infrastructure › Read only",
                "A › 2 › Client Alpha Corporation › Infrastructure › Read only",
                "A › 2 › C › Infrastructure › Read only",
                "A › 2 › C › I › Read only",
                "… › Read only",
            ),
            GroupPathFormatter.shortenings(path),
        )
        assertEquals(listOf("Work › Mail", "W › Mail", "… › Mail"), GroupPathFormatter.shortenings(listOf("Work", "Mail")))
        assertEquals(listOf("Mail"), GroupPathFormatter.shortenings(listOf("Mail")))
        assertEquals("Ж", GroupPathFormatter.shortenings(listOf("Жильё", "B"))[1].split(" › ")[0], "a letter of any script")
        assertEquals("😀", GroupPathFormatter.shortenings(listOf("😀 fun", "B"))[1].split(" › ")[0], "never half a character")
    }
}

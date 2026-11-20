package dev.keething.android.groups

import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.Vault
import dev.keething.domain.vault.VaultGroup
import dev.keething.search.EntrySearch
import java.text.Collator
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class GroupsOverviewTest {
    private val root = VaultGroup("root", "Passwords", emptyList())
    private val work = VaultGroup("work", "Work", listOf("Work"))
    private val servers = VaultGroup("srv", "Servers", listOf("Work", "Servers"))
    private val archive = VaultGroup("arc", "Archive", listOf("Work", "Archive"))
    private val family = VaultGroup("fam", "Family Shared", listOf("Family Shared"))

    private fun entry(id: String, group: VaultGroup, vararg tags: String) =
        Entry(id = id, title = id, groupPath = group.path, groupId = group.id, tags = tags.toList())

    private val vault = Vault(
        entries = listOf(
            entry("a", root, "Bank"),
            entry("b", work, "bank", "dev"),
            entry("c", servers, "Dev"),
            entry("d", servers, "Bänk"),
            entry("e", family),
        ),
        settings = entry("settings", root),
        groups = listOf(root, work, servers, archive, family),
        recycleBin = listOf(Entry(id = "x", groupPath = listOf("Recycle Bin"))),
    )

    private val overview = GroupsOverview.of(vault, Collator.getInstance(Locale.ENGLISH))

    @Test
    fun theTreeStartsWithTheRootGroupThenParentsBeforeChildrenAndSiblingsByName() {
        assertEquals(listOf("root", "fam", "work", "arc", "srv"), overview.groups.map { it.id })
        assertEquals(listOf(0, 1, 1, 2, 2), overview.groups.map { it.depth })
    }

    @Test
    fun eachGroupCountsTheEntriesRightInItAndBelowItWithoutTheSettingsEntry() {
        assertEquals(listOf(1, 1, 1, 0, 2), overview.groups.map { it.here })
        assertEquals(listOf(5, 1, 3, 0, 2), overview.groups.map { it.total }, "an empty group is there too, with 0")
        assertEquals(1, overview.recycleBin)
    }

    @Test
    fun aRowSearchesForItsEntriesAsTheCardsChipsDo() {
        val byId = overview.groups.associateBy { it.id }
        assertEquals("", byId.getValue("root").search, "the root group is the whole list")
        assertEquals("@Work ", byId.getValue("work").search)
        assertEquals("@\"Family Shared\" ", byId.getValue("fam").search)
        assertEquals("#dev ", overview.tags.first { it.name == "dev" }.search)
        assertEquals("", EntrySearch.recycleBinQuery(GroupsOverview.RECYCLE_BIN_SEARCH), "the whole recycle bin")
    }

    @Test
    fun tagsDifferingOnlyInCaseOrDiacriticsAreOneWithTheirOtherSpellings() {
        assertEquals(listOf("Bank", "dev"), overview.tags.map { it.name })
        assertEquals(listOf(3, 2), overview.tags.map { it.count })
        assertEquals(listOf("bank", "Bänk"), overview.tags[0].otherSpellings)
        assertEquals(listOf("Dev"), overview.tags[1].otherSpellings)
    }

    @Test
    fun twoGroupsOfOneNameInOneParentAreEachThemselvesWithTheirOwnCountsAndGroupsMarkedAsNamesakes() {
        // Another app made them: by the file's order each group's groups follow it.
        val alpha = VaultGroup("alpha", "Alpha", listOf("Work", "Alpha"))
        val otherWork = VaultGroup("work2", "work", listOf("work"))
        val zed = VaultGroup("zed", "Zed", listOf("work", "Zed"))
        val empty = VaultGroup("work3", "Work", listOf("Work"))
        val vault = Vault(
            entries = listOf(entry("a", work), entry("b", work), entry("c", alpha), entry("d", zed)),
            groups = listOf(root, work, alpha, otherWork, zed, empty),
        )
        val rows = GroupsOverview.of(vault, Collator.getInstance(Locale.ENGLISH)).groups.associateBy { it.id }
        assertEquals(3 to 2, rows.getValue("work").let { it.total to it.here })
        assertEquals(1 to 0, rows.getValue("work2").let { it.total to it.here })
        assertEquals(0 to 0, rows.getValue("work3").let { it.total to it.here })
        val order = GroupsOverview.of(vault, Collator.getInstance(Locale.ENGLISH)).groups.map { it.id }
        assertEquals(order.indexOf("work") + 1, order.indexOf("alpha"), "Alpha under its own Work")
        assertEquals(order.indexOf("work2") + 1, order.indexOf("zed"), "Zed under its own work")
        assertEquals(setOf("work", "work2", "work3"), rows.values.filter { it.hasNamesake }.map { it.id }.toSet())
        assertEquals(false, rows.getValue("root").hasNamesake)
    }
}

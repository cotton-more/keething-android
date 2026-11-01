package dev.keething.android.groups

import dev.keething.android.databases.DatabaseLabel
import dev.keething.android.databases.DatabaseMark
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.Vault
import dev.keething.domain.vault.VaultGroup
import dev.keething.vault.databases.DatabaseId
import java.text.Collator
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class GroupsBlocksTest {
    private val root = VaultGroup("root", "Root", emptyList())
    private val mail = VaultGroup("mail", "Mail", listOf("Mail"))
    private val bank = VaultGroup("bank", "Bank", listOf("Bank"))

    private fun entry(id: String, group: VaultGroup, vararg tags: String) =
        Entry(id = id, title = id, groupPath = group.path, groupId = group.id, tags = tags.toList())

    private val personal = DatabaseMark(DatabaseId("p"), DatabaseLabel("Personal"), 0)
    private val work = DatabaseMark(DatabaseId("w"), DatabaseLabel("Work", "Sync"), 1)

    private val personalVault = Vault(
        entries = listOf(entry("a", root), entry("b", bank, "money")),
        groups = listOf(root, bank),
        recycleBin = listOf(Entry(id = "x", groupPath = listOf("Recycle Bin")), Entry(id = "y", groupPath = listOf("Recycle Bin"))),
    )
    private val workVault = Vault(
        entries = listOf(entry("c", mail, "dev"), entry("d", mail, "Dev", "ops"), entry("e", root)),
        groups = listOf(root, mail),
    )

    private fun blocks() = GroupsBlocks(Collator.getInstance(Locale.ENGLISH))

    @Test
    fun eachOpenDatabaseHasItsBlockInTheWindowsOrderWithItsOwnCountsBinAndTags() {
        val shown = blocks().of(listOf(work to workVault, personal to personalVault))

        assertEquals(listOf(work.id, personal.id), shown.map { it.id })
        assertEquals(listOf(3, 2), shown.map { it.entries })
        assertEquals(listOf(listOf("root", "mail"), listOf("root", "bank")), shown.map { block -> block.overview.groups.map { it.id } })
        assertEquals(listOf(0, 2), shown.map { it.overview.recycleBin })
        assertEquals(listOf(listOf("dev", "ops"), listOf("money")), shown.map { block -> block.overview.tags.map { it.name } })
        assertEquals(2, shown[0].overview.tags[0].count)
    }

    @Test
    fun aDatabaseReadAgainIsBuiltAgainTheOthersKeptOneClosedGoes() {
        val blocks = blocks()
        val before = blocks.of(listOf(personal to personalVault, work to workVault))
        val readAgain = workVault.copy(entries = workVault.entries.drop(1))

        val after = blocks.of(listOf(personal to personalVault, work to readAgain))
        assertSame(before[0].overview, after[0].overview, "Personal was not read again")
        assertNotSame(before[1].overview, after[1].overview)
        assertEquals(2, after[1].entries)
        assertEquals(listOf(1), after[1].overview.tags.map { it.count }.distinct(), "dev and ops once each now")

        val closed = blocks.of(listOf(work to readAgain))
        assertEquals(listOf(work.id), closed.map { it.id }, "Personal closed or locked alone: its block goes")
        assertSame(after[1].overview, closed[0].overview)

        val opened = blocks.of(listOf(personal to personalVault, work to readAgain))
        assertEquals(listOf(personal.id, work.id), opened.map { it.id }, "opened again: its block is back")
    }

    @Test
    fun withOneDatabaseOpenARowSearchesAsTheCardsChipsDo() {
        val block = blocks().of(listOf(personal to personalVault)).single()
        val bankRow = block.overview.groups.first { it.id == "bank" }

        assertEquals("", block.search(block.overview.groups.first().search), "Top level: the whole list")
        assertEquals("@Bank ", block.search(bankRow.search))
        assertEquals("#money ", block.search(block.overview.tags.single().search))
        assertEquals("-", block.search(GroupsOverview.RECYCLE_BIN_SEARCH))
    }

    @Test
    fun withTwoOpenARowSearchesItsDatabaseOnly() {
        val shown = blocks().of(listOf(personal to personalVault, work to workVault))
        val (p, w) = shown

        assertEquals("!Personal ", p.search(""), "Top level: that database's whole list")
        assertEquals("!Personal @Bank ", p.search(p.overview.groups.first { it.id == "bank" }.search))
        assertEquals("!\"Work · Sync\" #dev ", w.search(w.overview.tags.first().search), "a label with a space is quoted")
        assertEquals("- !Personal ", p.search(GroupsOverview.RECYCLE_BIN_SEARCH), "the bin: a search starting with -")
    }
}

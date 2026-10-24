package dev.keepassbar.android.list

import dev.keepassbar.domain.secret.Secret
import dev.keepassbar.domain.vault.Attachment
import dev.keepassbar.domain.vault.Entry
import dev.keepassbar.domain.vault.Vault
import java.text.Collator
import java.time.Instant
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchChipsTest {
    private val listing = VaultListing(
        Vault(
            entries = listOf(
                Entry(id = "gh", title = "GitHub", username = "octocat", password = Secret.of("pw"), groupPath = listOf("Work", "Dev"), tags = listOf("code"), attachments = listOf(Attachment("key.txt", 3))),
                Entry(id = "jira", title = "Jira", groupPath = listOf("Work"), tags = listOf("Code", "office")),
                Entry(id = "spk", title = "Sparkasse", groupPath = listOf("Family Finance")),
            ),
            recycleBin = listOf(Entry(id = "old", title = "Old Router", groupPath = listOf("Recycle Bin", "Home"))),
        ),
        Instant.parse("2026-10-24T12:00:00Z"),
        Collator.getInstance(Locale.ENGLISH),
    )

    private fun chips(query: String, codesOnly: Boolean = false) = searchChips(query, listing.facets, listing.binFacets, codesOnly)

    @Test
    fun nothingTypedOffersTheFourFilters() {
        val chips = chips("")

        assertEquals(listOf("@", "#", "+", "-"), chips.map { it.query })
        assertEquals(listOf('@', '#', '+', '-'), chips.map { it.sigil })
        assertFalse(chips.any { it.selected })
    }

    @Test
    fun aFilterIsAddedAfterWhatIsTyped() {
        assertEquals("git @", chips("git").first().query)
        assertEquals("git @", chips("git ").first().query)
    }

    @Test
    fun theBinChipTogglesTheBinSearch() {
        assertEquals("- git", chips("git").last().query)
        val inBin = chips("-git").last()
        assertTrue(inBin.selected)
        assertEquals("git", inBin.query)
        assertEquals("", chips("-").last().query)
    }

    @Test
    fun theCodesListHasNoBin() {
        assertEquals(listOf("@", "#", "+"), chips("", codesOnly = true).map { it.query })
    }

    @Test
    fun typingAGroupOffersTheGroupsInTreeOrderWithTheirPaths() {
        val chips = chips("@")

        assertEquals(listOf("Family Finance", "Work", "Work › Dev"), chips.map { it.label })
        assertEquals(listOf(1, 2, 1), chips.map { it.count })
        assertEquals("@\"Family Finance\" ", chips[0].query, "a name with a space is quoted")
        assertEquals(listOf("Work › Dev"), chips("gh @de").map { it.label })
        assertEquals("gh @Dev ", chips("gh @de").single().query)
    }

    @Test
    fun tagsSpelledAlikeAreOne() {
        val chips = chips("#c")

        assertEquals(listOf("code"), chips.map { it.label })
        assertEquals(2, chips.single().count)
    }

    @Test
    fun aBarePlusOffersAnyFileFirst() {
        val chips = chips("+")

        assertEquals(listOf("Any file", "key.txt"), chips.map { it.label })
        assertEquals("+ ", chips[0].query)
        assertEquals("+key.txt ", chips[1].query)
    }

    @Test
    fun inTheBinTheBinsGroupsAreOffered() {
        assertEquals(listOf("Home"), chips("- @").map { it.label })
    }

    @Test
    fun noMatchOffersNothing() {
        assertEquals(emptyList(), chips("@zzz"))
    }
}

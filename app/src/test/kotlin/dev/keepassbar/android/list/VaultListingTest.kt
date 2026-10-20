package dev.keepassbar.android.list

import dev.keepassbar.card.ExpiryHint
import dev.keepassbar.domain.secret.Secret
import dev.keepassbar.domain.totp.TotpConfig
import dev.keepassbar.domain.vault.Attachment
import dev.keepassbar.domain.vault.CustomField
import dev.keepassbar.domain.vault.Entry
import dev.keepassbar.domain.vault.Vault
import java.text.Collator
import java.time.Instant
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VaultListingTest {
    private val now = Instant.parse("2026-10-18T12:00:00Z")

    private val github = Entry(
        id = "gh", title = "GitHub", username = "octocat", url = "https://github.com/login",
        totp = TotpConfig(Secret.of("12345678901234567890")), attachments = listOf(Attachment("key.txt", 3)),
    )
    private val bahn = Entry(id = "db", title = "Deutsche Bahn", username = "alex@example.org", url = "bahn.de")
    private val amazon = Entry(id = "az", title = "amazon", url = "https://www.amazon.de")
    private val passport = Entry(
        id = "pp", title = "Passport",
        customFields = listOf(CustomField.plain("expires", "2026-11-10")),
    )
    private val oldRouter = Entry(id = "r1", title = "Old Router", username = "admin")
    private val shop = Entry(id = "r2", title = "Shop", username = "me")

    private val listing = VaultListing(
        Vault(entries = listOf(github, bahn, amazon, passport), recycleBin = listOf(oldRouter, shop)),
        now,
        Collator.getInstance(Locale.ENGLISH),
    )

    @Test
    fun nothingTypedShowsTheRecentEntriesThenAllByTitle() {
        val sections = listing.sections("", recentIds = listOf("db", "gone", "gh"))

        assertEquals(listOf(ListSection.Kind.RECENT, ListSection.Kind.ALL), sections.map { it.kind })
        assertEquals(listOf("db", "gh"), sections[0].rows.map { it.id }, "ids no longer in the vault are skipped")
        assertEquals(listOf("az", "db", "gh", "pp"), sections[1].rows.map { it.id })
    }

    @Test
    fun withoutRecentEntriesOnlyAllIsShown() {
        val sections = listing.sections("  ", recentIds = emptyList())

        assertEquals(listOf(ListSection.Kind.ALL), sections.map { it.kind })
    }

    @Test
    fun typingSearchesEverythingBestFirst() {
        val sections = listing.sections("bahn", recentIds = listOf("gh"))

        assertEquals(1, sections.size)
        assertEquals(ListSection.Kind.RESULTS, sections[0].kind)
        assertEquals(listOf("db"), sections[0].rows.map { it.id })
    }

    @Test
    fun noMatchGivesNoSections() {
        assertTrue(listing.sections("zzzzqqq", emptyList()).isEmpty())
    }

    @Test
    fun aMinusListsTheRecycleBinLatestDeletedFirstOrSearchesIt() {
        val bin = listing.sections("-", emptyList())
        assertEquals(ListSection.Kind.RECYCLE_BIN, bin.single().kind)
        assertEquals(listOf("r2", "r1"), bin.single().rows.map { it.id })

        assertEquals(listOf("r1"), listing.sections("- router", emptyList()).single().rows.map { it.id })
    }

    @Test
    fun theCodesListHasOnlyEntriesWithACode() {
        val bank = Entry(id = "bk", title = "Bank", username = "alex", totp = TotpConfig(Secret.of("12345678901234567890")))
        val listing = VaultListing(Vault(entries = listOf(github, bahn, bank, amazon)), now, Collator.getInstance(Locale.ENGLISH))

        val all = listing.sections("", recentIds = listOf("db"), codesOnly = true)
        assertEquals(ListSection.Kind.CODES, all.single().kind, "no recent section")
        assertEquals(listOf("bk", "gh"), all.single().rows.map { it.id })

        val found = listing.sections("alex", emptyList(), codesOnly = true)
        assertEquals(ListSection.Kind.RESULTS, found.single().kind)
        assertEquals(listOf("bk"), found.single().rows.map { it.id }, "Deutsche Bahn matches but has no code")
        assertTrue(listing.sections("bahn", emptyList(), codesOnly = true).isEmpty())
    }

    @Test
    fun aRowShowsWhatTheListNeeds() {
        val rows = listing.sections("", emptyList()).single().rows.associateBy { it.id }

        val gh = rows.getValue("gh")
        assertEquals("GitHub", gh.title)
        assertEquals("octocat · github.com", gh.subtitle)
        assertTrue(gh.hasAttachments)
        assertNotNull(gh.totp)
        assertEquals("www.amazon.de", rows.getValue("az").subtitle, "no username: the site alone")
        assertEquals("", rows.getValue("pp").subtitle)
        assertNull(rows.getValue("db").totp)
    }

    @Test
    fun aRowSaysWhenSomethingEndsSoonOrEnded() {
        val expired = Entry(id = "x", title = "Licence", expires = true, expiryTime = Instant.parse("2026-10-17T08:00:00Z"))
        val listing = VaultListing(Vault(entries = listOf(passport, expired, bahn)), now, Collator.getInstance(Locale.ENGLISH))
        val rows = listing.sections("", emptyList()).single().rows.associateBy { it.id }

        assertEquals(RowDeadline("expires · in 23 days", ExpiryHint.Status.SOON), rows.getValue("pp").deadline)
        assertEquals(RowDeadline("Expired yesterday", ExpiryHint.Status.EXPIRED), rows.getValue("x").deadline)
        assertNull(rows.getValue("db").deadline)
    }
}

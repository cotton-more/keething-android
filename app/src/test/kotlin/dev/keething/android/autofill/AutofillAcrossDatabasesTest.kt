package dev.keething.android.autofill

import dev.keething.autofill.matching.AutofillMatcher
import dev.keething.autofill.saving.SavePlan
import dev.keething.autofill.saving.SavePlanner
import dev.keething.autofill.saving.TypedLogin
import dev.keething.autofill.target.FillTarget
import dev.keething.domain.secret.Secret
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.Vault
import dev.keething.search.site.SiteAddress
import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.databases.EntryRef
import kotlin.test.Test
import kotlin.test.assertEquals

/** Autofill over several open databases (keething-spec autofill.fill AC6, autofill.save AC7; plan AD11). */
class AutofillAcrossDatabasesTest {
    private val personal = DatabaseId("P")
    private val work = DatabaseId("W")
    private val page = FillTarget.WebPage(SiteAddress.parse("https://github.com/login")!!, "com.android.chrome")

    private fun entry(id: String, username: String, password: String = "pw") =
        Entry(id, title = "GitHub", username = username, password = Secret.of(password), url = "https://github.com")

    // A copy of a file keeps every UUID: the same id in both databases is two entries.
    private val databases = listOf(
        OpenVault(personal, Vault(entries = listOf(entry("same", "alex"), Entry("other", title = "Bank", url = "https://bank.example")))),
        OpenVault(work, Vault(entries = listOf(entry("same", "alex.morgan", "old")))),
    )

    // spec: autofill.fill/AC6
    @Test
    fun theMatchingEntriesComeFromEveryOpenDatabaseEachByItsRef() {
        val entries = AutofillEntries.entriesOf(databases)
        assertEquals(listOf(EntryRef(personal, "same").text, EntryRef(personal, "other").text, EntryRef(work, "same").text), entries.map { it.id })
        val candidates = AutofillMatcher(entries).candidates(page).map { it.entry }
        assertEquals(setOf(EntryRef(personal, "same").text, EntryRef(work, "same").text), candidates.map { it.id }.toSet())
        assertEquals(setOf("alex", "alex.morgan"), candidates.map { it.username }.toSet(), "each with its own values")
        assertEquals(emptyList(), AutofillEntries.entriesOf(emptyList()), "none open: nothing (Unlock Keething)")
    }

    // spec: autofill.save/AC7
    @Test
    fun aTypedLoginUpdatesTheEntryInItsOwnDatabase() {
        val candidates = AutofillMatcher(AutofillEntries.entriesOf(databases)).candidates(page).map { it.entry }
        val update = SavePlanner.plan(candidates, TypedLogin("Alex.Morgan", "new"), page, appName = null)
        assertEquals(EntryRef(work, "same"), EntryRef.of((update as SavePlan.Update).entry.id), "Work's entry, by its username")
        val unchanged = SavePlanner.plan(candidates, TypedLogin("alex", "pw"), page, appName = null)
        assertEquals(EntryRef(personal, "same").text, (unchanged as SavePlan.Unchanged).entry.id)
        assertEquals(
            SavePlan.New("github.com", "https://github.com"),
            SavePlanner.plan(candidates, TypedLogin("", "new"), page, appName = null),
            "without a username two accounts match, one in each: a new entry, which the editor places",
        )
    }
}

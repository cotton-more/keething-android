package dev.keething.autofill

import dev.keething.autofill.saving.SavePlan
import dev.keething.autofill.saving.SavePlanner
import dev.keething.autofill.saving.TypedLogin
import dev.keething.autofill.target.FillTarget
import dev.keething.domain.secret.Secret
import dev.keething.domain.vault.Entry
import dev.keething.search.site.SiteAddress
import kotlin.test.Test
import kotlin.test.assertEquals

/** What a login typed into a form becomes (SPEC §7, Save): nothing new, the matching entry's new password, or a new entry. */
class SavePlannerTest {
    private val page = FillTarget.WebPage(SiteAddress.parse("https://www.shop.example")!!, "com.android.chrome")
    private val app = FillTarget.App("com.example.bank")

    private fun entry(id: String, username: String, password: String) = Entry(id, title = id, username = username, password = Secret.of(password))

    @Test
    fun theMatchingEntryWithTheUsernameGetsTheNewPasswordUnlessItHasItAlready() {
        val candidates = listOf(entry("other", "bob", "x"), entry("mine", "Alex@Example.org", "old"))
        assertEquals(SavePlan.Update(candidates[1]), SavePlanner.plan(candidates, TypedLogin("alex@example.org", "new"), page, appName = null), "any case")
        assertEquals(SavePlan.Unchanged(candidates[1]), SavePlanner.plan(candidates, TypedLogin("alex@example.org", "old"), page, appName = null))
    }

    @Test
    fun withoutAUsernameThePasswordChangedIsTheOneWhoseCurrentPasswordWasTyped() {
        val candidates = listOf(entry("a", "alex", "one"), entry("b", "bob", "two"))
        assertEquals(SavePlan.Update(candidates[1]), SavePlanner.plan(candidates, TypedLogin("", "new", currentPassword = "two"), page, appName = null))
        assertEquals(SavePlan.Update(candidates[0]), SavePlanner.plan(candidates.take(1), TypedLogin("", "new"), page, appName = null), "the only one")
        assertEquals(SavePlan.New("shop.example", "https://www.shop.example"), SavePlanner.plan(candidates, TypedLogin("", "new"), page, appName = null), "which one is not known")
    }

    @Test
    fun aNewLoginIsNamedAfterTheSiteOrTheApp() {
        assertEquals(SavePlan.New("shop.example", "https://www.shop.example"), SavePlanner.plan(emptyList(), TypedLogin("alex", "pw"), page, appName = null))
        val local = FillTarget.WebPage(SiteAddress.parse("http://localhost:8000")!!, "org.mozilla.firefox")
        assertEquals(SavePlan.New("localhost", "http://localhost:8000"), SavePlanner.plan(emptyList(), TypedLogin("alex", "pw"), local, appName = null))
        assertEquals(SavePlan.New("Bank", "androidapp://com.example.bank"), SavePlanner.plan(emptyList(), TypedLogin("alex", "pw"), app, appName = "Bank"))
        assertEquals(SavePlan.New("com.example.bank", "androidapp://com.example.bank"), SavePlanner.plan(emptyList(), TypedLogin("alex", "pw"), app, appName = null))
        assertEquals(
            SavePlan.New("shop.example", "https://www.shop.example"),
            SavePlanner.plan(listOf(entry("other", "bob", "x")), TypedLogin("alex", "pw"), page, appName = null),
            "another username on the same site is another account",
        )
    }
}

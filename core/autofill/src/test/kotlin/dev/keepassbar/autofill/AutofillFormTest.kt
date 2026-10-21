package dev.keepassbar.autofill

import dev.keepassbar.autofill.form.AutofillForm
import dev.keepassbar.autofill.form.FormField
import dev.keepassbar.autofill.target.FillTarget
import dev.keepassbar.domain.secret.Secret
import dev.keepassbar.domain.vault.Entry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutofillFormTest {
    private val own = "dev.keepassbar.android"
    private val app = "com.example.bank"
    private val chrome = "com.android.chrome"

    private fun text(index: Int, id: String? = null, focused: Boolean = false, domain: String? = null, scheme: String? = null, hint: String? = null) =
        FormField(index, inputType = 1, idEntry = id, hint = hint, isFocused = focused, webDomain = domain, webScheme = scheme)

    private fun password(index: Int, id: String? = null, focused: Boolean = false, domain: String? = null, scheme: String? = null) =
        FormField(index, inputType = 0x81, idEntry = id, isFocused = focused, webDomain = domain, webScheme = scheme)

    @Test
    fun anAppsLoginForm() {
        val form = AutofillForm.parse(app, listOf(text(0, "login"), password(1), text(2, "promo")), own)!!
        assertEquals(FillTarget.App(app), form.target)
        assertEquals(listOf(0), form.usernames)
        assertEquals(listOf(1), form.passwords)
        assertEquals(listOf(0, 1), form.fields)
        assertFalse(form.asksOnlyForCode)
    }

    @Test
    fun theTextFieldRightBeforeThePasswordIsTheUsername() {
        val form = AutofillForm.parse(app, listOf(text(0), text(1), password(2), text(3)), own)!!
        assertEquals(listOf(1), form.usernames)
    }

    @Test
    fun aPhoneFieldIsTheUsernameOnlyWhenNothingElseIs() {
        val phone = FormField(0, inputType = 3)
        assertEquals(listOf(0), AutofillForm.parse(app, listOf(phone, password(1)), own)!!.usernames)
        assertEquals(listOf(1), AutofillForm.parse(app, listOf(phone, text(1, "email"), password(2)), own)!!.usernames)
    }

    @Test
    fun aUsernameAloneIsTheFirstStepOfALogin() {
        val form = AutofillForm.parse(app, listOf(FormField(0, inputType = 0x21)), own)!!
        assertEquals(listOf(0), form.usernames)
        assertEquals(emptyList(), form.passwords)
    }

    @Test
    fun aCodeAlone() {
        val form = AutofillForm.parse(app, listOf(text(0, hint = "Verification code")), own)!!
        assertTrue(form.asksOnlyForCode)
        assertEquals(listOf(0), form.codes)
    }

    @Test
    fun nothingToFill() {
        assertNull(AutofillForm.parse(app, listOf(text(0), text(1)), own), "plain fields only")
        assertNull(AutofillForm.parse(app, emptyList(), own))
        assertNull(AutofillForm.parse(own, listOf(text(0, "login"), password(1)), own), "KeePassBar itself")
    }

    @Test
    fun aSignUpIsNotFilledButItsNewPasswordIsSaved() {
        val fields = listOf(FormField(0, inputType = 0x21), FormField(1, listOf("newPassword")), FormField(2, listOf("newPassword")))
        val form = AutofillForm.parse(app, fields, own)!!
        assertTrue(form.isSignUp)
        assertEquals(listOf(0), form.usernames, "the username is saved with it")
        assertEquals(emptyList(), form.passwords)
        assertEquals(listOf(1, 2), form.newPasswords, "a generated password goes into both")
        assertEquals(listOf(1, 2), form.savedPasswords)
        assertEquals(listOf(0, 1, 2), form.allFields)
    }

    @Test
    fun aPasswordChangeGetsOnlyTheCurrentPasswordAndSavesTheNewOne() {
        val fields = listOf(password(0, "current_password"), password(1, "new_password"), password(2, "confirm_password"))
        val form = AutofillForm.parse(app, fields, own)!!
        assertEquals(listOf(0), form.passwords)
        assertEquals(listOf(0), form.fields, "the entry fills the current one only")
        assertEquals(listOf(1, 2), form.newPasswords)
        assertEquals(listOf(1, 2), form.savedPasswords)
        assertFalse(form.isSignUp)
    }

    @Test
    fun aLoginSavesItsPassword() {
        val form = AutofillForm.parse(app, listOf(text(0, "login"), password(1)), own)!!
        assertEquals(listOf(1), form.savedPasswords)
        assertEquals(emptyList(), AutofillForm.parse(app, listOf(FormField(0, listOf("smsOTPCode"))), own)!!.savedPasswords, "a code is not saved")
    }

    @Test
    fun aManualRequestTakesTheFocusedPlainFieldAsTheUsername() {
        val fields = listOf(text(0), text(1, focused = true))
        assertNull(AutofillForm.parse(app, fields, own))
        assertEquals(listOf(1), AutofillForm.parse(app, fields, own, manual = true)!!.usernames)
    }

    @Test
    fun aBrowserPageIsItsWebDomain() {
        val fields = listOf(text(0, "login", domain = "github.com", scheme = "https"), password(1, domain = "github.com", scheme = "https"))
        val target = assertIs<FillTarget.WebPage>(AutofillForm.parse(chrome, fields, own)!!.target)
        assertEquals("github.com", target.site.host)
        assertTrue(target.site.isSecure)
        assertEquals(chrome, target.browserPackage)
    }

    @Test
    fun aPlainHttpPageIsNotSecure() {
        val form = AutofillForm.parse(chrome, listOf(password(0, domain = "localhost:8000", scheme = "http")), own)!!
        val site = assertIs<FillTarget.WebPage>(form.target).site
        assertFalse(site.isSecure)
        assertEquals(8000, site.port)
    }

    @Test
    fun aWebDomainWithoutAPortLeavesThePortOpen() {
        // What Firefox reports for http://localhost:8000.
        val form = AutofillForm.parse(chrome, listOf(password(0, domain = "localhost", scheme = "http")), own)!!
        val page = assertIs<FillTarget.WebPage>(form.target)
        assertFalse(page.portKnown)
        assertTrue(assertIs<FillTarget.WebPage>(AutofillForm.parse(chrome, listOf(password(0, domain = "localhost:8000")), own)!!.target).portKnown)
    }

    @Test
    fun withoutASchemeThePageIsTakenAsHttps() {
        val form = AutofillForm.parse(chrome, listOf(password(0, domain = "github.com")), own)!!
        assertTrue(assertIs<FillTarget.WebPage>(form.target).site.isSecure)
    }

    @Test
    fun aBrowserFieldWithoutAWebPageIsNotFilled() {
        assertNull(AutofillForm.parse(chrome, listOf(text(0, "login"), password(1)), own))
        assertNull(AutofillForm.parse(chrome, listOf(password(0, domain = "settings", scheme = "chrome")), own))
    }

    @Test
    fun aFrameOfAnotherSiteIsLeftOut() {
        val fields = listOf(
            text(0, "login", domain = "shop.example", scheme = "https"),
            password(1, domain = "shop.example", scheme = "https", focused = true),
            password(2, domain = "ads.example", scheme = "https"),
        )
        val form = AutofillForm.parse(chrome, fields, own)!!
        assertEquals("shop.example", assertIs<FillTarget.WebPage>(form.target).site.host)
        assertEquals(listOf(0, 1), form.fields)
    }

    @Test
    fun theFocusedFieldsPageWins() {
        val fields = listOf(password(0, domain = "a.example"), password(1, domain = "b.example", focused = true))
        val form = AutofillForm.parse(chrome, fields, own)!!
        assertEquals("b.example", assertIs<FillTarget.WebPage>(form.target).site.host)
        assertEquals(listOf(1), form.passwords)
    }

    @Test
    fun anAppsWebDomainIsNotTrusted() {
        // Any app can show (or claim) a bank's page in a web view: only browsers are taken at their word.
        val fields = listOf(text(0, "login", domain = "bank.de", scheme = "https"), password(1, domain = "bank.de", scheme = "https"))
        assertEquals(FillTarget.App(app), AutofillForm.parse(app, fields, own)!!.target)
    }

    @Test
    fun theValuesAnEntryFillsIn() {
        val form = AutofillForm.parse(app, listOf(text(0, "login"), password(1), text(2, "otp")), own)!!
        val entry = Entry(id = "e", username = "alice", password = Secret.of("s3cret"))
        assertEquals(mapOf(0 to "alice", 1 to "s3cret", 2 to "123456"), form.valuesFor(entry, "123456"))
        assertEquals(mapOf(0 to null, 1 to "s3cret", 2 to null), form.valuesFor(Entry(id = "p", password = Secret.of("s3cret")), null))
    }
}

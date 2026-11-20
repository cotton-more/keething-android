package dev.keething.autofill

import dev.keething.autofill.form.AutofillForm
import dev.keething.autofill.form.FormField
import dev.keething.autofill.target.Browsers
import dev.keething.autofill.target.FillTarget
import dev.keething.autofill.target.SigningCheck
import dev.keething.domain.secret.Secret
import dev.keething.domain.vault.Entry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutofillFormTest {
    private val own = "dev.keething.android"
    private val app = "com.example.bank"
    private val chrome = "com.android.chrome"

    // Chrome's certificate as Google's list of browsers gives it; the system's answer is faked from it.
    private val chromesCertificate = "f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83"
    private fun signedWith(certificate: String) = SigningCheck { _, sha256 -> sha256.hex() == certificate }
    private val signed = signedWith(chromesCertificate)
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private fun text(index: Int, id: String? = null, focused: Boolean = false, domain: String? = null, scheme: String? = null, hint: String? = null) =
        FormField(index, inputType = 1, idEntry = id, hint = hint, isFocused = focused, webDomain = domain, webScheme = scheme)

    private fun password(index: Int, id: String? = null, focused: Boolean = false, domain: String? = null, scheme: String? = null) =
        FormField(index, inputType = 0x81, idEntry = id, isFocused = focused, webDomain = domain, webScheme = scheme)

    @Test
    fun anAppsLoginForm() {
        val form = AutofillForm.parse(app, listOf(text(0, "login"), password(1), text(2, "promo")), own, signed)!!
        assertEquals(FillTarget.App(app), form.target)
        assertEquals(listOf(0), form.usernames)
        assertEquals(listOf(1), form.passwords)
        assertEquals(listOf(0, 1), form.fields)
        assertFalse(form.asksOnlyForCode)
    }

    @Test
    fun theTextFieldRightBeforeThePasswordIsTheUsername() {
        val form = AutofillForm.parse(app, listOf(text(0), text(1), password(2), text(3)), own, signed)!!
        assertEquals(listOf(1), form.usernames)
    }

    @Test
    fun aPhoneFieldIsTheUsernameOnlyWhenNothingElseIs() {
        val phone = FormField(0, inputType = 3, hint = "Telefonnummer")
        assertEquals(listOf(0), AutofillForm.parse(app, listOf(phone), own, signed)!!.usernames)
        assertEquals(listOf(0), AutofillForm.parse(app, listOf(phone, password(1)), own, signed)!!.usernames)
        assertEquals(listOf(1), AutofillForm.parse(app, listOf(phone, text(1, "email"), password(2)), own, signed)!!.usernames)
    }

    // spec: autofill.form/AC5
    @Test
    fun anAmountOnAPhoneKeypadIsOfferedNothing() {
        // A bank's transfer: the amount typed on a phone keypad, a note.
        val amount = FormField(0, inputType = 3, idEntry = "amount", isFocused = true)
        assertNull(AutofillForm.parse(app, listOf(amount, text(1, hint = "Notiz hinzufügen")), own, signed))
        // Before a password, an unnamed keypad field is still the username.
        assertEquals(listOf(0), AutofillForm.parse(app, listOf(FormField(0, inputType = 3), password(1)), own, signed)!!.usernames)
    }

    @Test
    fun aUsernameAloneIsTheFirstStepOfALogin() {
        val form = AutofillForm.parse(app, listOf(FormField(0, inputType = 0x21)), own, signed)!!
        assertEquals(listOf(0), form.usernames)
        assertEquals(emptyList(), form.passwords)
    }

    @Test
    fun aCodeAlone() {
        val form = AutofillForm.parse(app, listOf(text(0, hint = "Verification code")), own, signed)!!
        assertTrue(form.asksOnlyForCode)
        assertEquals(listOf(0), form.codes)
    }

    @Test
    fun nothingToFill() {
        assertNull(AutofillForm.parse(app, listOf(text(0), text(1)), own, signed), "plain fields only")
        assertNull(AutofillForm.parse(app, emptyList(), own, signed))
        assertNull(AutofillForm.parse(own, listOf(text(0, "login"), password(1)), own, signed), "Keething itself")
    }

    @Test
    fun aSignUpIsNotFilledButItsNewPasswordIsSaved() {
        val fields = listOf(FormField(0, inputType = 0x21), FormField(1, listOf("newPassword")), FormField(2, listOf("newPassword")))
        val form = AutofillForm.parse(app, fields, own, signed)!!
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
        val form = AutofillForm.parse(app, fields, own, signed)!!
        assertEquals(listOf(0), form.passwords)
        assertEquals(listOf(0), form.fields, "the entry fills the current one only")
        assertEquals(listOf(1, 2), form.newPasswords)
        assertEquals(listOf(1, 2), form.savedPasswords)
        assertFalse(form.isSignUp)
    }

    @Test
    fun aCodeBesideNewPasswordsKeepsThem() {
        // A password reset: the code from the SMS, a new password and its repetition.
        val fields = listOf(text(0, hint = "Code from the SMS"), password(1, "new_password"), password(2, "repeat_new_password"))
        val form = AutofillForm.parse(app, fields, own, signed)!!
        assertEquals(listOf(0), form.codes)
        assertEquals(listOf(0), form.fields, "the code's entries fill the code alone")
        assertEquals(listOf(1, 2), form.newPasswords, "generated")
        assertEquals(listOf(1, 2), form.savedPasswords, "and saved")
        assertFalse(form.isSignUp)
        assertEquals(mapOf(0 to "123456"), form.valuesFor(Entry(id = "e", username = "alice", password = Secret.of("old")), "123456"))
    }

    @Test
    fun byHandASignUpsNewPasswordsGetTheEntrysPassword() {
        // A login whose password says new-password (to keep browsers from filling it), or a sign-up, asked for by hand.
        val fields = listOf(FormField(0, inputType = 0x21), FormField(1, listOf("newPassword"), isFocused = true))
        val form = AutofillForm.parse(app, fields, own, signed, manual = true)!!
        assertFalse(form.isSignUp, "entries are offered")
        assertEquals(listOf(0, 1), form.fields)
        assertEquals(listOf(1), form.newPasswords, "Generate kept")
        assertEquals(listOf(1), form.savedPasswords, "the save offer kept")
        assertEquals(mapOf(0 to "alice", 1 to "s3cret"), form.valuesFor(Entry(id = "e", username = "alice", password = Secret.of("s3cret")), null))
        assertTrue(AutofillForm.parse(app, fields, own, signed)!!.isSignUp, "not by hand: a sign-up still")
    }

    @Test
    fun byHandAPasswordChangeKeepsItsNewPasswords() {
        val fields = listOf(password(0, "current_password", focused = true), password(1, "new_password"), password(2, "confirm_password"))
        val form = AutofillForm.parse(app, fields, own, signed, manual = true)!!
        assertEquals(listOf(0), form.fields, "the entry fills the current one only")
        assertEquals(listOf(1, 2), form.newPasswords)
        assertEquals(listOf(1, 2), form.savedPasswords)
        assertEquals(mapOf(0 to "s3cret"), form.valuesFor(Entry(id = "e", password = Secret.of("s3cret")), null))
    }

    @Test
    fun aLoginSavesItsPassword() {
        val form = AutofillForm.parse(app, listOf(text(0, "login"), password(1)), own, signed)!!
        assertEquals(listOf(1), form.savedPasswords)
        assertEquals(emptyList(), AutofillForm.parse(app, listOf(FormField(0, listOf("smsOTPCode"))), own, signed)!!.savedPasswords, "a code is not saved")
    }

    @Test
    fun aManualRequestTakesTheFocusedPlainFieldAsTheUsername() {
        val fields = listOf(text(0), text(1, focused = true))
        assertNull(AutofillForm.parse(app, fields, own, signed))
        assertEquals(listOf(1), AutofillForm.parse(app, fields, own, signed, manual = true)!!.usernames)
    }

    @Test
    fun aBrowserPageIsItsWebDomain() {
        val fields = listOf(text(0, "login", domain = "github.com", scheme = "https"), password(1, domain = "github.com", scheme = "https"))
        val target = assertIs<FillTarget.WebPage>(AutofillForm.parse(chrome, fields, own, signed)!!.target)
        assertEquals("github.com", target.site.host)
        assertTrue(target.site.isSecure)
        assertEquals(chrome, target.browserPackage)
    }

    @Test
    fun aPlainHttpPageIsNotSecure() {
        val form = AutofillForm.parse(chrome, listOf(password(0, domain = "localhost:8000", scheme = "http")), own, signed)!!
        val site = assertIs<FillTarget.WebPage>(form.target).site
        assertFalse(site.isSecure)
        assertEquals(8000, site.port)
    }

    @Test
    fun aWebDomainWithoutAPortLeavesThePortOpen() {
        // What Firefox reports for http://localhost:8000.
        val form = AutofillForm.parse(chrome, listOf(password(0, domain = "localhost", scheme = "http")), own, signed)!!
        val page = assertIs<FillTarget.WebPage>(form.target)
        assertFalse(page.portKnown)
        assertTrue(assertIs<FillTarget.WebPage>(AutofillForm.parse(chrome, listOf(password(0, domain = "localhost:8000")), own, signed)!!.target).portKnown)
    }

    @Test
    fun withoutASchemeThePageIsTakenAsHttps() {
        val form = AutofillForm.parse(chrome, listOf(password(0, domain = "github.com")), own, signed)!!
        assertTrue(assertIs<FillTarget.WebPage>(form.target).site.isSecure)
    }

    @Test
    fun aBrowserFieldWithoutAWebPageIsNotFilled() {
        assertNull(AutofillForm.parse(chrome, listOf(text(0, "login"), password(1)), own, signed))
        assertNull(AutofillForm.parse(chrome, listOf(password(0, domain = "settings", scheme = "chrome")), own, signed))
    }

    @Test
    fun aFrameOfAnotherSiteIsLeftOut() {
        val fields = listOf(
            text(0, "login", domain = "shop.example", scheme = "https"),
            password(1, domain = "shop.example", scheme = "https", focused = true),
            password(2, domain = "ads.example", scheme = "https"),
        )
        val form = AutofillForm.parse(chrome, fields, own, signed)!!
        assertEquals("shop.example", assertIs<FillTarget.WebPage>(form.target).site.host)
        assertEquals(listOf(0, 1), form.fields)
    }

    @Test
    fun theFocusedFieldsPageWins() {
        val fields = listOf(password(0, domain = "a.example"), password(1, domain = "b.example", focused = true))
        val form = AutofillForm.parse(chrome, fields, own, signed)!!
        assertEquals("b.example", assertIs<FillTarget.WebPage>(form.target).site.host)
        assertEquals(listOf(1), form.passwords)
    }

    @Test
    fun anAppsWebDomainIsNotTrusted() {
        // Any app can show (or claim) a bank's page in a web view: only browsers are taken at their word.
        val fields = listOf(text(0, "login", domain = "bank.de", scheme = "https"), password(1, domain = "bank.de", scheme = "https"))
        assertEquals(FillTarget.App(app), AutofillForm.parse(app, fields, own, signed)!!.target)
    }

    @Test
    fun aListedBrowserSignedOtherwiseIsAnApp() {
        // An app installed under the package of a browser the phone does not have: its page is not taken.
        val fields = listOf(text(0, "login", domain = "bank.de", scheme = "https"), password(1, domain = "bank.de", scheme = "https"))
        val impostor = signedWith("00".repeat(32))
        assertEquals(FillTarget.App(chrome), AutofillForm.parse(chrome, fields, own, impostor)!!.target)
        assertIs<FillTarget.WebPage>(AutofillForm.parse(chrome, fields, own, signed)!!.target)
    }

    @Test
    fun aBrowserIsAskedForItsOwnCertificatesOnly() {
        val asked = mutableListOf<String>()
        val check = SigningCheck { name, sha256 -> asked += "$name ${sha256.hex()}"; false }
        assertFalse(Browsers.isBrowser(chrome, check))
        assertFalse(Browsers.isBrowser(app, check))
        assertEquals(listOf(
            "$chrome $chromesCertificate",
            "$chrome 1975b2f17177bc89a5dff31f9e64a6cae281a53dc1d1d59b1d147fe1c82afa00",
        ), asked, "an unlisted app is never asked about")
    }

    @Test
    fun theValuesAnEntryFillsIn() {
        val form = AutofillForm.parse(app, listOf(text(0, "login"), password(1), text(2, "otp")), own, signed)!!
        val entry = Entry(id = "e", username = "alice", password = Secret.of("s3cret"))
        assertEquals(mapOf(0 to "alice", 1 to "s3cret", 2 to "123456"), form.valuesFor(entry, "123456"))
        assertEquals(mapOf(0 to null, 1 to "s3cret", 2 to null), form.valuesFor(Entry(id = "p", password = Secret.of("s3cret")), null))
    }
}

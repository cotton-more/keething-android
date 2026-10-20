package dev.keepassbar.autofill

import dev.keepassbar.autofill.form.FieldClassifier
import dev.keepassbar.autofill.form.FieldKind
import dev.keepassbar.autofill.form.FormField
import kotlin.test.Test
import kotlin.test.assertEquals

class FieldClassifierTest {
    private fun kind(
        hints: List<String> = emptyList(),
        inputType: Int = TEXT,
        html: Map<String, String>? = null,
        id: String? = null,
        hint: String? = null,
    ) = FieldClassifier.kind(
        FormField(0, hints, inputType, htmlTag = html?.let { "input" }, htmlAttributes = html.orEmpty(), idEntry = id, hint = hint),
    )

    @Test
    fun autofillHintsComeFirst() {
        assertEquals(FieldKind.PASSWORD, kind(hints = listOf("password")))
        assertEquals(FieldKind.USERNAME, kind(hints = listOf("emailAddress")))
        assertEquals(FieldKind.USERNAME, kind(hints = listOf("username"), inputType = TEXT or 0x80))
        assertEquals(FieldKind.NEW_PASSWORD, kind(hints = listOf("newPassword")))
        assertEquals(FieldKind.CODE, kind(hints = listOf("smsOTPCode")))
        assertEquals(FieldKind.CODE, kind(hints = listOf("2faAppOTPCode")))
        assertEquals(FieldKind.PHONE, kind(hints = listOf("phone")))
        assertEquals(FieldKind.IGNORED, kind(hints = listOf("postalAddress"), id = "login"))
        assertEquals(FieldKind.IGNORED, kind(hints = listOf("creditCardNumber"), inputType = NUMBER or 0x10))
        assertEquals(FieldKind.IGNORED, kind(hints = listOf("newUsername")))
    }

    @Test
    fun aHintThatSaysNothingLeavesItToTheRest() {
        assertEquals(FieldKind.PASSWORD, kind(hints = listOf("off"), inputType = TEXT or 0x80))
        assertEquals(FieldKind.USERNAME, kind(hints = listOf("on"), id = "login"))
    }

    @Test
    fun aPasswordHintNamedNewOrConfirmIsANewPassword() {
        assertEquals(FieldKind.NEW_PASSWORD, kind(hints = listOf("password"), id = "confirmPassword"))
    }

    @Test
    fun htmlAutocompleteThenType() {
        assertEquals(FieldKind.PASSWORD, kind(html = mapOf("autocomplete" to "current-password")))
        assertEquals(FieldKind.NEW_PASSWORD, kind(html = mapOf("type" to "password", "autocomplete" to "new-password")))
        assertEquals(FieldKind.CODE, kind(html = mapOf("type" to "text", "autocomplete" to "one-time-code")))
        assertEquals(FieldKind.USERNAME, kind(html = mapOf("autocomplete" to "section-login username webauthn")))
        assertEquals(FieldKind.IGNORED, kind(html = mapOf("autocomplete" to "cc-number", "name" to "password")))
        assertEquals(FieldKind.PASSWORD, kind(html = mapOf("type" to "password", "name" to "pw")))
        assertEquals(FieldKind.NEW_PASSWORD, kind(html = mapOf("type" to "password", "name" to "password_confirmation")))
        assertEquals(FieldKind.USERNAME, kind(html = mapOf("type" to "email")))
        assertEquals(FieldKind.IGNORED, kind(html = mapOf("type" to "hidden", "name" to "username")))
        assertEquals(FieldKind.IGNORED, kind(html = mapOf("type" to "search")))
        assertEquals(FieldKind.IGNORED, kind(html = mapOf("type" to "checkbox", "name" to "remember_login")))
    }

    @Test
    fun inputTypes() {
        assertEquals(FieldKind.PASSWORD, kind(inputType = TEXT or 0x80))
        assertEquals(FieldKind.PASSWORD, kind(inputType = TEXT or 0xe0))
        assertEquals(FieldKind.PASSWORD, kind(inputType = NUMBER or 0x10), "a PIN")
        assertEquals(FieldKind.USERNAME, kind(inputType = TEXT or 0x20))
        assertEquals(FieldKind.USERNAME, kind(inputType = TEXT or 0xd0))
        assertEquals(FieldKind.IGNORED, kind(inputType = TEXT or 0xb0), "a filter")
        assertEquals(FieldKind.PHONE, kind(inputType = 3))
    }

    @Test
    fun aVisiblePasswordInputTypeIsAPasswordOnlyByItsWords() {
        // Apps use it to keep the keyboard from suggesting words in a username field.
        assertEquals(FieldKind.USERNAME, kind(inputType = TEXT or 0x90, id = "login_field"))
        assertEquals(FieldKind.PLAIN, kind(inputType = TEXT or 0x90))
        assertEquals(FieldKind.PASSWORD, kind(inputType = TEXT or 0x90, id = "passwordShown"))
    }

    @Test
    fun wordsInIdsAndHints() {
        assertEquals(FieldKind.PASSWORD, kind(id = "editTextPassword"))
        assertEquals(FieldKind.USERNAME, kind(id = "et_user_name"))
        assertEquals(FieldKind.USERNAME, kind(hint = "E-Mail-Adresse"))
        assertEquals(FieldKind.USERNAME, kind(hint = "Benutzername"))
        assertEquals(FieldKind.USERNAME, kind(hint = "Логин"))
        assertEquals(FieldKind.PASSWORD, kind(hint = "Пароль"))
        assertEquals(FieldKind.NEW_PASSWORD, kind(hint = "Новый пароль"))
        assertEquals(FieldKind.NEW_PASSWORD, kind(hint = "Passwort wiederholen"))
        assertEquals(FieldKind.PHONE, kind(hint = "Номер телефона"))
        assertEquals(FieldKind.IGNORED, kind(id = "searchField", hint = "Search accounts"))
        assertEquals(FieldKind.PLAIN, kind(hint = "First name"))
    }

    @Test
    fun wordsThatOnlyLookLikePasswords() {
        assertEquals(FieldKind.PLAIN, kind(id = "passport_number"))
        assertEquals(FieldKind.PLAIN, kind(hint = "Passenger"))
        assertEquals(FieldKind.PLAIN, kind(id = "compass"))
    }

    @Test
    fun oneTimeCodesByTheirWords() {
        assertEquals(FieldKind.CODE, kind(id = "otp_input", inputType = NUMBER))
        assertEquals(FieldKind.CODE, kind(hint = "Verification code"))
        assertEquals(FieldKind.CODE, kind(hint = "Code"))
        assertEquals(FieldKind.CODE, kind(hint = "Код подтверждения"))
        assertEquals(FieldKind.CODE, kind(hint = "Код"))
        assertEquals(FieldKind.CODE, kind(html = mapOf("name" to "totp")))
        assertEquals(FieldKind.PLAIN, kind(hint = "Postal code"))
        assertEquals(FieldKind.PLAIN, kind(hint = "Promo code"))
        assertEquals(FieldKind.PLAIN, kind(hint = "Gutscheincode"))
    }

    @Test
    fun hiddenDisabledAndNonTextFieldsAreIgnored() {
        assertEquals(FieldKind.IGNORED, FieldClassifier.kind(FormField(0, listOf("password"), isVisible = false)))
        assertEquals(FieldKind.IGNORED, FieldClassifier.kind(FormField(0, listOf("password"), isEnabled = false)))
        assertEquals(FieldKind.IGNORED, FieldClassifier.kind(FormField(0, listOf("password"), isText = false)))
    }

    private companion object {
        const val TEXT = 1
        const val NUMBER = 2
    }
}

package dev.keepassbar.autofill.form

/**
 * One input field of the screen to fill, as the app reads it from the system's view structure: only what
 * the rules look at, never the text in it.
 */
public data class FormField(
    /** Position among the screen's fields in tree order; the app maps it back to the field's autofill id. */
    val index: Int,
    /** The app's or the browser's hints (`username`, `password`, `smsOTPCode`, …). */
    val autofillHints: List<String> = emptyList(),
    /** `android.text.InputType` bits. */
    val inputType: Int = 0,
    /** A web field's HTML tag (`input`) and its attributes (`type`, `name`, `id`, `autocomplete`, …). */
    val htmlTag: String? = null,
    val htmlAttributes: Map<String, String> = emptyMap(),
    /** The view's resource id name (`editTextPassword`). */
    val idEntry: String? = null,
    /** The hint shown in the empty field. */
    val hint: String? = null,
    /** Takes text (`AUTOFILL_TYPE_TEXT`). */
    val isText: Boolean = true,
    /** Shown: the view and all its parents are visible. */
    val isVisible: Boolean = true,
    val isEnabled: Boolean = true,
    val isFocused: Boolean = false,
    /** The web page's domain (`www.example.com`) and scheme (`https`), from the field or its nearest parent. */
    val webDomain: String? = null,
    val webScheme: String? = null,
)

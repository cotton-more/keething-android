package dev.keething.android.autofill

import android.app.assist.AssistStructure
import android.view.View
import android.view.autofill.AutofillId
import dev.keething.autofill.form.FormField

/**
 * The input fields of the screen to fill, in tree order, read from the system's view structure: what the
 * rules look at (hints, input type, HTML attributes, ids), never the text in a field — except for a save
 * ([texts], SPEC §7), where what was typed is the point. [ids] maps a field's [FormField.index] back to its view.
 */
internal class ScreenFields private constructor(
    val packageName: String,
    val fields: List<FormField>,
    val ids: List<AutofillId>,
    /** What each field holds; read only for a save request, else empty. */
    val texts: List<String?>,
) {
    companion object {
        fun read(structure: AssistStructure, withTexts: Boolean = false): ScreenFields {
            val fields = mutableListOf<FormField>()
            val ids = mutableListOf<AutofillId>()
            val texts = mutableListOf<String?>()

            fun visit(node: AssistStructure.ViewNode, parentVisible: Boolean, parentDomain: String?, parentScheme: String?) {
                val visible = parentVisible && node.visibility == View.VISIBLE
                // A frame of another site names its own domain; the fields under it belong to that one.
                val domain = node.webDomain?.takeIf { it.isNotEmpty() } ?: parentDomain
                val scheme = if (node.webDomain.isNullOrEmpty()) parentScheme else node.webScheme
                val id = node.autofillId
                val html = node.htmlInfo
                val isText = node.autofillType == View.AUTOFILL_TYPE_TEXT
                if (id != null && (isText || html?.tag.equals("input", ignoreCase = true))) {
                    fields += FormField(
                        index = fields.size,
                        autofillHints = node.autofillHints?.toList().orEmpty(),
                        inputType = node.inputType,
                        htmlTag = html?.tag,
                        htmlAttributes = html?.attributes.orEmpty().filter { it.first != null && it.second != null }.associate { it.first to it.second },
                        idEntry = node.idEntry,
                        hint = node.hint,
                        isText = isText,
                        isVisible = visible,
                        isEnabled = node.isEnabled,
                        isFocused = node.isFocused,
                        webDomain = domain,
                        webScheme = scheme,
                    )
                    ids += id
                    if (withTexts) texts += node.autofillValue?.takeIf { it.isText }?.textValue?.toString()
                }
                for (i in 0 until node.childCount) visit(node.getChildAt(i), visible, domain, scheme)
            }

            for (w in 0 until structure.windowNodeCount) visit(structure.getWindowNodeAt(w).rootViewNode, true, null, null)
            return ScreenFields(structure.activityComponent.packageName, fields, ids, texts)
        }
    }
}

package dev.keepassbar.android.autofill

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.service.autofill.InlinePresentation
import android.service.autofill.Presentations
import android.view.View
import android.view.inputmethod.InlineSuggestionsRequest
import android.widget.RemoteViews
import androidx.annotation.DrawableRes
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.v1.InlineSuggestionUi
import dev.keepassbar.android.MainActivity
import dev.keepassbar.android.R

/**
 * How a suggestion looks: a row of the dropdown under the field, and a chip in the keyboard where the
 * keyboard offers them (Gboard: inline suggestions, SPEC §7).
 */
internal class AutofillPresentations(private val context: Context, inline: InlineSuggestionsRequest?) {
    private val specs = inline?.inlinePresentationSpecs.orEmpty()
        .takeIf { list -> list.isNotEmpty() && list.all { UiVersions.getVersions(it.style).contains(UiVersions.INLINE_UI_VERSION_1) } }
        .orEmpty()

    /** How many chips the keyboard takes; the last one is the pinned "Search KeePassBar". */
    private val inlineSlots = if (specs.isEmpty()) 0 else inline?.maxSuggestionCount ?: 0

    /** Entries shown as chips: room is left for the pinned search. */
    val inlineEntrySlots: Int get() = (inlineSlots - 1).coerceAtLeast(0)

    /** An entry: its title and username, on the chip too (the username tells two accounts apart). */
    fun entry(index: Int, title: String, subtitle: String?): Presentations =
        build(title, subtitle, R.drawable.ic_autofill_key, inlineIndex = index.takeIf { it < inlineEntrySlots }, chipSubtitle = true, pinned = false)

    /** "Unlock KeePassBar", "Search KeePassBar": the chips are narrow, the title says enough there. */
    fun action(title: String, subtitle: String?, @DrawableRes icon: Int, index: Int, pinned: Boolean): Presentations =
        build(title, subtitle, icon, inlineIndex = index.takeIf { inlineSlots > 0 }, chipSubtitle = false, pinned = pinned)

    private fun build(
        title: String,
        subtitle: String?,
        @DrawableRes icon: Int,
        inlineIndex: Int?,
        chipSubtitle: Boolean,
        pinned: Boolean,
    ): Presentations {
        val menu = RemoteViews(context.packageName, R.layout.autofill_item).apply {
            setTextViewText(R.id.autofill_title, title)
            setTextViewText(R.id.autofill_subtitle, subtitle.orEmpty())
            setViewVisibility(R.id.autofill_subtitle, if (subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE)
            setImageViewResource(R.id.autofill_icon, icon)
        }
        val builder = Presentations.Builder().setMenuPresentation(menu)
        if (inlineIndex != null && specs.isNotEmpty()) {
            val spec = specs[inlineIndex.coerceAtMost(specs.lastIndex)]
            // A pinned chip stays at the keyboard's edge and is drawn as an icon only (Gboard shrinks a titled one to a dot).
            val content = InlineSuggestionUi.newContentBuilder(attribution(inlineIndex))
                .apply {
                    if (!pinned) setTitle(title)
                    if (!pinned && chipSubtitle && !subtitle.isNullOrEmpty()) setSubtitle(subtitle)
                }
                .setStartIcon(Icon.createWithResource(context, icon))
                .setContentDescription(listOfNotNull(title, subtitle).joinToString(", "))
                .build()
            builder.setInlinePresentation(InlinePresentation(content.slice, spec, pinned))
        }
        return builder.build()
    }

    /** What a long press on a keyboard chip opens: the app. One per chip position, so the chips do not share one. */
    private fun attribution(position: Int): PendingIntent = PendingIntent.getActivity(
        context,
        position,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

package dev.keething.android.editor

import android.icu.text.DateFormat
import dev.keething.domain.vault.NoteDates
import java.time.Instant
import java.util.Date
import java.util.Locale

/**
 * An untitled protected note's time as the phone writes it (SPEC §6.4; keething-spec types.note AC5): ICU's
 * `yMMMdjmm` in the phone's locale and time zone ("Oct 31, 2026, 7:45 PM", "31 Oct 2026, 19:45", "31. Okt. 2026,
 * 19:45"), which only Android's formatter resolves.
 */
internal object PhoneNoteDates : NoteDates {
    override fun format(made: Instant): String =
        DateFormat.getInstanceForSkeleton(NoteDates.SKELETON, Locale.getDefault()).format(Date.from(made))
}

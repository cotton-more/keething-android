package dev.keepassbar.android.sharing

import dev.keepassbar.sharing.message.ShareCode
import dev.keepassbar.sharing.message.ShareRequest
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** What the sharing screens and messages say about requests and answers (SPEC §10). */
internal object SharingTexts {
    /** A request as text: a few words for the person, then the code. */
    fun requestMessage(request: ShareRequest): String =
        "${request.name} asks for “${request.reason}” with Keething. To answer: Keething › Sharing › Read a code › Paste, " +
            "then ask ${request.name} by a call which six pictures the request shows.\n\n" + ShareCode.text(request.encoded())

    fun answerMessage(reason: String, answer: ByteArray): String =
        "An answer to your Keething request “$reason”: Keething › Sharing › Read a code › Paste.\n\n" + ShareCode.text(answer)

    fun requestPicture(request: ShareRequest): CodePicture =
        CodePicture(ShareCode.text(request.encoded()), "keething-request.png", listOf("Keething · ${request.name} asks for", request.reason))

    fun answerPictures(reason: String, qrTexts: List<String>): List<CodePicture> = qrTexts.mapIndexed { index, text ->
        val several = qrTexts.size > 1
        CodePicture(
            text,
            if (several) "keething-answer-${index + 1}-of-${qrTexts.size}.png" else "keething-answer.png",
            listOf("Keething · answer to “$reason”", if (several) "Code ${index + 1} of ${qrTexts.size}: all are needed" else "Only the asking phone opens it"),
        )
    }

    /** "expires in 2 days", "expires in 5 h", "expired". */
    fun expires(at: Instant, now: Instant): String {
        val left = Duration.between(now, at)
        return when {
            left.isNegative || left.isZero -> "expired"
            left.toHours() < 1 -> "expires in ${maxOf(1, left.toMinutes())} min"
            left.toHours() < 24 -> "expires in ${left.toHours()} h"
            // To the nearest day: a request made a minute ago for 3 days expires "in 3 days".
            (left.toHours() + 12) / 24 == 1L -> "expires in 1 day"
            else -> "expires in ${(left.toHours() + 12) / 24} days"
        }
    }

    /** "today 11:40", "yesterday 18:02", else the day and time. */
    fun moment(at: Instant, now: Instant): String {
        val zone = ZoneId.systemDefault()
        val day = at.atZone(zone).toLocalDate()
        val today = now.atZone(zone).toLocalDate()
        val time = at.atZone(zone).format(TIME)
        return when (day) {
            today -> "today $time"
            today.minusDays(1) -> "yesterday $time"
            else -> at.atZone(zone).format(DAY_AND_TIME)
        }
    }

    /** "Sat 25 Oct, 13:58" style, as the phone's locale writes it. */
    fun dateTime(at: Instant): String = at.atZone(ZoneId.systemDefault()).format(DAY_AND_TIME)

    private val TIME: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    private val DAY_AND_TIME: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
}

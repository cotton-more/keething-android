package dev.keething.android.sharing

import dev.keething.sharing.message.ShareCode
import dev.keething.sharing.message.ShareRequest
import dev.keething.sharing.pictures.Pictures
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** What the sharing screens and messages say about requests, answers and envelopes (SPEC §10). */
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

    /** An envelope as text: a few words for the person, then the code. Never its pictures: they go another way. */
    fun envelopeMessage(from: String, envelope: ByteArray): String =
        "$from sent you entries with Keething. To open them: Keething › Sharing › Read a code › Paste, then tap the ten " +
            "pictures $from gives you another way.\n\n" + ShareCode.text(envelope)

    fun envelopePictures(from: String, qrTexts: List<String>): List<CodePicture> = qrTexts.mapIndexed { index, text ->
        val several = qrTexts.size > 1
        CodePicture(
            text,
            if (several) "keething-entries-${index + 1}-of-${qrTexts.size}.png" else "keething-entries.png",
            listOf("Keething · entries from $from", if (several) "Code ${index + 1} of ${qrTexts.size}: all are needed" else "Opens with the ten pictures $from gives you"),
        )
    }

    /** An envelope's pictures on one line, "🐶 Dog · 🚀 Rocket · …": what Copy puts on the clipboard. */
    fun picturesLine(pictures: List<Int>): String = Pictures.named(pictures).joinToString(" · ") { "${it.emoji} ${it.name}" }

    /** An envelope's pictures to send: none of the words around them is a picture's name (`Pictures.find`). */
    fun picturesMessage(pictures: List<Int>): String =
        "The ten pictures that open the Keething entries I sent you:\n" + picturesLine(pictures.take(5)) + "\n" +
            picturesLine(pictures.drop(5)) + "\nIn Keething, after reading the code: Paste the pictures."

    /** "Sparkasse", "Sparkasse and 2 more". */
    fun titles(titles: List<String>): String = when (titles.size) {
        0 -> "Entries"
        1 -> titles.single()
        else -> "${titles.first()} and ${titles.size - 1} more"
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

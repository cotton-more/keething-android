package dev.keething.android.sharing

import dev.keething.android.R
import dev.keething.android.ui.UiText
import dev.keething.android.ui.uiPlain
import dev.keething.android.ui.uiPlural
import dev.keething.android.ui.uiText
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
    fun requestMessage(request: ShareRequest): UiText =
        uiText(R.string.sharing_message_request, request.name, request.reason, ShareCode.text(request.encoded()))

    fun answerMessage(reason: String, answer: ByteArray): UiText = uiText(R.string.sharing_message_answer, reason, ShareCode.text(answer))

    fun requestPicture(request: ShareRequest): CodePicture =
        CodePicture(ShareCode.text(request.encoded()), "keething-request.png", listOf(uiText(R.string.sharing_caption_request, request.name), uiPlain(request.reason)))

    fun answerPictures(reason: String, qrTexts: List<String>): List<CodePicture> = qrTexts.mapIndexed { index, text ->
        val several = qrTexts.size > 1
        CodePicture(
            text,
            if (several) "keething-answer-${index + 1}-of-${qrTexts.size}.png" else "keething-answer.png",
            listOf(
                uiText(R.string.sharing_caption_answer, reason),
                if (several) uiText(R.string.sharing_caption_part, index + 1, qrTexts.size) else uiText(R.string.sharing_caption_answer_single),
            ),
        )
    }

    /** An envelope as text: a few words for the person, then the code. Never its pictures: they go another way. */
    fun envelopeMessage(from: String, envelope: ByteArray): UiText = uiText(R.string.sharing_message_envelope, from, ShareCode.text(envelope))

    fun envelopePictures(from: String, qrTexts: List<String>): List<CodePicture> = qrTexts.mapIndexed { index, text ->
        val several = qrTexts.size > 1
        CodePicture(
            text,
            if (several) "keething-entries-${index + 1}-of-${qrTexts.size}.png" else "keething-entries.png",
            listOf(
                uiText(R.string.sharing_caption_envelope, from),
                if (several) uiText(R.string.sharing_caption_part, index + 1, qrTexts.size) else uiText(R.string.sharing_caption_envelope_single, from),
            ),
        )
    }

    /** An envelope's pictures on one line, "🐶 Dog · 🚀 Rocket · …": what Copy puts on the clipboard. */
    fun picturesLine(pictures: List<Int>): String = Pictures.named(pictures).joinToString(" · ") { "${it.emoji} ${it.name}" }

    /** An envelope's pictures to send: none of the words around them is a picture's name (`Pictures.find`). */
    fun picturesMessage(pictures: List<Int>): UiText =
        uiText(R.string.sharing_message_pictures, picturesLine(pictures.take(5)), picturesLine(pictures.drop(5)))

    /** "Sparkasse", "Sparkasse and 2 more". */
    fun titles(titles: List<String>): UiText = when (titles.size) {
        0 -> uiText(R.string.sharing_titles_none)
        1 -> uiPlain(titles.single())
        else -> uiPlural(R.plurals.sharing_titles_more, titles.size - 1, titles.first(), titles.size - 1)
    }

    /** "expires in 2 days", "expires in 5 h", "expired". */
    fun expires(at: Instant, now: Instant): UiText {
        val left = Duration.between(now, at)
        return when {
            left.isNegative || left.isZero -> uiText(R.string.sharing_expired)
            left.toHours() < 1 -> uiPlural(R.plurals.sharing_expires_minutes, maxOf(1, left.toMinutes()).toInt())
            left.toHours() < 24 -> uiPlural(R.plurals.sharing_expires_hours, left.toHours().toInt())
            // To the nearest day: a request made a minute ago for 3 days expires "in 3 days".
            else -> uiPlural(R.plurals.sharing_expires_days, ((left.toHours() + 12) / 24).toInt())
        }
    }

    /** "today 11:40", "yesterday 18:02", else the day and time. */
    fun moment(at: Instant, now: Instant): UiText {
        val zone = ZoneId.systemDefault()
        val day = at.atZone(zone).toLocalDate()
        val today = now.atZone(zone).toLocalDate()
        val time = at.atZone(zone).format(TIME)
        return when (day) {
            today -> uiText(R.string.sharing_today, time)
            today.minusDays(1) -> uiText(R.string.sharing_yesterday, time)
            else -> uiPlain(at.atZone(zone).format(DAY_AND_TIME))
        }
    }

    /** "Sat 25 Oct, 13:58" style, as the phone's locale writes it. */
    fun dateTime(at: Instant): String = at.atZone(ZoneId.systemDefault()).format(DAY_AND_TIME)

    private val TIME: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    private val DAY_AND_TIME: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
}

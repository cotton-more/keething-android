package dev.keething.android.sharing

import dev.keething.android.ui.english
import dev.keething.sharing.message.ShareCode
import dev.keething.sharing.pictures.Pictures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SharingTextsTest {
    /** Every picture once among them, two-word names and an emoji with U+FE0F included. */
    private val pictures = listOf(36, 41, 21, 29, 63, 0, 47, 10, 54, 5)

    @Test
    fun thePicturesSentOrCopiedArePastedBack() {
        assertEquals(pictures, Pictures.find(SharingTexts.picturesMessage(pictures).english(), Pictures.ENVELOPE_COUNT).pictures)
        assertEquals(pictures, Pictures.find(SharingTexts.picturesLine(pictures), Pictures.ENVELOPE_COUNT).pictures)
    }

    @Test
    fun theCodesTextCarriesTheCodeAndNoPictures() {
        val text = SharingTexts.envelopeMessage("Ivan", byteArrayOf(1, 4, 0, 0)).english()

        assertEquals(1, ShareCode.find(text).messages.size)
        assertEquals(0, Pictures.find(text.substringBefore(ShareCode.PREFIX), Pictures.ENVELOPE_COUNT).longest)
        assertFalse(Pictures.named(pictures).any { text.contains(it.emoji) })
    }
}

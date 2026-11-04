package dev.keething.android.sharing

import io.nayuki.qrcodegen.QrCode
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Reading a one-time code's QR code from a picture's pixels (keething-spec edit.one-time-code AC4, AC8). */
class QrReadingTest {
    private val link = "otpauth://totp/GitHub:me?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&issuer=GitHub"

    /** The code's modules, [scale] pixels each, with [margin] white modules around. */
    private fun pixels(margin: Int, scale: Int = 4): Triple<Int, Int, IntArray> {
        val code = QrCode.encodeText(link, QrCode.Ecc.MEDIUM)
        val side = (code.size + 2 * margin) * scale
        val pixels = IntArray(side * side) { i ->
            val x = i % side / scale - margin
            val y = i / side / scale - margin
            if (code.getModule(x, y)) 0xFF000000.toInt() else -1
        }
        return Triple(side, side, pixels)
    }

    // spec: edit.one-time-code/AC4
    @Test
    fun aPicturesQrCodeIsRead() {
        val (width, height, pixels) = pixels(margin = 4)
        assertEquals(listOf(link), QrReading.texts(width, height, pixels))
        assertEquals(emptyList(), QrReading.texts(64, 64, IntArray(64 * 64) { -1 }), "no code: nothing")
    }

    // An SVG's code (AC8) is drawn by AndroidSVG first, on the phone only; what is drawn has no margin.
    @Test
    fun aCodeWithoutAWhiteMarginIsReadToo() {
        val (width, height, pixels) = pixels(margin = 0, scale = 3)
        assertEquals(listOf(link), QrReading.texts(width, height, pixels))
    }

    /**
     * An SVG as GitHub gives it (3 units a module, no margin) drawn anti-aliased, as AndroidSVG draws it: by a whole
     * scale the code is read; at 1024 px exactly (9.2 times) it was not, on the phone and here.
     */
    @Test
    fun anSvgIsDrawnByAWholeScale() {
        val code = QrCode.encodeText(link, QrCode.Ecc.MEDIUM)
        val units = code.size * 3f
        fun drawn(scale: Float): List<String> {
            val side = (units * scale).toInt()
            val image = BufferedImage(side, side, BufferedImage.TYPE_INT_RGB)
            image.createGraphics().apply {
                setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                color = Color.WHITE
                fillRect(0, 0, side, side)
                color = Color.BLACK
                for (y in 0 until code.size) for (x in 0 until code.size) {
                    if (code.getModule(x, y)) fill(Rectangle2D.Float(x * 3 * scale, y * 3 * scale, 3 * scale, 3 * scale))
                }
                dispose()
            }
            return QrReading.texts(side, side, image.getRGB(0, 0, side, side, null, 0, side))
        }
        val scale = QrReading.svgScale(units, units)
        assertEquals(9f, scale)
        assertEquals(listOf(link), drawn(scale))
        assertEquals(emptyList(), drawn(1024 / units), "the old way")
        assertEquals(1f, QrReading.svgScale(3000f, 2000f), "a large one is not made smaller")
    }

    @Test
    fun anSvgIsKnownByItsFirstElement() {
        assertTrue(QrReading.isSvg("<svg xmlns=\"http://www.w3.org/2000/svg\"/>".encodeToByteArray()))
        assertTrue(QrReading.isSvg("﻿<?xml version=\"1.0\" standalone=\"yes\"?>\n<svg version=\"1.1\">".encodeToByteArray()))
        assertFalse(QrReading.isSvg(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)), "a PNG")
        assertFalse(QrReading.isSvg("<?xml version=\"1.0\"?><html/>".encodeToByteArray()))
    }
}

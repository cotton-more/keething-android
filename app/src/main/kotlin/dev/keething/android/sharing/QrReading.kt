package dev.keething.android.sharing

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.camera.core.ImageProxy
import com.caverock.androidsvg.SVG
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.multi.qrcode.QRCodeMultiReader

/**
 * The texts of the QR codes in a camera frame or a picture (SPEC §10; a one-time code's, keething-spec
 * edit.one-time-code AC4, AC8), read by ZXing.
 */
internal object QrReading {
    private val HINTS = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
    )

    /** A camera frame: its brightness plane is all a QR code needs. */
    fun texts(frame: ImageProxy): List<String> {
        val plane = frame.planes[0]
        val buffer = plane.buffer.duplicate().apply { rewind() }
        val data = ByteArray(plane.rowStride * frame.height)
        buffer.get(data, 0, minOf(buffer.remaining(), data.size))
        return texts(PlanarYUVLuminanceSource(data, plane.rowStride, frame.height, 0, 0, frame.width, frame.height, false), several = false)
    }

    /**
     * A picture from the gallery, from another app, a file or the clipboard; one may hold several codes (a screenshot
     * of all parts). An SVG image (GitHub gives a one-time code's QR as one), which ImageDecoder does not decode, is
     * drawn by AndroidSVG first. Throws when it is no image that can be read.
     */
    fun texts(resolver: ContentResolver, uri: Uri): List<String> {
        val bytes = resolver.openInputStream(uri)?.use { it.readNBytes(MAX_BYTES + 1) } ?: error("no content")
        require(bytes.size <= MAX_BYTES) { "too large" }
        val bitmap = if (isSvg(bytes)) svgBitmap(bytes) else decoded(bytes)
        return try {
            texts(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun decoded(bytes: ByteArray): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(bytes)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > MAX_SIDE) {
                decoder.setTargetSize(info.size.width * MAX_SIDE / longest, info.size.height * MAX_SIDE / longest)
            }
        }

    /** An XML file whose first element is `svg`. */
    internal fun isSvg(bytes: ByteArray): Boolean {
        val head = bytes.copyOf(minOf(bytes.size, 1024)).decodeToString().trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        return head.startsWith("<svg") || (head.startsWith("<?xml") || head.startsWith("<!")) && "<svg" in head
    }

    /** Drawn on white at about [SVG_SIDE] on its longer side, as the Mac draws it, by a whole [svgScale]. */
    private fun svgBitmap(bytes: ByteArray): Bitmap {
        val svg = SVG.getFromInputStream(bytes.inputStream())
        val box = svg.documentViewBox
        val (width, height) = when {
            box != null && box.width() > 0 && box.height() > 0 -> box.width() to box.height()
            svg.documentWidth > 0 && svg.documentHeight > 0 -> svg.documentWidth to svg.documentHeight
            else -> 1f to 1f
        }
        val scale = svgScale(width, height)
        val bitmap = Bitmap.createBitmap(maxOf(1, (width * scale).toInt()), maxOf(1, (height * scale).toInt()), Bitmap.Config.ARGB_8888)
        if (box == null) svg.setDocumentViewBox(0f, 0f, width, height)
        svg.setDocumentWidth(bitmap.width.toFloat())
        svg.setDocumentHeight(bitmap.height.toFloat())
        Canvas(bitmap).apply { drawColor(Color.WHITE) }.also { svg.renderToCanvas(it) }
        return bitmap
    }

    /**
     * A whole number, at least 1, so the squares of a code drawn at whole units (GitHub's are 3 units a module) end on
     * whole pixels: at 9.2 times their anti-aliased edges fall between pixels and ZXing finds no code.
     */
    internal fun svgScale(width: Float, height: Float): Float =
        maxOf(1f, kotlin.math.round(SVG_SIDE / maxOf(width, height)))

    private fun texts(bitmap: Bitmap): List<String> {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return texts(bitmap.width, bitmap.height, pixels)
    }

    /** The codes of a picture's pixels (ARGB); a code drawn to the picture's edge, without a white margin, too. */
    internal fun texts(width: Int, height: Int, pixels: IntArray): List<String> =
        texts(RGBLuminanceSource(width, height, pixels), several = true)

    private fun texts(source: LuminanceSource, several: Boolean): List<String> {
        val image = BinaryBitmap(HybridBinarizer(source))
        if (several) {
            try {
                return QRCodeMultiReader().decodeMultiple(image, HINTS).mapNotNull { it.text }.distinct()
            } catch (_: NotFoundException) {
                // One code the multi-reader missed may still be found alone.
            }
        }
        return try {
            listOfNotNull(QRCodeReader().decode(image, HINTS).text)
        } catch (_: ReaderException) {
            emptyList()
        }
    }

    /** Larger pictures are read at this size: a code in them stays readable, and decoding stays quick. */
    private const val MAX_SIDE = 2000

    /** An SVG is drawn this large on its longer side: a dense code still has a few pixels a module. */
    private const val SVG_SIDE = 1024f

    /** A picture larger than this is no screenshot of a code. */
    private const val MAX_BYTES = 50 * 1024 * 1024
}

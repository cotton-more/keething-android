package dev.keepassbar.android.sharing

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.camera.core.ImageProxy
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

/** The texts of the QR codes in a camera frame or a picture (SPEC §10), read by ZXing. */
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

    /** A picture from the gallery or from another app; one may hold several codes (a screenshot of all parts). */
    fun texts(resolver: ContentResolver, uri: Uri): List<String> {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > MAX_SIDE) {
                decoder.setTargetSize(info.size.width * MAX_SIDE / longest, info.size.height * MAX_SIDE / longest)
            }
        }
        return try {
            texts(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun texts(bitmap: Bitmap): List<String> {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return texts(RGBLuminanceSource(bitmap.width, bitmap.height, pixels), several = true)
    }

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
}

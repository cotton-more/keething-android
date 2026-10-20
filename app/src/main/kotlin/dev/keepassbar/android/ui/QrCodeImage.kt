package dev.keepassbar.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.nayuki.qrcodegen.DataTooLongException
import io.nayuki.qrcodegen.QrCode
import kotlin.math.floor

/** The quiet zone around a QR code, in modules: readers need it. */
private const val QUIET_ZONE = 4

/** A QR code of [text], or null when it does not fit in one (about 2,300 characters). */
internal fun qrCodeOf(text: String): QrCode? =
    try {
        // Medium error correction, as the Mac app (Mac `QRCode.image`).
        QrCode.encodeText(text, QrCode.Ecc.MEDIUM)
    } catch (_: DataTooLongException) {
        null
    }

/**
 * A QR code, black on white in either theme (readers expect dark modules on light), square and crisp: each
 * module is a whole number of pixels.
 */
@Composable
internal fun QrCodeImage(text: String, description: String, modifier: Modifier = Modifier) {
    val code = remember(text) { qrCodeOf(text) } ?: return
    Canvas(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12))
            .background(Color.White)
            .semantics { contentDescription = description },
    ) {
        val modules = code.size + 2 * QUIET_ZONE
        val cell = floor(size.minDimension / modules).coerceAtLeast(1f)
        val origin = (size.minDimension - cell * code.size) / 2
        for (y in 0 until code.size) {
            for (x in 0 until code.size) {
                if (code.getModule(x, y)) {
                    drawRect(Color.Black, topLeft = Offset(origin + x * cell, origin + y * cell), size = Size(cell, cell))
                }
            }
        }
    }
}

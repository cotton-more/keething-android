package dev.keepassbar.android.sharing

import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.provider.MediaStore
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.FileProvider
import dev.keepassbar.android.ui.qrCodeOf
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID

/** One QR code of a request or an answer as a picture to send or keep, with what it is written under it. */
internal class CodePicture(val qrText: String, val fileName: String, val caption: List<String>)

/**
 * The pictures a request or an answer travels as (SPEC §10): the QR code black on white, each module whole pixels, the
 * caption under it (never the pictures to compare: they must come from the other phone, not from what travelled).
 * Sent through the share sheet from `cacheDir/sharing` (cleared when the app starts), or kept in Pictures/Keething.
 * Nothing in them is secret: a request is public, an answer is sealed.
 */
internal class CodePictures(private val context: Context) {
    private val folder = File(context.cacheDir, "sharing")

    fun clear() {
        folder.deleteRecursively()
    }

    fun png(picture: CodePicture): ByteArray {
        val code = qrCodeOf(picture.qrText) ?: throw IOException("too large for a QR code")
        val modules = code.size + 2 * QUIET_ZONE
        val scale = maxOf(4, WIDTH / modules)
        val side = modules * scale
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = side / 26f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        val lineHeight = paint.fontSpacing
        val bitmap = Bitmap.createBitmap(side, side + (lineHeight * picture.caption.size + scale * QUIET_ZONE).toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val modulePaint = Paint().apply { color = Color.BLACK }
        for (y in 0 until code.size) {
            for (x in 0 until code.size) {
                if (!code.getModule(x, y)) continue
                val left = ((x + QUIET_ZONE) * scale).toFloat()
                val top = ((y + QUIET_ZONE) * scale).toFloat()
                canvas.drawRect(left, top, left + scale, top + scale, modulePaint)
            }
        }
        picture.caption.forEachIndexed { index, line ->
            val fitted = TextUtils.ellipsize(line, paint, side - 2f * QUIET_ZONE * scale, TextUtils.TruncateAt.END).toString()
            canvas.drawText(fitted, (side - paint.measureText(fitted)) / 2, side - scale * QUIET_ZONE / 2f + lineHeight * (index + 0.8f), paint)
        }
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }

    /** The share sheet with [pictures] (one or several). */
    fun sendIntent(pictures: List<CodePicture>): Intent {
        val visit = File(folder, UUID.randomUUID().toString()).apply { mkdirs() }
        val uris = pictures.map { picture ->
            val file = File(visit, picture.fileName).apply { writeBytes(png(picture)) }
            FileProvider.getUriForFile(context, "${context.packageName}.sharing", file)
        }
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.single())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(uris))
        }
        return chooser(send.setType("image/png").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    /** The share sheet without Keething's own "Read with Keething". */
    private fun chooser(send: Intent): Intent = Intent.createChooser(send, null)
        .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(context, ReceiveCodeActivity::class.java)))

    /** Keeps [pictures] in Pictures/Keething, where messengers and mail find them; no permission needed for the app's own. */
    fun save(pictures: List<CodePicture>) {
        val resolver = context.contentResolver
        for (picture in pictures) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, picture.fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Keething")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: throw IOException("the gallery refused the picture")
            try {
                resolver.openOutputStream(uri)?.use { it.write(png(picture)) } ?: throw IOException("the gallery refused the picture")
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            } catch (failure: IOException) {
                resolver.delete(uri, null, null)
                throw failure
            }
        }
    }

    /** The share sheet with [text]: a few words for the person and the code itself. */
    fun sendTextIntent(text: String): Intent = chooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text))

    private companion object {
        /** Readers need four modules of white around a code. */
        const val QUIET_ZONE = 4

        /** About this many pixels wide: sharp after a messenger shrinks it. */
        const val WIDTH = 1000
    }
}

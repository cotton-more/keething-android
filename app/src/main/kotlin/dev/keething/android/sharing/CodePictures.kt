package dev.keething.android.sharing

import android.app.PendingIntent
import android.content.BroadcastReceiver
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
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import dev.keething.android.KeethingApplication
import dev.keething.android.ui.UiText
import dev.keething.android.ui.qrCodeOf
import dev.keething.android.ui.resolve
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID

/** One QR code of a request or an answer as a picture to send or keep, with what it is written under it. */
internal class CodePicture(val qrText: String, val fileName: String, val caption: List<UiText>)

/**
 * The pictures a request or an answer travels as (SPEC §10): the QR code black on white, each module whole pixels, the
 * caption under it (never the pictures to compare: they must come from the other phone, not from what travelled).
 * Sent through the share sheet from `cacheDir/sharing` (cleared when the app starts), or kept in Pictures/Keething.
 * Nothing in them is secret: a request is public, an answer or an envelope is sealed.
 *
 * An envelope's code and its pictures should go different ways: the share sheet tells [ChosenAppReceiver] which app
 * took each ([Route]), and the other one's share sheet leaves that app out.
 */
internal class CodePictures(private val context: Context) {
    private val folder = File(context.cacheDir, "sharing")

    fun clear() {
        folder.deleteRecursively()
    }

    fun png(picture: CodePicture): ByteArray {
        val code = qrCodeOf(picture.qrText) ?: throw IOException("too large for a QR code") // not user text (a detail after "Could not make the picture:", as the system's own are)
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
            val fitted = TextUtils.ellipsize(line.resolve(context.resources), paint, side - 2f * QUIET_ZONE * scale, TextUtils.TruncateAt.END).toString()
            canvas.drawText(fitted, (side - paint.measureText(fitted)) / 2, side - scale * QUIET_ZONE / 2f + lineHeight * (index + 0.8f), paint)
        }
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }

    /** An envelope's code or its pictures on their way out: the app they go through is noted, [leaveOut] not offered. */
    class Route(val envelopeId: String, val pictures: Boolean, val leaveOut: String)

    /** The share sheet with [pictures] (one or several). */
    fun sendIntent(pictures: List<CodePicture>, route: Route? = null): Intent {
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
        return chooser(send.setType("image/png").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), route)
    }

    /** The share sheet ([ShareSheet], without Keething's own targets), and without [route]'s app to leave out. */
    private fun chooser(send: Intent, route: Route? = null): Intent {
        val chosen = route?.let {
            val tell = Intent(context, ChosenAppReceiver::class.java)
                .putExtra(ChosenAppReceiver.ENVELOPE, it.envelopeId)
                .putExtra(ChosenAppReceiver.PICTURES, it.pictures)
            // Mutable: the system adds the app chosen. Explicit, so nothing else can receive it.
            PendingIntent.getBroadcast(context, if (it.pictures) 1 else 0, tell, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE).intentSender
        }
        return ShareSheet.of(context, send, chosen, listOfNotNull(route?.leaveOut?.let(ComponentName::unflattenFromString)))
    }

    /** The name of the app [component] (a flat component name) belongs to, when this app may see it. */
    fun appName(component: String): String? {
        val name = ComponentName.unflattenFromString(component) ?: return null
        val packages = context.packageManager
        return runCatching { packages.getApplicationInfo(name.packageName, 0).loadLabel(packages).toString() }.getOrNull()
    }

    /** Keeps [pictures] in Pictures/Keething, where messengers and mail find them; no permission needed for the app's own. */
    fun save(pictures: List<CodePicture>) {
        val resolver = context.contentResolver
        for (picture in pictures) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, picture.fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Keething") // not user text
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: throw IOException("the gallery refused the picture") // not user text (a detail after "Could not save the picture:", as the system's own are)
            try {
                resolver.openOutputStream(uri)?.use { it.write(png(picture)) } ?: throw IOException("the gallery refused the picture") // not user text (as above)
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            } catch (failure: IOException) {
                resolver.delete(uri, null, null)
                throw failure
            }
        }
    }

    /** The share sheet with [text]: a few words for the person and the code itself, or an envelope's pictures. */
    fun sendTextIntent(text: UiText, route: Route? = null): Intent =
        chooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text.resolve(context.resources)), route)

    private companion object {
        /** Readers need four modules of white around a code. */
        const val QUIET_ZONE = 4

        /** About this many pixels wide: sharp after a messenger shrinks it. */
        const val WIDTH = 1000
    }
}

/** Told by the share sheet which app took an envelope's code or pictures ([CodePictures.Route]); not exported. */
internal class ChosenAppReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(ENVELOPE) ?: return
        val component = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_CHOSEN_COMPONENT, ComponentName::class.java) ?: return
        val pictures = intent.getBooleanExtra(PICTURES, false)
        Log.i("Sharing", if (pictures) "pictures handed to an app" else "code handed to an app")
        (context.applicationContext as KeethingApplication).container.shareRequests.sentVia(id, pictures, component.flattenToString())
    }

    companion object {
        const val ENVELOPE = "envelope"
        const val PICTURES = "pictures"
    }
}

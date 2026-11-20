package dev.keething.android.sharing

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.keething.android.AppContainer
import dev.keething.android.R
import dev.keething.android.ui.ButtonLabel
import dev.keething.android.ui.resolve
import dev.keething.android.ui.uiText
import dev.keething.sharing.message.ShareCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Dark = Color(0xFF101112)
private val OnDark = Color(0xFFF2F3F5)
private val Muted = Color(0xFFC9CCD1)
private val ButtonDark = Color(0xFF2A2D31)

/**
 * Reading a code (SPEC §10): the back camera, or a picture from the gallery, or text pasted. [incoming]: codes another
 * app handed over, read at once. A request leads to its pictures ([onTheirs]), an answer to its entries and an envelope
 * to its pictures ([onSealed]), both in place of this screen.
 */
@Composable
internal fun ReadCodeScreen(
    container: AppContainer,
    incoming: List<String>,
    onClose: () -> Unit,
    onTheirs: (id: String) -> Unit,
    onSealed: (text: String) -> Unit,
) {
    val model = viewModel { ReadCodeModel(container.shareRequests, container.answerParts) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(model) {
        model.outcomesFlow.collect { outcome ->
            when (outcome) {
                is ReadCodeModel.Outcome.Theirs -> onTheirs(outcome.id)
                is ReadCodeModel.Outcome.Sealed -> onSealed(outcome.text)
            }
        }
    }
    var handedOver by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!handedOver && incoming.isNotEmpty()) model.read(incoming)
        handedOver = true
    }

    fun granted() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var camera by remember { mutableStateOf(granted()) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { camera = it }
    LaunchedEffect(Unit) {
        if (!camera && !asked && incoming.isEmpty()) {
            asked = true
            askCamera.launch(Manifest.permission.CAMERA)
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { camera = granted() }

    // Several at once: all the pictures of a large answer.
    val pickPictures = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(ShareCode.MAX_PARTS)) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val texts = withContext(Dispatchers.Default) {
                uris.map { uri -> runCatching { QrReading.texts(context.contentResolver, uri) }.getOrNull() }
            }
            when {
                texts.all { it == null } -> model.problem(uiText(R.string.sharing_read_unreadable_title), uiText(R.string.sharing_read_unreadable_text))
                texts.all { it.isNullOrEmpty() } -> model.problem(uiText(R.string.sharing_read_no_qr_title), uiText(R.string.sharing_read_no_qr_text))
                else -> model.read(texts.flatMap { it.orEmpty() })
            }
        }
    }
    val paste = {
        val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        if (text.isNullOrBlank()) {
            model.problem(uiText(R.string.sharing_read_nothing_title), uiText(R.string.sharing_read_nothing_text))
        } else {
            model.read(listOf(text))
        }
    }

    Box(Modifier.fillMaxSize().background(Dark)) {
        if (camera) CodeCamera(onTexts = { model.read(it, fromCamera = true) }, modifier = Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.sharing_close), tint = OnDark) }
                Text(stringResource(R.string.sharing_read_a_code), color = OnDark, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleLarge)
            }
            model.banner?.let { BannerView(it) }
            Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Box(Modifier.size(264.dp), contentAlignment = Alignment.Center) {
                    ViewFinder()
                    if (model.busy) LoadingIndicator(color = OnDark)
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    stringResource(if (camera) R.string.sharing_read_point else R.string.sharing_read_no_camera),
                    Modifier.padding(horizontal = 32.dp),
                    color = Muted,
                    textAlign = TextAlign.Center,
                )
                if (!camera) {
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = {
                        if (!asked) {
                            asked = true
                            askCamera.launch(Manifest.permission.CAMERA)
                        } else {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                        }
                    }) { Text(stringResource(R.string.sharing_read_allow_camera)) }
                }
            }
            Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DarkButton(stringResource(R.string.sharing_read_from_picture), Icons.Rounded.Image, Modifier.weight(1f)) {
                    pickPictures.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                DarkButton(stringResource(R.string.sharing_paste), Icons.Rounded.ContentPaste, Modifier.weight(1f), paste)
            }
        }
    }
}

@Composable
private fun BannerView(banner: ReadCodeModel.Banner) {
    Row(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(OnDark).padding(16.dp).announced(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(if (banner.isProblem) Icons.Rounded.ErrorOutline else Icons.Rounded.Info, contentDescription = null, tint = Color(0xFF17181A))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(banner.title.resolve(), color = Color(0xFF17181A), fontWeight = FontWeight.SemiBold)
            Text(banner.text.resolve(), color = Color(0xFF17181A), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun DarkButton(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.height(56.dp),
        colors = ButtonDefaults.buttonColors(containerColor = ButtonDark, contentColor = OnDark),
    ) {
        ButtonLabel(label, icon)
    }
}

/** The four corners the code is to be held in. */
@Composable
private fun ViewFinder() {
    Canvas(Modifier.fillMaxSize()) {
        val stroke = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
        val radius = 36.dp.toPx()
        val arm = 24.dp.toPx()
        val w = size.width
        val h = size.height
        val corners = listOf(Offset(0f, 0f) to 180f, Offset(w - 2 * radius, 0f) to 270f, Offset(w - 2 * radius, h - 2 * radius) to 0f, Offset(0f, h - 2 * radius) to 90f)
        for ((topLeft, start) in corners) drawArc(Color.White, start, 90f, false, topLeft, Size(2 * radius, 2 * radius), style = stroke)
        drawLine(Color.White, Offset(radius, 0f), Offset(radius + arm, 0f), stroke.width, StrokeCap.Round)
        drawLine(Color.White, Offset(0f, radius), Offset(0f, radius + arm), stroke.width, StrokeCap.Round)
        drawLine(Color.White, Offset(w - radius, 0f), Offset(w - radius - arm, 0f), stroke.width, StrokeCap.Round)
        drawLine(Color.White, Offset(w, radius), Offset(w, radius + arm), stroke.width, StrokeCap.Round)
        drawLine(Color.White, Offset(w - radius, h), Offset(w - radius - arm, h), stroke.width, StrokeCap.Round)
        drawLine(Color.White, Offset(w, h - radius), Offset(w, h - radius - arm), stroke.width, StrokeCap.Round)
        drawLine(Color.White, Offset(radius, h), Offset(radius + arm, h), stroke.width, StrokeCap.Round)
        drawLine(Color.White, Offset(0f, h - radius), Offset(0f, h - radius - arm), stroke.width, StrokeCap.Round)
    }
}

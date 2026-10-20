package dev.keepassbar.android.entry

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.keepassbar.android.session.DatabaseSession
import dev.keepassbar.android.theme.MonospaceFamily
import dev.keepassbar.android.theme.extraColors
import dev.keepassbar.android.ui.QrCodeImage
import dev.keepassbar.android.ui.colouredSecret
import dev.keepassbar.android.ui.qrCodeOf
import dev.keepassbar.domain.vault.Entry
import dev.keepassbar.vault.session.SessionState

/** The full-screen views of a password (SPEC §6.2). */
internal enum class PasswordView { QR_CODE, LARGE_TYPE }

/**
 * A password shown full screen, read from the vault by the entry's id (the back stack never holds it): as a
 * QR code for another phone to scan, or in large type to type it elsewhere. Closes when the vault locks or
 * the entry is gone.
 */
@Composable
internal fun PasswordScreen(database: DatabaseSession, entryId: String, view: PasswordView, onBack: () -> Unit) {
    val state by database.session.state.collectAsStateWithLifecycle()
    val vault = (state as? SessionState.Unlocked)?.vault
    val entry: Entry? = remember(vault, entryId) { vault?.let { it.entries.firstOrNull { e -> e.id == entryId } ?: it.recycleBin.firstOrNull { e -> e.id == entryId } } }
    val password = remember(entry) { entry?.password?.takeIf { !it.isEmpty() }?.reveal() }
    LaunchedEffect(password == null) { if (password == null) onBack() }
    if (entry == null || password == null) return

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(entry.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Rounded.Close, contentDescription = "Close") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            when (view) {
                PasswordView.QR_CODE -> QrCodeView(password)
                PasswordView.LARGE_TYPE -> LargeType(password)
            }
        }
    }
}

@Composable
private fun QrCodeView(password: String) {
    FullBrightness()
    if (qrCodeOf(password) == null) {
        Text("The password is too long for a QR code.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    QrCodeImage(password, description = "QR code of the password", modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp))
    Text(
        "Scan it with the other device's camera. Nothing leaves this phone.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    var shown by rememberSaveable { mutableStateOf(false) }
    FilledTonalButton(onClick = { shown = !shown }) {
        Icon(if (shown) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, contentDescription = null, Modifier.padding(end = ButtonDefaults.IconSpacing))
        Text(if (shown) "Hide the password" else "Show the password")
    }
    if (shown) {
        Text(colouredSecret(password), style = MaterialTheme.typography.titleLarge, fontFamily = MonospaceFamily, textAlign = TextAlign.Center)
    }
}

/** Each character in its own cell, numbered, so a long password is typed without losing the place. */
@Composable
private fun LargeType(password: String) {
    val characters = remember(password) { characters(password) }
    val digit = MaterialTheme.extraColors.digit
    val symbol = MaterialTheme.extraColors.symbol
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        characters.forEachIndexed { index, character ->
            val space = character.isBlank()
            Column(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.extraColors.row)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.widthIn(min = 28.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (space) "␣" else character,
                        fontFamily = MonospaceFamily,
                        fontSize = 40.sp,
                        lineHeight = 48.sp,
                        color = when {
                            space -> MaterialTheme.colorScheme.onSurfaceVariant
                            character.first().isDigit() -> digit
                            character.first().isLetter() -> MaterialTheme.colorScheme.onSurface
                            else -> symbol
                        },
                    )
                }
                Text("${index + 1}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The characters of [text], a surrogate pair (an emoji) kept as one. */
private fun characters(text: String): List<String> = buildList {
    var index = 0
    while (index < text.length) {
        val length = Character.charCount(text.codePointAt(index))
        add(text.substring(index, index + length))
        index += length
    }
}

/** The screen at full brightness while a code is shown, so a camera reads it; as it was afterwards. */
@Composable
private fun FullBrightness() {
    val window = LocalActivity.current?.window ?: return
    DisposableEffect(window) {
        val before = window.attributes.screenBrightness
        window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL }
        onDispose { window.attributes = window.attributes.apply { screenBrightness = before } }
    }
}

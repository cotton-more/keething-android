package dev.keepassbar.android.entry

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
import android.content.Intent
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import dev.keepassbar.android.clipboard.SensitiveClipboard
import dev.keepassbar.card.AddressCheck
import dev.keepassbar.card.CryptoAddress
import dev.keepassbar.card.CryptoNetwork
import dev.keepassbar.card.EntryCardBuilder
import dev.keepassbar.card.SeedPhrase
import dev.keepassbar.android.session.DatabaseSession
import dev.keepassbar.android.theme.MonospaceFamily
import dev.keepassbar.android.theme.extraColors
import dev.keepassbar.android.ui.FullBrightness
import dev.keepassbar.android.ui.NetworkBadge
import dev.keepassbar.android.ui.QrCodeImage
import dev.keepassbar.android.ui.colouredSecret
import dev.keepassbar.android.ui.qrCodeOf
import dev.keepassbar.domain.vault.Entry
import dev.keepassbar.vault.session.SessionState
import java.time.Instant

/**
 * The full-screen views of a password (SPEC §6.2), the QR code to join an entry's Wi-Fi network, a wallet's recovery
 * phrase word by word and a receiving address's QR code.
 */
internal enum class PasswordView { QR_CODE, LARGE_TYPE, WIFI_QR, SEED_WORDS, ADDRESS_QR }

/**
 * A password shown full screen, read from the vault by the entry's id (the back stack never holds it): as a
 * QR code for another phone to scan, or in large type to type it elsewhere. Likewise a wallet's recovery phrase, a
 * word at a time, and an address's QR code ([rowId] says which). Closes when the vault locks or the entry is gone.
 */
@Composable
internal fun PasswordScreen(database: DatabaseSession, entryId: String, view: PasswordView, rowId: String?, clipboard: SensitiveClipboard, onBack: () -> Unit) {
    val state by database.session.state.collectAsStateWithLifecycle()
    val vault = (state as? SessionState.Unlocked)?.vault
    val entry: Entry? = remember(vault, entryId) { vault?.let { it.entries.firstOrNull { e -> e.id == entryId } ?: it.recycleBin.firstOrNull { e -> e.id == entryId } } }
    // A network's code holds its key, read as the card reads it; a wallet's parts too; else the entry's password.
    val card = remember(entry, view) { entry?.takeIf { view != PasswordView.QR_CODE && view != PasswordView.LARGE_TYPE }?.let { EntryCardBuilder.build(it, Instant.now()) } }
    val network = card?.takeIf { view == PasswordView.WIFI_QR }
    val password = remember(entry, card) {
        when (view) {
            PasswordView.WIFI_QR -> network?.wifi?.let { wifi -> wifi.qrPayload(wifi.passwordRowId?.let { id -> network.rows.firstOrNull { it.id == id } }?.storedText().orEmpty()) }
            PasswordView.SEED_WORDS -> card?.crypto?.seedRowId?.let { id -> card.rows.firstOrNull { it.id == id } }?.takeIf { !it.isEmpty }?.storedText()
            PasswordView.ADDRESS_QR -> card?.crypto?.addresses?.firstOrNull { it.rowId == rowId }?.text
            else -> entry?.password?.takeIf { !it.isEmpty() }?.reveal()
        }
    }
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
                PasswordView.WIFI_QR -> NetworkQrCode(password, network?.wifi?.ssid.orEmpty())
                PasswordView.SEED_WORDS -> SeedWords(password)
                PasswordView.ADDRESS_QR -> AddressQrCode(password) { clipboard.copy(password, sensitive = false) }
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

/** The code another phone reads to join the network (SPEC §6.2): at full brightness, nothing else on it. */
@Composable
private fun NetworkQrCode(payload: String, ssid: String) {
    FullBrightness()
    QrCodeImage(payload, description = "QR code to join $ssid", modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp))
    Text("Join $ssid", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
    Text(
        "Point the other phone's camera at the code.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/**
 * The recovery phrase a word at a time (SPEC §6.2), large, numbered, with the words before and after it, to write it down
 * or type it into a wallet without losing the place. Back and Next, or a sideways swipe.
 */
@Composable
private fun SeedWords(text: String) {
    val words = remember(text) { SeedPhrase.read(text)?.words.orEmpty() }
    if (words.isEmpty()) return
    var index by rememberSaveable { mutableIntStateOf(0) }
    val current = index.coerceIn(words.indices)
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().height(6.dp).semantics { contentDescription = "Word ${current + 1} of ${words.size}" },
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for (i in words.indices) {
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(3.dp))
                    .background(if (i == current) colors.primary else if (i < current) colors.onSurfaceVariant else colors.outlineVariant),
            )
        }
    }
    var drag by remember { mutableFloatStateOf(0f) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(32.dp))
            .background(MaterialTheme.extraColors.row)
            .pointerInput(words.size) {
                detectHorizontalDragGestures(
                    onDragStart = { drag = 0f },
                    onDragEnd = {
                        if (drag < -80f && index < words.size - 1) index++
                        if (drag > 80f && index > 0) index--
                    },
                    onHorizontalDrag = { _, amount -> drag += amount },
                )
            }
            .padding(horizontal = 12.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Word ${current + 1} of ${words.size}", style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant)
        Text(
            words[current],
            fontFamily = MonospaceFamily,
            fontSize = 52.sp,
            lineHeight = 64.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Text(
            listOfNotNull(
                words.getOrNull(current - 1)?.let { "$current $it" },
                words.getOrNull(current + 1)?.let { "${current + 2} $it" },
            ).joinToString("   ·   "),
            fontFamily = MonospaceFamily,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FilledTonalButton(onClick = { index = current - 1 }, enabled = current > 0, modifier = Modifier.weight(1f).heightIn(min = 64.dp)) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = null)
            Text("Back", style = MaterialTheme.typography.titleMedium)
        }
        Button(onClick = { index = current + 1 }, enabled = current < words.size - 1, modifier = Modifier.weight(1.4f).heightIn(min = 64.dp)) {
            Text(if (current == words.size - 1) "Last word" else "Next", style = MaterialTheme.typography.titleMedium)
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
        }
    }
    Text(
        "Swipe, or tap the buttons. Screenshots are blocked; this closes when the app locks.",
        style = MaterialTheme.typography.bodyMedium,
        color = colors.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/** A receiving address's QR code (SPEC §6.2), to show someone who sends money: the plain address, at full brightness. */
@Composable
private fun AddressQrCode(address: String, onCopy: () -> Unit) {
    FullBrightness()
    val read = remember(address) { CryptoAddress.read(address) }
    val context = LocalContext.current
    read?.network?.let { network ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NetworkBadge(network, 28.dp)
            Text("Receive · ${network.displayName}", style = MaterialTheme.typography.titleMedium)
        }
    }
    QrCodeImage(address, description = "QR code of the address", modifier = Modifier.fillMaxWidth().widthIn(max = 340.dp))
    Text(
        groupedAddress(address, MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.onSurfaceVariant),
        fontFamily = MonospaceFamily,
        fontSize = 19.sp,
        lineHeight = 28.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(max = 340.dp),
    )
    read?.network?.let { network ->
        Note(
            Icons.Rounded.WarningAmber,
            if (network == CryptoNetwork.ETHEREUM) "Ethereum or the EVM network this wallet uses: coins of another network sent here can be lost." else "Only ${network.displayName} to this address. Coins of another network sent here can be lost.",
            MaterialTheme.extraColors.warnContainer,
            MaterialTheme.extraColors.onWarnContainer,
        )
    }
    if (read?.check == AddressCheck.FAILS) {
        Note(Icons.Rounded.ErrorOutline, "The address fails its checksum (a typo?)", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onCopy) {
            Icon(Icons.Rounded.ContentCopy, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
            Text("Copy")
        }
        // An address is public: the share sheet without a warning.
        FilledTonalButton(onClick = {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, address)
            context.startActivity(Intent.createChooser(send, null))
        }) {
            Icon(Icons.Rounded.Share, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
            Text("Share")
        }
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

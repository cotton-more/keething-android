package dev.keething.android.about

import android.content.res.AssetManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.keething.android.theme.MonospaceFamily
import dev.keething.android.theme.extraColors

/** A part of the app under its own licence, its text the asset `licenses/<file>`. */
internal data class ThirdParty(val name: String, val credit: String, val licence: String, val file: String)

/**
 * The third-party parts in the app (SPEC §6.2 "About"), in the order About lists them; every file of
 * `assets/licenses` is one of them (LicencesTest). The credit is said in the list, where CC BY asks for it.
 */
internal val THIRD_PARTY = listOf(
    ThirdParty(
        "EFF Large Wordlist for Passphrases",
        "The password generator's words, by the Electronic Frontier Foundation (eff.org/dice)",
        "CC BY 3.0 US",
        "eff-wordlist-CC-BY-3.0.txt",
    ),
    ThirdParty("BIP-39 word lists", "The recovery phrase check's words, by the BIP-39 authors", "MIT", "bip39-wordlists-MIT.txt"),
    ThirdParty("Matrix SAS emoji", "The pictures for checking a request, by The Matrix.org Foundation", "Apache 2.0", "matrix-sas-emoji-Apache-2.0.txt"),
    ThirdParty("Google Sans Code", "The typeface of passwords and codes, by The Google Sans Code Project Authors", "OFL 1.1", "google-sans-code-OFL.txt"),
    ThirdParty("QR Code generator", "QR codes of a password and a Wi-Fi network, by Project Nayuki", "MIT", "qrcodegen-MIT.txt"),
    ThirdParty("ZXing", "Reading the QR codes of sharing, by the ZXing authors", "Apache 2.0", "zxing-Apache-2.0.txt"),
    ThirdParty(
        "AndroidX, Kotlin, kotlinx.coroutines",
        "Compose, CameraX and the other Jetpack libraries, by The Android Open Source Project and JetBrains",
        "Apache 2.0",
        "androidx-kotlin-Apache-2.0.txt",
    ),
)

/** The app's own licence (SPEC §6.2 "About"): its SPDX name, its text the asset Gradle copies from the repository's LICENSE. */
internal const val LICENCE = "GPL-3.0-or-later"
internal const val LICENCE_ASSET = "LICENSE.txt"

/** How to report a vulnerability (keething-spec app.security-policy): the repository's SECURITY.md, copied by Gradle. */
internal const val SECURITY_ASSET = "SECURITY.md"
internal const val SOURCE = "https://github.com/cotton-more/keething-android"

internal fun readAsset(assets: AssetManager, path: String): String =
    runCatching { assets.open(path).use { it.readBytes().decodeToString() } }.getOrDefault("The text could not be read.")

/** Third-party licences: each part with its credit and licence; a tap shows the licence's text. */
@Composable
internal fun ThirdPartyScreen(onOpen: (ThirdParty) -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    Page("Third-party licences", onClose) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.extraColors.row)) {
            THIRD_PARTY.forEachIndexed { index, part ->
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                Column(Modifier.fillMaxWidth().clickable { onOpen(part) }.padding(horizontal = 20.dp, vertical = 14.dp)) {
                    Text(part.name, style = MaterialTheme.typography.bodyLarge)
                    Text(part.credit, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(part.licence, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/** A licence's whole text, as the file has it. */
@Composable
internal fun LicenceTextScreen(title: String, asset: String, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val assets = LocalContext.current.assets
    val text = remember(asset) { readAsset(assets, asset) }
    Page(title, onClose) {
        SelectionContainer {
            Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = MonospaceFamily)
        }
    }
}

@Composable
private fun Page(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .displayCutoutPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Row(Modifier.height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1)
        }
        Column(Modifier.fillMaxWidth().wrapContentWidth().widthIn(max = 720.dp).padding(horizontal = 8.dp, vertical = 8.dp)) {
            content()
        }
    }
}

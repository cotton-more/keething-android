package dev.keething.android.about

import dev.keething.android.ui.READABLE_WIDTH
import android.content.res.AssetManager
import androidx.annotation.StringRes
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.keething.android.R
import dev.keething.android.ui.UiText
import dev.keething.android.ui.resolve
import dev.keething.android.ui.uiPlain
import dev.keething.android.ui.uiText
import dev.keething.android.theme.MonospaceFamily
import dev.keething.android.theme.extraColors

/**
 * A part of the app under its own licence, its text the asset `licenses/<file>`. Its [name] and the licences' names are
 * theirs, said as they are; [credit] is the app's sentence about it. [artifacts]: the release's Maven artifacts it covers,
 * as `group:artifact` with `*` for any part (keething-spec app.dependency-licences AC2); a part copied into the source
 * has none, its files are in `app/third-party-files.txt` (AC3).
 */
internal data class ThirdParty(
    val name: String,
    @StringRes val credit: Int,
    val licence: UiText,
    val file: String,
    val artifacts: List<String> = emptyList(),
) {
    constructor(name: String, @StringRes credit: Int, licence: String, file: String, artifacts: List<String> = emptyList()) :
        this(name, credit, uiPlain(licence), file, artifacts)
}

/**
 * The third-party parts in the app (SPEC §6.2 "About"), in the order About lists them; every file of
 * `assets/licenses` is one of them (LicencesTest). The credit is said in the list, where CC BY asks for it.
 */
internal val THIRD_PARTY = listOf(
    ThirdParty(
        "EFF Large Wordlist for Passphrases", // not user text: names
        R.string.about_credit_eff_wordlist,
        "CC BY 3.0 US", // not user text: names
        "eff-wordlist-CC-BY-3.0.txt",
    ),
    ThirdParty("BIP-39 word lists", R.string.about_credit_bip39, "MIT", "bip39-wordlists-MIT.txt"), // not user text: names
    ThirdParty("Matrix SAS emoji", R.string.about_credit_matrix_sas, "Apache 2.0", "matrix-sas-emoji-Apache-2.0.txt"), // not user text: names
    ThirdParty("Google Sans Code", R.string.about_credit_google_sans_code, "OFL 1.1", "google-sans-code-OFL.txt"), // not user text: names
    ThirdParty("QR Code generator", R.string.about_credit_qrcodegen, "MIT", "qrcodegen-MIT.txt", listOf("io.nayuki:qrcodegen")), // not user text: names
    ThirdParty("ZXing", R.string.about_credit_zxing, "Apache 2.0", "zxing-Apache-2.0.txt", listOf("com.google.zxing:core")), // not user text: names
    ThirdParty("AndroidSVG", R.string.about_credit_androidsvg, "Apache 2.0", "androidsvg-Apache-2.0.txt", listOf("com.caverock:*")), // not user text: names
    ThirdParty(
        "Argon2", // not user text: names
        R.string.about_credit_argon2,
        uiText(R.string.about_licence_either, "CC0 1.0", "Apache 2.0"), // not user text: names
        "argon2-CC0-Apache-2.0.txt", // not user text: an asset
    ),
    ThirdParty(
        "Public Suffix List", // not user text: names
        R.string.about_credit_public_suffix_list,
        "MPL 2.0", // not user text: names
        "public-suffix-list-MPL-2.0.txt", // not user text: an asset
    ),
    ThirdParty(
        "AndroidX, Kotlin, kotlinx", // not user text: names
        R.string.about_credit_androidx,
        uiText(R.string.about_licence_and, "Apache 2.0", "BSD 3-Clause"), // not user text: names
        "androidx-kotlin-Apache-2.0.txt", // not user text: an asset
        listOf("androidx.*:*", "org.jetbrains.kotlin:*", "org.jetbrains.kotlinx:*"), // not user text: coordinates
    ),
    ThirdParty(
        "Guava, Dagger, annotations", // not user text: names
        R.string.about_credit_google_libraries,
        "Apache 2.0", // not user text: names
        "google-libraries-Apache-2.0.txt", // not user text: an asset
        listOf(
            "com.google.guava:*", "com.google.dagger:*", "com.google.auto.value:*", "com.google.errorprone:*", // not user text: coordinates
            "com.google.j2objc:*", "com.google.code.findbugs:*", "javax.inject:*", "jakarta.inject:*", // not user text: coordinates
            "org.jetbrains:annotations", "org.jspecify:*", // not user text: coordinates
        ),
    ),
    ThirdParty(
        "Checker Framework qualifiers", // not user text: names
        R.string.about_credit_checker_qual,
        "MIT", // not user text: names
        "checker-qual-MIT.txt", // not user text: an asset
        listOf("org.checkerframework:checker-qual"), // not user text: coordinates
    ),
)

/** The app's own licence (SPEC §6.2 "About"): its SPDX name, its text the asset Gradle copies from the repository's LICENSE. */
internal const val LICENCE = "GPL-3.0-or-later"
internal const val LICENCE_ASSET = "LICENSE.txt"

/** How to report a vulnerability (keething-spec app.security-policy): the repository's SECURITY.md, copied by Gradle. */
internal const val SECURITY_ASSET = "SECURITY.md"
internal const val SOURCE = "https://github.com/cotton-more/keething-android"

/** The asset's text; null when it could not be read. */
internal fun readAsset(assets: AssetManager, path: String): String? =
    runCatching { assets.open(path).use { it.readBytes().decodeToString() } }.getOrNull()

/** Third-party licences: each part with its credit and licence; a tap shows the licence's text. */
@Composable
internal fun ThirdPartyScreen(onOpen: (ThirdParty) -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    Page(stringResource(R.string.about_third_party_title), onClose) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.extraColors.row)) {
            THIRD_PARTY.forEachIndexed { index, part ->
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                Column(Modifier.fillMaxWidth().clickable { onOpen(part) }.padding(horizontal = 20.dp, vertical = 14.dp)) {
                    Text(part.name, style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(part.credit), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(part.licence.resolve(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
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
    val text = remember(asset) { readAsset(assets, asset) } ?: stringResource(R.string.about_text_unreadable)
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
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.about_back)) }
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1)
        }
        // At most the readable width, centred (keething-spec app.wide-screens AC5).
        Column(Modifier.fillMaxWidth().wrapContentWidth().widthIn(max = READABLE_WIDTH).padding(horizontal = 8.dp, vertical = 8.dp)) {
            content()
        }
    }
}

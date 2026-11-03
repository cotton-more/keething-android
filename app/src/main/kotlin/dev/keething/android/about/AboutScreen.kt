package dev.keething.android.about

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.keething.android.theme.extraColors
import dev.keething.android.ui.AppLogo

/**
 * Whether About covers the app (SPEC §6.2 "About"): opened from a corner of the screens with nothing open (the welcome,
 * the chooser, a lock screen) and from Settings, so the build can be told with every database locked.
 */
internal class AboutPage {
    var shown: Boolean by mutableStateOf(false)
}

/**
 * About: the app, its version, when this build was made and from which commit (keething-spec app.about), its licence
 * and source, and the third-party licences; a licence's text over it, Back returning to the page under it.
 */
@Composable
internal fun AboutScreen(onClose: () -> Unit) {
    var thirdParty by rememberSaveable { mutableStateOf(false) }
    // The asset of the licence shown, if one is.
    var text by rememberSaveable { mutableStateOf<String?>(null) }
    val shown = text
    when {
        shown != null -> LicenceTextScreen(
            title = when (shown) {
                LICENCE_ASSET -> "Licence"
                SECURITY_ASSET -> "Security policy"
                else -> THIRD_PARTY.firstOrNull { "licenses/${it.file}" == shown }?.name ?: "Licence"
            },
            asset = shown,
            onClose = { text = null },
        )
        thirdParty -> ThirdPartyScreen(onOpen = { text = "licenses/${it.file}" }, onClose = { thirdParty = false })
        else -> AboutMain(onLicence = { text = LICENCE_ASSET }, onSecurity = { text = SECURITY_ASSET }, onThirdParty = { thirdParty = true }, onClose = onClose)
    }
}

@Composable
private fun AboutMain(onLicence: () -> Unit, onSecurity: () -> Unit, onThirdParty: () -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val info = remember { BuildInfo.read(context.assets) }
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .displayCutoutPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Row(Modifier.height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
            Text("About", style = MaterialTheme.typography.titleLarge)
        }
        Column(
            Modifier.fillMaxWidth().wrapContentWidth().widthIn(max = 480.dp).padding(horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            AppLogo(Modifier.size(96.dp))
            Spacer(Modifier.height(16.dp))
            Text("Keething", style = MaterialTheme.typography.headlineMedium)
            Text("for Android", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(32.dp))
            // Selectable: a bug report quotes them.
            SelectionContainer {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.extraColors.row).padding(vertical = 8.dp),
                ) {
                    Fact("Version", info.versionText)
                    Fact("Built", info.builtText())
                    Fact("Commit", info.commitText)
                }
            }
            Spacer(Modifier.height(16.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.extraColors.row).padding(vertical = 8.dp)) {
                Fact("Licence", LICENCE, Modifier.clickable(onClick = onLicence))
                Fact("Source", SOURCE.removePrefix("https://"), Modifier.clickable { openInBrowser(context, SOURCE) })
                Fact("Security", "How to report a vulnerability", Modifier.clickable(onClick = onSecurity))
            }
            Spacer(Modifier.height(24.dp))
            FilledTonalButton(onClick = onThirdParty) { Text("Third-party licences") }
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = { ProblemReport.open(context) }) { Text("Report a problem") }
            Text(
                "Opens a new issue on GitHub with this build, Android and the phone's model filled in; nothing from a database.",
                Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun Fact(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(min = 72.dp))
        Text(value, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

/** About's way in, in the top corner of a screen with nothing open; [content]: the screen under it. */
@Composable
internal fun WithAboutCorner(onAbout: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        content()
        IconButton(onClick = onAbout, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().displayCutoutPadding().padding(4.dp)) {
            Icon(Icons.Outlined.Info, contentDescription = "About Keething", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

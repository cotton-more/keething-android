package dev.keething.android.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.keething.android.R

/**
 * Material 3 Expressive with the wallpaper's colours (SPEC §6.1), plus the few colours Material has no
 * role for: warnings, the digits and symbols of a shown password, the background of list and card rows.
 */
@Composable
fun KeethingTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colors = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    CompositionLocalProvider(LocalExtraColors provides extraColors(colors, dark)) {
        MaterialExpressiveTheme(
            colorScheme = colors,
            motionScheme = MotionScheme.expressive(),
            typography = typography(),
            content = content,
        )
    }
}

@Immutable
internal data class ExtraColors(
    /** Rows of the segmented groups on the list and the card. */
    val row: Color,
    val warn: Color,
    /** The fingerprint opens it, and what the chooser's run opened (the agreed mock-up's green). */
    val good: Color,
    val warnContainer: Color,
    val onWarnContainer: Color,
    /** A shown password's digits and symbols (letters keep the text colour), as 1Password does. */
    val digit: Color,
    val symbol: Color,
    /** A Wi-Fi network's face: the label under a router (SPEC §6.2), its edge and its small print. */
    val sticker: Color,
    val stickerEdge: Color,
    val stickerLabel: Color,
    /** An identity document's face: paper in either theme (a shade darker at night), its guilloche and small print. */
    val paper: Color,
    val paperEdge: Color,
    val paperWave: Color,
    val paperLabel: Color,
    val paperPhoto: Color,
)

private fun extraColors(scheme: ColorScheme, dark: Boolean) = if (dark) {
    ExtraColors(
        row = scheme.surfaceContainer,
        warn = Color(0xFFF2BE72),
        good = Color(0xFF83D494),
        warnContainer = Color(0xFF4A3510),
        onWarnContainer = Color(0xFFFFE3BC),
        digit = Color(0xFF9EC2FF),
        symbol = Color(0xFFFFAA88),
        sticker = scheme.surfaceContainerHigh,
        stickerEdge = scheme.outlineVariant,
        stickerLabel = scheme.onSurfaceVariant,
        paper = Color(0xFFC9DADC),
        paperEdge = Color(0xFF8FA5A8),
        paperWave = Color(0x66789FA4),
        paperLabel = Color(0xFF3D5A66),
        paperPhoto = Color(0xFFADBAC2),
    )
} else {
    ExtraColors(
        row = scheme.surfaceContainerLowest,
        warn = Color(0xFF8F5410),
        good = Color(0xFF005F21),
        warnContainer = Color(0xFFFBE4C4),
        onWarnContainer = Color(0xFF5C3A0E),
        digit = Color(0xFF2659C0),
        symbol = Color(0xFFB8461F),
        sticker = Color(0xFFF7F6F1),
        stickerEdge = Color(0xFFDAD8CF),
        stickerLabel = Color(0xFF5E5D57),
        paper = Color(0xFFE9F3F4),
        paperEdge = Color(0xFFC3D3D5),
        paperWave = Color(0x6696C4CA),
        paperLabel = Color(0xFF45616C),
        paperPhoto = Color(0xFFCBD3D9),
    )
}

internal val LocalExtraColors = staticCompositionLocalOf<ExtraColors> { error("No KeethingTheme") }

internal val MaterialTheme.extraColors: ExtraColors
    @Composable @ReadOnlyComposable get() = LocalExtraColors.current

/** Google Sans Code (OFL, `assets/licenses`): passwords and codes, where every character must be clear. */
internal val MonospaceFamily = FontFamily(
    Font(R.font.google_sans_code, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.google_sans_code, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.google_sans_code, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
)

/** The system font (Google Sans Flex on a Pixel), with heavier titles. */
private fun typography(): Typography {
    val base = Typography()
    return base.copy(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    )
}

package dev.keepassbar.android.generator

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.keepassbar.android.theme.MonospaceFamily
import dev.keepassbar.android.theme.extraColors
import dev.keepassbar.android.ui.colouredSecret
import dev.keepassbar.generator.CharacterClass
import dev.keepassbar.generator.GeneratorOptions
import dev.keepassbar.generator.PasswordKind
import dev.keepassbar.generator.Strength
import kotlin.math.roundToInt

/** What the sheet's main button does with the password: put it into the entry, or copy it. */
internal sealed interface GeneratorUse {
    /** In the editor: the password goes into the entry. */
    class IntoEntry(val use: (String) -> Unit) : GeneratorUse

    /** From the list: a password without an entry, copied (sensitive, cleared after 30 s). */
    class Copy(val copy: (String) -> Unit) : GeneratorUse
}

/**
 * The password generator (Mac SPEC §3.20) as a sheet: the kind, the password monospaced with digits and symbols
 * coloured, how strong the recipe is, the kind's options, and New password / Use this password (or Copy).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PasswordGeneratorSheet(state: PasswordGeneratorState, use: GeneratorUse, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Password generator", style = MaterialTheme.typography.headlineSmall)
            KindChoice(state.options.kind) { state.change(state.options.copy(kind = it)) }
            Text(
                colouredSecret(state.password),
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                fontFamily = MonospaceFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 19.sp,
                lineHeight = 26.sp,
            )
            StrengthLine(state)
            Options(state)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = state::regenerate) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("New password")
                }
                Spacer(Modifier.weight(1f))
                when (use) {
                    is GeneratorUse.IntoEntry -> Button(onClick = { use.use(state.password) }) { Text("Use this password") }
                    is GeneratorUse.Copy -> Button(onClick = { use.copy(state.password) }) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Copy")
                    }
                }
            }
        }
    }
}

@Composable
private fun KindChoice(kind: PasswordKind, onChoose: (PasswordKind) -> Unit) {
    val kinds = PasswordKind.entries
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        kinds.forEachIndexed { i, each ->
            SegmentedButton(
                selected = each == kind,
                onClick = { onChoose(each) },
                shape = SegmentedButtonDefaults.itemShape(i, kinds.size),
                icon = {},
                label = { Text(each.title, maxLines = 1) },
            )
        }
    }
}

private val PasswordKind.title: String
    get() = when (this) {
        PasswordKind.PIN -> "PIN"
        PasswordKind.STRONG -> "Strong"
        PasswordKind.MEMORABLE -> "Words"
        PasswordKind.PASSPHRASE -> "Phrase"
    }

@Composable
private fun StrengthLine(state: PasswordGeneratorState) {
    val (label, color) = when (state.strength) {
        Strength.WEAK -> "weak" to MaterialTheme.colorScheme.error
        Strength.FAIR -> "fair" to MaterialTheme.extraColors.warn
        Strength.STRONG -> "strong" to MaterialTheme.colorScheme.primary
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LinearProgressIndicator(
            progress = { (state.bits / 128).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.width(96.dp),
            color = color,
            drawStopIndicator = {},
        )
        Text("${state.bits.roundToInt()} bits · $label", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text("${state.password.length} characters", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Options(state: PasswordGeneratorState) {
    val o = state.options
    val change = state::change
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (o.kind) {
            PasswordKind.PIN -> {
                LengthSlider("Length", o.pinLength, GeneratorOptions.PIN_LENGTHS) { change(o.copy(pinLength = it)) }
                Hint("For a device or a card that locks after a few wrong tries; too weak for anything else.")
            }
            PasswordKind.STRONG -> {
                LengthSlider("Length", o.strongLength, GeneratorOptions.STRONG_LENGTHS) { change(o.copy(strongLength = it)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (cls in CharacterClass.entries) {
                        FilterChip(
                            selected = cls in o.classes,
                            onClick = { state.setClass(cls, on = cls !in o.classes) },
                            label = { Text(cls.title, fontFamily = MonospaceFamily) },
                        )
                    }
                }
                Row(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .toggleable(o.excludeLookAlikes, role = Role.Checkbox) { change(o.copy(excludeLookAlikes = it)) }
                        .padding(end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = o.excludeLookAlikes, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                    Text("Leave out look-alikes ", style = MaterialTheme.typography.bodyMedium)
                    Text("0 O o 1 l I |", style = MaterialTheme.typography.bodyMedium, fontFamily = MonospaceFamily)
                }
            }
            PasswordKind.MEMORABLE -> {
                Stepper("Words", o.memorableWords, GeneratorOptions.MEMORABLE_WORDS) { change(o.copy(memorableWords = it)) }
                Stepper("Digits, at least", o.memorableDigits, GeneratorOptions.MEMORABLE_EXTRAS) { change(o.copy(memorableDigits = it)) }
                Stepper("Symbols, at least", o.memorableSymbols, GeneratorOptions.MEMORABLE_EXTRAS) { change(o.copy(memorableSymbols = it)) }
                Hint("Each word in Capitals or in CAPITALS, joined by digits and symbols.")
            }
            PasswordKind.PASSPHRASE -> {
                Stepper("Words", o.passphraseWords, GeneratorOptions.PASSPHRASE_WORDS) { change(o.copy(passphraseWords = it)) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Between words", style = MaterialTheme.typography.bodyLarge)
                    SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                        GeneratorOptions.SEPARATORS.forEachIndexed { i, separator ->
                            SegmentedButton(
                                selected = separator == o.separator,
                                onClick = { change(o.copy(separator = separator)) },
                                shape = SegmentedButtonDefaults.itemShape(i, GeneratorOptions.SEPARATORS.size),
                                icon = {},
                                label = { Text(if (separator == " ") "space" else separator, maxLines = 1) },
                            )
                        }
                    }
                }
                Hint("Lowercase words only: easy to type on a phone or a TV, good for a master password.")
            }
        }
    }
}

private val CharacterClass.title: String
    get() = when (this) {
        CharacterClass.UPPER -> "A–Z"
        CharacterClass.LOWER -> "a–z"
        CharacterClass.DIGITS -> "0–9"
        CharacterClass.SYMBOLS -> "!#$"
    }

@Composable
private fun LengthSlider(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = range.last - range.first - 1,
            modifier = Modifier.weight(1f),
        )
        Text("$value", Modifier.width(28.dp), style = MaterialTheme.typography.bodyLarge, fontFamily = MonospaceFamily)
    }
}

@Composable
private fun Stepper(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        IconButton(onClick = { onChange(value - 1) }, enabled = value > range.first) {
            Icon(Icons.Rounded.Remove, contentDescription = "Fewer")
        }
        Text("$value", Modifier.width(28.dp), style = MaterialTheme.typography.titleMedium, fontFamily = MonospaceFamily)
        IconButton(onClick = { onChange(value + 1) }, enabled = value < range.last) {
            Icon(Icons.Rounded.Add, contentDescription = "More")
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

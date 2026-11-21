package dev.keething.android.generator

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.keething.android.ui.StepSlider
import dev.keething.android.R
import dev.keething.android.theme.MonospaceFamily
import dev.keething.android.theme.extraColors
import dev.keething.android.ui.ButtonLabel
import dev.keething.android.ui.FitText
import dev.keething.android.ui.colouredSecret
import dev.keething.generator.CharacterClass
import dev.keething.generator.GeneratorOptions
import dev.keething.generator.PasswordKind
import dev.keething.generator.Strength
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
            Text(stringResource(R.string.generator_title), style = MaterialTheme.typography.headlineSmall)
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
            Buttons(state, use)
        }
    }
}

/** New password and the main button: side by side while both labels fit whole, else one above the other. */
@Composable
private fun Buttons(state: PasswordGeneratorState, use: GeneratorUse) {
    val main = stringResource(if (use is GeneratorUse.Copy) R.string.generator_copy else R.string.generator_use)
    val newLabel = stringResource(R.string.generator_new)
    val useShort = stringResource(R.string.generator_use_short)
    val style = MaterialTheme.typography.labelLarge
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Half the row, less the gap, the button's padding (24 dp a side) and an icon with its gap where there is one.
        val half = (maxWidth - 12.dp) / 2 - 48.dp
        val density = LocalDensity.current
        val besideEachOther = listOf(newLabel to true, main to (use is GeneratorUse.Copy)).all { (label, withIcon) ->
            val room = with(density) { (if (withIcon) half - 26.dp else half).toPx() }
            measurer.measure(label, style, maxLines = 1, softWrap = false).size.width <= room
        }
        val newButton = @Composable { modifier: Modifier ->
            OutlinedButton(onClick = state::regenerate, modifier) {
                ButtonLabel(newLabel, Icons.Rounded.Refresh, iconSize = 18.dp, gap = 8.dp)
            }
        }
        val mainButton = @Composable { modifier: Modifier ->
            when (use) {
                is GeneratorUse.IntoEntry -> Button(onClick = { use.use(state.password) }, modifier) {
                    ButtonLabel(main, short = useShort)
                }
                is GeneratorUse.Copy -> Button(onClick = { use.copy(state.password) }, modifier) {
                    ButtonLabel(main, Icons.Rounded.ContentCopy, iconSize = 18.dp, gap = 8.dp)
                }
            }
        }
        if (besideEachOther) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                newButton(Modifier.weight(1f))
                mainButton(Modifier.weight(1f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                mainButton(Modifier.fillMaxWidth())
                newButton(Modifier.fillMaxWidth())
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
                label = { FitText(stringResource(each.title)) },
            )
        }
    }
}

private val PasswordKind.title: Int
    get() = when (this) {
        PasswordKind.PIN -> R.string.generator_kind_pin
        PasswordKind.STRONG -> R.string.generator_kind_strong
        PasswordKind.MEMORABLE -> R.string.generator_kind_memorable
        PasswordKind.PASSPHRASE -> R.string.generator_kind_passphrase
    }

@Composable
private fun StrengthLine(state: PasswordGeneratorState) {
    val (label, color) = when (state.strength) {
        Strength.WEAK -> R.string.generator_strength_weak to MaterialTheme.colorScheme.error
        Strength.FAIR -> R.string.generator_strength_fair to MaterialTheme.extraColors.warn
        Strength.STRONG -> R.string.generator_strength_strong to MaterialTheme.colorScheme.primary
    }
    // With a large font the length goes to a line of its own rather than being broken up.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        LinearProgressIndicator(
            progress = { (state.bits / 128).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.width(96.dp),
            color = color,
            drawStopIndicator = {},
        )
        val style = MaterialTheme.typography.bodySmall
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        val bits = state.bits.roundToInt()
        Text(pluralStringResource(R.plurals.generator_strength_bits, bits, bits, stringResource(label)), style = style, color = muted, maxLines = 1)
        Spacer(Modifier.weight(1f))
        val length = state.password.length
        Text(pluralStringResource(R.plurals.generator_length_characters, length, length), style = style, color = muted, maxLines = 1)
    }
}

@Composable
private fun Options(state: PasswordGeneratorState) {
    val o = state.options
    val change = state::change
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (o.kind) {
            PasswordKind.PIN -> {
                LengthSlider(stringResource(R.string.generator_length), o.pinLength, GeneratorOptions.PIN_LENGTHS) { change(o.copy(pinLength = it)) }
                Hint(stringResource(R.string.generator_pin_hint))
            }
            PasswordKind.STRONG -> {
                LengthSlider(stringResource(R.string.generator_length), o.strongLength, GeneratorOptions.STRONG_LENGTHS) { change(o.copy(strongLength = it)) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    // One paragraph, so a large font wraps it as words; the look-alikes stay together on one line.
                    val lookAlikes = "0 O o 1 l I |".replace(' ', '\u00A0')
                    val line = stringResource(R.string.generator_leave_out_look_alikes, lookAlikes)
                    val at = line.indexOf(lookAlikes)
                    Text(
                        buildAnnotatedString {
                            append(line)
                            if (at >= 0) addStyle(SpanStyle(fontFamily = MonospaceFamily), at, at + lookAlikes.length)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            PasswordKind.MEMORABLE -> {
                Stepper(stringResource(R.string.generator_words), o.memorableWords, GeneratorOptions.MEMORABLE_WORDS) { change(o.copy(memorableWords = it)) }
                Stepper(stringResource(R.string.generator_digits), o.memorableDigits, GeneratorOptions.MEMORABLE_EXTRAS) { change(o.copy(memorableDigits = it)) }
                Stepper(stringResource(R.string.generator_symbols), o.memorableSymbols, GeneratorOptions.MEMORABLE_EXTRAS) { change(o.copy(memorableSymbols = it)) }
                Hint(stringResource(R.string.generator_memorable_hint))
            }
            PasswordKind.PASSPHRASE -> {
                Stepper(stringResource(R.string.generator_words), o.passphraseWords, GeneratorOptions.PASSPHRASE_WORDS) { change(o.copy(passphraseWords = it)) }
                // The label above the choice, so that "space" has room with a large font.
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.generator_between_words), style = MaterialTheme.typography.bodyLarge)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        GeneratorOptions.SEPARATORS.forEachIndexed { i, separator ->
                            SegmentedButton(
                                selected = separator == o.separator,
                                onClick = { change(o.copy(separator = separator)) },
                                shape = SegmentedButtonDefaults.itemShape(i, GeneratorOptions.SEPARATORS.size),
                                icon = {},
                                label = { FitText(if (separator == " ") stringResource(R.string.generator_separator_space) else separator) },
                            )
                        }
                    }
                }
                Hint(stringResource(R.string.generator_passphrase_hint))
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
        StepSlider(value.toFloat(), range.first.toFloat()..range.last.toFloat(), range.last - range.first - 1, Modifier.weight(1f)) {
            onChange(it.roundToInt())
        }
        Text("$value", Modifier.widthIn(min = 28.dp), style = MaterialTheme.typography.bodyLarge, fontFamily = MonospaceFamily)
    }
}

@Composable
private fun Stepper(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        IconButton(onClick = { onChange(value - 1) }, enabled = value > range.first) {
            Icon(Icons.Rounded.Remove, contentDescription = stringResource(R.string.generator_fewer))
        }
        Text("$value", Modifier.widthIn(min = 28.dp), style = MaterialTheme.typography.titleMedium, fontFamily = MonospaceFamily, textAlign = TextAlign.Center)
        IconButton(onClick = { onChange(value + 1) }, enabled = value < range.last) {
            Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.generator_more))
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

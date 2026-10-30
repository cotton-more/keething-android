package dev.keething.android.databases

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.keething.android.theme.extraColors
import dev.keething.android.ui.AppLogo
import dev.keething.android.ui.ButtonLabel
import dev.keething.android.ui.SegmentGap
import dev.keething.android.ui.segmentShape
import dev.keething.vault.databases.DatabaseId

/**
 * The chooser (plan AD13; keething-spec databases.chooser AC2–AC4; the agreed mock-up "⋮ › Databases, the chooser and its
 * run"): nothing open and several in the window. Why the fingerprint opened none ([note]), the window's databases with
 * how each opens, a check on those to ask (what typing cannot open cannot be checked; Locate… for one not found), past
 * five rows scrolling; Unlock with the fingerprint while it would open one, Unlock N databases, Open other… and New
 * database…. Nothing is done while the fingerprint runs ([busy]).
 */
@Composable
internal fun ChooserScreen(
    rows: List<ChooserRow>?,
    note: String?,
    busy: Boolean,
    onToggle: (DatabaseId) -> Unit,
    onLocate: (DatabaseId) -> Unit,
    onUnlock: (List<DatabaseId>) -> Unit,
    onFingerprint: () -> Unit,
    onOpenOther: () -> Unit,
    onNewDatabase: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .displayCutoutPadding()
            .wrapContentWidth()
            .widthIn(max = 520.dp)
            .padding(start = 16.dp, end = 16.dp, top = 40.dp, bottom = 20.dp),
    ) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            AppLogo(Modifier.size(52.dp))
            Text("Open your databases", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        }
        note?.let { Text(it, Modifier.padding(start = 8.dp, end = 8.dp, top = 18.dp), style = MaterialTheme.typography.bodyLarge) }
        Text(
            ChooserRows.HOW,
            Modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val shown = rows.orEmpty()
        // Four and a half rows, then they scroll and fade at the bottom.
        val many = ChooserRows.countText(shown) != null
        // The rest of the screen; the buttons stay at the bottom, the rows scroll when they do not fit (a phone on its side).
        Column(Modifier.weight(1f)) {
            Box(Modifier.weight(1f, fill = false)) {
                Column(
                    Modifier.then(if (many) Modifier.heightIn(max = 330.dp) else Modifier).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(SegmentGap),
                ) {
                    shown.forEachIndexed { index, row ->
                        ChooserRowView(row, segmentShape(index, shown.size, outer = 22.dp), enabled = !busy, onToggle = { onToggle(row.id) }, onLocate = { onLocate(row.id) })
                    }
                }
                if (many) {
                    val surface = MaterialTheme.colorScheme.surface
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(56.dp).background(Brush.verticalGradient(listOf(Color.Transparent, surface))))
                }
            }
            ChooserRows.countText(shown)?.let {
                Text(it, Modifier.padding(start = 16.dp, top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (ChooserRows.offersFingerprint(shown)) {
                FilledTonalButton(onClick = onFingerprint, enabled = !busy, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    ButtonLabel("Unlock with the fingerprint", Icons.Rounded.Fingerprint, iconSize = 20.dp)
                }
            }
            val checked = ChooserRows.checked(shown)
            Button(onClick = { onUnlock(checked) }, enabled = !busy && checked.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(56.dp)) {
                ButtonLabel(ChooserRows.unlockTitle(shown), Icons.Rounded.LockOpen, iconSize = 20.dp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                TextButton(onClick = onOpenOther, enabled = !busy) { Text("Open other…") }
                TextButton(onClick = onNewDatabase, enabled = !busy) { Text("New database…") }
            }
        }
    }
}

/** A database in the chooser: its check (none for what typing cannot open), dot, label and how it opens. */
@Composable
private fun ChooserRowView(row: ChooserRow, shape: RoundedCornerShape, enabled: Boolean, onToggle: () -> Unit, onLocate: () -> Unit) {
    val colors = databaseColors(row.line.colorIndex)
    val how = when (row.tone) {
        ChooserRow.Tone.PLAIN -> MaterialTheme.colorScheme.onSurfaceVariant
        ChooserRow.Tone.READY -> MaterialTheme.extraColors.good
        ChooserRow.Tone.WARNING -> MaterialTheme.extraColors.warn
        ChooserRow.Tone.PROBLEM -> MaterialTheme.colorScheme.error
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(shape)
            .background(MaterialTheme.extraColors.row)
            .toggleable(value = row.isChecked, enabled = enabled && row.canCheck, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Checkbox(checked = row.isChecked, onCheckedChange = null, enabled = enabled && row.canCheck, modifier = Modifier.padding(start = 8.dp))
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(colors.dot))
        Column(Modifier.weight(1f)) {
            val name = MaterialTheme.typography.titleMedium.copy(color = if (row.canCheck) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            DatabaseLabelText(row.line.label, name)
            Text(row.opensWith, style = MaterialTheme.typography.bodyMedium, color = how, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (row.offersLocate) TextButton(onClick = onLocate, enabled = enabled) { Text("Locate…") }
    }
}

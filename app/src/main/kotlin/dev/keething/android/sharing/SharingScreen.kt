package dev.keething.android.sharing

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.keething.android.AppContainer
import dev.keething.android.list.SectionTitle
import dev.keething.android.theme.extraColors
import dev.keething.android.ui.BesideOrUnder
import dev.keething.android.ui.ButtonLabel
import dev.keething.android.ui.FitText
import dev.keething.android.ui.SegmentGap
import dev.keething.android.ui.dotsAtLineEnd
import dev.keething.android.ui.readableInset
import dev.keething.android.ui.rememberSecondsNow
import dev.keething.android.ui.segmentShape

/**
 * Sharing (SPEC §10): the requests to answer and those waiting for an answer, newest first; reading a code and asking
 * for an entry at the foot, in thumb reach.
 */
@Composable
internal fun SharingScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onTheirs: (id: String) -> Unit,
    onMine: (id: String) -> Unit,
    onSent: (id: String) -> Unit,
    onRead: () -> Unit,
    onAsk: () -> Unit,
    onSend: () -> Unit,
) {
    val book by container.shareRequests.book.collectAsStateWithLifecycle()
    val validity by container.settings.requestValidity.collectAsStateWithLifecycle()
    val now by rememberSecondsNow()
    val theirs = book?.theirs.orEmpty()
    val mine = book?.mine.orEmpty()
    val sent = book?.sent.orEmpty()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Sharing") },
                subtitle = {
                    val days = validity.inWholeDays
                    FitText("Encrypted for one phone · requests last ${if (days == 1L) "1 day" else "$days days"}")
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back to the list") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                scrollBehavior = scroll,
            )
        },
        bottomBar = {
            BottomActions {
                FilledTonalButton(onClick = onSend, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    ButtonLabel("Send entries without a request", Icons.AutoMirrored.Rounded.Send, iconSize = 20.dp, gap = 8.dp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = onRead, modifier = Modifier.weight(1f).height(56.dp)) {
                        ButtonLabel("Read a code", Icons.Rounded.QrCodeScanner)
                    }
                    Button(onClick = onAsk, modifier = Modifier.weight(1f).height(56.dp)) {
                        ButtonLabel("Ask for an entry", Icons.Rounded.Add, short = "Ask")
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = readableInset(), end = readableInset(), top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 16.dp),
        ) {
            if (theirs.isNotEmpty()) {
                item(key = "theirs") { SectionTitle("To answer") }
                itemsIndexed(theirs, key = { _, it -> "theirs/${it.request.id}" }) { index, their ->
                    RequestRow(
                        shape = segmentShape(index, theirs.size),
                        leading = { PersonIcon(their.request.name, 44.dp) },
                        title = their.request.reason,
                        subtitle = "${their.request.name} · ${SharingTexts.expires(their.request.expires, now)}",
                        trailing = { CheckedChip(their.checked) },
                        onClick = { onTheirs(their.request.id) },
                        modifier = Modifier.animateItem().padding(bottom = SegmentGap),
                    )
                }
                item(key = "gap/theirs") { Spacer(Modifier.height(18.dp)) }
            }
            if (mine.isNotEmpty()) {
                item(key = "mine") { SectionTitle("Waiting for an answer") }
                itemsIndexed(mine, key = { _, it -> "mine/${it.request.id}" }) { index, my ->
                    RequestRow(
                        shape = segmentShape(index, mine.size),
                        leading = {
                            Box(
                                Modifier.size(44.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Rounded.QrCode2, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                        },
                        title = my.request.reason,
                        subtitle = "Asked ${SharingTexts.moment(my.request.created, now)} · ${SharingTexts.expires(my.request.expires, now)}",
                        trailing = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        onClick = { onMine(my.request.id) },
                        modifier = Modifier.animateItem().padding(bottom = SegmentGap),
                    )
                }
                item(key = "gap/mine") { Spacer(Modifier.height(18.dp)) }
            }
            if (sent.isNotEmpty()) {
                item(key = "sent") { SectionTitle("Sent, pictures kept") }
                itemsIndexed(sent, key = { _, it -> "sent/${it.envelope.id}" }) { index, one ->
                    RequestRow(
                        shape = segmentShape(index, sent.size),
                        leading = {
                            Box(
                                Modifier.size(44.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.tertiaryContainer),
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer) }
                        },
                        title = SharingTexts.titles(one.titles),
                        subtitle = listOfNotNull(
                            one.recipient.takeIf { it.isNotEmpty() }?.let { "For $it" },
                            "sent ${SharingTexts.moment(one.envelope.created, now)}",
                            SharingTexts.expires(one.envelope.expires, now),
                        ).joinToString(" · ").replaceFirstChar { it.uppercase() },
                        trailing = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        onClick = { onSent(one.envelope.id) },
                        modifier = Modifier.animateItem().padding(bottom = SegmentGap),
                    )
                }
            }
            if (theirs.isEmpty() && mine.isEmpty() && sent.isEmpty()) {
                item(key = "empty") {
                    Explanation(
                        "Nothing to answer, nothing waiting",
                        "To get an entry, ask for it: the answer comes back as a code only this phone can open. " +
                            "To give one, read the code of someone's request, or send it without one: it opens with ten " +
                            "pictures you give another way.",
                    )
                }
            }
        }
    }
}

@Composable
private fun RequestRow(
    shape: RoundedCornerShape,
    leading: @Composable () -> Unit,
    title: String,
    subtitle: String,
    trailing: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .clip(shape)
            .background(MaterialTheme.extraColors.row)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        leading()
        // The chip goes under the text where beside it the title would keep fewer than about seven characters.
        val titleStyle = MaterialTheme.typography.bodyLarge
        val minRoom = with(LocalDensity.current) { titleStyle.fontSize.toDp() * 7.5f }
        BesideOrUnder(minRoom, Modifier.weight(1f), gap = 16.dp, main = {
            Column {
                Text(title, style = titleStyle, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    subtitle.dotsAtLineEnd(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }, trailing = trailing)
    }
}

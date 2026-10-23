package dev.keepassbar.android.sharing

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.keepassbar.android.AppContainer
import dev.keepassbar.android.list.SectionTitle
import dev.keepassbar.android.theme.extraColors
import dev.keepassbar.android.ui.SegmentGap
import dev.keepassbar.android.ui.rememberSecondsNow
import dev.keepassbar.android.ui.segmentShape

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
    onRead: () -> Unit,
    onAsk: () -> Unit,
) {
    val book by container.shareRequests.book.collectAsStateWithLifecycle()
    val validity by container.settings.requestValidity.collectAsStateWithLifecycle()
    val now by rememberSecondsNow()
    val theirs = book?.theirs.orEmpty()
    val mine = book?.mine.orEmpty()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Sharing") },
                subtitle = {
                    val days = validity.inWholeDays
                    Text("Encrypted for one phone · requests last ${if (days == 1L) "1 day" else "$days days"}", maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = onRead, modifier = Modifier.weight(1f).height(56.dp)) {
                        Icon(Icons.Rounded.QrCodeScanner, contentDescription = null)
                        Spacer(Modifier.size(10.dp))
                        Text("Read a code")
                    }
                    Button(onClick = onAsk, modifier = Modifier.weight(1f).height(56.dp)) {
                        Icon(Icons.Rounded.Add, contentDescription = null)
                        Spacer(Modifier.size(10.dp))
                        Text("Ask for an entry")
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 16.dp),
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
            }
            if (theirs.isEmpty() && mine.isEmpty()) {
                item(key = "empty") {
                    Explanation(
                        "Nothing to answer, nothing waiting",
                        "To get an entry, ask for it: the answer comes back as a code only this phone can open. " +
                            "To give one, read the code of someone's request.",
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
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
        trailing()
    }
}

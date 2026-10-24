package dev.keepassbar.android.sharing

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.keepassbar.android.AppContainer
import dev.keepassbar.android.theme.extraColors
import dev.keepassbar.android.ui.SegmentGap
import dev.keepassbar.android.ui.rememberSecondsNow
import dev.keepassbar.android.ui.segmentShape
import dev.keepassbar.sharing.requests.TheirRequest

/**
 * The card's "Send to a request" (SPEC §10): the requests others sent, to answer with this entry ([onPick]); one not
 * yet checked goes to its pictures first. Or reading a new request ([onRead]), or sending it without one, in an envelope
 * opened by ten pictures ([onSendWithout]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SendToRequestSheet(
    container: AppContainer,
    title: String,
    onPick: (TheirRequest) -> Unit,
    onRead: () -> Unit,
    onSendWithout: () -> Unit,
    onDismiss: () -> Unit,
) {
    val book by container.shareRequests.book.collectAsStateWithLifecycle()
    val theirs = book?.theirs.orEmpty()
    val now by rememberSecondsNow()
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Send “$title” to", style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    if (theirs.isEmpty()) {
                        "No one has asked yet. Ask them for a request (Keething › Sharing › Ask for an entry), or send it " +
                            "without one: it opens with ten pictures you give another way."
                    } else {
                        "A request someone sent you: only their phone can open what you send. Or send it without one."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column {
                theirs.forEachIndexed { index, their ->
                    Row(
                        Modifier
                            .padding(bottom = SegmentGap)
                            .fillMaxWidth()
                            .heightIn(min = 72.dp)
                            .clip(segmentShape(index, theirs.size))
                            .background(MaterialTheme.extraColors.row)
                            .clickable { onPick(their) }
                            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        PersonIcon(their.request.name, 40.dp)
                        Column(Modifier.weight(1f)) {
                            Text(their.request.reason, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${their.request.name} · ${SharingTexts.expires(their.request.expires, now)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!their.checked) CheckedChip(checked = false)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRead, modifier = Modifier.weight(1f)) {
                    ButtonLabel("Read a request", Icons.Rounded.QrCodeScanner, gap = 8.dp)
                }
                TextButton(onClick = onSendWithout, modifier = Modifier.weight(1f)) {
                    ButtonLabel("Without one", Icons.AutoMirrored.Rounded.Send, gap = 8.dp)
                }
            }
        }
    }
}

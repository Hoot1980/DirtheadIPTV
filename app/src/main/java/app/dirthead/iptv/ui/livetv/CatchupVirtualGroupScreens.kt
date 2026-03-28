package app.dirthead.iptv.ui.livetv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.dirthead.iptv.CatchupActivity
import app.dirthead.iptv.data.PlaylistStream
import app.dirthead.iptv.ui.common.StreamLogoImage

@Composable
internal fun CatchupVirtualGroupChannelList(
    streams: List<PlaylistStream>,
    getPhpUrl: String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    if (getPhpUrl.isNullOrBlank()) {
        Text(
            text = "Catch-up is only available with an Xtream Codes (get.php) playlist.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp),
        )
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(
            items = streams,
            key = { _, s -> s.streamUrl },
        ) { _, stream ->
            CatchupVirtualChannelCard(
                stream = stream,
                onClick = {
                    val sid = stream.epgStreamId?.trim().orEmpty()
                    if (sid.isEmpty()) return@CatchupVirtualChannelCard
                    context.startActivity(
                        CatchupActivity.intent(
                            context = context,
                            channelName = stream.displayName,
                            streamId = sid,
                            archiveDays = stream.tvArchiveDuration,
                            getPhpUrl = getPhpUrl,
                        ),
                    )
                },
            )
        }
    }
}

@Composable
private fun CatchupVirtualChannelCard(
    stream: PlaylistStream,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sid = stream.epgStreamId?.trim().orEmpty()
    val dur = stream.tvArchiveDuration
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StreamLogoImage(
                imageUrl = stream.logoUrl,
                contentDescription = stream.displayName,
                size = 48.dp,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                Text(
                    text = stream.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Stream ID: $sid",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    text = if (dur != null) {
                        "Catch-up window: $dur day(s)"
                    } else {
                        "Catch-up window: (not specified)"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF69F0AE),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

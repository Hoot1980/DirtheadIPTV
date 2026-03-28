package app.dirthead.iptv.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.dirthead.iptv.ui.LocalPlaylistRepository

/** Colors for [OmdbSynopsisSection] (Material or TV-guide style). */
data class OmdbSynopsisPalette(
    val title: Color,
    val body: Color,
    val muted: Color,
    val sourceAccent: Color,
    /** When non-null, used for the loading indicator; otherwise theme default. */
    val progressIndicatorColor: Color? = null,
)

@Composable
fun omdbSynopsisPaletteMaterial(): OmdbSynopsisPalette =
    OmdbSynopsisPalette(
        title = MaterialTheme.colorScheme.onSurface,
        body = MaterialTheme.colorScheme.onSurfaceVariant,
        muted = MaterialTheme.colorScheme.onSurfaceVariant,
        sourceAccent = MaterialTheme.colorScheme.tertiary,
    )

/**
 * Provider synopsis when present; otherwise OMDb plot when a key is configured.
 *
 * @param showTitleAbove When true, shows [lookupTitle] above the synopsis (movies / episodes).
 *   When false, only the synopsis block (EPG detail where the title is already shown above).
 */
@Composable
fun OmdbSynopsisSection(
    lookupTitle: String,
    providerDescription: String,
    effectKey: Any?,
    modifier: Modifier = Modifier,
    idle: (@Composable () -> Unit)? = null,
    showTitleAbove: Boolean = true,
    palette: OmdbSynopsisPalette = omdbSynopsisPaletteMaterial(),
    titleStyle: TextStyle = MaterialTheme.typography.titleSmall,
    bodyStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    smallStyle: TextStyle = MaterialTheme.typography.bodySmall,
    labelStyle: TextStyle = MaterialTheme.typography.labelSmall,
    maxProviderHeight: Dp = 140.dp,
    maxOmdbBodyHeight: Dp = 120.dp,
) {
    val repository = LocalPlaylistRepository.current
    val providerScroll = rememberScrollState()
    val omdbPlotScroll = rememberScrollState()
    val providerDesc = providerDescription.trim()

    var omdbPlot by remember { mutableStateOf<String?>(null) }
    var omdbLoading by remember { mutableStateOf(false) }

    LaunchedEffect(effectKey) {
        providerScroll.scrollTo(0)
        omdbPlotScroll.scrollTo(0)
    }

    LaunchedEffect(effectKey, providerDesc) {
        omdbPlot = null
        omdbLoading = false
        if (effectKey == null) return@LaunchedEffect
        if (providerDesc.isNotEmpty()) return@LaunchedEffect
        if (!repository.isOmdbPlotLookupConfigured()) return@LaunchedEffect
        omdbLoading = true
        omdbPlot = repository.lookupOmdbPlot(lookupTitle)
        omdbLoading = false
    }

    Column(modifier = modifier) {
        when {
            effectKey == null && idle != null -> idle()
            effectKey == null -> Unit
            providerDesc.isNotEmpty() -> {
                if (showTitleAbove && lookupTitle.isNotBlank()) {
                    Text(
                        text = lookupTitle,
                        style = titleStyle,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = palette.title,
                    )
                }
                Text(
                    text = providerDesc,
                    style = bodyStyle,
                    color = palette.body,
                    modifier = Modifier
                        .padding(top = if (showTitleAbove && lookupTitle.isNotBlank()) 6.dp else 0.dp)
                        .heightIn(max = maxProviderHeight)
                        .verticalScroll(providerScroll),
                )
            }
            else -> {
                if (showTitleAbove && lookupTitle.isNotBlank()) {
                    Text(
                        text = lookupTitle,
                        style = titleStyle,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = palette.title,
                    )
                }
                val topPad =
                    if (showTitleAbove && lookupTitle.isNotBlank()) 6.dp else 0.dp
                when {
                    !repository.isOmdbPlotLookupConfigured() -> {
                        Text(
                            text = "No description from the provider. Add OMDB_API_KEY to local.properties " +
                                "(free key at omdbapi.com) to load plot summaries from IMDb via OMDb.",
                            style = smallStyle,
                            color = palette.muted,
                            modifier = Modifier.padding(top = topPad),
                        )
                    }
                    omdbLoading -> {
                        Row(
                            modifier = Modifier.padding(top = if (topPad > 0.dp) topPad else 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            if (palette.progressIndicatorColor != null) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.dp,
                                    color = palette.progressIndicatorColor,
                                )
                            } else {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                            Text(
                                text = "Loading IMDb plot…",
                                style = smallStyle,
                                color = palette.muted,
                            )
                        }
                    }
                    !omdbPlot.isNullOrBlank() -> {
                        Text(
                            text = omdbPlot!!,
                            style = bodyStyle,
                            color = palette.body,
                            modifier = Modifier
                                .padding(top = topPad)
                                .heightIn(max = maxOmdbBodyHeight)
                                .verticalScroll(omdbPlotScroll),
                        )
                        Text(
                            text = "Plot source: OMDb (IMDb)",
                            style = labelStyle,
                            color = palette.sourceAccent,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    else -> {
                        Text(
                            text = "No IMDb plot found for this title (OMDb).",
                            style = smallStyle,
                            color = palette.muted,
                            modifier = Modifier.padding(top = topPad),
                        )
                    }
                }
            }
        }
    }
}

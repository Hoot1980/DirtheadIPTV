package app.dirthead.iptv.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.dirthead.iptv.ui.LocalPlaylistRepository
import app.dirthead.iptv.ui.common.focusWhiteRing
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    onChangePlaylist: () -> Unit,
    onCacheCleared: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val repository = LocalPlaylistRepository.current
    val scope = rememberCoroutineScope()
    var showClearConfirm by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        enabled = !clearing,
                        modifier = Modifier.focusWhiteRing(CircleShape),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
        ) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(24.dp))
            OutlinedButton(
                onClick = onChangePlaylist,
                enabled = !clearing,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusWhiteRing(ButtonDefaults.shape),
            ) {
                Text("Change playlist")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Open the playlist list to pick another source. Your cache stays on this device until you clear it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = { showClearConfirm = true },
                enabled = !clearing,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusWhiteRing(ButtonDefaults.shape),
            ) {
                Text("Clear cache")
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Removes the saved playlist, TV guide data, and channel artwork stored on this device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { if (!clearing) showClearConfirm = false },
            title = { Text("Clear cache?") },
            text = {
                Text(
                    "This deletes cached playlist and guide data from storage. You will return to the playlist loading screen to download again.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        clearing = true
                        scope.launch {
                            try {
                                repository.clearCachesAndResetCatalog()
                                showClearConfirm = false
                                onCacheCleared()
                            } finally {
                                clearing = false
                            }
                        }
                    },
                    enabled = !clearing,
                    modifier = Modifier.focusWhiteRing(ButtonDefaults.shape),
                ) { Text("Clear") }
            },
            dismissButton = {
                TextButton(
                    onClick = { showClearConfirm = false },
                    enabled = !clearing,
                    modifier = Modifier.focusWhiteRing(ButtonDefaults.shape),
                ) { Text("Cancel") }
            },
        )
    }
}

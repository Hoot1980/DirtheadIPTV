package app.dirthead.iptv.ui.common

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Live clock for the top-right corner; respects safe area / display cutout. */
@Composable
fun ScreenCornerClock(modifier: Modifier = Modifier) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            now = System.currentTimeMillis()
            val msIntoMinute = now % 60_000L
            delay((60_000L - msIntoMinute).coerceAtLeast(1L))
        }
    }
    val fmt = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    Text(
        text = fmt.format(Date(now)),
        style = MaterialTheme.typography.titleMedium,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(top = 4.dp, end = 10.dp),
    )
}

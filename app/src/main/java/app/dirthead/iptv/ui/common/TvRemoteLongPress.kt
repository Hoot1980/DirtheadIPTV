package app.dirthead.iptv.ui.common

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * TV / D-pad: long-press on select often still delivers a click after the long-press gesture.
 * Handle the long-press key here first, run [onLongSelect], and consume the event so [onClick] does not run.
 * Pair with [androidx.compose.foundation.combinedClickable] (touch long-press uses [onLongClick] there).
 */
fun Modifier.tvRemoteLongSelectConsumesClick(
    enabled: Boolean = true,
    onLongSelect: () -> Unit,
): Modifier = this.onPreviewKeyEvent { event ->
    if (!enabled) return@onPreviewKeyEvent false
    if (event.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
    val ne = event.nativeKeyEvent ?: return@onPreviewKeyEvent false
    if (!ne.isLongPress) return@onPreviewKeyEvent false
    when (ne.keyCode) {
        AndroidKeyEvent.KEYCODE_DPAD_CENTER,
        AndroidKeyEvent.KEYCODE_ENTER,
        AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
        -> {
            onLongSelect()
            true
        }
        else -> false
    }
}

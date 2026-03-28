package app.dirthead.iptv.ui.common

import androidx.compose.foundation.border
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

private val FocusRingStroke = 3.dp

/**
 * White ring when this focusable row has focus (D-pad / keyboard). Use before [androidx.compose.foundation.clickable] /
 * [androidx.compose.foundation.combinedClickable] so the ring matches the card shape.
 */
@Composable
fun Modifier.focusWhiteRing(shape: Shape = CardDefaults.shape): Modifier {
    var focused by remember { mutableStateOf(false) }
    return this
        .onFocusChanged { focused = it.isFocused }
        .border(
            width = FocusRingStroke,
            color = if (focused) Color.White else Color.Transparent,
            shape = shape,
        )
}

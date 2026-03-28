package app.dirthead.iptv.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private val letterRows = listOf(
    listOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P"),
    listOf("A", "S", "D", "F", "G", "H", "J", "K", "L"),
    listOf("Z", "X", "C", "V", "B", "N", "M"),
)

private val digitRow = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")

private val punctRow = listOf("-", "'", ".", ",", "&")

/** D-pad friendly on-screen keyboard (no soft IME). */
@Composable
fun TvSearchKeyboard(
    onAppend: (String) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        KeyboardRow(keys = digitRow, onAppend = onAppend, firstKeyFocusRequester = focusRequester)
        for (row in letterRows) {
            KeyboardRow(keys = row, onAppend = onAppend, firstKeyFocusRequester = null)
        }
        KeyboardRow(keys = punctRow, onAppend = onAppend, firstKeyFocusRequester = null)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SearchKeyButton(
                label = "Space",
                onClick = { onAppend(" ") },
                modifier = Modifier.weight(3f),
            )
            SearchKeyButton(
                label = "⌫",
                onClick = onBackspace,
                modifier = Modifier.weight(1f),
            )
            SearchKeyButton(
                label = "Clear",
                onClick = onClear,
                modifier = Modifier.weight(1.2f),
            )
        }
    }
}

@Composable
private fun KeyboardRow(
    keys: List<String>,
    onAppend: (String) -> Unit,
    firstKeyFocusRequester: FocusRequester?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        keys.forEachIndexed { index, label ->
            val mod =
                if (index == 0 && firstKeyFocusRequester != null) {
                    Modifier
                        .weight(1f)
                        .focusRequester(firstKeyFocusRequester)
                } else {
                    Modifier.weight(1f)
                }
            SearchKeyButton(
                label = label,
                onClick = {
                    val ch = label.single()
                    val out = if (ch.isLetter()) ch.lowercaseChar().toString() else label
                    onAppend(out)
                },
                modifier = mod,
            )
        }
    }
}

@Composable
private fun SearchKeyButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .height(44.dp)
            .focusWhiteRing(ButtonDefaults.shape),
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

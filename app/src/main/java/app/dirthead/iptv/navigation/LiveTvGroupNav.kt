package app.dirthead.iptv.navigation

import android.util.Base64
import java.nio.charset.StandardCharsets

internal object LiveTvGroupNav {
    fun encode(groupTitle: String): String =
        Base64.encodeToString(
            groupTitle.toByteArray(StandardCharsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )

    fun decode(encoded: String): String? =
        runCatching {
            val bytes = Base64.decode(encoded, Base64.URL_SAFE)
            String(bytes, StandardCharsets.UTF_8)
        }.getOrNull()
}

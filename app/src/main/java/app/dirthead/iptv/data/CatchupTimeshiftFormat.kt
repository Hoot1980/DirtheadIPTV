package app.dirthead.iptv.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal object CatchupTimeshiftFormat {
    /** Xtream timeshift segment start token: `YYYY-MM-DD:HH-MM` (local timezone). */
    fun formatStartForTimeshiftUrl(startMillis: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US)
        sdf.timeZone = TimeZone.getDefault()
        return sdf.format(Date(startMillis))
    }

    fun durationMinutes(startMillis: Long, endMillis: Long): Int {
        val span = endMillis - startMillis
        return (span / 60_000L).toInt().coerceAtLeast(1)
    }
}

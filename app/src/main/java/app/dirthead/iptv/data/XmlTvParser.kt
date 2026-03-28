package app.dirthead.iptv.data

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.util.Calendar
import java.util.SimpleTimeZone
import java.util.TimeZone

/**
 * Parses [XMLTV](https://wiki.xmltv.org/) (xmltv.dtd) into [EpgProgram] lists keyed by `channel` id
 * (matches M3U `tvg-id` / Xtream `epg_channel_id` when aligned with the provider).
 */
object XmlTvParser {

    fun parse(xml: String, filterChannelIds: Set<String>?): Map<String, List<EpgProgram>> {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))
        val merged = mutableMapOf<String, MutableList<EpgProgram>>()
        val now = System.currentTimeMillis()
        val pruneBefore = now - 6 * 3600_000L
        val effectiveFilter = filterChannelIds?.takeIf { it.isNotEmpty() }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "programme") {
                val channel = parser.getAttributeValue(null, "channel")?.trim().orEmpty()
                val startRaw = parser.getAttributeValue(null, "start").orEmpty()
                val stopRaw = parser.getAttributeValue(null, "stop").orEmpty()
                if (channel.isNotEmpty() && effectiveFilter != null &&
                    effectiveFilter.none { it == channel || it.equals(channel, ignoreCase = true) }
                ) {
                    skipProgrammeSubtree(parser)
                } else if (channel.isNotEmpty()) {
                    val startMs = parseXmltvTimestamp(startRaw)
                    val endMs = parseXmltvTimestamp(stopRaw).takeIf { it > 0 }
                        ?: (startMs + 3600_000L).takeIf { startMs > 0 }
                        ?: 0L
                    val (title, desc) = readProgrammeTitleAndDesc(parser)
                    val titleDecoded = decodeEpgTextIfBase64(title).trim()
                    val descDecoded = decodeEpgTextIfBase64(desc).trim()
                    if (titleDecoded.isNotBlank() && startMs > 0 && endMs > startMs && endMs >= pruneBefore) {
                        merged.getOrPut(channel) { mutableListOf() }.add(
                            EpgProgram(
                                title = titleDecoded,
                                startMillis = startMs,
                                endMillis = endMs,
                                description = descDecoded.takeIf { it.isNotBlank() },
                            ),
                        )
                    }
                } else {
                    skipProgrammeSubtree(parser)
                }
            }
            event = parser.next()
        }
        return merged.mapValues { (_, list) ->
            list.sortedBy { it.startMillis }.distinctBy { it.startMillis to it.title }
        }
    }

    private fun skipProgrammeSubtree(parser: XmlPullParser) {
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                else -> Unit
            }
        }
    }

    /**
     * After [XmlPullParser.START_TAG] `programme`, walk children until `/programme`.
     * Returns first [title] and first non-empty [desc] (XMLTV `desc` element).
     */
    private fun readProgrammeTitleAndDesc(parser: XmlPullParser): Pair<String, String> {
        var depth = 1
        var title = ""
        var desc = ""
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    depth++
                    if (parser.name == "title" && depth == 2) {
                        val nt = parser.next()
                        if (nt == XmlPullParser.TEXT || nt == XmlPullParser.CDSECT) {
                            val t = parser.text.trim()
                            if (t.isNotEmpty()) title = t
                        }
                    } else if (parser.name == "desc" && depth == 2) {
                        val nt = parser.next()
                        if (nt == XmlPullParser.TEXT || nt == XmlPullParser.CDSECT) {
                            val t = parser.text.trim()
                            if (t.isNotEmpty() && desc.isEmpty()) desc = t
                        }
                    }
                }
                XmlPullParser.END_TAG -> depth--
                else -> Unit
            }
        }
        return title to desc
    }

    /** XMLTV format: `yyyyMMddHHmmss ZZZZ` e.g. `20240324203000 +0100` */
    internal fun parseXmltvTimestamp(raw: String): Long {
        val s = raw.trim()
        if (s.length < 14) return 0L
        val digits = s.take(14)
        if (digits.any { !it.isDigit() }) return 0L
        val zonePart = s.drop(14).trim()
        val tz = xmltvOffsetToTimeZone(zonePart)
        val y = digits.substring(0, 4).toInt()
        val mo = digits.substring(4, 6).toInt() - 1
        val d = digits.substring(6, 8).toInt()
        val h = digits.substring(8, 10).toInt()
        val mi = digits.substring(10, 12).toInt()
        val sec = digits.substring(12, 14).toInt()
        val cal = Calendar.getInstance(tz)
        cal.set(Calendar.YEAR, y)
        cal.set(Calendar.MONTH, mo)
        cal.set(Calendar.DAY_OF_MONTH, d)
        cal.set(Calendar.HOUR_OF_DAY, h)
        cal.set(Calendar.MINUTE, mi)
        cal.set(Calendar.SECOND, sec)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun xmltvOffsetToTimeZone(zonePart: String): TimeZone {
        if (zonePart.isEmpty()) return TimeZone.getDefault()
        val sign = when {
            zonePart.startsWith("-") -> -1
            else -> 1
        }
        val clean = zonePart.removePrefix("+").removePrefix("-").filter { it.isDigit() }
        if (clean.isEmpty()) return TimeZone.getDefault()
        val hours = clean.take(2).toIntOrNull() ?: 0
        val mins = clean.drop(2).take(2).toIntOrNull() ?: 0
        val offsetMs = sign * (hours * 3600_000 + mins * 60_000)
        return SimpleTimeZone(offsetMs, "XMLTV")
    }
}

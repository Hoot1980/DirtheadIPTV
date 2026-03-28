package app.dirthead.iptv.data

import android.util.JsonReader
import android.util.JsonToken
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * Streaming JSON parsing for large Xtream [player_api.php] arrays (categories / live streams)
 * so we never load the full response into a single [String].
 */
internal object XtreamStreamingJson {

    fun parseLiveCategoriesRoot(input: InputStream): Map<String, String> {
        val reader = JsonReader(InputStreamReader(input, StandardCharsets.UTF_8))
        return reader.use { r ->
            when (r.peek()) {
                JsonToken.BEGIN_ARRAY -> parseCategoryObjectArray(r)
                JsonToken.BEGIN_OBJECT -> parseCategoriesWrappedObject(r)
                else -> {
                    r.skipValue()
                    emptyMap()
                }
            }
        }
    }

    private fun parseCategoriesWrappedObject(reader: JsonReader): Map<String, String> {
        var map = emptyMap<String, String>()
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "categories", "data" -> map = parseCategoryObjectArray(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return map
    }

    private fun parseCategoryObjectArray(reader: JsonReader): Map<String, String> {
        val out = linkedMapOf<String, String>()
        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                reader.skipValue()
                continue
            }
            val fields = readFlatStringMap(reader)
            val id = fields["category_id"]?.trim()?.takeIf { it.isNotEmpty() }
                ?: fields["id"]?.trim()?.takeIf { it.isNotEmpty() }
                ?: ""
            val name = fields["category_name"]?.trim()?.takeIf { it.isNotEmpty() }
                ?: fields["name"]?.trim()?.takeIf { it.isNotEmpty() }
                ?: ""
            if (id.isNotEmpty() && name.isNotEmpty()) {
                out[id] = name
            }
        }
        reader.endArray()
        return out
    }

    fun parseLiveStreamsRoot(
        input: InputStream,
        categoryNames: Map<String, String>,
        creds: XtreamCredentials,
        baseUrl: String,
    ): List<PlaylistStream> {
        val reader = JsonReader(InputStreamReader(input, StandardCharsets.UTF_8))
        return reader.use { r ->
            when (r.peek()) {
                JsonToken.BEGIN_ARRAY -> parseLiveStreamObjectArray(r, categoryNames, creds, baseUrl)
                JsonToken.BEGIN_OBJECT -> parseLiveStreamsWrappedObject(r, categoryNames, creds, baseUrl)
                else -> {
                    r.skipValue()
                    emptyList()
                }
            }
        }
    }

    private fun parseLiveStreamsWrappedObject(
        reader: JsonReader,
        categoryNames: Map<String, String>,
        creds: XtreamCredentials,
        baseUrl: String,
    ): List<PlaylistStream> {
        val out = ArrayList<PlaylistStream>(256)
        reader.beginObject()
        while (reader.hasNext()) {
            reader.nextName()
            if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                reader.beginArray()
                while (reader.hasNext()) {
                    if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                        val fields = readFlatStringMap(reader)
                        XtreamPlayerApiLiveStreamMapper.liveStreamFromFlatFields(
                            fields,
                            categoryNames,
                            creds,
                            baseUrl,
                        )?.let { out.add(it) }
                    } else {
                        reader.skipValue()
                    }
                }
                reader.endArray()
            } else {
                reader.skipValue()
            }
        }
        reader.endObject()
        return out
    }

    private fun parseLiveStreamObjectArray(
        reader: JsonReader,
        categoryNames: Map<String, String>,
        creds: XtreamCredentials,
        baseUrl: String,
    ): List<PlaylistStream> {
        val out = ArrayList<PlaylistStream>(256)
        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                reader.skipValue()
                continue
            }
            val fields = readFlatStringMap(reader)
            XtreamPlayerApiLiveStreamMapper.liveStreamFromFlatFields(
                fields,
                categoryNames,
                creds,
                baseUrl,
            )?.let { out.add(it) }
        }
        reader.endArray()
        return out
    }

    /**
     * Reads a JSON object whose values are primitives only (skips nested objects/arrays).
     * Numbers and booleans are normalized to strings for downstream field lookup.
     */
    private fun readFlatStringMap(reader: JsonReader): Map<String, String> {
        val m = linkedMapOf<String, String>()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            when (reader.peek()) {
                JsonToken.STRING -> m[name] = reader.nextString()
                // Preserves large integer ids (e.g. stream_id) without double precision loss.
                JsonToken.NUMBER -> m[name] = reader.nextString()
                JsonToken.BOOLEAN -> m[name] = if (reader.nextBoolean()) "true" else "false"
                JsonToken.NULL -> {
                    reader.nextNull()
                    m[name] = ""
                }
                else -> {
                    reader.skipValue()
                }
            }
        }
        reader.endObject()
        return m
    }
}

/**
 * Bridges streaming flat maps to [PlaylistStream]; kept separate so [XtreamStreamingJson] stays parser-only.
 */
internal object XtreamPlayerApiLiveStreamMapper {

    fun liveStreamFromFlatFields(
        fields: Map<String, String>,
        categoryNames: Map<String, String>,
        creds: XtreamCredentials,
        baseUrl: String,
    ): PlaylistStream? {
        val name = fields["name"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val streamId = fields["stream_id"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val categoryId = fields["category_id"]?.trim().orEmpty()
        val groupTitle = when {
            categoryId.isEmpty() -> PlaylistStream.DEFAULT_GROUP
            else -> categoryNames[categoryId] ?: categoryId
        }
        val direct = fields["direct_source"]?.trim().orEmpty()
        val u = creds.username
        val p = creds.password
        val ext = creds.liveStreamPathExtension()
        val url = XtreamPlayerApi.resolveXtreamStreamUrl(direct, baseUrl, "/live/$u/$p/$streamId.$ext")
        val tvgId = sequenceOf(
            fields["epg_channel_id"]?.trim().orEmpty(),
            fields["tvg_id"]?.trim().orEmpty(),
            fields["tvg-id"]?.trim().orEmpty(),
        ).firstOrNull { it.isNotEmpty() } ?: streamId
        val tvArchiveRaw = fields["tv_archive"]?.trim().orEmpty().lowercase()
        val tvArchive = tvArchiveRaw == "1" || tvArchiveRaw == "true"
        val tvArchiveDuration = fields["tv_archive_duration"]?.trim()?.toIntOrNull()?.takeIf { it > 0 }
        val logoUrl = xtreamImageUrlFromFields(fields)
        return PlaylistStream(
            displayName = name,
            streamUrl = url,
            groupTitle = groupTitle,
            epgStreamId = streamId,
            tvgId = tvgId,
            logoUrl = logoUrl,
            tvArchive = tvArchive,
            tvArchiveDuration = tvArchiveDuration,
        )
    }

    private fun xtreamImageUrlFromFields(fields: Map<String, String>): String? {
        val keys = listOf("stream_icon", "movie_image", "cover", "cover_big", "icon", "backdrop_path")
        for (k in keys) {
            fields[k]?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return null
    }
}

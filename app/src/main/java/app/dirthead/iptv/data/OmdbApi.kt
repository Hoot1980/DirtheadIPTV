package app.dirthead.iptv.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * [OMDb](https://www.omdbapi.com/) returns IMDb-linked metadata (including plot) when given an API key.
 */
internal object OmdbApi {

    /** Strip common IPTV suffixes so "Movie Name (2020) [4K]" matches OMDb better. */
    fun cleanMovieTitleForLookup(displayName: String): String {
        var t = displayName.trim()
        if (t.isEmpty()) return t
        t = t.replace(Regex("""\[[^\]]*]"""), "").trim()
        t = t.replace(Regex("""\s*\(\d{4}\)\s*$"""), "").trim()
        t = t.replace(Regex("""\s+\d{4}\s*$"""), "").trim()
        return t.ifBlank { displayName.trim() }
    }

    fun fetchPlot(client: OkHttpClient, apiKey: String, title: String): String? {
        val t = title.trim()
        if (t.isEmpty() || apiKey.isBlank()) return null
        val url = "https://www.omdbapi.com/".toHttpUrlOrNull() ?: return null
        val httpUrl = url.newBuilder()
            .addQueryParameter("apikey", apiKey)
            .addQueryParameter("t", t)
            .addQueryParameter("plot", "full")
            .build()
        val body = runCatching {
            client.newCall(Request.Builder().url(httpUrl).get().build()).execute().use { r ->
                if (!r.isSuccessful) return@use null
                r.body?.string()
            }
        }.getOrNull() ?: return null
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (!root.optString("Response", "").equals("True", ignoreCase = true)) return null
        val plot = root.optString("Plot", "").trim()
        if (plot.isEmpty() || plot.equals("N/A", ignoreCase = true)) return null
        return plot
    }
}

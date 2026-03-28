package app.dirthead.iptv.update

import app.dirthead.iptv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val VERSION_JSON_URL =
    "https://raw.githubusercontent.com/Hoot1980/DirtheadIPTV/main/version.json"

data class RemoteAppVersion(
    val versionCode: Int,
    val apkUrl: String,
    val changelog: String,
)

object AppUpdateChecker {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Fetches [VERSION_JSON_URL] on [Dispatchers.IO]. Returns null on network/parse errors.
     */
    suspend fun fetchRemoteVersion(): RemoteAppVersion? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(VERSION_JSON_URL)
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                val json = JSONObject(body)
                val code = json.optInt("versionCode", -1)
                if (code < 0) return@use null
                val apkUrl = json.optString("apkUrl", "").trim()
                if (apkUrl.isEmpty()) return@use null
                val changelog = json.optString("changelog", "").trim()
                RemoteAppVersion(
                    versionCode = code,
                    apkUrl = apkUrl,
                    changelog = changelog.ifEmpty { "A new version is available." },
                )
            }
        }.getOrNull()
    }

    fun isNewerThanInstalled(remote: RemoteAppVersion): Boolean =
        remote.versionCode > BuildConfig.VERSION_CODE
}

package app.dirthead.iptv.update

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object AppUpdateDownloader {

    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Enqueues an APK download into the app-specific external Download directory
     * ([Context.getExternalFilesDir] + [Environment.DIRECTORY_DOWNLOADS]) — works on Fire TV without public storage access.
     */
    fun enqueue(context: Context, apkUrl: String) {
        val appContext = context.applicationContext
        ioExecutor.execute {
            val resolvedUrl = runCatching { resolveDirectDownloadUrl(apkUrl) }
                .getOrElse { e ->
                    Log.w(TAG, "URL resolve failed, using original: ${e.message}")
                    apkUrl
                }
            val uri = Uri.parse(resolvedUrl)
            Log.i(TAG, "Update download URL (after redirects): $uri")

            val destDir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            if (destDir == null) {
                mainHandler.post {
                    Toast.makeText(appContext, "Storage not available", Toast.LENGTH_SHORT).show()
                }
                return@execute
            }

            val destFile = File(destDir, AppUpdateDownloadState.APK_FILE_NAME)
            runCatching {
                if (destFile.exists()) destFile.delete()
            }

            val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val prefs = appContext.getSharedPreferences(AppUpdateDownloadState.PREFS_NAME, Context.MODE_PRIVATE)
            val oldId = prefs.getLong(AppUpdateDownloadState.KEY_PENDING_DOWNLOAD_ID, -1L)
            if (oldId != -1L) {
                dm.remove(oldId)
            }

            val request = DownloadManager.Request(uri).apply {
                setTitle("Dirthead IPTV")
                setDescription("Downloading update...")
                setMimeType("application/vnd.android.package-archive")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                setAllowedNetworkTypes(
                    DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE,
                )
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
                setDestinationInExternalFilesDir(
                    appContext,
                    Environment.DIRECTORY_DOWNLOADS,
                    AppUpdateDownloadState.APK_FILE_NAME,
                )
            }

            val downloadId = try {
                dm.enqueue(request)
            } catch (e: Exception) {
                Log.e(TAG, "DownloadManager.enqueue failed", e)
                mainHandler.post {
                    Toast.makeText(appContext, "Could not start download", Toast.LENGTH_SHORT).show()
                }
                return@execute
            }

            prefs.edit().putLong(AppUpdateDownloadState.KEY_PENDING_DOWNLOAD_ID, downloadId).apply()
            mainHandler.post {
                Toast.makeText(context, "Downloading update...", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Follows redirects with OkHttp so [DownloadManager] receives a direct URL (fewer failures on Fire TV / CDNs).
     */
    private fun resolveDirectDownloadUrl(original: String): String {
        val client = OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        val ua = "Mozilla/5.0 (Linux; Android 10; DirtheadIPTV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        fun finalUrlFrom(response: okhttp3.Response): String? {
            val code = response.code
            if (response.isSuccessful || code == 206) {
                return response.request.url.toString()
            }
            return null
        }

        // Prefer HEAD (no body); some hosts return 405 — then try tiny ranged GET.
        runCatching {
            val headReq = Request.Builder()
                .url(original)
                .head()
                .header("User-Agent", ua)
                .header("Accept", "*/*")
                .build()
            client.newCall(headReq).execute().use { response ->
                finalUrlFrom(response)?.let { return it }
            }
        }

        runCatching {
            val getReq = Request.Builder()
                .url(original)
                .get()
                .header("Range", "bytes=0-0")
                .header("User-Agent", ua)
                .header("Accept", "*/*")
                .build()
            client.newCall(getReq).execute().use { response ->
                finalUrlFrom(response)?.let { return it }
            }
        }

        return original
    }

    private const val TAG = "AppUpdateDownload"
}

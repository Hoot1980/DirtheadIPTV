package app.dirthead.iptv.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

class AppUpdateDownloadReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
            Log.d(TAG, "Ignoring action: ${intent.action}")
            return
        }

        val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (downloadId == -1L) {
            Log.w(TAG, "DOWNLOAD_COMPLETE with invalid download id")
            return
        }

        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(AppUpdateDownloadState.PREFS_NAME, Context.MODE_PRIVATE)
        val pendingId = prefs.getLong(AppUpdateDownloadState.KEY_PENDING_DOWNLOAD_ID, -1L)
        if (downloadId != pendingId) {
            Log.d(TAG, "Download id $downloadId does not match pending update id $pendingId; ignoring")
            return
        }

        prefs.edit().remove(AppUpdateDownloadState.KEY_PENDING_DOWNLOAD_ID).apply()

        val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(downloadId)

        var status = DownloadManager.STATUS_FAILED
        var localUriString: String? = null
        var localPathString: String? = null

        dm.query(query).use { cursor ->
            if (!cursor.moveToFirst()) {
                Log.e(TAG, "DownloadManager query returned no row for id=$downloadId")
                toast(appContext, "Update download not found")
                return
            }
            val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
            if (statusIdx < 0) {
                Log.e(TAG, "COLUMN_STATUS missing in DownloadManager cursor")
                toast(appContext, "Update download status unknown")
                return
            }
            status = cursor.getInt(statusIdx)

            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                Log.w(TAG, "Download finished with status=$status (${statusName(status)}) for id=$downloadId")
                toast(appContext, "Update download failed")
                return
            }

            val uriIdx = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
            if (uriIdx >= 0 && !cursor.isNull(uriIdx)) {
                localUriString = cursor.getString(uriIdx)
            }
            val pathIdx = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_FILENAME)
            if (pathIdx >= 0 && !cursor.isNull(pathIdx)) {
                localPathString = cursor.getString(pathIdx)
            }
        }

        Log.d("UPDATE", "Download complete")
        Log.i(TAG, "Download successful id=$downloadId localUri=$localUriString localPath=$localPathString")

        val fallbackDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val apkFile = resolveApkFile(localUriString, localPathString, fallbackDir)
        if (apkFile == null || !apkFile.exists() || apkFile.length() == 0L) {
            Log.e(TAG, "APK file missing or empty after download. resolved=$apkFile fallbackDir=$fallbackDir")
            toast(appContext, "Update file missing")
            return
        }

        Log.i(TAG, "Resolved APK file: ${apkFile.absolutePath} (${apkFile.length()} bytes)")

        val authority = "${appContext.packageName}.fileprovider"
        val apkUri = try {
            FileProvider.getUriForFile(appContext, authority, apkFile)
        } catch (e: Exception) {
            Log.e(TAG, "FileProvider.getUriForFile failed for ${apkFile.absolutePath}", e)
            toast(appContext, "Could not prepare update file")
            return
        }

        Log.i(TAG, "FileProvider URI: $apkUri")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!appContext.packageManager.canRequestPackageInstalls()) {
                Log.w(TAG, "canRequestPackageInstalls() is false; opening unknown-sources settings")
                toast(appContext, "Allow installing updates for this app in Settings")
                val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${appContext.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                runCatching { appContext.startActivity(settingsIntent) }
                    .onFailure { e -> Log.e(TAG, "Failed to open unknown-sources settings", e) }
                return
            }
        }

        toast(appContext, "Download complete. Opening installer…")

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            clipData = ClipData.newRawUri("", apkUri)
        }

        runCatching {
            appContext.startActivity(installIntent)
            Log.i(TAG, "Installer activity started successfully")
        }.onFailure { e ->
            Log.e(TAG, "Failed to start package installer", e)
            toast(appContext, "Could not open installer")
        }
    }

    private fun resolveApkFile(
        localUriString: String?,
        localPathString: String?,
        fallbackDir: File?,
    ): File? {
        localPathString?.trim()?.takeIf { it.isNotEmpty() }?.let { path ->
            File(path).takeIf { it.exists() }?.let { return it }
        }
        localUriString?.trim()?.takeIf { it.isNotEmpty() }?.let { uriStr ->
            val uri = Uri.parse(uriStr)
            if ("file".equals(uri.scheme, ignoreCase = true)) {
                uri.path?.let { path ->
                    File(path).takeIf { it.exists() }?.let { return it }
                }
            }
        }
        if (fallbackDir != null) {
            val expected = File(fallbackDir, AppUpdateDownloadState.APK_FILE_NAME)
            if (expected.exists()) return expected
        }
        return null
    }

    private fun statusName(status: Int): String = when (status) {
        DownloadManager.STATUS_FAILED -> "FAILED"
        DownloadManager.STATUS_PAUSED -> "PAUSED"
        DownloadManager.STATUS_PENDING -> "PENDING"
        DownloadManager.STATUS_RUNNING -> "RUNNING"
        DownloadManager.STATUS_SUCCESSFUL -> "SUCCESSFUL"
        else -> "UNKNOWN($status)"
    }

    private fun toast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val TAG = "AppUpdateDownload"
    }
}

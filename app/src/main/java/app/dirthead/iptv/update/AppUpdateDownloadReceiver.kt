package app.dirthead.iptv.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

class AppUpdateDownloadReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return

        val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (downloadId == -1L) return

        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(AppUpdateDownloadState.PREFS_NAME, Context.MODE_PRIVATE)
        val pendingId = prefs.getLong(AppUpdateDownloadState.KEY_PENDING_DOWNLOAD_ID, -1L)
        if (downloadId != pendingId) return

        prefs.edit().remove(AppUpdateDownloadState.KEY_PENDING_DOWNLOAD_ID).apply()

        val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(downloadId)
        dm.query(query).use { cursor ->
            if (!cursor.moveToFirst()) return
            val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
            if (statusIdx < 0) return
            val status = cursor.getInt(statusIdx)
            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                Toast.makeText(appContext, "Update download failed", Toast.LENGTH_SHORT).show()
                return
            }
        }

        val dir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return
        val apkFile = File(dir, AppUpdateDownloadState.APK_FILE_NAME)
        if (!apkFile.exists() || apkFile.length() == 0L) {
            Toast.makeText(appContext, "Update file missing", Toast.LENGTH_SHORT).show()
            return
        }

        val authority = "${appContext.packageName}.fileprovider"
        val apkUri = FileProvider.getUriForFile(appContext, authority, apkFile)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!appContext.packageManager.canRequestPackageInstalls()) {
                Toast.makeText(
                    appContext,
                    "Allow installing updates for this app in Settings",
                    Toast.LENGTH_LONG,
                ).show()
                val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${appContext.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                runCatching { appContext.startActivity(settingsIntent) }
                return
            }
        }

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        runCatching {
            appContext.startActivity(installIntent)
        }.onFailure {
            Toast.makeText(appContext, "Could not open installer", Toast.LENGTH_SHORT).show()
        }
    }
}

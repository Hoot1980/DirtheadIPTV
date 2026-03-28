package app.dirthead.iptv.update

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import java.io.File

object AppUpdateDownloader {

    /**
     * Enqueues an APK download into the shared **Downloads** folder
     * ([DownloadManager.Request.setDestinationInExternalPublicDir]) as `DirtheadIPTV.apk`.
     */
    fun enqueue(context: Context, apkUrl: String) {
        val appContext = context.applicationContext

        if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) {
            Toast.makeText(appContext, "Storage not available", Toast.LENGTH_SHORT).show()
            return
        }

        val destFile = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            AppUpdateDownloadState.APK_FILE_NAME,
        )
        runCatching {
            if (destFile.exists()) destFile.delete()
        }

        val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val prefs = appContext.getSharedPreferences(AppUpdateDownloadState.PREFS_NAME, Context.MODE_PRIVATE)
        val oldId = prefs.getLong(AppUpdateDownloadState.KEY_PENDING_DOWNLOAD_ID, -1L)
        if (oldId != -1L) {
            dm.remove(oldId)
        }

        val request = DownloadManager.Request(Uri.parse(apkUrl)).apply {
            setTitle("Dirthead IPTV")
            setDescription("Downloading update...")
            setMimeType("application/vnd.android.package-archive")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_ONLY_COMPLETION)
            setAllowedNetworkTypes(
                DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE,
            )
            setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                AppUpdateDownloadState.APK_FILE_NAME,
            )
        }

        val downloadId = try {
            dm.enqueue(request)
        } catch (e: Exception) {
            Toast.makeText(appContext, "Could not start download", Toast.LENGTH_SHORT).show()
            return
        }

        prefs.edit().putLong(AppUpdateDownloadState.KEY_PENDING_DOWNLOAD_ID, downloadId).apply()
        Toast.makeText(context, "Downloading update...", Toast.LENGTH_SHORT).show()
    }
}

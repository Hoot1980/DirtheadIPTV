package app.dirthead.iptv

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.dirthead.iptv.data.PlaylistRepository
import app.dirthead.iptv.navigation.IptvNavHost
import app.dirthead.iptv.ui.LocalPlaylistRepository
import app.dirthead.iptv.ui.theme.DirtheadIPTVTheme
import app.dirthead.iptv.update.AppUpdateChecker
import app.dirthead.iptv.update.AppUpdateDownloader
import app.dirthead.iptv.update.RemoteAppVersion

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        configureImmersiveFullScreen()
        setContent {
            val playlistRepository = remember { PlaylistRepository(this@MainActivity) }
            val context = LocalContext.current
            var remoteUpdate by remember { mutableStateOf<RemoteAppVersion?>(null) }

            LaunchedEffect(Unit) {
                val remote = AppUpdateChecker.fetchRemoteVersion()
                if (remote != null && AppUpdateChecker.isNewerThanInstalled(remote)) {
                    remoteUpdate = remote
                }
            }

            CompositionLocalProvider(LocalPlaylistRepository provides playlistRepository) {
                DirtheadIPTVTheme(darkTheme = true, dynamicColor = false) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = Color.Black,
                        ) {
                            IptvNavHost(modifier = Modifier.fillMaxSize())
                        }

                        remoteUpdate?.let { remote ->
                            AlertDialog(
                                onDismissRequest = { remoteUpdate = null },
                                title = { Text("Update Available") },
                                text = {
                                    Column(
                                        modifier = Modifier.heightIn(max = 320.dp).verticalScroll(
                                            rememberScrollState(),
                                        ),
                                    ) {
                                        Text(remote.changelog)
                                    }
                                },
                                confirmButton = {
                                    TextButton(
                                        onClick = {
                                            AppUpdateDownloader.enqueue(context, remote.apkUrl)
                                            remoteUpdate = null
                                        },
                                    ) {
                                        Text("Update")
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { remoteUpdate = null }) {
                                        Text("Later")
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /**
     * True fullscreen on every screen (including the player): draw behind display cutout and
     * hide status + navigation bars. Swipe from edge to briefly show system bars.
     */
    private fun configureImmersiveFullScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        hideSystemBars()
    }

    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}
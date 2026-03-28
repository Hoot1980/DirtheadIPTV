package app.dirthead.iptv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.dirthead.iptv.data.CatchupTimeshiftFormat
import app.dirthead.iptv.data.EpgProgram
import app.dirthead.iptv.data.XtreamCredentials
import app.dirthead.iptv.data.XtreamPlayerApi
import app.dirthead.iptv.ui.theme.DirtheadIPTVTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

private sealed interface CatchupProgramsLoadState {
    data object Loading : CatchupProgramsLoadState
    data class Ready(val programs: List<EpgProgram>) : CatchupProgramsLoadState
    data class Failed(val message: String) : CatchupProgramsLoadState
}

class CatchupActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val channelName = intent.getStringExtra(EXTRA_CHANNEL_NAME).orEmpty()
        val streamId = intent.getStringExtra(EXTRA_STREAM_ID).orEmpty()
        val archiveDays = intent.getIntExtra(EXTRA_ARCHIVE_DAYS, -1).takeIf { it > 0 }
        val getPhpUrl = intent.getStringExtra(EXTRA_GET_PHP_URL).orEmpty()
        if (streamId.isEmpty() || getPhpUrl.isEmpty()) {
            finish()
            return
        }
        setContent {
            DirtheadIPTVTheme(darkTheme = true, dynamicColor = false) {
                CatchupProgramsScreen(
                    channelName = channelName.ifEmpty { "Channel" },
                    streamId = streamId,
                    archiveDays = archiveDays,
                    getPhpUrl = getPhpUrl,
                    onBack = { finish() },
                    onPlayTimeshiftUrl = { url ->
                        startActivity(PlayerActivity.intent(this@CatchupActivity, url))
                    },
                )
            }
        }
    }

    companion object {
        const val EXTRA_CHANNEL_NAME: String = "catchup_channel_name"
        const val EXTRA_STREAM_ID: String = "catchup_stream_id"
        const val EXTRA_ARCHIVE_DAYS: String = "catchup_archive_days"
        const val EXTRA_GET_PHP_URL: String = "catchup_get_php_url"

        fun intent(
            context: Context,
            channelName: String,
            streamId: String,
            archiveDays: Int?,
            getPhpUrl: String,
        ): Intent =
            Intent(context, CatchupActivity::class.java).apply {
                putExtra(EXTRA_CHANNEL_NAME, channelName)
                putExtra(EXTRA_STREAM_ID, streamId)
                putExtra(EXTRA_GET_PHP_URL, getPhpUrl)
                if (archiveDays != null && archiveDays > 0) {
                    putExtra(EXTRA_ARCHIVE_DAYS, archiveDays)
                }
            }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CatchupProgramsScreen(
    channelName: String,
    streamId: String,
    archiveDays: Int?,
    getPhpUrl: String,
    onBack: () -> Unit,
    onPlayTimeshiftUrl: (String) -> Unit,
) {
    var loadState by remember(streamId, getPhpUrl) {
        mutableStateOf<CatchupProgramsLoadState>(CatchupProgramsLoadState.Loading)
    }
    val timeFmt = remember { SimpleDateFormat("EEE MMM d, h:mm a", Locale.getDefault()) }
    val client = remember { catchupHttpClient() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(streamId, getPhpUrl) {
        loadState = CatchupProgramsLoadState.Loading
        loadState = withContext(Dispatchers.IO) {
            val creds = XtreamCredentials.fromGetPhpUrl(getPhpUrl)
            if (creds == null) {
                return@withContext CatchupProgramsLoadState.Failed("Invalid Xtream playlist URL.")
            }
            runCatching {
                XtreamPlayerApi.getSimpleDataTable(client, creds, streamId)
            }.fold(
                onSuccess = { raw ->
                    val now = System.currentTimeMillis()
                    val cutoff = archiveDays?.let { days ->
                        now - days.toLong() * 24L * 3600_000L
                    }
                    val filtered = raw
                        .filter { p ->
                            p.startMillis < now &&
                                (cutoff == null || p.startMillis >= cutoff)
                        }
                        .sortedByDescending { it.startMillis }
                    CatchupProgramsLoadState.Ready(filtered)
                },
                onFailure = { e ->
                    CatchupProgramsLoadState.Failed(e.message ?: "Could not load catch-up listings.")
                },
            )
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                ),
                title = {
                    Column {
                        Text(
                            text = "Catch-up",
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = channelName,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.LightGray,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        when (val s = loadState) {
            CatchupProgramsLoadState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = Color.White)
                }
            }
            is CatchupProgramsLoadState.Failed -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = s.message,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            is CatchupProgramsLoadState.Ready -> {
                if (s.programs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "No programmes in the catch-up window.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.LightGray,
                        )
                    }
                } else {
                    val creds = remember(getPhpUrl) { XtreamCredentials.fromGetPhpUrl(getPhpUrl) }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                        items(s.programs, key = { "${it.startMillis}_${it.title}" }) { prog ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = creds != null) {
                                        val c = creds ?: return@clickable
                                        scope.launch(Dispatchers.IO) {
                                            val baseUrl = XtreamPlayerApi.resolveStreamBaseUrlForPlayback(client, c)
                                            val startTok = CatchupTimeshiftFormat.formatStartForTimeshiftUrl(prog.startMillis)
                                            val dur = CatchupTimeshiftFormat.durationMinutes(
                                                prog.startMillis,
                                                prog.endMillis,
                                            )
                                            val url = XtreamPlayerApi.buildTimeshiftUrl(
                                                baseUrl = baseUrl,
                                                username = c.username,
                                                password = c.password,
                                                durationMinutes = dur,
                                                startFormatted = startTok,
                                                streamId = streamId,
                                                streamExtension = c.liveStreamPathExtension(),
                                            )
                                            withContext(Dispatchers.Main) {
                                                onPlayTimeshiftUrl(url)
                                            }
                                        }
                                    }
                                    .padding(vertical = 12.dp),
                            ) {
                                Text(
                                    text = prog.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = timeFmt.format(Date(prog.startMillis)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF6DD3FF),
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                            HorizontalDivider(color = Color.DarkGray.copy(alpha = 0.5f))
                        }
                    }
                }
            }
        }
    }
}

private fun catchupHttpClient(): OkHttpClient =
    OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

@file:OptIn(UnstableApi::class)

package com.vlad.mysound

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("s", 0)
        NewPipe.init(OkDownloader(applicationContext) { prefs.getString("proxy", "") ?: "" })
        setContent { App() }
    }
}

suspend fun searchTracks(q: String): List<StreamInfoItem> = withContext(Dispatchers.IO) {
    val sc = ServiceList.SoundCloud
    val info = SearchInfo.getInfo(sc, sc.searchQHFactory.fromQuery(q, listOf("tracks"), ""))
    info.relatedItems.filterIsInstance<StreamInfoItem>()
}

suspend fun streamUrl(item: StreamInfoItem): String = withContext(Dispatchers.IO) {
    val a = StreamInfo.getInfo(item.url).audioStreams
    (a.firstOrNull { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP } ?: a.first()).content
}

val themeNames = listOf("Тёмная", "Светлая", "AMOLED")
val accents = listOf(0xFFFF5500, 0xFF4FC3F7, 0xFFBA68C8, 0xFF81C784)

@Composable
fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("s", 0) }

    var theme by remember { mutableIntStateOf(prefs.getInt("theme", 0)) }
    var accent by remember { mutableIntStateOf(prefs.getInt("accent", 0)) }
    var proxy by remember { mutableStateOf(prefs.getString("proxy", "") ?: "") }

    // свой шрифт
    val fontFile = remember { File(ctx.filesDir, "font.bin") }
    var fontVer by remember { mutableIntStateOf(0) }
    val family: FontFamily? = remember(fontVer) {
        if (fontFile.exists()) runCatching { FontFamily(Font(fontFile)) }.getOrNull() else null
    }
    val pickFont = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            ctx.contentResolver.openInputStream(uri)?.use { i -> fontFile.outputStream().use { o -> i.copyTo(o) } }
            fontVer++
        }
    }

    val base = when (theme) {
        1 -> lightColorScheme()
        2 -> darkColorScheme(background = Color.Black, surface = Color.Black)
        else -> darkColorScheme()
    }
    val scheme = base.copy(primary = Color(accents[accent]))
    val t = Typography()
    val typo = if (family == null) t else t.copy(
        bodyLarge = t.bodyLarge.copy(fontFamily = family),
        bodyMedium = t.bodyMedium.copy(fontFamily = family),
        bodySmall = t.bodySmall.copy(fontFamily = family),
        titleLarge = t.titleLarge.copy(fontFamily = family),
        titleMedium = t.titleMedium.copy(fontFamily = family),
        titleSmall = t.titleSmall.copy(fontFamily = family),
        labelLarge = t.labelLarge.copy(fontFamily = family),
        labelMedium = t.labelMedium.copy(fontFamily = family),
    )

    // плеер (пересоздаётся при смене прокси)
    val player = remember(proxy) {
        ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(Net.client(ctx, proxy))))
            .build()
    }
    var playing by remember { mutableStateOf(false) }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
        }
        player.addListener(l)
        onDispose { player.removeListener(l); player.release() }
    }

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<StreamInfoItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var nowTitle by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }

    MaterialTheme(colorScheme = scheme, typography = typo) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("MySound", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { showSettings = true }) { Text("Настройки") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true,
                        label = { Text("Поиск треков") })
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        scope.launch {
                            loading = true; error = null
                            try { results = searchTracks(query) } catch (e: Exception) { error = e.message ?: e.toString() }
                            loading = false
                        }
                    }) { Text("Найти") }
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 4.dp)) }

                LazyColumn(Modifier.weight(1f)) {
                    items(results) { item ->
                        Column(Modifier.fillMaxWidth().clickable {
                            scope.launch {
                                try {
                                    nowTitle = item.name
                                    val url = streamUrl(item)
                                    player.setMediaItem(MediaItem.fromUri(url))
                                    player.prepare(); player.play()
                                } catch (e: Exception) { error = e.message ?: e.toString() }
                            }
                        }.padding(vertical = 10.dp)) {
                            Text(item.name, style = MaterialTheme.typography.bodyLarge)
                            Text(item.uploaderName ?: "", style = MaterialTheme.typography.bodySmall)
                        }
                        HorizontalDivider()
                    }
                }

                if (nowTitle.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(nowTitle, Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = { if (playing) player.pause() else player.play() }) {
                            Text(if (playing) "Пауза" else "Играть")
                        }
                    }
                }
            }
        }

        if (showSettings) AlertDialog(
            onDismissRequest = { showSettings = false },
            confirmButton = { TextButton(onClick = { showSettings = false }) { Text("Готово") } },
            title = { Text("Настройки") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Тема")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        themeNames.forEachIndexed { i, n ->
                            FilterChip(theme == i, { theme = i; prefs.edit().putInt("theme", i).apply() }, { Text(n) })
                        }
                    }
                    Text("Акцент")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        accents.forEachIndexed { i, c ->
                            Box(Modifier.size(if (accent == i) 36.dp else 28.dp).clip(CircleShape)
                                .background(Color(c)).clickable { accent = i; prefs.edit().putInt("accent", i).apply() })
                        }
                    }
                    Text("Шрифт")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { pickFont.launch(arrayOf("*/*")) }) { Text("Выбрать .ttf/.otf") }
                        TextButton(onClick = { fontFile.delete(); fontVer++ }) { Text("Сбросить") }
                    }
                    OutlinedTextField(proxy, { proxy = it; prefs.edit().putString("proxy", it).apply() },
                        singleLine = true, label = { Text("Прокси (необязательно)") },
                        placeholder = { Text("host:port или socks5://host:port") },
                        supportingText = { Text("При включённом VPN прокси не используется") })
                }
            }
        )
    }
}

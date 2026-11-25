@file:OptIn(UnstableApi::class)

package com.vlad.mysound

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.File
import java.io.OutputStream
import kotlin.math.roundToInt

const val UA = "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("s", 0)
        NewPipe.init(OkDownloader(applicationContext) { prefs.getString("proxy", "") ?: "" })
        setContent { App() }
    }
}

// ---------------------------------------------------------------- данные

val sourceNames = listOf("SoundCloud", "YT Music")

/** source: 0 = SoundCloud, 1 = YouTube Music */
suspend fun searchTracks(source: Int, q: String): List<StreamInfoItem> = withContext(Dispatchers.IO) {
    val sc = if (source == 0) ServiceList.SoundCloud else ServiceList.YouTube
    val filter = if (source == 0) "tracks" else "music_songs"
    val info = SearchInfo.getInfo(sc, sc.searchQHFactory.fromQuery(q, listOf(filter), ""))
    info.relatedItems.filterIsInstance<StreamInfoItem>()
}

suspend fun streamUrl(item: StreamInfoItem): String = withContext(Dispatchers.IO) {
    val a = StreamInfo.getInfo(item.url).audioStreams
    (a.firstOrNull { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP } ?: a.first()).content
}

suspend fun savableStreams(item: StreamInfoItem): List<AudioStream> = withContext(Dispatchers.IO) {
    StreamInfo.getInfo(item.url).audioStreams.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
}

fun streamLabel(s: AudioStream): String {
    val ext = (s.format?.suffix ?: "?").uppercase()
    return if (s.averageBitrate > 0) "$ext · ${s.averageBitrate} кбит/с" else ext
}

/** Качает кусками по ~1 МБ (так надёжнее для YouTube) */
fun downloadFile(client: OkHttpClient, url: String, out: OutputStream) {
    var pos = 0L
    val chunk = 1_000_000L
    while (true) {
        val req = okhttp3.Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Range", "bytes=$pos-${pos + chunk - 1}")
            .build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            val body = r.body ?: error("пустой ответ")
            val n = body.byteStream().copyTo(out)
            if (r.code == 200) return
            pos += n
            val total = r.header("Content-Range")?.substringAfter("/")?.toLongOrNull()
            if (n == 0L || (total != null && pos >= total)) return
        }
    }
}

suspend fun saveToFolder(ctx: Context, tree: Uri, name: String, stream: AudioStream, proxy: String): String =
    withContext(Dispatchers.IO) {
        val ext = stream.format?.suffix ?: "m4a"
        val mime = stream.format?.mimeType ?: "audio/mpeg"
        val clean = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "track" }
        val fileName = "$clean.$ext"
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val doc = DocumentsContract.createDocument(ctx.contentResolver, parent, mime, fileName)
            ?: error("не удалось создать файл")
        ctx.contentResolver.openOutputStream(doc)!!.use { downloadFile(Net.client(ctx, proxy), stream.content, it) }
        fileName
    }

fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

fun coverOf(item: StreamInfoItem): String? = item.thumbnails.maxByOrNull { it.height }?.url

// ---------------------------------------------------------------- иконки

private fun vec(name: String, path: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.Black))
        .build()

private val IcPlay = vec("play", "M8,5v14l11,-7z")
private val IcPause = vec("pause", "M6,19h4V5H6v14zM14,5v14h4V5h-4z")
private val IcNext = vec("next", "M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z")
private val IcPrev = vec("prev", "M6,6h2v12H6zM9.5,12l8.5,6V6z")
private val IcDownload = vec("download", "M19,9h-4V3H9v6H5l7,7 7,-7zM5,18v2h14v-2H5z")
private val IcDown = vec("down", "M7.41,8.59L12,13.17l4.59,-4.58L18,10l-6,6 -6,-6 1.41,-1.41z")
private val IcMore = vec("more", "M12,8c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2zM12,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2zM12,16c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z")
private val IcSearch = vec("search", "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z")

// ---------------------------------------------------------------- виджеты

@Composable
fun Cover(url: String?, modifier: Modifier, radius: Int = 8) {
    Box(modifier.clip(RoundedCornerShape(radius.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (url != null) AsyncImage(model = url, contentDescription = null,
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
fun TrackRow(item: StreamInfoItem, active: Boolean, onPlay: () -> Unit, onDownload: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Cover(coverOf(item), Modifier.size(56.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium,
                color = if (active) MaterialTheme.colorScheme.primary else Color.Unspecified)
            val sub = listOfNotNull(item.uploaderName, if (item.duration > 0) fmt(item.duration * 1000) else null)
            Text(sub.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onDownload) { Icon(IcDownload, "Скачать") }
    }
}

@Composable
fun MiniPlayer(
    item: StreamInfoItem, playing: Boolean, loading: Boolean, progress: Float,
    onToggle: () -> Unit, onNext: () -> Unit, onOpen: () -> Unit,
    onDrag: (Float) -> Unit, onDragEnd: () -> Unit
) {
    Surface(
        tonalElevation = 6.dp,
        modifier = Modifier.fillMaxWidth()
            .pointerInput(Unit) {
                detectVerticalDragGestures(onDragEnd = onDragEnd, onDragCancel = onDragEnd) { c, dy ->
                    c.consume(); onDrag(dy)
                }
            }
            .clickable(onClick = onOpen)
    ) {
        Column {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
            else LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(2.dp))
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(coverOf(item), Modifier.size(48.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    Text(item.uploaderName ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onToggle) { Icon(if (playing) IcPause else IcPlay, "Играть/пауза") }
                IconButton(onClick = onNext) { Icon(IcNext, "Дальше") }
            }
        }
    }
}

@Composable
fun FullPlayer(
    item: StreamInfoItem, playing: Boolean, loading: Boolean, pos: Long, dur: Long,
    onClose: () -> Unit, onToggle: () -> Unit, onPrev: () -> Unit, onNext: () -> Unit,
    onSeek: (Long) -> Unit, onDownload: () -> Unit
) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val max = dur.coerceAtLeast(1L).toFloat()
    val shown = (drag ?: pos.toFloat()).coerceIn(0f, max)

    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(IcDown, "Свернуть") }
            Text("Сейчас играет", Modifier.weight(1f), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.size(48.dp))
        }
        Spacer(Modifier.height(16.dp))
        Cover(coverOf(item), Modifier.fillMaxWidth(0.85f).aspectRatio(1f), 20)
        Spacer(Modifier.height(24.dp))
        Text(item.name, style = MaterialTheme.typography.titleLarge, maxLines = 2,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text(item.uploaderName ?: "", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))

        Slider(
            value = shown,
            onValueChange = { drag = it },
            onValueChangeFinished = { drag?.let { onSeek(it.toLong()) }; drag = null },
            valueRange = 0f..max
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            Text(fmt(shown.toLong()), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            Text(fmt(dur), style = MaterialTheme.typography.bodySmall)
        }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onPrev, modifier = Modifier.size(56.dp)) { Icon(IcPrev, "Назад", Modifier.size(32.dp)) }
            FilledIconButton(onClick = onToggle, modifier = Modifier.size(72.dp)) {
                if (loading) CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp,
                    color = MaterialTheme.colorScheme.onPrimary)
                else Icon(if (playing) IcPause else IcPlay, "Играть/пауза", Modifier.size(40.dp))
            }
            IconButton(onClick = onNext, modifier = Modifier.size(56.dp)) { Icon(IcNext, "Дальше", Modifier.size(32.dp)) }
        }
        TextButton(onClick = onDownload) {
            Icon(IcDownload, null)
            Spacer(Modifier.width(8.dp))
            Text("Скачать")
        }
    }
}

// ---------------------------------------------------------------- приложение

val themeNames = listOf("Тёмная", "Светлая", "AMOLED")
val accents = listOf(0xFFFF5500, 0xFF4FC3F7, 0xFFBA68C8, 0xFF81C784)

@Composable
fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("s", 0) }
    val snack = remember { SnackbarHostState() }

    var theme by remember { mutableIntStateOf(prefs.getInt("theme", 0)) }
    var accent by remember { mutableIntStateOf(prefs.getInt("accent", 0)) }
    var proxy by remember { mutableStateOf(prefs.getString("proxy", "") ?: "") }
    var source by remember { mutableIntStateOf(prefs.getInt("source", 0)) }
    var folder by remember { mutableStateOf(prefs.getString("folder", null)) }

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<StreamInfoItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }

    // очередь воспроизведения
    var queue by remember { mutableStateOf<List<StreamInfoItem>>(emptyList()) }
    var index by remember { mutableIntStateOf(-1) }
    var loadingTrack by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    val current = queue.getOrNull(index)

    // диалог скачивания
    var dlItem by remember { mutableStateOf<StreamInfoItem?>(null) }
    var dlStreams by remember { mutableStateOf<List<AudioStream>>(emptyList()) }
    var dlName by remember { mutableStateOf("") }
    var dlChoice by remember { mutableIntStateOf(0) }
    var pending by remember { mutableStateOf<StreamInfoItem?>(null) }

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

    // плеер (пересоздаётся при смене прокси)
    val player = remember(proxy) {
        val factory = OkHttpDataSource.Factory(Net.client(ctx, proxy)).setUserAgent(UA)
        ExoPlayer.Builder(ctx).setMediaSourceFactory(DefaultMediaSourceFactory(factory)).build()
    }

    fun playAt(i: Int) {
        if (i !in queue.indices) return
        scope.launch {
            try {
                index = i; loadingTrack = true
                val url = streamUrl(queue[i])
                player.setMediaItem(MediaItem.fromUri(url))
                player.prepare(); player.play()
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            }
            loadingTrack = false
        }
    }
    fun next() { if (index + 1 < queue.size) playAt(index + 1) }
    fun prev() { if (player.currentPosition > 3000 || index <= 0) player.seekTo(0) else playAt(index - 1) }
    fun toggle() { if (playing) player.pause() else player.play() }

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) next() }
        }
        player.addListener(l)
        onDispose { player.removeListener(l); player.release() }
    }
    LaunchedEffect(player) {
        while (true) {
            pos = player.currentPosition
            dur = player.duration.takeIf { it > 0 } ?: 0L
            delay(400)
        }
    }

    fun doSearch() {
        if (query.isBlank()) return
        scope.launch {
            loading = true; error = null
            try { results = searchTracks(source, query) } catch (e: Exception) { error = e.message ?: e.toString() }
            loading = false
        }
    }

    // скачивание: открыть диалог (грузим список форматов)
    fun openDialog(item: StreamInfoItem) {
        scope.launch {
            busy = true
            try {
                val list = savableStreams(item)
                if (list.isEmpty()) snack.showSnackbar("Для этого трека нет файла для скачивания")
                else { dlStreams = list; dlName = item.name; dlChoice = 0; dlItem = item }
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            }
            busy = false
        }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            folder = uri.toString()
            prefs.edit().putString("folder", folder).apply()
            pending?.let { openDialog(it) }
        }
        pending = null
    }
    fun onDownloadClick(item: StreamInfoItem) {
        if (folder == null) {
            pending = item
            scope.launch {
                launch { snack.showSnackbar("Выберите папку для сохранения") }
                delay(900)
                pickFolder.launch(null)
            }
        } else openDialog(item)
    }

    // тема и шрифт
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

    val sheet = remember { Animatable(0f) } // 0 = свёрнут, 1 = плеер на весь экран

    MaterialTheme(colorScheme = scheme, typography = typo) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val hPx = constraints.maxHeight.toFloat()
            fun dragSheet(dy: Float) { scope.launch { sheet.snapTo((sheet.value - dy / hPx).coerceIn(0f, 1f)) } }
            fun settle(wasOpen: Boolean) {
                scope.launch {
                    val open = if (wasOpen) sheet.value > 0.7f else sheet.value > 0.2f
                    sheet.animateTo(if (open) 1f else 0f)
                }
            }

            Scaffold(
                snackbarHost = { SnackbarHost(snack) },
                bottomBar = {
                    current?.let {
                        MiniPlayer(
                            item = it, playing = playing, loading = loadingTrack,
                            progress = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f,
                            onToggle = { toggle() }, onNext = { next() },
                            onOpen = { scope.launch { sheet.animateTo(1f) } },
                            onDrag = { dy -> dragSheet(dy) }, onDragEnd = { settle(false) }
                        )
                    }
                }
            ) { pad ->
                Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("MySound", style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f))
                        IconButton(onClick = { showSettings = true }) { Icon(IcMore, "Настройки") }
                    }
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        shape = RoundedCornerShape(28.dp),
                        placeholder = { Text("Поиск треков") },
                        leadingIcon = { Icon(IcSearch, null) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { doSearch() })
                    )
                    Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        sourceNames.forEachIndexed { i, n ->
                            FilterChip(source == i, {
                                source = i; prefs.edit().putInt("source", i).apply()
                                results = emptyList()
                                if (query.isNotBlank()) doSearch()
                            }, { Text(n) })
                        }
                    }
                    if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 4.dp))
                    }
                    if (results.isEmpty() && !loading) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Найди любой трек", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            itemsIndexed(results) { i, item ->
                                TrackRow(
                                    item = item, active = current?.url == item.url,
                                    onPlay = { queue = results; playAt(i) },
                                    onDownload = { onDownloadClick(item) }
                                )
                            }
                        }
                    }
                }
            }

            // полноэкранный плеер выезжает снизу
            if (sheet.value > 0.001f) {
                BackHandler { scope.launch { sheet.animateTo(0f) } }
                Surface(
                    modifier = Modifier.fillMaxSize()
                        .offset { IntOffset(0, ((1f - sheet.value) * hPx).roundToInt()) }
                        .pointerInput(Unit) {
                            detectVerticalDragGestures(onDragEnd = { settle(true) }, onDragCancel = { settle(true) }) { c, dy ->
                                c.consume(); dragSheet(dy)
                            }
                        },
                    color = MaterialTheme.colorScheme.background
                ) {
                    current?.let {
                        FullPlayer(
                            item = it, playing = playing, loading = loadingTrack, pos = pos, dur = dur,
                            onClose = { scope.launch { sheet.animateTo(0f) } },
                            onToggle = { toggle() }, onPrev = { prev() }, onNext = { next() },
                            onSeek = { player.seekTo(it) },
                            onDownload = { onDownloadClick(it) }
                        )
                    }
                }
            }
        }

        // диалог: название + формат
        dlItem?.let {
            AlertDialog(
                onDismissRequest = { dlItem = null },
                title = { Text("Скачать") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(dlName, { dlName = it }, singleLine = true, label = { Text("Название файла") })
                        Text("Формат")
                        dlStreams.forEachIndexed { i, s ->
                            Row(Modifier.fillMaxWidth().clickable { dlChoice = i }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(dlChoice == i, { dlChoice = i })
                                Text(streamLabel(s))
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val s = dlStreams[dlChoice]
                        val tree = Uri.parse(folder ?: "")
                        val name = dlName
                        dlItem = null
                        scope.launch {
                            busy = true
                            val msg = try { "Сохранено: " + saveToFolder(ctx, tree, name, s, proxy) }
                            catch (e: Exception) { "Ошибка: " + (e.message ?: e.toString()) }
                            busy = false
                            snack.showSnackbar(msg)
                        }
                    }) { Text("Скачать") }
                },
                dismissButton = { TextButton(onClick = { dlItem = null }) { Text("Отмена") } }
            )
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
                    Text("Папка для скачивания")
                    Button(onClick = { pending = null; pickFolder.launch(null) }) {
                        Text(if (folder == null) "Выбрать папку" else "Сменить папку")
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

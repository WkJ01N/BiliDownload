package io.github.bilidownload

import android.Manifest
import android.content.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Modifier
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.Alignment
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.res.painterResource
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import java.util.UUID

class MainActivity : ComponentActivity() {
    private var input by mutableStateOf("")
    private var incoming by mutableIntStateOf(0)
    private var resumeCount by mutableIntStateOf(0)
    override fun onResume() { super.onResume(); resumeCount++ }
    private var detectRequested = false
    private var lastClipboard: String? = null
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); if(savedInstanceState != null) input = savedInstanceState.getString("input").orEmpty() else acceptIntent(intent); setContent { BiliScreen() } }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("input", input); super.onSaveInstanceState(outState) }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); acceptIntent(intent) }
    private fun acceptIntent(intent: Intent) {
        if(intent.action == Intent.ACTION_SEND) { input = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty(); incoming++ }
        if(intent.action == "io.github.bilidownload.PASTE") detectRequested = true
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if(hasFocus && (detectRequested || getSharedPreferences("settings", MODE_PRIVATE).getBoolean("detect", false))) {
            detectRequested = false
            runCatching { val clipboard = getSystemService(ClipboardManager::class.java); val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
                if(text != lastClipboard && Links.supported(text).isNotEmpty()) { lastClipboard = text; input = text } }
        }
    }
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable private fun BiliScreen() {
        val prefs = remember { getSharedPreferences("settings", MODE_PRIVATE) }
        var theme by rememberSaveable { mutableStateOf(prefs.getString("theme", "Default")!!) }
        val dark = theme == "Dark" || theme == "Default" && isSystemInDarkTheme()
        BiliTheme(dark) {
            var tab by rememberSaveable { mutableIntStateOf(0) }
            LaunchedEffect(incoming) { if (incoming > 0) tab = 0 }
            val youtube = BiliApplication.youtube
            val ytAccount by youtube.session.account.collectAsStateWithLifecycle()
            val installation = remember(resumeCount) { youtube.installation() }
            var expand by remember { mutableStateOf(prefs.getBoolean("expandRelated", false)) }
            val pagesSaver = listSaver<List<PlaylistPage>, String>(save = { list -> list.map { it.id + ":" + it.start + ":" + it.music } },
                restore = { list -> list.map { it.split(':').let { p -> PlaylistPage(p[0],p[1].toInt(),p.getOrNull(2).toBoolean()) } } })
            var pages by rememberSaveable(stateSaver = pagesSaver) { mutableStateOf(emptyList<PlaylistPage>()) }
            var message by rememberSaveable { mutableStateOf("") }
            var loading by remember { mutableStateOf(false) }
            val mediaSaver = Saver<List<MediaItem>, String>(save = { Gson().toJson(it) }, restore = { Gson().fromJson(it, object : TypeToken<List<MediaItem>>() {}.type) })
            val variantsSaver = Saver<List<Variant>, String>(save = { Gson().toJson(it) }, restore = { Gson().fromJson(it, object : TypeToken<List<Variant>>() {}.type) })
            var media by rememberSaveable(stateSaver = mediaSaver) { mutableStateOf(emptyList<MediaItem>()) }
            var variants by rememberSaveable(stateSaver = variantsSaver) { mutableStateOf(emptyList<Variant>()) }
            var selected by rememberSaveable(stateSaver = listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() })) { mutableStateOf(emptySet<String>()) }
            var mode by rememberSaveable { mutableIntStateOf(0) }
            var mp3 by rememberSaveable { mutableStateOf(false) }
            var bitrate by rememberSaveable { mutableIntStateOf(320) }
            var trackIds by rememberSaveable { mutableStateOf(android.os.Bundle()) }
            val audioCache = remember { mutableStateMapOf<String,List<AudioTrack>>() }
            fun audioMode(item: MediaItem) = mode == 2 || mode == 0 && item.music
            fun output(item: MediaItem): AudioOutput? {
                if(!audioMode(item)) return null
                val tracks = audioCache[item.id] ?: return null
                val actual = tracks.firstOrNull { it.id == trackIds.getString(item.id) } ?: tracks.firstOrNull() ?: return null
                return AudioOutput(actual, if(mp3) bitrate else 0)
            }
            fun ext(item: MediaItem) = if(audioMode(item)) output(item)?.extension ?: if(mp3) ".mp3" else ".m4a" else ".mp4"
            var quality by rememberSaveable { mutableIntStateOf(80) }
            var detect by remember { mutableStateOf(prefs.getBoolean("detect", false)) }
            var directory by remember { mutableStateOf(prefs.getString("directory", null)) }
            var namingTemplate by rememberSaveable { mutableStateOf(prefs.getString("filenameTemplate", FileNaming.DEFAULT) ?: FileNaming.DEFAULT) }
            var showNamingVariables by rememberSaveable { mutableStateOf(false) }
            var namingDraft by rememberSaveable { mutableStateOf(namingTemplate) }
            var customNamesJson by rememberSaveable { mutableStateOf("{}") }
            fun customNames(): Map<String, String> = Gson().fromJson(customNamesJson, object : TypeToken<Map<String, String>>() {}.type)
            val sizeCache = remember { mutableMapOf<String, List<Variant>>() }
            var sizes by remember { mutableStateOf(emptyMap<String, Variant?>()) }
            var sizeLoading by remember { mutableStateOf(false) }
            var detailRetry by remember { mutableStateOf(false) }
            var detailAttempt by remember { mutableIntStateOf(0) }
            var removeId by rememberSaveable { mutableStateOf<String?>(null) }
            var renameId by rememberSaveable { mutableStateOf<String?>(null) }
            var renameText by rememberSaveable { mutableStateOf("") }
            var qualityOpen by remember { mutableStateOf(false) }
            var failureKind by rememberSaveable { mutableStateOf<String?>(null) }
            var retryParse by rememberSaveable { mutableStateOf(false) }
            var parsedInput by rememberSaveable { mutableStateOf<String?>(null) }
            var pendingTask by rememberSaveable { mutableStateOf<String?>(null) }
            val listStates = listOf(rememberLazyListState(), rememberLazyListState(), rememberLazyListState(), rememberLazyListState())
            val entries by BiliApplication.store.entries.collectAsStateWithLifecycle()
            val account by BiliApplication.session.account.collectAsStateWithLifecycle()
            LaunchedEffect(account.state) {
                if(account.state == SessionState.Saved) try { BiliApplication.api.api("https://api.bilibili.com/x/web-interface/nav") } catch(e: CancellationException) { throw e } catch(_: Exception) { }
            }
            val scope = rememberCoroutineScope()
            val cookiePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) scope.launch {
                    try { youtube.import(uri); sizeCache.clear(); audioCache.clear(); detailAttempt++; message = "会话已导入，可返回重试。" }
                    catch (e: Exception) { message = if (e is BiliException) e.message.orEmpty() else "无法导入会话文件。" }
                }
            }
            fun installExtension() {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(YoutubeClient.APK))) }
                    .onFailure { message = "无法打开扩展下载链接。" }
            }
            val activate = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
                    youtube.acceptActivation(result.data?.extras?.getBinder("engine"))
                    failureKind = null; message = "YouTube 扩展已连接，可返回重试。"
                }
            }
            val audioProcessor = BiliApplication.audio
            val audioActivation = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if(result.resultCode == RESULT_OK) { audioProcessor.activate(result.data?.extras?.getBinder("engine")); failureKind = null; message = "音频处理扩展已连接，可重试。" }
            }
            fun manageAudio(kind: String?) {
                try {
                    if(kind == "AudioConnect") audioActivation.launch(Intent().setComponent(ComponentName(AudioProcessor.PACKAGE, AudioProcessor.PACKAGE + ".ConnectActivity")))
                    else startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(AudioProcessor.APK)))
                } catch(_: Exception) { message = "无法打开音频处理扩展，请检查安装状态。" }
            }
            fun manageYoutube(kind: String?) {
                if (kind == BiliErrorKind.YoutubeSession.name) cookiePicker.launch(arrayOf("text/*", "application/octet-stream"))
                else if (kind == "YoutubeConnect") runCatching { activate.launch(Intent().setComponent(ComponentName(YoutubeClient.PACKAGE, YoutubeClient.PACKAGE + ".ConnectActivity"))) }.onFailure { message = "请先更新 YouTube 扩展。" }
                else installExtension()
            }
            val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
            val login = rememberLauncherForActivityResult(LoginActivity.Contract()) { result ->
                if(result.outcome == LoginOutcome.Success) failureKind = null
                AuthRecovery.loginMessage(result, pendingTask != null)?.let { message = it }
                pendingTask = null
            }
            fun launchLogin(taskId: String? = null) { pendingTask = taskId; login.launch(Unit) }
            fun parseLinks(preferAudio: Boolean = false) {
                if(loading) return
                val requestInput = input
                val oldSelection = if(parsedInput == requestInput) selected else null
                val previousItems = media.filter { it.id in oldSelection.orEmpty() && it.playlistId != null }
                val oldQuality = if(parsedInput == requestInput) quality else null
                scope.launch {
                    sizeCache.clear(); audioCache.clear(); pages = emptyList(); loading = true; message = ""; failureKind = null; retryParse = false
                    if(parsedInput != requestInput) { customNamesJson = "{}"; sizeCache.clear(); sizes = emptyMap(); media = emptyList(); variants = emptyList(); selected = emptySet() }
                    try {
                        val catalogs = Links.supported(requestInput).map { url ->
                            YoutubeLink.parse(url)?.let { link ->
                                var catalog = youtube.resolve(link, expand)
                                while (catalog.more != null && previousItems.any { old -> old.playlistId == catalog.more?.id && catalog.items.none { it.id == old.id } }) {
                                    val more = youtube.page(catalog.more!!)
                                    catalog = catalog.copy(items = (catalog.items + more.items).distinctBy { it.id }, more = more.more)
                                }
                                catalog
                            } ?: run { var target: String? = null; val items = BiliApplication.api.resolve(url) { target = it }; YoutubeCatalog(items,target) }
                        }
                        if (input != requestInput) return@launch
                        val resolved = catalogs.flatMap { it.items }.distinctBy { it.id }.sortedBy { it.music }
                        if (resolved.isEmpty()) throw BiliException("没有找到可下载的视频。")
                        catalogs.forEach { catalog -> catalog.target?.let { id -> catalog.variants?.let { sizeCache[id] = it }; catalog.audio?.let { audioCache[id] = it } } }
                        if(parsedInput != requestInput) { mode = if(resolved.all { it.music }) 2 else if(resolved.none { it.music }) 1 else 0; mp3 = false; bitrate = 320; trackIds = android.os.Bundle() }
                        if(preferAudio) mode = 2
                        pages = catalogs.mapNotNull { it.more }; media = resolved; parsedInput = requestInput
                        selected = oldSelection?.intersect(resolved.map { it.id }.toSet()) ?: catalogs.mapNotNull { it.target }.toSet()
                        val reference = resolved.firstOrNull { it.id in selected && !audioMode(it) }
                        variants = if (reference == null) emptyList() else sizeCache[reference.id] ?: youtube.variants(reference).also { sizeCache[reference.id] = it }
                        quality = preferredQuality(variants, oldQuality)
                    } catch(e: CancellationException) { throw e }
                    catch(e: Exception) { failureKind = (e as? BiliException)?.kind?.name; retryParse = true; message = if(e is BiliException) e.message.orEmpty() else "解析失败，请检查网络后重试。" }
                    finally { loading = false }
                }
            }
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                if(uri != null) { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION); directory = uri.toString(); prefs.edit().putString("directory", directory).apply() }
            }
            LaunchedEffect(Unit) { if(Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS) }
            LaunchedEffect(media, selected, quality, loading, detailAttempt, mode, trackIds) {
                if(loading || retryParse) return@LaunchedEffect
                sizeLoading = true; detailRetry = false
                val result = mutableMapOf<String, Variant?>()
                try {
                    val chosen = media.filter { it.id in selected }
                    for(item in chosen.filter(::audioMode)) {
                        if(item.id !in audioCache) audioCache[item.id] = youtube.audio(item)
                        if(audioCache[item.id].isNullOrEmpty()) throw BiliException("当前没有可用音轨。")
                    }
                    val first = chosen.firstOrNull { !audioMode(it) }
                    if (first == null) { variants = emptyList(); sizes = emptyMap(); return@LaunchedEffect }
                    val firstVariants = sizeCache[first.id] ?: youtube.variants(first).also { sizeCache[first.id] = it }
                    variants = firstVariants
                    val limit = firstVariants.firstOrNull { it.quality == quality } ?: run {
                        quality = preferredQuality(firstVariants, null); firstVariants.firstOrNull { it.quality == quality }
                    }
                    for(item in chosen.filter { !audioMode(it) }) {
                        val available = try { sizeCache[item.id] ?: youtube.variants(item).also { sizeCache[item.id] = it } }
                        catch(e: CancellationException) { throw e } catch(e: Exception) {
                            detailRetry = true; failureKind = (e as? BiliException)?.kind?.name
                            message = item.title + "：" + (if(e is BiliException) e.message.orEmpty() else "无法获取画质。")
                            emptyList()
                        }
                        result[item.id] = limit?.let { atMost(available, it, item) }
                    }
                    sizes = result
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { sizes = emptyMap(); variants = emptyList(); failureKind = (e as? BiliException)?.kind?.name; detailRetry = true; message = if (e is BiliException) e.message.orEmpty() else "无法获取画质，请重试。" }
                finally { sizeLoading = false }
            }
            removeId?.let { id -> AlertDialog(onDismissRequest = { removeId = null }, title = { Text("移除任务记录？") }, text = { Text("进行中的下载会停止，并清理临时缓存。已下载的文件会保留。") }, confirmButton = { TextButton(onClick = {
                removeId = null
                scope.launch { try { withContext(Dispatchers.IO) { DownloadActions.remove(this@MainActivity, id) } } catch(_: Exception) { message = "移除失败，请重试。" } }
            }) { Text("移除") } }, dismissButton = { TextButton(onClick = { removeId = null }) { Text("取消") } }) }
            renameId?.let { id ->
                fun finish(custom: String?) {
                    val updated = customNames().toMutableMap()
                    if(custom == null) updated.remove(id) else updated[id] = FileNaming.normalize(custom, ext(media.first { it.id == id })).substringBeforeLast('.')
                    customNamesJson = Gson().toJson(updated); renameId = null
                }
                AlertDialog(onDismissRequest = { renameId = null }, title = { Text("修改文件名") }, text = { Column {
                    BiliTextField(value = renameText, onValueChange = { renameText = it }, label = { Text("文件名（自动添加 " + ext(media.first { it.id == id }) + "）") })
                    Text("实际文件名：" + FileNaming.normalize(renameText,ext(media.first { it.id == id })))
                } }, confirmButton = { TextButton(onClick = { finish(renameText) }) { Text("保存") } }, dismissButton = { Row {
                    TextButton(onClick = { finish(null) }) { Text("恢复默认") }; TextButton(onClick = { renameId = null }) { Text("取消") }
                } })
            }
            Scaffold(modifier = Modifier.fillMaxSize(), topBar = { TopAppBar(title = { Text("BiliDownload") }) }, bottomBar = {
                NavigationBar(tonalElevation = 0.dp, containerColor = MaterialTheme.colorScheme.surfaceContainer) { listOf("解析", "下载", "已完成", "设置").forEachIndexed { i, text -> NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(painterResource(listOf(R.drawable.ic_link, R.drawable.ic_download, R.drawable.ic_complete, R.drawable.ic_settings)[i]), contentDescription = text) }, label = { Text(text) }) } }
            }) { padding ->
                AnimatedContent(targetState = tab, modifier = Modifier.padding(padding).fillMaxSize().clipToBounds(), transitionSpec = {
                    fadeIn(tween(250, easing = LinearOutSlowInEasing)) togetherWith fadeOut(tween(150, easing = LinearOutSlowInEasing))
                }, label = "pageTransition") { activeTab ->
                LazyColumn(Modifier.fillMaxSize(), state = listStates[activeTab], contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if(message.isNotBlank()) item { BiliCard { Column(Modifier.padding(16.dp)) {
                        Text(message)
                        if(failureKind in listOf("YoutubeComponent", "YoutubeSession", "YoutubeConnect")) TextButton(onClick = { manageYoutube(failureKind) }) { Text(if(failureKind == "YoutubeSession") "导入 YouTube 会话" else if(failureKind == "YoutubeConnect") "打开 YouTube 扩展" else "安装 YouTube 扩展") }
                        if(failureKind in listOf("AudioComponent","AudioConnect")) TextButton(onClick = { manageAudio(failureKind) }) { Text(if(failureKind == "AudioConnect") "打开音频处理扩展" else "安装音频处理扩展") }
                        if(AuthRecovery.needsLogin(failureKind, BiliApplication.session.state)) TextButton(onClick = { launchLogin() }) { Text("登录 B 站") }
                        if(activeTab == 0 && detailRetry && !retryParse) TextButton(enabled = !sizeLoading, onClick = { detailAttempt++; message = "" }) { Text("重新获取画质") }
                        if(activeTab == 0 && retryParse) TextButton(enabled = !loading, onClick = { parseLinks() }) { Text("重新解析") }
                    } } }
                    when(activeTab) {
                        0 -> {
                            item { Text("链接解析", style = MaterialTheme.typography.headlineSmall) }
                            item { BiliTextField(value = input, onValueChange = { input = it }, label = { Text("B 站或 YouTube 链接") }, minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth()) }
                            item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(enabled = !loading, onClick = { parseLinks() }) { Text(if(loading) "解析中…" else "解析链接") }
                                OutlinedButton(border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline), onClick = { runCatching { input = getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString().orEmpty() } }) { Text("粘贴") }
                            } }
                            if(media.isNotEmpty()) {
                                item { Text("找到 ${media.size} 个条目") }
                                item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { TextButton(onClick = { selected = media.map { it.id }.toSet() }) { Text("全选") }; TextButton(onClick = { selected = emptySet() }) { Text("取消全选") } } }
                                item { ChoiceMenu("下载内容",listOf("按链接类型","视频","音频"),mode) { mode = it } }
                                if(media.any(::audioMode)) item { ChoiceMenu("音频格式",listOf("保留原音","MP3"),if(mp3) 1 else 0) { mp3 = it == 1 } }
                                if(mp3 && media.any(::audioMode)) item { val rates = listOf(320,192,128); ChoiceMenu("MP3 码率",rates.map { "$it kb/s" },rates.indexOf(bitrate)) { bitrate = rates[it] } }
                                if (variants.isNotEmpty() && media.any { !audioMode(it) }) item { Box { OutlinedButton(border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline), onClick = { qualityOpen = true }) { Text("画质上限：${variants.firstOrNull { it.quality == quality }?.label.orEmpty()}") }; DropdownMenu(expanded = qualityOpen, onDismissRequest = { qualityOpen = false }) { variants.forEach { v -> DropdownMenuItem(text = { Text(v.label + "  " + MediaSize.format(v.estimatedBytes)) }, onClick = { quality = v.quality; qualityOpen = false }) } } } }
                                val chosen = media.filter { it.id in selected }
                                val totals = chosen.map { if(audioMode(it)) output(it)?.estimatedBytes else sizes[it.id]?.estimatedBytes }
                                item { Text(if(sizeLoading) "正在估算大小…" else "选中 ${selected.size} 项：" + MediaSize.format(totals.filterNotNull().takeIf { it.isNotEmpty() }?.sum()) + if(totals.any { it == null }) "，部分大小未知" else "") }
                                item { Button(enabled = chosen.isNotEmpty() && chosen.all { if(audioMode(it)) output(it) != null else sizes[it.id] != null } && !sizeLoading && !loading && !retryParse && input == parsedInput, onClick = {
                                    try {
                                        if(chosen.any { output(it)?.needsProcessor == true }) audioProcessor.requireInstalled()
                                        chosen.forEach { item ->
                                            val audio = output(item)
                                            val actual = sizes[item.id]
                                            val cap = if(audio != null) 0 else if(item.youtubeId == null && variants.firstOrNull()?.formatId == null) quality else requireNotNull(actual).quality
                                            if(BiliApplication.store.entries.value.none { (it.item.youtubeId ?: it.item.id) == (item.youtubeId ?: item.id) && it.quality == cap && it.audioOutput?.track?.id == audio?.track?.id && it.audioOutput?.mp3Kbps == audio?.mp3Kbps && it.state !in listOf("Failed","Canceled") })
                                                DownloadActions.schedule(this@MainActivity,DownloadEntry(UUID.randomUUID().toString(),item,cap,formatId = audio?.track?.id ?: actual?.formatId,outputFileName = FileNaming.render(item,namingTemplate,customNames()[item.id],ext(item)),audioOutput = audio))
                                        }; message = "已加入下载队列"; tab = 1
                                    } catch(e: Exception) { failureKind = (e as? BiliException)?.kind?.name; message = if(e is BiliException) e.message.orEmpty() else "系统暂时无法开始下载，请保持应用可见后重试。" }
                                }) { Text("下载选中内容") } }
                                if (pages.isNotEmpty()) item { TextButton(enabled = !loading, onClick = {
                                    val next = pages.first()
                                    val original = parsedInput
                                    loading = true
                                    scope.launch {
                                        try {
                                            val page = youtube.page(next)
                                            if (parsedInput == original && input == original) {
                                                media = (media + page.items).distinctBy { it.id }
                                                pages = pages.drop(1) + listOfNotNull(page.more)
                                            }
                                        } catch (e: CancellationException) { throw e }
                                        catch (e: Exception) { message = if (e is BiliException) e.message.orEmpty() else "无法加载播放列表，请重试。" }
                                        finally { loading = false }
                                    }
                                }) { Text("加载更多视频") } }
                                items(media.sortedBy { audioMode(it) }, key = { it.id }) { item -> BiliCard { Column {
                                    Cover(item.cover)
                                    Row(Modifier.padding(12.dp)) {
                                        BiliCheckbox(checked = item.id in selected, onCheckedChange = { selected = if(it) selected + item.id else selected - item.id })
                                        Column {
                                            Text(item.title, style = MaterialTheme.typography.titleMedium)
                                            if(item.partTitle.isNotBlank() && item.partTitle != item.title) Text(item.partTitle)
                                            if(item.id in selected && audioMode(item)) {
                                                val tracks = audioCache[item.id].orEmpty()
                                                if(tracks.isNotEmpty()) ChoiceMenu("音质",listOf("自动最高") + tracks.map { it.label },tracks.indexOfFirst { it.id == trackIds.getString(item.id) } + 1) { index ->
                                                    trackIds = android.os.Bundle(trackIds).apply { if(index == 0) remove(item.id) else putString(item.id,tracks[index-1].id) }
                                                }
                                                output(item)?.let { Text(it.summary) }
                                            } else if(item.id in selected) Text(if(sizeLoading) "正在估算…" else sizes[item.id]?.let { it.label + "  " + MediaSize.format(it.estimatedBytes) } ?: "没有兼容画质，可选择音频")
                                            val name = FileNaming.render(item,namingTemplate,customNames()[item.id],ext(item))
                                            Text(name); TextButton(onClick = { renameId = item.id; renameText = name.substringBeforeLast('.') }) { Text("修改文件名") }
                                        }
                                    }
                                } } }
                            }
                        }
                        1, 2 -> {
                            val list = entries.filter { activeTab == 1 || it.state == "Completed" }
                            item { Text(if(activeTab == 1) "下载队列" else "已完成", style = MaterialTheme.typography.headlineSmall) }
                            if(list.isEmpty()) item { Text("暂无下载任务") }
                            items(list, key = { it.id }) { entry -> BiliCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(entry.item.title, style = MaterialTheme.typography.titleMedium); if(entry.item.partTitle.isNotBlank() && entry.item.partTitle != entry.item.title) Text(entry.item.partTitle); (entry.finalFileName ?: entry.outputFileName)?.let { Text(it) }; LinearProgressIndicator(progress = { entry.progress }, modifier = Modifier.fillMaxWidth()); Text(entry.message)
                                if(entry.state == "Completed" && entry.audioOutput != null) TextButton(onClick = { runCatching { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(entry.uri),entry.audioOutput.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }.onFailure { message = "无法打开音频文件。" } }) { Text("播放音频") }
                                if(entry.state == "Completed") Button(onClick = { try { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(entry.audioOutput?.mime ?: "video/mp4").putExtra(Intent.EXTRA_STREAM, Uri.parse(entry.uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("file", Uri.parse(entry.uri)) }, if(entry.audioOutput == null) "分享视频" else "分享音频")) } catch(e: Exception) { message = "文件无法分享，请确认文件仍然存在。" } }) { Text(if(entry.audioOutput == null) "分享视频" else "分享音频") }
                                else Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if(entry.state == "Failed" && entry.failureKind in listOf("AudioComponent","AudioConnect")) TextButton(onClick = { manageAudio(entry.failureKind) }) { Text(if(entry.failureKind == "AudioConnect") "打开音频处理扩展" else "安装音频处理扩展") }
                                    if(entry.state == "Failed" && entry.failureKind == "TrackChanged") TextButton(onClick = { input = entry.item.youtubeId?.let { (if(entry.item.music) "https://music.youtube.com" else "https://www.youtube.com") + "/watch?v=" + it } ?: if(entry.item.episodeId > 0) "https://www.bilibili.com/" + (if(entry.item.kind == "course") "cheese/play/ep" else "bangumi/play/ep") + entry.item.episodeId else "https://www.bilibili.com/video/" + entry.item.bvid + "?p=" + entry.item.index; tab = 0; parseLinks(true) }) { Text("重新选择音轨") }

                                    if(entry.state == "Failed" && entry.failureKind in listOf("YoutubeComponent", "YoutubeSession", "YoutubeConnect")) TextButton(onClick = { manageYoutube(entry.failureKind) }) { Text(if(entry.failureKind == "YoutubeSession") "导入会话" else if(entry.failureKind == "YoutubeConnect") "打开扩展" else "安装扩展") }
                                    if(entry.state == "Failed" && AuthRecovery.needsLogin(entry.failureKind, BiliApplication.session.state)) TextButton(onClick = { launchLogin(entry.id) }) { Text("登录 B 站") }
                                    if(entry.state in listOf("Paused", "Failed")) TextButton(onClick = { runCatching { DownloadActions.schedule(this@MainActivity, entry) }.onFailure { message = "无法开始任务，请重试。" } }) { Text("继续 / 重试") }
                                    if(entry.state in listOf("Queued", "Downloading", "Merging")) TextButton(onClick = { scope.launch(Dispatchers.IO) { DownloadActions.pause(this@MainActivity, entry.id) } }) { Text("暂停") }
                                    if(entry.state != "Canceled") TextButton(onClick = { scope.launch(Dispatchers.IO) { DownloadActions.pause(this@MainActivity, entry.id, true) } }) { Text("取消") }
                                }
                                TextButton(onClick = { removeId = entry.id }) { Text("移除记录") }
                            } } }
                        }
                        3 -> {
                            item { Text("设置", style = MaterialTheme.typography.headlineSmall) }
                            item { Text("B 站账号", style = MaterialTheme.typography.titleMedium); Text(account.label) }
                            item { if(!account.loggedIn) Button(onClick = { launchLogin() }) { Text("登录 B 站") } else OutlinedButton(border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline), onClick = { BiliApplication.session.clear(); android.webkit.CookieManager.getInstance().removeAllCookies(null); message = "已退出登录" }) { Text("退出登录") } }
                            item { Text("YouTube", style = MaterialTheme.typography.titleMedium); Text("下载扩展：" + installation)
                                TextButton(onClick = { installExtension() }) { Text(if (installation == "已安装") "下载扩展更新" else "安装扩展") }
                                Row { TextButton(onClick = { manageYoutube("YoutubeSession") }) { Text(if (ytAccount.loggedIn) "替换 cookies.txt" else "导入 cookies.txt") }
                                    if (ytAccount.loggedIn) TextButton(onClick = { youtube.session.clear(); sizeCache.clear(); audioCache.clear(); detailAttempt++; message = "YouTube 会话已移除" }) { Text("移除会话") } }
                            }
                            item { Row(verticalAlignment = Alignment.CenterVertically) { BiliSwitch(checked = expand, onCheckedChange = { expand = it; prefs.edit().putBoolean("expandRelated", it).apply() }); Text("展开 YouTube 播放列表", Modifier.padding(12.dp)) } }
                            item { Row(verticalAlignment = Alignment.CenterVertically) { BiliSwitch(checked = detect, onCheckedChange = { detect = it; prefs.edit().putBoolean("detect", it).apply() }); Text("切回应用时识别剪贴板", Modifier.padding(12.dp)) } }
                            item { Text("音频处理", style = MaterialTheme.typography.titleMedium); TextButton(onClick = { manageAudio(null) }) { Text(if(audioProcessor.installed()) "下载扩展更新" else "安装音频处理扩展") } }
                            item { Text("文件命名", style = MaterialTheme.typography.titleMedium) }
                            item { BiliTextField(value = namingDraft, onValueChange = { namingDraft = it }, label = { Text("默认文件名模板") }, modifier = Modifier.fillMaxWidth()) }
                            item { TextButton(onClick = { showNamingVariables = !showNamingVariables }) { Text(if(showNamingVariables) "收起命名变量" else "插入命名变量") } }
                            if(showNamingVariables) item { Column { FileNaming.variables.chunked(3).forEach { row -> Row { row.forEach { variable -> TextButton(onClick = { namingDraft += "{$variable}" }) { Text(mapOf("name" to "默认名称", "title" to "标题", "part" to "分项标题", "index" to "序号", "bvid" to "BV号", "id" to "编号").getValue(variable)) } } } } } }
                            item {
                                val error = runCatching { FileNaming.validate(namingDraft) }.exceptionOrNull()?.message
                                Text(error ?: "预览：" + FileNaming.render(MediaItem("video:123", "示例视频", "第二部分", "video", 1, "BV1xx411c7mD", 123, 0, 2, partCount = 3), namingDraft))
                                Row { TextButton(enabled = error == null, onClick = { namingTemplate = namingDraft; prefs.edit().putString("filenameTemplate", namingTemplate).apply(); message = "文件名模板已保存" }) { Text("保存命名设置") }; TextButton(onClick = { namingDraft = FileNaming.DEFAULT }) { Text("恢复默认") } }
                            }
                            item { Text("保存位置：${directory ?: "视频 Movies/BiliDownload，音频 Music/BiliDownload"}") }
                            item { Row { OutlinedButton(border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline), onClick = { picker.launch(null) }) { Text("选择目录") }; TextButton(onClick = { directory = null; prefs.edit().remove("directory").apply() }) { Text("恢复默认") } } }
                            item { Row { listOf("Default" to "系统", "Light" to "浅色", "Dark" to "深色").forEach { (value,label) -> TextButton(onClick = { theme = value; prefs.edit().putString("theme", value).apply() }) { Text(label) } } } }
                            item { Text("BiliDownload  Android 0.3.0") }
                            item { TextButton(onClick = { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/WkJ01N/BiliDownload"))) }.onFailure { message = "无法打开浏览器，请访问 github.com/WkJ01N/BiliDownload。" } }) { Text("GitHub 仓库") } }
                        }
                    }
                }
                }
            }
        }
    }
}

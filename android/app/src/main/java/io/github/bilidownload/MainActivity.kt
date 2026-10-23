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
    private var detectRequested = false
    private var lastClipboard: String? = null
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); if(savedInstanceState != null) input = savedInstanceState.getString("input").orEmpty() else acceptIntent(intent); setContent { BiliScreen() } }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("input", input); super.onSaveInstanceState(outState) }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); acceptIntent(intent) }
    private fun acceptIntent(intent: Intent) {
        if(intent.action == Intent.ACTION_SEND) input = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        if(intent.action == "io.github.bilidownload.PASTE") detectRequested = true
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if(hasFocus && (detectRequested || getSharedPreferences("settings", MODE_PRIVATE).getBoolean("detect", false))) {
            detectRequested = false
            runCatching { val clipboard = getSystemService(ClipboardManager::class.java); val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
                if(text != lastClipboard && Links.extract(text).isNotEmpty()) { lastClipboard = text; input = text } }
        }
    }
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable private fun BiliScreen() {
        val prefs = remember { getSharedPreferences("settings", MODE_PRIVATE) }
        var theme by rememberSaveable { mutableStateOf(prefs.getString("theme", "Default")!!) }
        val dark = theme == "Dark" || theme == "Default" && isSystemInDarkTheme()
        MaterialTheme(colorScheme = if(dark) darkColorScheme() else lightColorScheme()) {
            var tab by rememberSaveable { mutableIntStateOf(0) }
            var message by rememberSaveable { mutableStateOf("") }
            var loading by remember { mutableStateOf(false) }
            val mediaSaver = Saver<List<MediaItem>, String>(save = { Gson().toJson(it) }, restore = { Gson().fromJson(it, object : TypeToken<List<MediaItem>>() {}.type) })
            val variantsSaver = Saver<List<Variant>, String>(save = { Gson().toJson(it) }, restore = { Gson().fromJson(it, object : TypeToken<List<Variant>>() {}.type) })
            var media by rememberSaveable(stateSaver = mediaSaver) { mutableStateOf(emptyList<MediaItem>()) }
            var variants by rememberSaveable(stateSaver = variantsSaver) { mutableStateOf(emptyList<Variant>()) }
            var selected by rememberSaveable(stateSaver = listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() })) { mutableStateOf(emptySet<String>()) }
            var quality by rememberSaveable { mutableIntStateOf(80) }
            var detect by remember { mutableStateOf(prefs.getBoolean("detect", false)) }
            var directory by remember { mutableStateOf(prefs.getString("directory", null)) }
            var namingTemplate by rememberSaveable { mutableStateOf(prefs.getString("filenameTemplate", FileNaming.DEFAULT) ?: FileNaming.DEFAULT) }
            var namingDraft by rememberSaveable { mutableStateOf(namingTemplate) }
            var customNamesJson by rememberSaveable { mutableStateOf("{}") }
            fun customNames(): Map<String, String> = Gson().fromJson(customNamesJson, object : TypeToken<Map<String, String>>() {}.type)
            val sizeCache = remember { mutableMapOf<String, List<Variant>>() }
            var sizes by remember { mutableStateOf(emptyMap<String, Variant?>()) }
            var sizeLoading by remember { mutableStateOf(false) }
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
            val scope = rememberCoroutineScope()
            val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
            val login = rememberLauncherForActivityResult(LoginActivity.Contract()) { result ->
                if(result.outcome == LoginOutcome.Success) failureKind = null
                AuthRecovery.loginMessage(result, pendingTask != null)?.let { message = it }
                pendingTask = null
            }
            fun launchLogin(taskId: String? = null) { pendingTask = taskId; login.launch(Unit) }
            fun parseLinks() {
                if(loading) return
                val requestInput = input
                val oldSelection = if(parsedInput == requestInput) selected else null
                val oldQuality = if(parsedInput == requestInput) quality else null
                scope.launch {
                    sizeCache.clear(); loading = true; message = ""; failureKind = null; retryParse = false
                    if(parsedInput != requestInput) { customNamesJson = "{}"; sizeCache.clear(); sizes = emptyMap(); media = emptyList(); variants = emptyList(); selected = emptySet() }
                    try {
                        val resolved = BiliApplication.api.resolve(requestInput)
                        media = resolved; parsedInput = requestInput
                        selected = oldSelection?.intersect(resolved.map { it.id }.toSet()) ?: setOf(resolved.first().id)
                        variants = BiliApplication.api.variants(resolved.first()); sizeCache[resolved.first().id] = variants
                        if(variants.isEmpty()) throw BiliException("没有可用的兼容画质。")
                        quality = variants.firstOrNull { it.quality == oldQuality }?.quality ?: variants.filter { it.quality <= 80 }.maxByOrNull { it.quality }?.quality ?: variants.first().quality
                    } catch(e: CancellationException) { throw e }
                    catch(e: Exception) { failureKind = (e as? BiliException)?.kind?.name; retryParse = true; message = if(e is BiliException) e.message.orEmpty() else "解析失败，请检查网络后重试。" }
                    finally { loading = false }
                }
            }
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                if(uri != null) { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION); directory = uri.toString(); prefs.edit().putString("directory", directory).apply() }
            }
            LaunchedEffect(Unit) { if(Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS) }
            LaunchedEffect(media, selected, quality, loading) {
                if(loading) return@LaunchedEffect
                sizeLoading = true
                val result = mutableMapOf<String, Variant?>()
                try {
                    for(item in media.filter { it.id in selected }) {
                        val available = try { sizeCache[item.id] ?: BiliApplication.api.variants(item).also { sizeCache[item.id] = it } }
                        catch(e: CancellationException) { throw e } catch(_: Exception) { emptyList() }
                        result[item.id] = available.filter { it.quality <= quality }.maxByOrNull { it.quality }
                    }
                    sizes = result
                } finally { sizeLoading = false }
            }
            removeId?.let { id -> AlertDialog(onDismissRequest = { removeId = null }, title = { Text("移除任务记录？") }, text = { Text("进行中的下载会停止，并清理临时缓存。已下载的视频文件会保留。") }, confirmButton = { TextButton(onClick = {
                removeId = null
                scope.launch { try { withContext(Dispatchers.IO) { DownloadActions.remove(this@MainActivity, id) } } catch(_: Exception) { message = "移除失败，请重试。" } }
            }) { Text("移除") } }, dismissButton = { TextButton(onClick = { removeId = null }) { Text("取消") } }) }
            renameId?.let { id ->
                fun finish(custom: String?) {
                    val updated = customNames().toMutableMap()
                    if(custom == null) updated.remove(id) else updated[id] = FileNaming.normalize(custom)
                    customNamesJson = Gson().toJson(updated); renameId = null
                }
                AlertDialog(onDismissRequest = { renameId = null }, title = { Text("修改文件名") }, text = { Column {
                    OutlinedTextField(value = renameText, onValueChange = { renameText = it }, label = { Text("文件名（自动添加 .mp4）") })
                    Text("实际文件名：" + FileNaming.normalize(renameText))
                } }, confirmButton = { TextButton(onClick = { finish(renameText) }) { Text("保存") } }, dismissButton = { Row {
                    TextButton(onClick = { finish(null) }) { Text("恢复默认") }; TextButton(onClick = { renameId = null }) { Text("取消") }
                } })
            }
            Scaffold(modifier = Modifier.fillMaxSize(), topBar = { TopAppBar(title = { Text("BiliDownload") }) }, bottomBar = {
                NavigationBar { listOf("解析", "下载", "已完成", "设置").forEachIndexed { i, text -> NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(painterResource(listOf(R.drawable.ic_link, R.drawable.ic_download, R.drawable.ic_complete, R.drawable.ic_settings)[i]), contentDescription = text) }, label = { Text(text) }) } }
            }) { padding ->
                AnimatedContent(targetState = tab, modifier = Modifier.padding(padding).fillMaxSize().clipToBounds(), transitionSpec = {
                    val direction = if(targetState > initialState) 1 else -1
                    (fadeIn(tween(220)) + slideInHorizontally(tween(220)) { direction * it / 12 }) togetherWith
                        (fadeOut(tween(160)) + slideOutHorizontally(tween(220)) { -direction * it / 12 })
                }, label = "pageTransition") { activeTab ->
                LazyColumn(Modifier.fillMaxSize(), state = listStates[activeTab], contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if(message.isNotBlank()) item { Card { Column(Modifier.padding(16.dp)) {
                        Text(message)
                        if(AuthRecovery.needsLogin(failureKind, BiliApplication.session.state)) TextButton(onClick = { launchLogin() }) { Text("登录 B 站") }
                        if(activeTab == 0 && retryParse) TextButton(enabled = !loading, onClick = { parseLinks() }) { Text("重新解析") }
                    } } }
                    when(activeTab) {
                        0 -> {
                            item { Text("链接解析", style = MaterialTheme.typography.headlineSmall) }
                            item { OutlinedTextField(value = input, onValueChange = { input = it }, label = { Text("B 站链接或分享文案") }, minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth()) }
                            item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(enabled = !loading, onClick = { parseLinks() }) { Text(if(loading) "解析中…" else "解析链接") }
                                OutlinedButton(onClick = { runCatching { input = getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString().orEmpty() } }) { Text("粘贴") }
                            } }
                            if(media.isNotEmpty()) {
                                item { Text("找到 ${media.size} 个条目，勾选后下载") }
                                item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { TextButton(onClick = { selected = media.map { it.id }.toSet() }) { Text("全选") }; TextButton(onClick = { selected = emptySet() }) { Text("取消全选") } } }
                                item { Box { OutlinedButton(onClick = { qualityOpen = true }) { Text("画质上限：${BiliApi.label(quality)}") }; DropdownMenu(expanded = qualityOpen, onDismissRequest = { qualityOpen = false }) { variants.forEach { v -> DropdownMenuItem(text = { Text(v.label + " · " + MediaSize.format(v.estimatedBytes)) }, onClick = { quality = v.quality; qualityOpen = false }) } } } }
                                item { Text(if(sizeLoading) "正在估算大小…" else "选中 ${selected.size} 项：" + MediaSize.format(sizes.values.mapNotNull { it?.estimatedBytes }.takeIf { it.isNotEmpty() }?.sum()) + (if(sizes.values.any { it?.estimatedBytes == null }) "，部分大小未知" else "") + "。大小为音视频估算值，最终文件可能略有差异。") }
                                item { Button(enabled = selected.isNotEmpty() && variants.isNotEmpty() && !loading && !retryParse, onClick = {
                                    try {
                                        media.filter { it.id in selected }.forEach { item ->
                                            if(entries.none { it.item.id == item.id && it.quality == quality && it.state !in listOf("Failed", "Canceled") }) DownloadActions.schedule(this@MainActivity, DownloadEntry(UUID.randomUUID().toString(), item, quality, outputFileName = FileNaming.render(item, namingTemplate, customNames()[item.id])))
                                        }; message = "已加入下载队列"; tab = 1
                                    } catch(e: Exception) { message = if(e is BiliException) e.message.orEmpty() else "系统暂时无法开始下载，请保持应用可见后重试。" }
                                }) { Text("下载选中内容") } }
                                items(media, key = { it.id }) { item -> Card { Column { Cover(item.cover); Row(Modifier.padding(12.dp)) { Checkbox(checked = item.id in selected, onCheckedChange = { selected = if(it) selected + item.id else selected - item.id }); Column { Text(item.title, style = MaterialTheme.typography.titleMedium); Text(item.partTitle); Text(if(item.id !in selected) "选择后估算大小" else if(sizeLoading) "正在估算…" else (sizes[item.id]?.let { it.label + " · " + MediaSize.format(it.estimatedBytes) } ?: "大小未知，可重试解析")); Text(FileNaming.render(item, namingTemplate, customNames()[item.id])); TextButton(onClick = { renameId = item.id; renameText = FileNaming.render(item, namingTemplate, customNames()[item.id]).removeSuffix(".mp4") }) { Text("修改文件名") } } } } } }
                            }
                        }
                        1, 2 -> {
                            val list = entries.filter { activeTab == 1 || it.state == "Completed" }
                            item { Text(if(activeTab == 1) "下载队列" else "已完成", style = MaterialTheme.typography.headlineSmall) }
                            if(list.isEmpty()) item { Text("还没有任务，先去解析一个链接吧。") }
                            items(list, key = { it.id }) { entry -> Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(entry.item.title, style = MaterialTheme.typography.titleMedium); Text(entry.item.partTitle); (entry.finalFileName ?: entry.outputFileName)?.let { Text(it) }; LinearProgressIndicator(progress = { entry.progress }, modifier = Modifier.fillMaxWidth()); Text(entry.message)
                                if(entry.state == "Completed") Button(onClick = { try { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, Uri.parse(entry.uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("video", Uri.parse(entry.uri)) }, "分享视频")) } catch(e: Exception) { message = "文件无法分享，请确认文件仍然存在。" } }) { Text("分享视频") }
                                else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                            item { Text("账号凭据在本机加密保存") }
                            item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { Button(onClick = { launchLogin() }) { Text("登录 B 站") }; OutlinedButton(onClick = { BiliApplication.session.clear(); android.webkit.CookieManager.getInstance().removeAllCookies(null); message = "已退出登录" }) { Text("退出登录") } } }
                            item { Row { Switch(checked = detect, onCheckedChange = { detect = it; prefs.edit().putBoolean("detect", it).apply() }); Text("切回应用时识别剪贴板", Modifier.padding(12.dp)) } }
                            item { Text("可从 B 站分享给本应用，也可添加“识别 B 站链接”快捷设置磁贴。后台读取剪贴板受 Android 系统限制。") }
                            item { Text("文件命名", style = MaterialTheme.typography.titleMedium) }
                            item { OutlinedTextField(value = namingDraft, onValueChange = { namingDraft = it }, label = { Text("默认文件名模板") }, modifier = Modifier.fillMaxWidth()) }
                            item { Text("变量：name 默认名称；title 视频标题；part 分项标题；index 序号；bvid BV号；id 条目编号。扩展名 .mp4 自动添加。") }
                            item { Column { FileNaming.variables.chunked(3).forEach { row -> Row { row.forEach { variable -> TextButton(onClick = { namingDraft += "{$variable}" }) { Text("{$variable}") } } } } } }
                            item {
                                val error = runCatching { FileNaming.validate(namingDraft) }.exceptionOrNull()?.message
                                Text(error ?: "预览：" + FileNaming.render(MediaItem("video:123", "示例视频", "第二部分", "video", 1, "BV1xx411c7mD", 123, 0, 2, partCount = 3), namingDraft))
                                Row { TextButton(enabled = error == null, onClick = { namingTemplate = namingDraft; prefs.edit().putString("filenameTemplate", namingTemplate).apply(); message = "文件名模板已保存" }) { Text("保存命名设置") }; TextButton(onClick = { namingDraft = FileNaming.DEFAULT }) { Text("恢复默认") } }
                            }
                            item { Text("保存位置：${directory ?: "Movies/BiliDownload"}") }
                            item { Row { OutlinedButton(onClick = { picker.launch(null) }) { Text("选择目录") }; TextButton(onClick = { directory = null; prefs.edit().remove("directory").apply() }) { Text("恢复默认") } } }
                            item { Row { listOf("Default" to "系统", "Light" to "浅色", "Dark" to "深色").forEach { (value,label) -> TextButton(onClick = { theme = value; prefs.edit().putString("theme", value).apply() }) { Text(label) } } } }
                            item { Text("BiliDownload · Android 预览版 0.1.5") }
                            item { TextButton(onClick = { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/WkJ01N/BiliDownload"))) }.onFailure { message = "无法打开浏览器，请访问 github.com/WkJ01N/BiliDownload。" } }) { Text("GitHub 仓库 · WkJ01N/BiliDownload") } }
                        }
                    }
                }
                }
            }
        }
    }
}

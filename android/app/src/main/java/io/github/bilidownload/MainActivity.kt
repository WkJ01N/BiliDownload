package io.github.bilidownload

import android.Manifest
import android.content.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.gson.reflect.TypeToken
import io.github.bilidownload.ui.components.SaltAlertBanner
import io.github.bilidownload.ui.components.SaltBatchActionBar
import io.github.bilidownload.ui.components.SaltCheckbox
import io.github.bilidownload.ui.components.SaltFloatingNavBar
import io.github.bilidownload.ui.components.SaltTextField
import io.github.bilidownload.ui.screens.HomeScreen
import io.github.bilidownload.ui.screens.QueueScreen
import io.github.bilidownload.ui.screens.SettingsScreen
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.UUID

class MainActivity : ComponentActivity() {
    private var input by mutableStateOf("")
    private var incoming by mutableIntStateOf(0)
    private var detectRequested = false
    private var lastClipboard: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState != null) input = savedInstanceState.getString("input").orEmpty()
        else acceptIntent(intent)
        setContent { BiliScreen() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("input", input)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptIntent(intent)
    }

    private fun acceptIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_SEND) {
            input = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            incoming++
        }
        if (intent.action == "io.github.bilidownload.PASTE") detectRequested = true
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && (detectRequested || getSharedPreferences("settings", MODE_PRIVATE).getBoolean("detect", false))) {
            detectRequested = false
            runCatching {
                val clipboard = getSystemService(ClipboardManager::class.java)
                val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
                if (text != lastClipboard && Links.supported(text).isNotEmpty()) {
                    lastClipboard = text
                    input = text
                }
            }
        }
    }

    @Composable
    private fun BiliScreen() {
        val prefs = remember { getSharedPreferences("settings", MODE_PRIVATE) }
        var theme by rememberSaveable { mutableStateOf(prefs.getString("theme", "Default")!!) }
        var accent by rememberSaveable { mutableStateOf(prefs.getString("accent", "Pink") ?: "Pink") }
        var listDensity by rememberSaveable { mutableStateOf(prefs.getString("listDensity", "Standard") ?: "Standard") }
        var pageAnimStyle by rememberSaveable { mutableStateOf(prefs.getString("pageAnimStyle", "SharedAxis") ?: "SharedAxis") }
        var pageAnimDurationMs by rememberSaveable { mutableIntStateOf(prefs.getInt("pageAnimDurationMs", 340).coerceIn(200, 520)) }
        var pageAnimDamping by rememberSaveable { mutableFloatStateOf(prefs.getFloat("pageAnimDamping", 0.90f).coerceIn(0.72f, 1.0f)) }
        var pageAnimIntensity by rememberSaveable { mutableFloatStateOf(prefs.getFloat("pageAnimIntensity", 1.0f).coerceIn(0.5f, 1.6f)) }
        var navFollowSpeed by rememberSaveable { mutableFloatStateOf(prefs.getFloat("navFollowSpeed", 22.5f).coerceIn(14f, 34f)) }
        var navBounciness by rememberSaveable { mutableFloatStateOf(prefs.getFloat("navBounciness", 0.20f).coerceIn(0f, 1f)) }
        var navLiquidDeform by rememberSaveable { mutableFloatStateOf(prefs.getFloat("navLiquidDeform", 1.15f).coerceIn(0.3f, 2.0f)) }
        var navDockPressScale by rememberSaveable { mutableFloatStateOf(prefs.getFloat("navDockPressScale", 1.04f).coerceIn(1.00f, 1.08f)) }
        val dark = theme == "Dark" || theme == "Default" && isSystemInDarkTheme()

        DisposableEffect(dark) {
            enableEdgeToEdge(
                statusBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
                navigationBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            )
            onDispose {}
        }

        BiliTheme(dark = dark, accent = accent) {
            val scope = rememberCoroutineScope()
            var currentTab by rememberSaveable { mutableIntStateOf(0) }
            var settingsSubPage by rememberSaveable { mutableStateOf("Overview") }
            var previousTab by remember { mutableIntStateOf(0) }
            val mountedTabs = remember { mutableStateMapOf<Int, Boolean>() }
            mountedTabs[currentTab] = true
            val pageTransition = remember { Animatable(1f) }
            var transitionRunning by remember { mutableStateOf(false) }
            var navJob by remember { mutableStateOf<Job?>(null) }

            fun navigateToTab(target: Int) {
                if (target == currentTab) return
                if (target != 3) settingsSubPage = "Overview"
                mountedTabs[target] = true
                previousTab = currentTab
                currentTab = target
                navJob?.cancel()
                val norm = ((pageAnimDurationMs - 200f) / 320f).coerceIn(0f, 1f)
                val stiffness = 520f + (140f - 520f) * norm
                val animSpec = spring<Float>(
                    dampingRatio = pageAnimDamping.coerceIn(0.72f, 1.0f),
                    stiffness = stiffness,
                )
                navJob = scope.launch {
                    transitionRunning = true
                    try {
                        pageTransition.snapTo(0f)
                        pageTransition.animateTo(1f, animationSpec = animSpec)
                    } finally {
                        transitionRunning = false
                    }
                }
            }

            LaunchedEffect(incoming) { if (incoming > 0) navigateToTab(0) }

            val youtube = BiliApplication.youtube
            val ytAccount by youtube.session.account.collectAsStateWithLifecycle()
            val xAccount by youtube.xSession.account.collectAsStateWithLifecycle()
            var xGrouped by remember { mutableStateOf(prefs.getBoolean("xGrouped", true)) }
            var gifIds by rememberSaveable(stateSaver = listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() })) { mutableStateOf(emptySet<String>()) }
            val components = BiliApplication.components
            val componentState by components.state.collectAsStateWithLifecycle()
            var legacyComponents by remember { mutableStateOf(emptyList<LegacyComponent>()) }
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    legacyComponents = withContext(Dispatchers.IO) {
                        LegacyComponent.entries.filter { it.installed(this@MainActivity) }
                    }
                }
            }

            var pendingComponent by rememberSaveable { mutableStateOf<Bundle?>(null) }
            fun requestComponent(operation: String, taskId: String? = null, preferAudio: Boolean = false) {
                pendingComponent = Bundle().apply {
                    putString("operation", operation)
                    putString("input", input)
                    putString("task", taskId)
                    putBoolean("audio", preferAudio)
                }
            }

            var expand by remember { mutableStateOf(prefs.getBoolean("expandRelated", false)) }
            val pagesSaver = listSaver<List<PlaylistPage>, String>(
                save = { list -> list.map { it.id + ":" + it.start + ":" + it.music } },
                restore = { list -> list.map { it.split(':').let { p -> PlaylistPage(p[0], p[1].toInt(), p.getOrNull(2).toBoolean()) } } },
            )
            var pages by rememberSaveable(stateSaver = pagesSaver) { mutableStateOf(emptyList<PlaylistPage>()) }
            var message by rememberSaveable { mutableStateOf("") }
            var messageIsError by rememberSaveable { mutableStateOf(false) }
            var loading by remember { mutableStateOf(false) }
            val mediaSaver = Saver<List<MediaItem>, String>(
                save = { BiliApplication.gson.toJson(it) },
                restore = { BiliApplication.gson.fromJson(it, object : TypeToken<List<MediaItem>>() {}.type) },
            )
            var media by rememberSaveable(stateSaver = mediaSaver) { mutableStateOf(emptyList<MediaItem>()) }
            var selected by rememberSaveable(stateSaver = listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() })) { mutableStateOf(emptySet<String>()) }
            var mode by rememberSaveable { mutableIntStateOf(0) }
            var mp3 by rememberSaveable { mutableStateOf(false) }
            var bitrate by rememberSaveable { mutableIntStateOf(320) }
            var trackIds by rememberSaveable { mutableStateOf(Bundle()) }
            val audioCache = remember { mutableStateMapOf<String, List<AudioTrack>>() }
            val sizeCache = remember { mutableStateMapOf<String, List<Variant>>() }

            fun audioMode(item: MediaItem) = (item.x == null || item.x.audio && item.x.kind == "video") && (mode == 2 || mode == 0 && item.music)
            fun videoMode(item: MediaItem) = (item.x == null || item.x.video) && !audioMode(item)
            fun output(item: MediaItem): AudioOutput? {
                if (!audioMode(item)) return null
                val tracks = audioCache[item.id] ?: return null
                val actual = tracks.firstOrNull { it.id == trackIds.getString(item.id) } ?: tracks.firstOrNull() ?: return null
                return AudioOutput(actual, if (mp3) bitrate else 0)
            }
            fun ext(item: MediaItem) = if (audioMode(item)) output(item)?.extension ?: if (mp3) ".mp3" else ".m4a"
            else if (item.x?.kind == "gif" && item.id in gifIds) ".gif" else item.x?.extension ?: ".mp4"

            var preferredCap by rememberSaveable { mutableStateOf<Int?>(null) }
            val reference = media.firstOrNull { it.id in selected && videoMode(it) }
            val variants = reference?.let { sizeCache[it.id] }.orEmpty()
            val quality = preferredQuality(variants, preferredCap)
            val sizeLimit = variants.firstOrNull { it.quality == quality }
            val sizes = if (sizeLimit == null) emptyMap() else media.asSequence()
                .filter { it.id in selected && videoMode(it) }
                .associate { item -> item.id to sizeCache[item.id]?.let { atMost(it, sizeLimit, item) } }

            var detect by remember { mutableStateOf(prefs.getBoolean("detect", false)) }
            var directory by remember { mutableStateOf(prefs.getString("directory", null)) }
            var namingTemplate by rememberSaveable { mutableStateOf(prefs.getString("filenameTemplate", FileNaming.DEFAULT) ?: FileNaming.DEFAULT) }
            var namingDraft by rememberSaveable { mutableStateOf(namingTemplate) }
            var customNamesJson by rememberSaveable { mutableStateOf("{}") }
            fun customNames(): Map<String, String> = BiliApplication.gson.fromJson(customNamesJson, object : TypeToken<Map<String, String>>() {}.type)

            var sizeLoading by remember { mutableStateOf(false) }
            var detailRetry by remember { mutableStateOf(false) }
            var detailAttempt by remember { mutableIntStateOf(0) }
            var removeIds by rememberSaveable(stateSaver = listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() })) { mutableStateOf(emptySet()) }
            var deleteLocalFiles by remember { mutableStateOf(false) }
            fun askRemove(ids: Set<String>) {
                deleteLocalFiles = false
                removeIds = ids
            }
            var renameId by rememberSaveable { mutableStateOf<String?>(null) }
            var renameText by rememberSaveable { mutableStateOf("") }
            var failureKind by rememberSaveable { mutableStateOf<String?>(null) }
            var retryParse by rememberSaveable { mutableStateOf(false) }
            var parsedInput by rememberSaveable { mutableStateOf<String?>(null) }
            var pendingTask by rememberSaveable { mutableStateOf<String?>(null) }
            var pendingPlatform by rememberSaveable { mutableStateOf(LoginPlatform.Bilibili) }

            val listStates = listOf(rememberLazyListState(), rememberLazyListState(), rememberLazyListState(), rememberLazyListState())
            val entries by BiliApplication.store.entries.collectAsStateWithLifecycle()
            val account by BiliApplication.session.account.collectAsStateWithLifecycle()
            val activeDownloadCount = remember(entries) {
                entries.count { it.state == "Queued" || it.state == "Downloading" || it.state == "Merging" }
            }
            val completedEntries = remember(entries) {
                entries.filter { it.state == "Completed" }
            }

            var completedSelectionMode by rememberSaveable { mutableStateOf(false) }
            var selectedCompleted by rememberSaveable(stateSaver = listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() })) { mutableStateOf(emptySet()) }

            LaunchedEffect(currentTab, completedEntries) {
                val validIds = completedEntries.mapTo(mutableSetOf()) { it.id }
                selectedCompleted = selectedCompleted.intersect(validIds)
                if (currentTab != 2 || completedEntries.isEmpty()) {
                    completedSelectionMode = false
                    if (completedEntries.isEmpty()) selectedCompleted = emptySet()
                }
            }

            LaunchedEffect(account.state) {
                if (account.state == SessionState.Saved) {
                    delay(450L)
                    try { BiliApplication.api.api("https://api.bilibili.com/x/web-interface/nav") }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { }
                }
            }

            var importingX by rememberSaveable { mutableStateOf(false) }
            val cookiePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) scope.launch {
                    try {
                        youtube.import(uri, importingX)
                        sizeCache.clear(); audioCache.clear(); detailAttempt++
                        message = "会话已导入，可返回重试。"
                        messageIsError = false
                    } catch (e: Exception) {
                        message = if (e is BiliException) e.message.orEmpty() else "无法导入会话文件。"
                        messageIsError = true
                    }
                }
            }

            fun importCookieFile(isX: Boolean) {
                importingX = isX
                cookiePicker.launch(arrayOf("text/*", "application/octet-stream"))
            }

            fun retryTask(entry: DownloadEntry) {
                val needsParser = entry.item.youtubeId != null || entry.item.x?.let { it.document == null } == true
                if (needsParser && !components.state.value.ready) requestComponent("task", entry.id)
                else runCatching { DownloadActions.schedule(this@MainActivity, entry) }.onFailure {
                    message = "无法开始任务，请重试。"
                    messageIsError = true
                }
            }

            val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
            val login = rememberLauncherForActivityResult(LoginActivity.Contract()) { result ->
                if (result.outcome == LoginOutcome.Success) {
                    failureKind = null; sizeCache.clear(); audioCache.clear(); detailAttempt++
                }
                when (pendingPlatform) {
                    LoginPlatform.YouTube -> when (result.outcome) {
                        LoginOutcome.Success -> { message = "YouTube 登录成功，可返回重试。"; messageIsError = false }
                        LoginOutcome.Failed -> { message = result.message ?: "YouTube 登录失败，请重试。"; messageIsError = true }
                        LoginOutcome.Canceled -> Unit
                    }
                    LoginPlatform.X -> when (result.outcome) {
                        LoginOutcome.Success -> { message = "X 登录成功，可返回重试。"; messageIsError = false }
                        LoginOutcome.Failed -> { message = result.message ?: "X 登录失败，请重试。"; messageIsError = true }
                        LoginOutcome.Canceled -> Unit
                    }
                    LoginPlatform.Bilibili -> AuthRecovery.loginMessage(result, pendingTask != null)?.let {
                        message = it
                        messageIsError = result.outcome == LoginOutcome.Failed
                    }
                }
                pendingTask = null
            }

            fun launchLogin(platform: LoginPlatform = LoginPlatform.Bilibili, taskId: String? = null) {
                pendingPlatform = platform
                pendingTask = taskId
                login.launch(platform)
            }

            fun parseLinks(preferAudio: Boolean = false) {
                if (loading) return
                val requestInput = input
                val links = Links.supported(requestInput)
                val sameInput = parsedInput == requestInput
                val oldSelection = selected.takeIf { sameInput }
                val previousItems = media.filter { sameInput && it.playlistId != null }
                val previousPages = pages.takeIf { sameInput }.orEmpty()
                loading = true
                scope.launch {
                    try {
                        val needsComponent = links.any { YoutubeLink.parse(it) != null || XLink.parse(it) != null || XLink.short(it) }
                        if (needsComponent && !components.state.first { !it.checking }.ready) {
                            if (input == requestInput) requestComponent("parse", preferAudio = preferAudio)
                            return@launch
                        }
                        message = ""; messageIsError = false; failureKind = null; retryParse = false
                        if (!sameInput) {
                            pages = emptyList(); media = emptyList(); selected = emptySet()
                            gifIds = emptySet(); customNamesJson = "{}"; preferredCap = null
                            sizeCache.clear(); audioCache.clear()
                        }
                        val catalogs = links.mapMetadata { url ->
                            if (XLink.short(url) || XLink.parse(url) != null) youtube.xPost(XLink.parse(url) ?: XLink.expand(url))
                            else YoutubeLink.parse(url)?.let { link ->
                                val catalog = youtube.resolve(link, expand)
                                val listId = link.playlist
                                if (listId == null || link.video != null && !expand) catalog
                                else {
                                    val retained = previousItems.filter { it.playlistId == listId && it.music == link.music }
                                    val indexedIds = retained.mapTo(mutableSetOf()) { it.id }
                                    catalog.copy(
                                        items = (catalog.items.filterNot { it.playlistId == null && it.id in indexedIds } + retained).distinctBy { it.id },
                                        more = previousPages.firstOrNull { it.id == listId && it.music == link.music } ?: catalog.more,
                                    )
                                }
                            } ?: run {
                                var target: String? = null
                                val items = BiliApplication.api.resolve(url) { target = it }
                                YoutubeCatalog(items, target)
                            }
                        }
                        if (input != requestInput) return@launch
                        val resolved = catalogs.flatMap { it.items }.distinctBy { it.id }.sortedBy { it.music }
                        if (resolved.isEmpty()) throw BiliException("没有找到可下载的内容。")
                        sizeCache.clear(); audioCache.clear()
                        catalogs.forEach { catalog ->
                            catalog.target?.let { id ->
                                catalog.variants?.let { sizeCache[id] = it }
                                catalog.audio?.let { audioCache[id] = it }
                            }
                        }
                        if (!sameInput) {
                            mode = if (resolved.all { it.music }) 2 else if (resolved.none { it.music }) 1 else 0
                            mp3 = false; bitrate = 320; trackIds = Bundle()
                        }
                        if (preferAudio) mode = 2
                        pages = catalogs.mapNotNull { it.more }; media = resolved; parsedInput = requestInput
                        selected = oldSelection?.intersect(resolved.filter { it.x?.available != false }.map { it.id }.toSet())
                            ?: catalogs.flatMap { it.defaults }.toSet()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (input != requestInput) return@launch
                        failureKind = (e as? BiliException)?.kind?.name
                        if (!components.state.value.ready && failureKind in listOf("YoutubeComponent", "XComponent")) {
                            requestComponent("parse", preferAudio = preferAudio)
                        } else {
                            retryParse = true
                            messageIsError = true
                            message = if (e is BiliException) e.message.orEmpty() else "解析失败，请检查网络后重试。"
                        }
                    } finally {
                        loading = false
                    }
                }
            }

            LaunchedEffect(componentState.ready, pendingComponent) {
                val pending = pendingComponent ?: return@LaunchedEffect
                if (!componentState.ready) return@LaunchedEffect
                pendingComponent = null
                when (pending.getString("operation")) {
                    "task" -> pending.getString("task")?.let(BiliApplication.store::get)
                        ?.takeIf { it.state == "Failed" || it.state == "Paused" }?.let(::retryTask)
                    "parse" -> if (input == pending.getString("input")) parseLinks(pending.getBoolean("audio"))
                    "details" -> if (input == pending.getString("input") && parsedInput == input) {
                        failureKind = null; message = ""; messageIsError = false; detailAttempt++
                    }
                }
            }

            if (pendingComponent != null && !componentState.ready && !componentState.checking) {
                MediaComponentDialog(
                    componentState,
                    components.downloadBytes,
                    onInstall = { components.startInstall() },
                    onDismiss = {
                        pendingComponent = null
                        if (components.state.value.downloading) components.cancelInstall()
                    },
                )
            }

            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                if (uri != null) {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    directory = uri.toString()
                    prefs.edit().putString("directory", directory).apply()
                }
            }

            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                // Stagger background composition of remaining tabs after frame 0 so startup stays at full framerate
                for (idx in 0..3) {
                    if (mountedTabs[idx] != true) {
                        delay(350L)
                        mountedTabs[idx] = true
                    }
                }
            }
            LaunchedEffect(componentState.ready) {
                if (componentState.ready) {
                    delay(500L)
                    youtube.warmUp()
                }
            }
            LaunchedEffect(media, selected, loading, detailAttempt, mode, parsedInput, input) {
                if (loading || retryParse || parsedInput != input) return@LaunchedEffect
                val requested = media.filter { it.id in selected }.mapNotNull { item ->
                    when {
                        audioMode(item) && item.id !in audioCache -> item to true
                        videoMode(item) && item.id !in sizeCache -> item to false
                        else -> null
                    }
                }
                detailRetry = false
                if (requested.isEmpty()) return@LaunchedEffect
                sizeLoading = true
                try {
                    val failures = requested.mapMetadata { (item, audio) ->
                        try {
                            if (audio) {
                                val tracks = youtube.audio(item)
                                if (tracks.isEmpty()) throw BiliException("当前没有可用音轨。")
                                audioCache[item.id] = tracks
                            } else {
                                sizeCache[item.id] = youtube.variants(item)
                            }
                            null
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            item to e
                        }
                    }
                    failures.filterNotNull().firstOrNull()?.let { (item, e) ->
                        detailRetry = true
                        failureKind = (e as? BiliException)?.kind?.name
                        messageIsError = true
                        message = item.title + "：" + (if (e is BiliException) e.message.orEmpty() else "无法获取格式，请重试。")
                    }
                } finally {
                    sizeLoading = false
                }
            }

            if (removeIds.isNotEmpty()) {
                val targetIds = removeIds
                val completedTargets = entries.filter { it.id in targetIds && it.state == "Completed" && !it.uri.isNullOrBlank() }
                AlertDialog(
                    onDismissRequest = { removeIds = emptySet() },
                    shape = RoundedCornerShape(20.dp),
                    containerColor = saltColors().card,
                    title = { Text(if (targetIds.size > 1) "移除 ${targetIds.size} 条记录？" else "移除任务记录？") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                if (completedTargets.isNotEmpty()) "将移除选中的下载记录。"
                                else "进行中的下载会停止，并清理临时缓存。已下载的文件会保留。",
                            )
                            if (completedTargets.isNotEmpty()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { deleteLocalFiles = !deleteLocalFiles }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    SaltCheckbox(
                                        checked = deleteLocalFiles,
                                        onCheckedChange = { deleteLocalFiles = it },
                                    )
                                    Text(
                                        text = if (completedTargets.size > 1) "同时删除已下载的本地文件（${completedTargets.size} 个）" else "同时删除已下载的本地文件",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val alsoDeleteFiles = deleteLocalFiles && completedTargets.isNotEmpty()
                            removeIds = emptySet()
                            scope.launch {
                                try {
                                    val failedDeletes = withContext(Dispatchers.IO) {
                                        var missed = 0
                                        for (id in targetIds) {
                                            if (!DownloadActions.remove(this@MainActivity, id, alsoDeleteFiles)) {
                                                missed++
                                            }
                                        }
                                        missed
                                    }
                                    if (targetIds.size > 1 || completedSelectionMode) {
                                        selectedCompleted = selectedCompleted - targetIds
                                        if (selectedCompleted.isEmpty()) completedSelectionMode = false
                                    }
                                    if (alsoDeleteFiles && failedDeletes > 0) {
                                        message = "记录已移除，有 $failedDeletes 个本地文件未能删除或已不存在。"
                                        messageIsError = true
                                    }
                                } catch (_: Exception) {
                                    message = "移除失败，请重试。"
                                    messageIsError = true
                                }
                            }
                        }) { Text("移除") }
                    },
                    dismissButton = { TextButton(onClick = { removeIds = emptySet() }) { Text("取消") } },
                )
            }

            renameId?.let { id ->
                fun finish(custom: String?) {
                    val updated = customNames().toMutableMap()
                    if (custom == null) updated.remove(id)
                    else updated[id] = FileNaming.normalize(custom, ext(media.first { it.id == id })).substringBeforeLast('.')
                    customNamesJson = BiliApplication.gson.toJson(updated)
                    renameId = null
                }
                AlertDialog(
                    onDismissRequest = { renameId = null },
                    shape = RoundedCornerShape(20.dp),
                    containerColor = saltColors().card,
                    title = { Text("修改文件名") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SaltTextField(
                                value = renameText,
                                onValueChange = { renameText = it },
                                label = "文件名（自动添加 " + ext(media.first { it.id == id }) + "）",
                                placeholder = "输入文件名",
                            )
                            Text(
                                text = "实际文件名：" + FileNaming.normalize(renameText, ext(media.first { it.id == id })),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    confirmButton = { TextButton(onClick = { finish(renameText) }) { Text("保存") } },
                    dismissButton = {
                        Row {
                            TextButton(onClick = { finish(null) }) { Text("恢复默认") }
                            TextButton(onClick = { renameId = null }) { Text("取消") }
                        }
                    },
                )
            }

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                        if (message.isNotBlank()) {
                            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                                SaltAlertBanner(
                                    message = message,
                                    isError = messageIsError,
                                    onDismiss = { message = ""; messageIsError = false },
                                ) {
                                    if (failureKind == "XSession") {
                                        TextButton(onClick = { launchLogin(LoginPlatform.X) }) { Text("登录 X") }
                                    } else if (failureKind == "YoutubeSession") {
                                        TextButton(onClick = { launchLogin(LoginPlatform.YouTube) }) { Text("登录 YouTube") }
                                    }
                                    if (AuthRecovery.needsLogin(failureKind, BiliApplication.session.state)) {
                                        TextButton(onClick = { launchLogin(LoginPlatform.Bilibili) }) { Text("登录 B 站") }
                                    }
                                    if (currentTab == 0 && detailRetry && !retryParse) {
                                        TextButton(enabled = !sizeLoading, onClick = {
                                            if (!componentState.ready && failureKind in listOf("XComponent", "YoutubeComponent", "YoutubeConnect")) {
                                                requestComponent("details")
                                            } else {
                                                detailAttempt++; message = ""; messageIsError = false
                                            }
                                        }) { Text("重新获取画质") }
                                    }
                                    if (currentTab == 0 && retryParse) {
                                        TextButton(enabled = !loading, onClick = { parseLinks() }) { Text("重新解析") }
                                    }
                                }
                            }
                        }

                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            fun smoothStep(x: Float): Float {
                                val t = x.coerceIn(0f, 1f)
                                return t * t * (3f - 2f * t)
                            }
                            for (activeTab in 0..3) {
                                key(activeTab) {
                                    val isActive = activeTab == currentTab
                                    val isLeaving = activeTab == previousTab && activeTab != currentTab
                                    val isMounted = mountedTabs[activeTab] == true || isActive || isLeaving
                                    if (isMounted) {
                                        Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .zIndex(if (isActive) 1f else 0f)
                                            .drawWithContent {
                                                if (isActive || (isLeaving && transitionRunning)) {
                                                    drawContent()
                                                }
                                            }
                                            .graphicsLayer {
                                                val p = pageTransition.value
                                                val pClamped = p.coerceIn(0f, 1f)
                                                val dir = if (currentTab >= previousTab) 1f else -1f
                                                when (pageAnimStyle) {
                                                    "Parallax" -> {
                                                        val inShift = size.width * 0.38f * pageAnimIntensity
                                                        val outShift = size.width * 0.14f * pageAnimIntensity
                                                        when {
                                                            isActive -> {
                                                                alpha = smoothStep(p / 0.35f)
                                                                translationX = dir * inShift * (1f - p)
                                                                scaleX = 1f
                                                                scaleY = 1f
                                                            }
                                                            isLeaving -> {
                                                                alpha = 1f - smoothStep(p / 0.85f) * 0.85f
                                                                translationX = -dir * outShift * pClamped
                                                                val s = 1f - 0.035f * pageAnimIntensity * pClamped
                                                                scaleX = s
                                                                scaleY = s
                                                            }
                                                            else -> {
                                                                alpha = 0f
                                                            }
                                                        }
                                                    }
                                                    "BreathFade" -> {
                                                        val depth = 0.032f * pageAnimIntensity
                                                        when {
                                                            isActive -> {
                                                                alpha = smoothStep((p - 0.10f) / 0.82f)
                                                                translationX = 0f
                                                                val s = 1f - depth * (1f - p)
                                                                scaleX = s
                                                                scaleY = s
                                                            }
                                                            isLeaving -> {
                                                                alpha = 1f - smoothStep(p / 0.55f)
                                                                translationX = 0f
                                                                val s = 1f - depth * pClamped
                                                                scaleX = s
                                                                scaleY = s
                                                            }
                                                            else -> {
                                                                alpha = 0f
                                                            }
                                                        }
                                                    }
                                                    else -> {
                                                        // SharedAxis (default): Material 3 Shared Axis X spring transition
                                                        val shift = size.width * 0.20f * pageAnimIntensity
                                                        when {
                                                            isActive -> {
                                                                alpha = smoothStep((p - 0.12f) / 0.78f)
                                                                translationX = dir * shift * (1f - p)
                                                                val s = 1f - 0.025f * pageAnimIntensity * (1f - p)
                                                                scaleX = s
                                                                scaleY = s
                                                            }
                                                            isLeaving -> {
                                                                alpha = 1f - smoothStep(p / 0.55f)
                                                                translationX = -dir * shift * pClamped
                                                                val s = 1f - 0.030f * pageAnimIntensity * pClamped
                                                                scaleX = s
                                                                scaleY = s
                                                            }
                                                            else -> {
                                                                alpha = 0f
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                            .then(
                                                if (pageAnimStyle == "Parallax") {
                                                    Modifier.background(MaterialTheme.colorScheme.background)
                                                } else {
                                                    Modifier
                                                },
                                            ),
                                    ) {
                                        when (activeTab) {
                                0 -> {
                                    val chosen = media.filter { it.id in selected }
                                    val canDownload = chosen.isNotEmpty() &&
                                        chosen.all { if (audioMode(it)) output(it) != null else !videoMode(it) || sizes[it.id] != null } &&
                                        !sizeLoading && !loading && !retryParse && input == parsedInput
                                    HomeScreen(
                                        listState = listStates[0],
                                        input = input,
                                        onInputChange = { input = it },
                                        loading = loading,
                                        media = media,
                                        selected = selected,
                                        mode = mode,
                                        onModeChange = { mode = it },
                                        mp3 = mp3,
                                        onMp3Change = { mp3 = it },
                                        bitrate = bitrate,
                                        onBitrateChange = { bitrate = it },
                                        variants = variants,
                                        sizeLimit = sizeLimit,
                                        onQualityCapSelect = { preferredCap = it },
                                        sizes = sizes,
                                        sizeLoading = sizeLoading,
                                        gifIds = gifIds,
                                        onToggleGif = { id, isGif -> gifIds = if (isGif) gifIds + id else gifIds - id },
                                        audioCache = audioCache,
                                        trackIds = trackIds,
                                        onSelectTrack = { id, trackId ->
                                            trackIds = Bundle(trackIds).apply {
                                                if (trackId == null) remove(id) else putString(id, trackId)
                                            }
                                        },
                                        audioMode = ::audioMode,
                                        videoMode = ::videoMode,
                                        outputFor = ::output,
                                        extFor = ::ext,
                                        namingTemplate = namingTemplate,
                                        customNames = customNames(),
                                        canDownload = canDownload,
                                        hasMorePages = pages.isNotEmpty(),
                                        onLoadMore = {
                                            val next = pages.first()
                                            val original = parsedInput
                                            loading = true
                                            scope.launch {
                                                try {
                                                    val page = youtube.page(next)
                                                    if (parsedInput == original && input == original) {
                                                        val pageIds = page.items.mapTo(mutableSetOf()) { it.id }
                                                        media = (media.filterNot { it.playlistId == null && it.id in pageIds } + page.items).distinctBy { it.id }
                                                        pages = pages.drop(1) + listOfNotNull(page.more)
                                                    }
                                                } catch (e: CancellationException) {
                                                    throw e
                                                } catch (e: Exception) {
                                                    message = if (e is BiliException) e.message.orEmpty() else "无法加载播放列表，请重试。"
                                                    messageIsError = true
                                                } finally {
                                                    loading = false
                                                }
                                            }
                                        },
                                        onParse = { parseLinks() },
                                        onPaste = {
                                            runCatching {
                                                input = getSystemService(ClipboardManager::class.java)
                                                    .primaryClip?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString().orEmpty()
                                            }
                                        },
                                        onSelectAll = { selected = media.filter { it.x?.available != false }.map { it.id }.toSet() },
                                        onSelectNone = { selected = emptySet() },
                                        onToggleItem = { id, checked -> selected = if (checked) selected + id else selected - id },
                                        onRenameItem = { item, initial -> renameId = item.id; renameText = initial },
                                        onDownloadSelected = {
                                            try {
                                                val related = mutableMapOf<String, MutableList<String>>()
                                                chosen.sortedBy { it.x?.document != null }.forEach { item ->
                                                    val audio = output(item)
                                                    val actual = sizes[item.id]
                                                    val cap = if (audio != null || !videoMode(item)) 0
                                                    else if (item.x == null && item.youtubeId == null && variants.firstOrNull()?.formatId == null) quality
                                                    else requireNotNull(actual).quality
                                                    val x = item.x
                                                    val destination = x?.let {
                                                        XDestination(xGrouped, directory, if (xGrouped && it.document != null) related[it.postId].orEmpty().toList() else emptyList())
                                                    }
                                                    val extension = ext(item)
                                                    val existing = BiliApplication.store.entries.value.firstOrNull {
                                                        (it.item.youtubeId ?: it.item.id) == (item.youtubeId ?: item.id) && it.quality == cap &&
                                                            it.audioOutput?.track?.id == audio?.track?.id && it.audioOutput?.mp3Kbps == audio?.mp3Kbps &&
                                                            it.state !in listOf("Failed", "Canceled") &&
                                                            (x == null || it.xDestination == destination && it.outputFileName?.endsWith(extension) == true)
                                                    }
                                                    val jobId = existing?.id ?: UUID.randomUUID().toString()
                                                    if (existing == null) {
                                                        DownloadActions.schedule(
                                                            this@MainActivity,
                                                            DownloadEntry(
                                                                jobId,
                                                                item,
                                                                cap,
                                                                formatId = audio?.track?.id ?: actual?.formatId,
                                                                outputFileName = FileNaming.render(item, namingTemplate, customNames()[item.id], extension),
                                                                audioOutput = audio,
                                                                xDestination = destination,
                                                            ),
                                                        )
                                                    }
                                                    if (x != null && x.document == null) related.getOrPut(x.postId) { mutableListOf() } += jobId
                                                }
                                                message = "已加入下载队列"; messageIsError = false
                                                navigateToTab(1)
                                            } catch (e: Exception) {
                                                failureKind = (e as? BiliException)?.kind?.name
                                                messageIsError = true
                                                message = if (e is BiliException) e.message.orEmpty() else "系统暂时无法开始下载，请保持应用可见后重试。"
                                            }
                                        },
                                    )
                                }

                                1, 2 -> QueueScreen(
                                    completedOnly = activeTab == 2,
                                    listState = listStates[activeTab],
                                    entries = entries,
                                    listDensity = listDensity,
                                    selectionMode = activeTab == 2 && completedSelectionMode,
                                    selectedIds = selectedCompleted,
                                    sessionState = account.state,
                                    componentReady = componentState.ready,
                                    onEnterSelection = { initialId ->
                                        completedSelectionMode = true
                                        if (initialId != null) selectedCompleted = selectedCompleted + initialId
                                    },
                                    onToggleSelect = { id ->
                                        selectedCompleted = if (id in selectedCompleted) selectedCompleted - id else selectedCompleted + id
                                    },
                                    onExitSelection = {
                                        completedSelectionMode = false
                                        selectedCompleted = emptySet()
                                    },
                                    onOpenFile = { entry ->
                                        runCatching {
                                            startActivity(
                                                Intent(Intent.ACTION_VIEW)
                                                    .setDataAndType(Uri.parse(entry.uri), entry.mime)
                                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                            )
                                        }.onFailure { message = "没有可打开该文件的应用，或文件已被移除。"; messageIsError = true }
                                    },
                                    onShare = { entry ->
                                        try {
                                            startActivity(
                                                Intent.createChooser(
                                                    Intent(Intent.ACTION_SEND)
                                                        .setType(entry.mime)
                                                        .putExtra(Intent.EXTRA_STREAM, Uri.parse(entry.uri))
                                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                        .apply { clipData = ClipData.newRawUri("file", Uri.parse(entry.uri)) },
                                                    if (entry.item.x != null) "分享文件" else if (entry.audioOutput == null) "分享视频" else "分享音频",
                                                ),
                                            )
                                        } catch (_: Exception) {
                                            message = "文件无法分享，请确认文件仍然存在。"
                                            messageIsError = true
                                        }
                                    },
                                    onReparseTrackChanged = { entry ->
                                        input = entry.item.x?.let { XLink(it.postId).url }
                                            ?: entry.item.youtubeId?.let { (if (entry.item.music) "https://music.youtube.com" else "https://www.youtube.com") + "/watch?v=" + it }
                                            ?: if (entry.item.episodeId > 0) "https://www.bilibili.com/" + (if (entry.item.kind == "course") "cheese/play/ep" else "bangumi/play/ep") + entry.item.episodeId
                                            else "https://www.bilibili.com/video/" + entry.item.bvid + "?p=" + entry.item.index
                                        navigateToTab(0)
                                        parseLinks(true)
                                    },
                                    onManageSession = { kind ->
                                        launchLogin(if (kind == "XSession") LoginPlatform.X else LoginPlatform.YouTube)
                                    },
                                    onLogin = { launchLogin(LoginPlatform.Bilibili, it) },
                                    onRetryTask = { retryTask(it) },
                                    onPause = { id -> scope.launch(Dispatchers.IO) { DownloadActions.pause(this@MainActivity, id) } },
                                    onCancel = { id -> scope.launch(Dispatchers.IO) { DownloadActions.pause(this@MainActivity, id, true) } },
                                    onRemove = { id -> askRemove(setOf(id)) },
                                    onGoHome = { navigateToTab(0) },
                                )

                                else -> SettingsScreen(
                                    listState = listStates[3],
                                    subPage = settingsSubPage,
                                    onSubPageChange = { settingsSubPage = it },
                                    account = account,
                                    ytAccount = ytAccount,
                                    xAccount = xAccount,
                                    componentState = componentState,
                                    legacyComponents = legacyComponents,
                                    theme = theme,
                                    accent = accent,
                                    listDensity = listDensity,
                                    pageAnimStyle = pageAnimStyle,
                                    pageAnimDurationMs = pageAnimDurationMs,
                                    pageAnimDamping = pageAnimDamping,
                                    pageAnimIntensity = pageAnimIntensity,
                                    navFollowSpeed = navFollowSpeed,
                                    navBounciness = navBounciness,
                                    navLiquidDeform = navLiquidDeform,
                                    navDockPressScale = navDockPressScale,
                                    expandRelated = expand,
                                    detectClipboard = detect,
                                    xGrouped = xGrouped,
                                    directory = directory,
                                    namingDraft = namingDraft,
                                    onLogin = { launchLogin(LoginPlatform.Bilibili) },
                                    onLogout = {
                                        BiliApplication.session.clear()
                                        android.webkit.CookieManager.getInstance().removeAllCookies(null)
                                        sizeCache.clear(); audioCache.clear(); detailAttempt++
                                        message = "已退出登录"; messageIsError = false
                                    },
                                    onLoginYoutube = { launchLogin(LoginPlatform.YouTube) },
                                    onImportYoutubeCookies = { importCookieFile(isX = false) },
                                    onClearYoutubeSession = {
                                        youtube.session.clear()
                                        sizeCache.clear(); audioCache.clear(); detailAttempt++
                                        message = "YouTube 账号已退出"; messageIsError = false
                                    },
                                    onLoginX = { launchLogin(LoginPlatform.X) },
                                    onImportXCookies = { importCookieFile(isX = true) },
                                    onClearXSession = {
                                        youtube.xSession.clear()
                                        message = "X 账号已退出"; messageIsError = false
                                    },
                                    onOpenComponentDialog = { requestComponent("settings") },
                                    onUninstallLegacy = { legacy ->
                                        if (legacy.installed(this@MainActivity)) {
                                            runCatching { startActivity(legacy.uninstallIntent()) }
                                                .onFailure { message = "无法打开系统卸载确认。"; messageIsError = true }
                                        }
                                    },
                                    onThemeChange = { value -> theme = value; prefs.edit().putString("theme", value).apply() },
                                    onAccentChange = { value -> accent = value; prefs.edit().putString("accent", value).apply() },
                                    onListDensityChange = { value -> listDensity = value; prefs.edit().putString("listDensity", value).apply() },
                                    onPageAnimStyleChange = { value -> pageAnimStyle = value; prefs.edit().putString("pageAnimStyle", value).apply() },
                                    onPageAnimDurationChange = { value -> pageAnimDurationMs = value; prefs.edit().putInt("pageAnimDurationMs", value).apply() },
                                    onPageAnimDampingChange = { value -> pageAnimDamping = value; prefs.edit().putFloat("pageAnimDamping", value).apply() },
                                    onPageAnimIntensityChange = { value -> pageAnimIntensity = value; prefs.edit().putFloat("pageAnimIntensity", value).apply() },
                                    onResetPageAnim = {
                                        pageAnimStyle = "SharedAxis"
                                        pageAnimDurationMs = 340
                                        pageAnimDamping = 0.90f
                                        pageAnimIntensity = 1.0f
                                        prefs.edit()
                                            .putString("pageAnimStyle", pageAnimStyle)
                                            .putInt("pageAnimDurationMs", pageAnimDurationMs)
                                            .putFloat("pageAnimDamping", pageAnimDamping)
                                            .putFloat("pageAnimIntensity", pageAnimIntensity)
                                            .apply()
                                    },
                                    onNavFollowSpeedChange = { value -> navFollowSpeed = value; prefs.edit().putFloat("navFollowSpeed", value).apply() },
                                    onNavBouncinessChange = { value -> navBounciness = value; prefs.edit().putFloat("navBounciness", value).apply() },
                                    onNavLiquidDeformChange = { value -> navLiquidDeform = value; prefs.edit().putFloat("navLiquidDeform", value).apply() },
                                    onNavDockPressScaleChange = { value -> navDockPressScale = value; prefs.edit().putFloat("navDockPressScale", value).apply() },
                                    onResetNavAnim = {
                                        navFollowSpeed = 22.5f
                                        navBounciness = 0.20f
                                        navLiquidDeform = 1.15f
                                        navDockPressScale = 1.04f
                                        prefs.edit()
                                            .putFloat("navFollowSpeed", navFollowSpeed)
                                            .putFloat("navBounciness", navBounciness)
                                            .putFloat("navLiquidDeform", navLiquidDeform)
                                            .putFloat("navDockPressScale", navDockPressScale)
                                            .apply()
                                    },
                                    onExpandChange = { checked -> expand = checked; prefs.edit().putBoolean("expandRelated", checked).apply() },
                                    onDetectChange = { checked -> detect = checked; prefs.edit().putBoolean("detect", checked).apply() },
                                    onXGroupedChange = { checked -> xGrouped = checked; prefs.edit().putBoolean("xGrouped", checked).apply() },
                                    onPickDirectory = { picker.launch(null) },
                                    onResetDirectory = { directory = null; prefs.edit().remove("directory").apply() },
                                    onNamingDraftChange = { namingDraft = it },
                                    onSaveNaming = {
                                        namingTemplate = namingDraft
                                        prefs.edit().putString("filenameTemplate", namingTemplate).apply()
                                        message = "文件名模板已保存"; messageIsError = false
                                    },
                                    onResetNaming = { namingDraft = FileNaming.DEFAULT },
                                    onOpenGithub = {
                                        runCatching {
                                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/WkJ01N/BiliDownload")))
                                        }.onFailure {
                                            message = "无法打开浏览器，请访问 github.com/WkJ01N/BiliDownload。"
                                            messageIsError = true
                                        }
                                    },
                                )
                            }
                                        if (!isActive) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .pointerInput(Unit) {
                                                        awaitPointerEventScope {
                                                            while (true) {
                                                                awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                                                            }
                                                        }
                                                    },
                                            )
                                        }
                                    }
                                    }
                                }
                            }
                        }
                    }

                    val bottomBarMode = when {
                        completedSelectionMode && currentTab == 2 -> "Batch"
                        currentTab == 3 && settingsSubPage != "Overview" -> "Hidden"
                        else -> "Nav"
                    }
                    AnimatedContent(
                        targetState = bottomBarMode,
                        modifier = Modifier.align(Alignment.BottomCenter),
                        transitionSpec = {
                            (slideInVertically(tween(220)) { it / 2 } + fadeIn(tween(200)))
                                .togetherWith(slideOutVertically(tween(180)) { it / 2 } + fadeOut(tween(160)))
                        },
                        label = "bottomBarMode",
                    ) { modeState ->
                        when (modeState) {
                            "Batch" -> {
                                val allIds = completedEntries.mapTo(mutableSetOf()) { it.id }
                                SaltBatchActionBar(
                                    selectedCount = selectedCompleted.size,
                                    allSelected = allIds.isNotEmpty() && selectedCompleted.size == allIds.size,
                                    onToggleSelectAll = {
                                        selectedCompleted = if (selectedCompleted.size == allIds.size) emptySet() else allIds
                                    },
                                    onBatchShare = {
                                        try {
                                            val chosen = completedEntries.filter { it.id in selectedCompleted && !it.uri.isNullOrBlank() }
                                            val uris = ArrayList(chosen.map { Uri.parse(it.uri) })
                                            if (uris.size == 1) {
                                                val single = chosen.first()
                                                startActivity(
                                                    Intent.createChooser(
                                                        Intent(Intent.ACTION_SEND)
                                                            .setType(single.mime)
                                                            .putExtra(Intent.EXTRA_STREAM, uris[0])
                                                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                            .apply { clipData = ClipData.newRawUri("file", uris[0]) },
                                                        "分享文件",
                                                    ),
                                                )
                                            } else if (uris.isNotEmpty()) {
                                                val mimeType = when {
                                                    chosen.all { it.mime.startsWith("video/") } -> "video/*"
                                                    chosen.all { it.mime.startsWith("audio/") } -> "audio/*"
                                                    chosen.all { it.mime.startsWith("image/") } -> "image/*"
                                                    else -> "*/*"
                                                }
                                                val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                                                    type = mimeType
                                                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                    clipData = ClipData.newRawUri("files", uris[0]).apply {
                                                        for (i in 1 until uris.size) addItem(ClipData.Item(uris[i]))
                                                    }
                                                }
                                                startActivity(Intent.createChooser(shareIntent, "批量分享 (${uris.size})"))
                                            }
                                        } catch (_: Exception) {
                                            message = "部分文件无法分享，请确认文件仍然存在。"
                                            messageIsError = true
                                        }
                                    },
                                    onBatchDelete = {
                                        if (selectedCompleted.isNotEmpty()) askRemove(selectedCompleted)
                                    },
                                    onExit = {
                                        completedSelectionMode = false
                                        selectedCompleted = emptySet()
                                    },
                                )
                            }
                            "Nav" -> {
                                SaltFloatingNavBar(
                                    currentPage = currentTab,
                                    activeDownloadCount = activeDownloadCount,
                                    onSelect = ::navigateToTab,
                                    onReselect = { idx ->
                                        if (idx == 3 && settingsSubPage != "Overview") {
                                            settingsSubPage = "Overview"
                                        } else {
                                            scope.launch { listStates[idx].animateScrollToItem(0) }
                                        }
                                    },
                                    followSpeed = navFollowSpeed,
                                    bounciness = navBounciness,
                                    liquidDeform = navLiquidDeform,
                                    dockPressScale = navDockPressScale,
                                )
                            }
                            else -> Box(Modifier.size(0.dp))
                        }
                    }
                }
            }
        }
    }
}
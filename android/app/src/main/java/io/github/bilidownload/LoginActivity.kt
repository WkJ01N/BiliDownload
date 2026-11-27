package io.github.bilidownload

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.github.bilidownload.ui.components.SaltAlertBanner
import kotlinx.coroutines.*
import java.net.URI

enum class LoginPlatform { Bilibili, YouTube, X }

class LoginActivity : ComponentActivity() {
    class Contract : ActivityResultContract<LoginPlatform, LoginResult>() {
        override fun createIntent(context: Context, input: LoginPlatform) =
            Intent(context, LoginActivity::class.java).putExtra("platform", input.name)

        override fun parseResult(resultCode: Int, intent: Intent?) = when (resultCode) {
            RESULT_OK -> LoginResult(LoginOutcome.Success)
            RESULT_FIRST_USER -> LoginResult(LoginOutcome.Failed, intent?.getStringExtra("message"))
            else -> LoginResult(LoginOutcome.Canceled)
        }
    }

    private var web: WebView? = null

    private fun isAllowedLoginUrl(url: String, platform: LoginPlatform): Boolean = when (platform) {
        LoginPlatform.Bilibili -> Links.allowed(url)
        LoginPlatform.YouTube -> isAllowedHttpsHost(
            url,
            setOf("youtube.com", "youtu.be", "google.com", "googleapis.com", "gstatic.com", "googleusercontent.com", "gvt1.com"),
        )
        LoginPlatform.X -> isAllowedHttpsHost(
            url,
            setOf("x.com", "twitter.com", "twimg.com", "arkoselabs.com", "funcaptcha.com", "google.com", "gstatic.com", "appleid.apple.com"),
        )
    }

    private fun isAllowedHttpsHost(url: String, domains: Set<String>): Boolean = runCatching {
        val uri = URI(url)
        val host = uri.host.orEmpty().lowercase()
        uri.scheme == "https" && uri.port == -1 && domains.any { host == it || host.endsWith(".$it") }
    }.getOrDefault(false)

    private fun hasDetectedSession(platform: LoginPlatform): Boolean {
        val cm = CookieManager.getInstance()
        return when (platform) {
            LoginPlatform.Bilibili -> cm.getCookie("https://www.bilibili.com/").orEmpty().contains("SESSDATA=")
            LoginPlatform.YouTube -> YoutubeCookies.hasAuthCookies(
                listOfNotNull(
                    cm.getCookie("https://www.youtube.com/"),
                    cm.getCookie("https://m.youtube.com/"),
                    cm.getCookie("https://music.youtube.com/"),
                ),
                x = false,
            )
            LoginPlatform.X -> YoutubeCookies.hasAuthCookies(
                listOfNotNull(
                    cm.getCookie("https://x.com/"),
                    cm.getCookie("https://twitter.com/"),
                ),
                x = true,
            )
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val platform = LoginPlatform.entries.firstOrNull { it.name == intent.getStringExtra("platform") }
            ?: LoginPlatform.Bilibili

        val title = when (platform) {
            LoginPlatform.Bilibili -> "登录 B 站"
            LoginPlatform.YouTube -> "登录 YouTube"
            LoginPlatform.X -> "登录 X"
        }
        val startUrl = when (platform) {
            LoginPlatform.Bilibili -> "https://passport.bilibili.com/login"
            LoginPlatform.YouTube -> "https://accounts.google.com/ServiceLogin?service=youtube&passive=true&continue=https%3A%2F%2Fwww.youtube.com%2F"
            LoginPlatform.X -> "https://x.com/i/flow/login"
        }

        setContent {
            val prefs = getSharedPreferences("settings", MODE_PRIVATE)
            val theme = prefs.getString("theme", "Default")
            val accent = prefs.getString("accent", "Pink") ?: "Pink"
            val dark = theme == "Dark" || theme == "Default" && isSystemInDarkTheme()

            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
                    navigationBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
                )
                onDispose {}
            }

            BiliTheme(dark = dark, accent = accent) {
                var message by remember { mutableStateOf("") }
                var authReady by remember { mutableStateOf(false) }
                var verifying by remember { mutableStateOf(false) }
                var pageProgress by remember { mutableIntStateOf(0) }
                var canGoBack by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()

                BackHandler(enabled = canGoBack) { web?.goBack() }

                Surface(modifier = Modifier.fillMaxSize(), color = saltColors().card) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .navigationBarsPadding()
                            .imePadding(),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            IconButton(onClick = { setResult(RESULT_CANCELED); finish() }) {
                                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "返回")
                            }
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { web?.reload() }) {
                                Icon(painterResource(R.drawable.ic_refresh), contentDescription = "刷新")
                            }
                            Button(
                                enabled = !verifying,
                                shape = RoundedCornerShape(12.dp),
                                onClick = {
                                    scope.launch {
                                        verifying = true
                                        try {
                                            val cm = CookieManager.getInstance()
                                            cm.flush()
                                            when (platform) {
                                                LoginPlatform.Bilibili -> {
                                                    val cookie = cm.getCookie("https://www.bilibili.com/").orEmpty()
                                                    if (!cookie.contains("SESSDATA=")) throw BiliException("还没有获取到登录信息，请先完成网页登录。")
                                                    val nav = BiliApplication.api.api("https://api.bilibili.com/x/web-interface/nav", cookie)
                                                    if (!nav.optBoolean("isLogin")) throw BiliException("登录验证未通过，请重试。")
                                                    BiliApplication.session.save(cookie, nav.optString("uname"))
                                                    BiliApplication.session.state = SessionState.Authenticated
                                                }
                                                LoginPlatform.YouTube -> {
                                                    val pairs = listOf(
                                                        "youtube.com" to "https://www.youtube.com/",
                                                        "m.youtube.com" to "https://m.youtube.com/",
                                                        "music.youtube.com" to "https://music.youtube.com/",
                                                    ).mapNotNull { (domain, url) ->
                                                        cm.getCookie(url)?.let { domain to it }
                                                    }
                                                    val netscape = YoutubeCookies.fromWebViewCookies(pairs, x = false)
                                                    BiliApplication.youtube.session.save(netscape)
                                                }
                                                LoginPlatform.X -> {
                                                    val pairs = listOf(
                                                        "x.com" to "https://x.com/",
                                                        "twitter.com" to "https://twitter.com/",
                                                    ).mapNotNull { (domain, url) ->
                                                        cm.getCookie(url)?.let { domain to it }
                                                    }
                                                    val netscape = YoutubeCookies.fromWebViewCookies(pairs, x = true)
                                                    BiliApplication.youtube.xSession.save(netscape)
                                                }
                                            }
                                            setResult(RESULT_OK)
                                            cm.removeAllCookies { finish() }
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            message = if (e is BiliException) e.message.orEmpty() else "登录验证失败，请检查网络后重试。"
                                            setResult(RESULT_FIRST_USER, Intent().putExtra("message", message))
                                        } finally {
                                            verifying = false
                                        }
                                    }
                                },
                            ) {
                                Text(if (verifying) "验证中…" else "完成登录")
                            }
                        }

                        if (pageProgress in 1..99) {
                            LinearProgressIndicator(
                                progress = { pageProgress / 100f },
                                modifier = Modifier.fillMaxWidth().height(2.dp),
                            )
                        } else {
                            HorizontalDivider(thickness = 0.5.dp, color = saltColors().subtleBorder)
                        }

                        if (authReady && message.isEmpty()) {
                            Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                SaltAlertBanner(
                                    message = "已检测到登录状态，点击右上角「完成登录」即可保存。",
                                    isError = false,
                                    onDismiss = { authReady = false },
                                )
                            }
                        }

                        if (message.isNotEmpty()) {
                            Box(Modifier.padding(12.dp)) {
                                SaltAlertBanner(
                                    message = message,
                                    isError = true,
                                    onDismiss = { message = "" },
                                )
                            }
                        }

                        Box(modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                            AndroidView(
                                factory = { context ->
                                    WebView(context).apply {
                                        web = this
                                        layoutParams = ViewGroup.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                        )
                                        ViewCompat.setOnApplyWindowInsetsListener(this) { _, _ ->
                                            WindowInsetsCompat.CONSUMED
                                        }
                                        val cm = CookieManager.getInstance()
                                        cm.setAcceptCookie(true)
                                        cm.setAcceptThirdPartyCookies(this, true)
                                        settings.javaScriptEnabled = true
                                        settings.domStorageEnabled = true
                                        settings.databaseEnabled = true
                                        settings.useWideViewPort = true
                                        settings.loadWithOverviewMode = true
                                        settings.textZoom = 100
                                        settings.setSupportZoom(true)
                                        settings.builtInZoomControls = true
                                        settings.displayZoomControls = false
                                        settings.javaScriptCanOpenWindowsAutomatically = true
                                        settings.allowFileAccess = false
                                        settings.allowContentAccess = false
                                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                        if (platform != LoginPlatform.Bilibili) {
                                            settings.userAgentString = settings.userAgentString
                                                .replace("; wv", "")
                                                .replace(Regex("Version/\\d+\\.\\d+\\s+"), "")
                                        }
                                        webChromeClient = object : WebChromeClient() {
                                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                                pageProgress = newProgress
                                            }
                                        }
                                        webViewClient = object : WebViewClient() {
                                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                                                request.isForMainFrame && !isAllowedLoginUrl(request.url.toString(), platform)

                                            override fun onPageFinished(view: WebView?, url: String?) {
                                                super.onPageFinished(view, url)
                                                canGoBack = view?.canGoBack() == true
                                                if (hasDetectedSession(platform)) authReady = true
                                            }

                                            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                                super.doUpdateVisitedHistory(view, url, isReload)
                                                canGoBack = view?.canGoBack() == true
                                                if (hasDetectedSession(platform)) authReady = true
                                            }
                                        }
                                        loadUrl(startUrl)
                                    }
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        web?.destroy()
        CookieManager.getInstance().removeAllCookies(null)
        super.onDestroy()
    }
}

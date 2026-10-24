package io.github.bilidownload

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.*

class LoginActivity : ComponentActivity() {
    class Contract : ActivityResultContract<Unit, LoginResult>() {
        override fun createIntent(context: Context, input: Unit) = Intent(context, LoginActivity::class.java)
        override fun parseResult(resultCode: Int, intent: Intent?) = when(resultCode) {
            RESULT_OK -> LoginResult(LoginOutcome.Success)
            RESULT_FIRST_USER -> LoginResult(LoginOutcome.Failed, intent?.getStringExtra("message"))
            else -> LoginResult(LoginOutcome.Canceled)
        }
    }
    private var web: WebView? = null
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val theme = getSharedPreferences("settings", MODE_PRIVATE).getString("theme", "Default")
            BiliTheme(theme == "Dark" || theme == "Default" && isSystemInDarkTheme()) {
                var message by remember { mutableStateOf("") }
                var verifying by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()
                Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(onClick = { setResult(RESULT_CANCELED); finish() }) { Text("返回") }
                        Button(enabled = !verifying, onClick = { scope.launch {
                            verifying = true
                            try {
                                val cookie = CookieManager.getInstance().getCookie("https://www.bilibili.com/").orEmpty()
                                if(!cookie.contains("SESSDATA=")) throw BiliException("还没有获取到登录信息，请先完成网页登录。")
                                val nav = BiliApplication.api.api("https://api.bilibili.com/x/web-interface/nav", cookie)
                                if(!nav.optBoolean("isLogin")) throw BiliException("登录验证未通过，请重试。")
                                BiliApplication.session.save(cookie, nav.optString("uname"))
                                BiliApplication.session.state = SessionState.Authenticated
                                setResult(RESULT_OK)
                                CookieManager.getInstance().removeAllCookies { finish() }
                            } catch(e: CancellationException) { throw e }
                            catch(e: Exception) { message = if(e is BiliException) e.message.orEmpty() else "登录验证失败，请检查网络后重试。"; setResult(RESULT_FIRST_USER, Intent().putExtra("message", message)) }
                            finally { verifying = false }
                        } }) { Text("完成登录") }
                    }
                    if(message.isNotEmpty()) Text(message, Modifier.padding(12.dp))
                    AndroidView(factory = { context -> WebView(context).apply {
                        web = this; settings.javaScriptEnabled = true; settings.domStorageEnabled = true
                        settings.allowFileAccess = false; settings.allowContentAccess = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = request.isForMainFrame && !Links.allowed(request.url.toString())
                        }
                        loadUrl("https://passport.bilibili.com/login")
                    } }, modifier = Modifier.weight(1f))
                }
            }
        }
    }
    override fun onDestroy() { web?.destroy(); CookieManager.getInstance().removeAllCookies(null); super.onDestroy() }
}

// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.school

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.os.*
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.van.timetable.VanApplication
import app.van.timetable.domain.*
import app.van.timetable.ui.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

class SchoolActivity : ComponentActivity() {
    private var web: WebView? = null
    private var extracting = false
    private var job: Job? = null
    private val app
        get() = application as VanApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val school =
            runCatching { schoolForAddress(intent.getStringExtra("url").orEmpty()) }.getOrNull()
                ?: run {
                    finish()
                    return
                }
        setContent {
            var p by remember { mutableStateOf(Preferences()) }
            LaunchedEffect(Unit) { app.store.preferences.collect { p = it } }
            VanTheme(p) {
                var address by remember { mutableStateOf(school.url) }
                var message by remember { mutableStateOf(school.note) }
                var busy by remember { mutableStateOf(false) }
                var prompt by remember { mutableStateOf<JsPromptResult?>(null) }
                var promptMessage by remember { mutableStateOf("") }
                var promptValue by remember { mutableStateOf("") }
                BackHandler { if (web?.canGoBack() == true) web?.goBack() else finish() }
                Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                    Row(
                        Modifier.padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        TextButton(onClick = { finish() }) { Text("退出") }
                        Text(school.name, Modifier.weight(1f).padding(top = 14.dp))
                        TextButton(onClick = { web?.reload() }) { Text("刷新") }
                    }
                    OutlinedTextField(
                        address,
                        { address = it },
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        label = { Text("教务系统地址") },
                        singleLine = true,
                    )
                    Row(Modifier.padding(horizontal = 12.dp)) {
                        TextButton(
                            onClick = {
                                runCatching { schoolAddress(address) }
                                    .onSuccess { web?.loadUrl(it) }
                                    .onFailure { message = it.message.orEmpty() }
                            }
                        ) {
                            Text("打开")
                        }
                        TextButton(
                            enabled = !busy,
                            onClick = {
                                busy = true
                                message = "正在获取课程，请稍候"
                                extract { result, error ->
                                    busy = false
                                    if (error != null) message = error
                                    else {
                                        result?.let {
                                            setResult(
                                                Activity.RESULT_OK,
                                                Intent()
                                                    .putExtra("result", it)
                                                    .putExtra(
                                                        "schoolName",
                                                        schoolForAddress(web?.url ?: address).name,
                                                    ),
                                            )
                                            finish()
                                        }
                                    }
                                }
                            },
                        ) {
                            Text("获取当前课表")
                        }
                    }
                    Text(
                        message,
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    AndroidView(
                        factory = { context ->
                            WebView(context).apply {
                                web = this
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.useWideViewPort = true
                                settings.loadWithOverviewMode = true
                                settings.builtInZoomControls = true
                                settings.displayZoomControls = false
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
                                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                if (Build.VERSION.SDK_INT >= 26) settings.safeBrowsingEnabled = true
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                                webChromeClient =
                                    object : WebChromeClient() {
                                        override fun onJsPrompt(
                                            view: WebView,
                                            url: String,
                                            message: String,
                                            defaultValue: String,
                                            result: JsPromptResult,
                                        ): Boolean {
                                            promptMessage = message
                                            promptValue = defaultValue
                                            prompt = result
                                            return true
                                        }
                                    }
                                webViewClient =
                                    object : WebViewClient() {
                                        override fun shouldOverrideUrlLoading(
                                            view: WebView,
                                            request: WebResourceRequest,
                                        ): Boolean {
                                            val ok = allowed(request.url)
                                            if (!ok) message = "此链接不是网页地址"
                                            return !ok
                                        }

                                        override fun onPageFinished(view: WebView, url: String) {
                                            address = url
                                        }

                                        override fun onReceivedSslError(
                                            view: WebView,
                                            handler: SslErrorHandler,
                                            error: SslError,
                                        ) {
                                            handler.cancel()
                                            message = "学校网站证书异常，已停止加载"
                                        }

                                        override fun onReceivedError(
                                            view: WebView,
                                            request: WebResourceRequest,
                                            error: WebResourceError,
                                        ) {
                                            if (request.isForMainFrame)
                                                message = "加载失败：${error.description}；请检查校园网、VPN或入口"
                                        }
                                    }
                                loadUrl(school.url)
                            }
                        },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
                if (prompt != null)
                    AlertDialog(
                        onDismissRequest = {
                            prompt?.cancel()
                            prompt = null
                        },
                        title = { Text("选择学期") },
                        text = {
                            Column {
                                Text(promptMessage)
                                OutlinedTextField(
                                    promptValue,
                                    { promptValue = it },
                                    label = { Text("学期代码") },
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    prompt?.confirm(promptValue)
                                    prompt = null
                                }
                            ) {
                                Text("获取")
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = {
                                    prompt?.cancel()
                                    prompt = null
                                }
                            ) {
                                Text("取消")
                            }
                        },
                    )
            }
        }
    }

    private fun allowed(uri: Uri) =
        uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()

    private fun extract(done: (String?, String?) -> Unit) {
        val view = web ?: return done(null, "页面尚未准备好")
        if (!allowed(Uri.parse(view.url))) return done(null, "当前不是教务网页")
        if (extracting) return
        extracting = true
        val source =
            schoolAdapterAssets(view.url.orEmpty()).joinToString("\n") { adapter ->
                assets.open("adapters/$adapter.js").bufferedReader().use { it.readText() }
            }
        view.evaluateJavascript(source, null)
        job =
            app.scope.launch {
                var result: String? = null
                repeat(80) {
                    if (result != null) return@repeat
                    delay(400)
                    withContext(Dispatchers.Main) {
                        view.evaluateJavascript(
                            "JSON.stringify(window.__vanImportResult || null)"
                        ) { value ->
                            val parsed =
                                runCatching { Codec.json.parseToJsonElement(value) }.getOrNull()
                            if (parsed is JsonPrimitive && parsed.content != "null")
                                result = parsed.content
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    extracting = false
                    if (isFinishing) return@withContext
                    val current = result
                    if (current == null) done(null, "获取超时，进入个人课表后重试")
                    else {
                        val error =
                            runCatching {
                                    Codec.json
                                        .parseToJsonElement(current)
                                        .jsonObject["error"]
                                        ?.jsonPrimitive
                                        ?.content
                                }
                                .getOrNull()
                        if (error != null) done(null, error) else done(current, null)
                    }
                }
            }
    }

    override fun onDestroy() {
        job?.cancel()
        val view = web
        web = null
        view?.stopLoading()
        view?.destroy()
        app.scope.launch {
            if (!app.store.prefs().keepLogin)
                withContext(Dispatchers.Main) {
                    CookieManager.getInstance().removeAllCookies(null)
                    CookieManager.getInstance().flush()
                    WebStorage.getInstance().deleteAllData()
                }
        }
        super.onDestroy()
    }
}

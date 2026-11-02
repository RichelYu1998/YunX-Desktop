package com.yunx.app.ui.jcef

import com.yunx.app.ui.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.yunx.app.ui.SnackbarController
import com.yunx.app.util.BrowserCookieImporter
import com.yunx.app.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.callback.CefCookieVisitor
import org.cef.misc.BoolRef
import org.cef.network.CefCookie
import org.cef.network.CefCookieManager
import java.awt.BorderLayout
import javax.swing.JPanel

private const val TAG = "YunX-JCEF-UI"

/** 登录态轮询间隔 */
private const val POLL_INTERVAL_MS = 1_500L

/** 同一份 Cookie 校验失败后，间隔多久才允许自动重试（网络闪断恢复后能自动补登） */
private const val AUTO_RETRY_MS = 10_000L

/**
 * 内嵌浏览器登录面板（方案 B）：
 * - SwingPanel 嵌入真实 Chromium（JCEF），加载网盘登录页，扫码 / 账号密码 / 短信验证等
 *   多方式登录都由官网页面自己完成；
 * - 每 [POLL_INTERVAL_MS] 轮询全局 Cookie，检测到必需键即**自动保存登录态**并返回，
 *   解析 / 下载随后自动携带这份 Cookie（token），无需手动点击；
 * - 底部保留「自动导入浏览器 Cookie」（方案 A）与「手动粘贴」两个兜底入口。
 *
 * @param requiredKeys 必须全部出现的 Cookie 键名（与键名集合取交集，全命中才算登录态）
 * @param anyOfKeys 候选键名（任一出现即视为登录态）；用于网页版仅下发 authorization、
 *                  客户端版仅下发 Os_SSo_Sid 的双形态平台（如 139 网盘）兜底
 */
@Composable
fun JcefLoginPane(
    loginUrl: String,
    domains: List<String>,
    requiredKeys: List<String>,
    platform: String,
    onSave: suspend (String) -> Boolean,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    onSwitchToPaste: () -> Unit,
    anyOfKeys: List<String> = emptyList()
) {
    val scope = rememberCoroutineScope()
    // ★ 必须观察 browserReady 而不是一次性取 app()：macOS 在窗口出现前同步初始化，
    //   但 Windows/Linux 是后台初始化，登录页可能先于 CEF 就绪被打开。此前用快照导致
    //   app==null 时永远停在「初始化中 / 不可用」，CEF 就绪后也不会重建浏览器。
    val ready = JcefHolder.browserReady
    val app = if (ready) JcefHolder.app() else null
    var detectedCookie by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var savedOnce by remember { mutableStateOf(false) }
    var browserFailure by remember { mutableStateOf<String?>(null) }
    // 回调可能在重组间变化，协程里始终取最新实现
    val currentOnSave by rememberUpdatedState(onSave)
    val currentOnSaved by rememberUpdatedState(onSaved)

    // 未就绪时再触发一次后台初始化；成功后 ready 翻转 → 下面的 remember(ready) 重建浏览器
    LaunchedEffect(Unit) { JcefHolder.initInBackground() }

    /** 保存登录态：成功即落库并关闭登录页；失败记录原因，由轮询冷却后自动重试 */
    val saveCookie: suspend (String) -> Boolean = { cookie ->
        isSaving = true
        saveError = null
        var failure: Exception? = null
        val ok = try {
            currentOnSave(cookie)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "save login cookie failed", e)
            failure = e
            false
        } finally {
            isSaving = false
        }
        if (ok) {
            savedOnce = true
            SnackbarController.show("登录成功")
            currentOnSaved()
        } else {
            // 区分「保存失败」与「凭证无效」：保存失败（如密钥/数据库异常）会一直失败，
            // 提示成「登录态已过期」会让用户误以为要重新登录
            saveError = failure?.let { "保存登录凭据失败：${it.message ?: it::class.simpleName}" }
                ?: "登录态无效（Cookie 里缺少必要字段），稍后自动重试"
        }
        ok
    }

    // 创建浏览器（Compose 桌面 UI 线程即 EDT，JCEF 要求在 EDT 上创建）
    val browserHolder = remember(ready) {
        if (app == null) {
            null
        } else {
            try {
                val client: CefClient = app.createClient()
                val browser: CefBrowser = client.createBrowser(loginUrl, false, false)
                // 不调用 createImmediately()：它会在画布尚未挂到窗口（尺寸为 0）时
                // 强行创建原生浏览器窗口，首次绘制丢失 → 白屏直到手动缩放窗口。
                // 交给 java-cef 在画布 addNotify（拿到真实尺寸）时自动创建，
                // 登录页一显示画布即挂载，页面加载与 Cookie 检测仅延迟几毫秒。
                Log.i(TAG, "内嵌浏览器已创建：$loginUrl")
                BrowserHolder(client, browser)
            } catch (t: Throwable) {
                browserFailure = "内嵌浏览器启动失败：${t.message}"
                Log.e(TAG, "create browser failed", t)
                null
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { browserHolder?.browser?.close(true) }
            runCatching { browserHolder?.client?.dispose() }
        }
    }

    BackHandler(enabled = !isSaving) { onBack() }

    // 轮询 Cookie：检测到登录态后**自动保存**，无需手动点按钮。
    // 与参考实现（Playwright 轮询 context.cookies、命中 token 即落盘）同一思路：
    // 页面登录成功那一刻就把 Cookie 写进账号库，解析 / 下载立即自动带上 token；
    // 校验失败（旧会话 / 网络抖动）时按 [AUTO_RETRY_MS] 冷却后自动重试。
    LaunchedEffect(browserHolder) {
        if (browserHolder == null) return@LaunchedEffect
        var lastAttemptCookie: String? = null
        var lastAttemptTs = 0L
        while (true) {
            val cookie = withContext(Dispatchers.IO) { collectCookies(domains, requiredKeys, anyOfKeys) }
            if (cookie != null && !savedOnce) {
                detectedCookie = cookie
                val now = System.currentTimeMillis()
                if (cookie != lastAttemptCookie || now - lastAttemptTs >= AUTO_RETRY_MS) {
                    lastAttemptCookie = cookie
                    lastAttemptTs = now
                    if (saveCookie(cookie)) return@LaunchedEffect
                }
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // 顶部导航栏：左上角返回（标准 IconButton 悬停高亮，与手动 Cookie 页一致）。
        // 登录页是提前 return 的覆盖层，不在 MainScreen 的 LocalContentColor 提供者内，
        // 默认黑色会让 IconButton 的悬停状态层在深色主题下变成"变暗"而非亮色高亮——
        // 这里显式提供 onSurface，高亮层与 TopAppBar 内的按钮观感一致。
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { if (!isSaving) onBack() },
                    enabled = !isSaving
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回"
                    )
                }
                Text(
                    text = "${platform}登录",
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }

        // 状态卡：登录态检测 → 自动保存全过程的状态反馈
        Surface(
            color = if (detectedCookie != null) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (detectedCookie != null) {
                    if (isSaving) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = when {
                                isSaving -> "已检测到登录态，正在自动保存…"
                                saveError != null -> "已检测到登录态"
                                else -> "已检测到登录态，正在自动保存…"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        saveError?.let { err ->
                            Text(
                                text = err,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    // 自动保存失败时的兜底：立即重试一次，不必等冷却
                    if (saveError != null && !isSaving) {
                        Button(
                            onClick = {
                                val cookie = detectedCookie ?: return@Button
                                scope.launch { saveCookie(cookie) }
                            }
                        ) { Text("立即保存") }
                    }
                } else {
                    Icon(
                        Icons.Outlined.Wifi,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = browserFailure ?: "请在下方网页中登录（扫码 / 账号密码 / 短信均可），登录成功后自动保存",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // 内嵌浏览器区域
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (browserHolder != null) {
                SwingPanel(
                    factory = {
                        // 强制画布尺寸与容器同步：Compose 侧尺寸变化时（窗口缩放/页面切换动画）
                        // BorderLayout 不一定立即重新布局 JCEF 画布，偶发"浏览器不铺满/留白"。
                        // 每次布局后校正画布边界，其内置 ComponentListener 会联动 CEF 原生窗口缩放。
                        object : JPanel(BorderLayout()) {
                            override fun doLayout() {
                                super.doLayout()
                                val canvas = browserHolder.browser.uiComponent
                                if (canvas.width != width || canvas.height != height ||
                                    canvas.x != 0 || canvas.y != 0
                                ) {
                                    canvas.setBounds(0, 0, width, height)
                                }
                            }
                        }.apply {
                            background = java.awt.Color.WHITE
                            add(browserHolder.browser.uiComponent, BorderLayout.CENTER)
                            addComponentListener(object : java.awt.event.ComponentAdapter() {
                                override fun componentResized(e: java.awt.event.ComponentEvent) {
                                    browserHolder.browser.uiComponent.setBounds(0, 0, width, height)
                                }
                            })
                            javax.swing.SwingUtilities.invokeLater { validate() }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    background = Color.White
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (!ready && JcefHolder.disabledReason == null) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        }
                        Text(
                            text = browserFailure ?: JcefHolder.unavailableReason()
                                ?: "内嵌浏览器不可用",
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        // 未禁用时给出重试入口：初始化失败多半是残留 CEF 子进程占着缓存目录，
                        // 重试会先清理残留再重新初始化
                        if (JcefHolder.disabledReason == null) {
                            OutlinedButton(onClick = {
                                browserFailure = null
                                JcefHolder.initInBackground()
                            }) { Text("重试加载内嵌浏览器") }
                        }
                    }
                }
            }
        }

        // 底部备选入口
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            AutoImportCookieButton(
                platform = platform,
                onImported = onSave,
                onSaved = onSaved,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(
                onClick = onSwitchToPaste,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Outlined.ContentPaste, contentDescription = null)
                Spacer(modifier = Modifier.size(6.dp))
                Text("手动粘贴")
            }
        }
    }
}

private class BrowserHolder(val client: CefClient, val browser: CefBrowser)

/**
 * 从全局 Cookie 管理器收集目标域 Cookie；必需键齐全时返回 "k=v; ..." 串。
 *
 * JCEF 的 visitAllCookies 是**异步**的：CefCookieVisitor.visit 回调在 CEF IO 线程执行，
 * visitAllCookies 返回时回调可能尚未开始/完成。因此调用后必须等待一小段窗口
 * 让回调把结果写入（线程安全收集容器），再判断必需键。
 *
 * 登录态判定：[requiredKeys] 全命中 OR [anyOfKeys] 任一命中即视为有效。
 * - requiredKeys 用于严格平台（如夸克：必须有 buser-u。
 * - anyOfKeys 用于双形态平台（如 139：客户端下发 Os_SSo_Sid，网页版仅下发 authorization）。
 */
private fun collectCookies(
    domains: List<String>,
    requiredKeys: List<String>,
    anyOfKeys: List<String> = emptyList()
): String? {
    return runCatching {
        val manager = CefCookieManager.getGlobalManager()
        val pairs = java.util.concurrent.ConcurrentLinkedQueue<Triple<String, String, String>>()
        manager.visitAllCookies(object : CefCookieVisitor {
            override fun visit(cookie: CefCookie, count: Int, total: Int, delete: BoolRef): Boolean {
                val domain = cookie.domain ?: ""
                if (domains.any { domainMatches(domain, it) }) {
                    pairs.add(Triple(cookie.name ?: "", cookie.value ?: "", domain))
                }
                return true
            }
        })
        // 等待 IO 线程回调完成（CEF 遍历 Cookie 库很快，几百毫秒足够）
        Thread.sleep(400)
        val all = pairs.toList()
        // ★ 过滤空名/空值：浏览器偶尔会写入无名 Cookie，拼进 Cookie 头会变成 ";; =v"，
        //   服务端解析失败 → 直接判定未登录（百度表现就是 errno=-6）
        val valid = all.filter { it.first.isNotBlank() && it.second.isNotBlank() }
        if (all.isNotEmpty()) {
            Log.d(
                TAG,
                "cookies for $domains: ${all.size}->${valid.size} " +
                    "(keys=${valid.map { it.first }.distinct()})"
            )
        }
        // ★ 同名 Cookie 去重：按 RFC 6265，域越具体优先级越高。visitAllCookies 顺序不保证，
        //   所以按 domain 长度升序写入，最后写进去的就是最具体那个域的值。
        val merged = LinkedHashMap<String, String>()
        valid.sortedBy { it.third.length }.forEach { (name, value, _) -> merged[name] = value }
        val names = merged.keys
        // 必需键全命中 OR anyOfKeys 任一命中；anyOfKeys 为空时仅看 requiredKeys
        val requiredOk = requiredKeys.all { names.contains(it) }
        val anyOk = anyOfKeys.isNotEmpty() && anyOfKeys.any { names.contains(it) }
        if (!requiredOk && !anyOk) return null
        merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }.getOrNull()
}

/**
 * Cookie 域是否属于目标域。
 *
 * ★ 必须按「域后缀」比较，不能按子串：此前用 `domain.contains(".baidu.com")`，
 *   于是 `pan.baidu.com` / `.pan.baidu.com` 这类**网盘自己域**的 Cookie 全部被漏掉，
 *   其中就有网盘网页版必备的 `PANWEB=1`。缺少它时百度网盘接口一律按未登录处理
 *   （列目录 errno=-6、gettemplatevariable 不回 bdstoken），表现正是「获取 bdstoken 失败」。
 */
private fun domainMatches(cookieDomain: String, target: String): Boolean {
    val d = cookieDomain.trim().lowercase().trimStart('.')
    val t = target.trim().lowercase().trimStart('.')
    if (d.isEmpty() || t.isEmpty()) return false
    return d == t || d.endsWith(".$t")
}

/**
 * 方案 A 入口按钮 + 对话框：扫描本机浏览器并列出可导入的 Cookie。
 * 可用于内嵌浏览器模式与纯粘贴模式。
 */
@Composable
fun AutoImportCookieButton(
    platform: String,
    onImported: suspend (String) -> Boolean,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var show by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<BrowserCookieImporter.FoundCookies>>(emptyList()) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    fun scan() {
        scope.launch {
            scanning = true
            errorMsg = null
            val found = withContext(Dispatchers.IO) {
                val domains = BrowserCookieImporter.domainSuffixes(platform)
                BrowserCookieImporter.discoverBrowsers().mapNotNull { browser ->
                    BrowserCookieImporter.importFrom(browser, domains)
                }
            }
            results = found
            scanning = false
            if (found.isEmpty()) {
                errorMsg = "未在本机浏览器中找到该平台的登录 Cookie。\n" +
                    "提示：需先在浏览器中登录过对应网盘；Chrome/Edge 较新版本因安全限制可能无法读取，可尝试 Firefox 或 360 浏览器。"
            }
        }
    }

    OutlinedButton(onClick = { show = true; scan() }, modifier = modifier) {
        Icon(Icons.Outlined.CheckCircle, contentDescription = null)
        Spacer(modifier = Modifier.size(6.dp))
        Text("自动导入浏览器 Cookie")
    }

    if (show) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { if (!scanning) show = false },
            title = { Text("自动导入浏览器 Cookie") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (scanning) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.size(10.dp))
                            Text("正在扫描本机浏览器…")
                        }
                    } else if (results.isNotEmpty()) {
                        results.forEach { found ->
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = found.browserName,
                                            style = MaterialTheme.typography.titleSmall
                                        )
                                        Text(
                                            text = "找到 ${found.count} 个 Cookie",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                scanning = true
                                                val ok = onImported(found.cookie)
                                                scanning = false
                                                if (ok) {
                                                    show = false
                                                    SnackbarController.show("登录成功")
                                                    onSaved()
                                                } else {
                                                    errorMsg = "该 Cookie 校验失败（可能已过期），请重新登录浏览器后重试"
                                                }
                                            }
                                        }
                                    ) { Text("导入") }
                                }
                            }
                        }
                    } else if (errorMsg != null) {
                        Text(
                            text = errorMsg ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = { if (!scanning) show = false },
                    enabled = !scanning
                ) { Text("关闭") }
            }
        )
    }
}

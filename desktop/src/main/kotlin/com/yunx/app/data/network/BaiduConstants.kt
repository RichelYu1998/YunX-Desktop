package com.yunx.app.data.network

/**
 * 百度网盘登录与 API 相关常量（依据抓包 + 文档）。
 */
object BaiduConstants {

    /** WebView 登录页（提取 BDUSS/STOKEN） */
    const val LOGIN_URL = "https://pan.baidu.com/"

    /** 提取 Cookie 的域名 */
    const val COOKIE_DOMAIN = "https://pan.baidu.com"

    /** PC 网页 UA（share/verify、xpan/share、gettemplatevariable 使用） */
    const val UA_WEB =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /** 百度客户端 UA（yun/api/list、filemanager、locatedownload 使用） */
    const val UA_NETDISK =
        "netdisk;12.24.6;piano;android-android;16;JSbridge4.4.0;jointBridge;1.1.0"

    /** 百度网盘统一 app_id */
    const val APP_ID = "250528"

    /** 临时转存目录名（对齐抓包） */
    const val TEMP_DIR_NAME = "YunX临时转存"

    /** 关键 Cookie 字段，缺失 BDUSS 则视为未登录 */
    fun isValidCookie(cookie: String?): Boolean =
        cookie != null && cookie.contains("BDUSS=")

    /**
     * 补齐网盘网页版必备但可能采集不到的 Cookie。
     *
     * `PANWEB=1` 由 pan.baidu.com 下发，domain 是 `pan.baidu.com`（不是 `.baidu.com`）。
     * 采集端若按域名过滤不当会漏掉它，而百度网盘接口在缺少 PANWEB 时一律按未登录处理：
     * 列目录返回 errno=-6、gettemplatevariable 不回 bdstoken。这里做一次兜底补齐，
     * 让老版本保存下来的 Cookie 也能正常使用（无需用户重新登录）。
     */
    fun normalizeCookie(cookie: String): String {
        val trimmed = cookie.trim().trimEnd(';')
        if (trimmed.isEmpty()) return trimmed
        return if (Regex("(?i)(^|;\\s*)PANWEB=").containsMatchIn(trimmed)) trimmed
        else "$trimmed; PANWEB=1"
    }
}

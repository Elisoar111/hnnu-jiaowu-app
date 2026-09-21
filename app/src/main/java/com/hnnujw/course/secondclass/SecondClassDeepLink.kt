package com.hnnujw.course.secondclass

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 第二课堂**外部入口**的统一解析结果。
 *
 * 两个来源，最终都归到这里：
 * - 系统相机扫到二维码 → 系统发 `ACTION_VIEW`（`qutuo://` 码，或 `ekta.hnnu.edu.cn` 的网页链接）；
 * - 剪贴板里是码或链接（同学转发过来的签到码 / 活动链接）。
 *
 * 拿到 [Scan] 后仍要过 [SecondClassScanCodec.evaluateSignScan] 的安全闸门，
 * 这里只负责「认出这是什么」，不负责「允不允许这么干」。
 */
sealed interface SecondClassDeepLink {

    /** 一个码：签到码 / 等待签到码 / 活动码 / 组织码。 */
    data class Scan(val payload: SecondClassScanPayload) : SecondClassDeepLink

    /** 活动页链接（带活动 id）→ 打开活动详情。 */
    data class Activity(val activityId: Int) : SecondClassDeepLink

    /** 不是我们认识的东西。 */
    data object None : SecondClassDeepLink

    companion object {

        /** 第二课堂站点主机。 */
        const val SITE_HOST = "ekta.hnnu.edu.cn"

        /** 站点自己的码协议，见 [SecondClassScanCodec.SCHEME]。 */
        private const val SCHEME_MARKER = "qutuo://"

        /**
         * 解析一条 URI / 链接文本。
         *
         * 只认两种形态，其它一律 [None] —— 这个函数同时用于**剪贴板**，
         * 而剪贴板里可能装着密码等敏感内容，宽松匹配会把无关文本误判成活动链接
         * 并把它展示出来。
         */
        fun fromUri(uri: String?): SecondClassDeepLink {
            val text = uri?.trim().orEmpty()
            if (text.isEmpty()) return None

            if (text.contains(SCHEME_MARKER, ignoreCase = true)) {
                return when (val payload = SecondClassScanCodec.parse(text)) {
                    // 码格式不完整（qutuo:// 但没有可用的 activityId）→ 当作没扫到
                    is SecondClassScanPayload.Text -> None
                    else -> Scan(payload)
                }
            }

            if (!text.contains(SITE_HOST, ignoreCase = true)) return None
            val activityId = queryValue(text, "activityId")?.toIntOrNull()
                ?: queryValue(text, "activityid")?.toIntOrNull()
                ?: queryValue(text, "id")?.toIntOrNull()
                ?: ACTIVITY_PATH_ID.find(text.lowercase())?.groupValues?.get(1)?.toIntOrNull()
            return if (activityId != null && activityId > 0) Activity(activityId) else None
        }

        /**
         * 剪贴板里的内容。
         *
         * 与 [fromUri] 同一套匹配规则（只认站点/协议形态）；**调用方不得把
         * 原文写进日志** —— 剪贴板可能装着密码（隐私红线），我们只保留解析结果。
         */
        fun fromClipboard(text: String?): SecondClassDeepLink = fromUri(text)

        /**
         * 取 query 参数（大小写不敏感）。
         *
         * 不用 `android.net.Uri`：hash 路由的链接（`https://host/#/x?id=1`）里
         * query 在 `#` 之后，`Uri.getQueryParameter` 取不到；手拆最稳，也才能在
         * JVM 单测里跑。
         */
        private fun queryValue(text: String, key: String): String? =
            text.substringAfter('?', "")
                .split('&')
                .mapNotNull { part ->
                    val index = part.indexOf('=')
                    if (index <= 0) null else part.substring(0, index) to part.substring(index + 1)
                }
                .firstOrNull { it.first.equals(key, ignoreCase = true) }
                ?.second
                ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }

        /** `.../activityDetail/7358`、`activityDetail?id=7358` 这类路径写法。 */
        private val ACTIVITY_PATH_ID = Regex("""activitydetail\D{0,3}(\d{1,12})""")
    }
}

/**
 * 深链/扫码的「Activity intent → Compose」桥。
 *
 * 与 `AppTabNavigation` 同一模式：挂在伴生对象上，冷启动时 Compose 的组合
 * 先于 Activity 的 intent 消费，用 Compose state 跨越这条时序差；消费一次即清空。
 */
object SecondClassDeepLinkNavigation {

    /** 待处理的深链；由二课页面观察并在处理完后 [consume]。 */
    var pending by mutableStateOf<SecondClassDeepLink?>(null)
        private set

    fun accept(intent: android.content.Intent?) {
        val link = SecondClassDeepLink.fromUri(intent?.dataString)
        if (link != SecondClassDeepLink.None) pending = link
    }

    fun accept(link: SecondClassDeepLink) {
        if (link != SecondClassDeepLink.None) pending = link
    }

    fun consume() {
        pending = null
    }
}

/**
 * 读一次剪贴板并解析。
 *
 * Android 10+ 只有前台能读剪贴板，所以放在二课页面回前台时调用；
 * 读不到、没内容、或不是我们认识的形态都返回 [SecondClassDeepLink.None]。
 *
 * 隐私红线：这里**绝不**把剪贴板原文写日志，也不弹原文预览，
 * 只展示解析出来的动作（"打开活动详情" / "签到"）。
 */
fun readSecondClassClipboard(context: Context): SecondClassDeepLink = try {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val text = manager?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
    SecondClassDeepLink.fromClipboard(text)
} catch (t: Throwable) {
    SecondClassDeepLink.None
}

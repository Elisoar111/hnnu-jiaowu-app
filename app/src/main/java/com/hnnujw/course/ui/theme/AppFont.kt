package com.hnnujw.course.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.hnnujw.course.R
import com.hnnujw.course.manager.AppFontOption
import com.hnnujw.course.manager.AppearanceSettingsManager
import com.hnnujw.course.manager.CustomFontStore

/**
 * 苹方字体族（随包内置，仅 Regular / Medium / Semibold 三个字重文件）。
 *
 * 权重映射口径：PingFang SC 官方没有比 Semibold 更重的字重，也没有比
 * Regular 更轻的独立文件。所以 Bold / ExtraBold / Black 都落在 Semibold
 * 上、Light 及以下都落在 Regular 上——比起让系统拿 Regular 硬加粗出糊字，
 * 用真实存在的最接近字重渲染效果好得多。
 *
 * 只带三个字重而不是六个：App 的 Typography 实际用到的只有
 * Normal / Medium / SemiBold / Bold 四档，其余档位纯冗余，
 * 每个文件 10MB+，能省则省。
 */
val PingFangFontFamily = FontFamily(
    Font(R.font.pingfang_sc_regular, FontWeight.Normal),
    Font(R.font.pingfang_sc_regular, FontWeight.Light),
    Font(R.font.pingfang_sc_regular, FontWeight.ExtraLight),
    Font(R.font.pingfang_sc_regular, FontWeight.Thin),
    Font(R.font.pingfang_sc_medium, FontWeight.Medium),
    Font(R.font.pingfang_sc_semibold, FontWeight.SemiBold),
    Font(R.font.pingfang_sc_semibold, FontWeight.Bold),
    Font(R.font.pingfang_sc_semibold, FontWeight.ExtraBold),
    Font(R.font.pingfang_sc_semibold, FontWeight.Black)
)

/**
 * 主题层取当前应用字体。
 *
 * 以（字体选项，自定义字体内容版本）为 remember 的 key：选项切换或重新
 * 导入字体重建 FontFamily，同一文件期间 Typeface 只加载一次——
 * [CustomFontStore.loadTypeface] 走的是磁盘 IO + 字体解析，不能放组合里裸调。
 *
 * 自定义字体用 [FontFamily] 的 Typeface 包装（而非逐字重 Font 映射）：
 * 用户导入的 ttf / otf 通常只有单一字重，交给平台按需合成粗体，
 * 比把所有权重都指到同一个文件、粗体加不出来要正确。
 *
 * 自定义字体文件丢失/损坏时回退系统字体（管理器在启动时已做同样的兜底，
 * 这里是运行期被清理后的第二道网）。
 */
@Composable
fun rememberAppFontFamily(): FontFamily {
    val context = LocalContext.current
    val option = AppearanceSettingsManager.appFont
    val customVersion = AppearanceSettingsManager.customFontVersion
    return remember(option, customVersion) {
        when (option) {
            AppFontOption.System -> FontFamily.Default
            AppFontOption.Apple -> PingFangFontFamily
            AppFontOption.Custom ->
                CustomFontStore.loadTypeface(context)?.let { FontFamily(it) }
                    ?: FontFamily.Default
        }
    }
}

package com.hnnujw.course.secondclass

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.regex.Pattern

/**
 * 盯住「量尺寸那次 [android.graphics.BitmapFactory.decodeStream] 的返回值进了 elvis」这个坑。
 *
 * `inJustDecodeBounds = true` 时 `decodeStream` **按契约返回 null**，只把尺寸写进 Options。
 * 所以写成一行的
 *
 * `openInputStream(uri)?.use { decodeStream(it, null, bounds) } ?: return null`
 *
 * 时，elvis 命中的永远是 decodeStream 的那个 null，而不是"流没打开" —— 于是**任何图都在真正
 * 解码之前直接返回 null**。用户看到的现象是「选了图却提示识别不到二维码」。
 *
 * 本仓库已经踩过三次：`WallpaperCropStore`、`WallpaperImageStore`（这两处都留了注释告诫），
 * 以及 `QrImageDecoder`（新增的相册识别二维码，注释没拦住第三次）。所以这次用测试盯住写法。
 *
 * 判据是**变量名**而不是笼统的形状：只把「绑定了带 `inJustDecodeBounds = true` 的 Options 的
 * 那个变量」参与的真解码调用算违规。真解码（传 `options`，带 inSampleSize）的返回值接 elvis 是
 * **合法**的 —— `decodeStream` 那时确实会返回 Bitmap 或 null —— 不能一起打掉。
 *
 * 说实话这是源码级断言，不是语义分析：它抓的是这个已知形状，变量改名或写成嵌套 lambda 会漏。
 * 但 decodeStream 需要 Android Bitmap，纯 JVM 单测跑不起来，守住写法是这里性价比最高的办法。
 */
class ImageDecodeElvisGuardTest {

    /** `val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }` 里的那个变量名。 */
    private val boundsVarPattern = Pattern.compile(
        "val\\s+(\\w+)\\s*=\\s*BitmapFactory\\.Options\\(\\)\\.apply\\s*\\{\\s*inJustDecodeBounds\\s*=\\s*true",
    )

    /** `decodeStream(<实参>) } ?:` —— use 的返回值进了 elvis（跨行也算）。 */
    private val elvisDecodePattern = Pattern.compile(
        "decodeStream\\(([^)]*)\\)\\s*\\}\\s*\\?[?:]",
        Pattern.DOTALL,
    )

    @Test
    fun measureStepResultMustNotEnterElvis() {
        val offenders = mutableListOf<String>()
        var scanned = 0
        var withBoundsVar = 0
        for (file in kotlinSources()) {
            scanned++
            val code = file.readText().stripComments()
            if (!code.contains("inJustDecodeBounds")) continue

            val boundsVars = boundsVarPattern.matcher(code)
                .let { m -> generateSequence { if (m.find()) m.group(1) else null }.toList() }
            if (boundsVars.isEmpty()) continue
            withBoundsVar++

            val matcher = elvisDecodePattern.matcher(code)
            while (matcher.find()) {
                val args = matcher.group(1)
                val hit = boundsVars.firstOrNull { name -> Regex("\\b$name\\b").containsMatchIn(args) }
                if (hit != null) {
                    offenders += "${file.relativePath()}：量尺寸用的 '$hit' 被传进了带 elvis 的 decodeStream"
                }
            }
        }

        // 先自证不是空跑 —— 扫不到文件、或连一个"量尺寸"的变量都没找到，这条护栏等于没测，
        // 那种"通过"比不写测试更危险。
        assertTrue(
            "只扫到 $scanned 个 Kotlin 源文件，护栏形同虚设（工作目录 ${System.getProperty("user.dir")}）",
            scanned > 50,
        )
        assertTrue("没有任何文件出现带 inJustDecodeBounds 的 Options 变量，护栏形同虚设", withBoundsVar > 0)

        assertTrue(
            "以下位置把 inJustDecodeBounds 那次 decodeStream 的返回值接进了 elvis，" +
                "会让任何图都在解码前就返回 null：\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun qrDecoderStillDoesRealDecode() {
        val file = kotlinSources().firstOrNull { it.name == "QrImageDecoder.kt" }
        assertTrue("QrImageDecoder.kt 不见了：相册识别二维码的整条链路都失效", file != null)

        val code = file!!.readText()
        assertTrue(
            "QrImageDecoder.kt 里找不到量尺寸那一步（inJustDecodeBounds）",
            code.contains("inJustDecodeBounds"),
        )
        assertTrue(
            "QrImageDecoder.kt 里找不到带 inSampleSize 的真解码步骤 —— 只剩量尺寸等于什么都解不出来",
            code.contains("inSampleSize = sample"),
        )
    }

    private fun kotlinSources(): List<File> =
        File(ascendUntil("用户手册.md"), "app/src/main/java")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    /** 去掉 `//` 行注释与 KDoc 续行 —— 讲这个坑的注释本身就含违规写法，不能算违规。 */
    private fun String.stripComments(): String = lineSequence()
        .filterNot { line ->
            val t = line.trimStart()
            t.startsWith("//") || t.startsWith("*")
        }
        .joinToString("\n")

    private fun File.relativePath(): String = relativeTo(ascendUntil("用户手册.md")).path

    /** Gradle 单测的工作目录是模块目录（app/），这里向上找，换个跑法也不会挂。 */
    private fun ascendUntil(marker: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, marker).isFile) return dir
            dir = dir.parentFile
        }
        error("找不到 $marker（当前工作目录 ${System.getProperty("user.dir")}）")
    }
}

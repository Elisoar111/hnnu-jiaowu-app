package com.hnnujw.course.network

import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * 安装包完整性校验的结论。
 *
 * 三态而不是布尔，是因为"没有校验和可比"和"比对不通过"是两件完全不同的事：
 * 前者是我们拿不到声明值（Gitee 的 Release 不强制带校验和），只能退回到已有的
 * 签名校验；后者是**明确对不上**，那这个包一定不能装。
 */
enum class AppUpdateIntegrityStatus {
    /** 没有可比对的校验和（发布方没写，或者读不到文件），退回签名校验。 */
    Unchecked,

    /** 与发布说明里声明的 SHA-256 一致。 */
    Trusted,

    /** 与声明的 SHA-256 不一致：包被替换或传输损坏，必须拒绝安装。 */
    Mismatch,
}

/** 一次校验的结果，附带可直接上屏的说明。 */
data class AppUpdateIntegrityReport(
    val status: AppUpdateIntegrityStatus,
    val summary: String,
    val expectedSha256: String? = null,
    val actualSha256: String? = null,
) {
    /** 只有明确对不上才拦。拿不到声明值时不能拦，否则大多数发布都装不上。 */
    val canInstall: Boolean get() = status != AppUpdateIntegrityStatus.Mismatch
}

/**
 * 更新包完整性校验（第 16 轮，借鉴参考项目的 build-verification 思路）。
 *
 * 参考项目（BiliPai）会在装之前核对安装包的 SHA-256 与 GitHub Release 里的
 * `build-metadata.json` / `checksums.txt`，对不上就拒绝安装。它那套依赖
 * CI 产出的元数据文件与 immutable release，本项目的发布走 Gitee Release，
 * 拿不到这些东西 —— 所以这里只借它的**思路**，落成一个不依赖服务端格式的版本：
 *
 * 1. 发布说明正文里可以写一行 `sha256: <64 位十六进制>`（大小写、`SHA-256：`
 *    这类写法都认）。写了就核对，对不上**拒绝安装**；
 * 2. 没写就退回到 [AppDownloader.isTrustedUpdate] 的签名比对（这个一直都有）；
 * 3. 无论哪条路，只要校验没通过，都不允许"点了没反应" —— 调用方必须给出
 *    「重新下载」这样的明确出路。
 *
 * 为什么值得做：强制更新意味着用户**没有拒绝的余地**，那"装上去的东西确实是
 * 发布方构建的那个包"就必须被证明，而不只是相信 URL 没被别人改过。
 *
 * 这个文件刻意做成纯 Kotlin（不依赖 Android API），方便以后加单元测试。
 */
object AppUpdateIntegrity {

    /** 一次读盘 64KB：手机上 20MB 的包也就几百次读，耗时可忽略，内存不爆。 */
    private const val BUFFER_SIZE = 64 * 1024

    /**
     * 发布说明里声明校验和的行。
     *
     * 允许前后有 Markdown 符号（列表项 `-`、加粗 `**`、行内代码 `` ` ``），
     * 中间分隔符接受 `:` 与全角 `：`，值必须是**完整的 64 位**十六进制，
     * 这样正文里碰巧出现的短哈希不会被误当成本包的校验和。
     */
    private val declaredSha256Line = Regex(
        """(?im)^[\s>*`\-]*sha-?256[\s*`]*[:：][\s*`]*([0-9a-f]{64})\b"""
    )

    /** 从发布说明正文里摘出声明的 SHA-256（小写）；没有就返回 null。 */
    fun parseDeclaredSha256(body: String): String? =
        declaredSha256Line.find(body)?.groupValues?.getOrNull(1)?.lowercase(Locale.US)

    /**
     * 展示前把校验和行摘掉。
     *
     * 那一行是给机器读的，直接露在弹窗里就是一串没人看得懂的十六进制，
     * 还会把用户真正想看的更新说明挤走几行。
     */
    fun stripDeclaredSha256(body: String): String = body.replace(declaredSha256Line, "")

    /**
     * 流式计算文件的 SHA-256。
     *
     * 不用 `readBytes()` 一把读进来：安装包二三十兆，整读会在低端机上瞬间申请
     * 一块大内存，赶上系统内存紧张就直接 OOM 了。
     */
    fun sha256Of(file: File): String? = runCatching {
        if (!file.isFile || file.length() <= 0L) return null
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().toHex()
    }.getOrNull()

    /**
     * 校验一个已下载的文件。
     *
     * @param expected 发布说明里声明的 SHA-256；为空表示这一版没声明。
     */
    fun verify(file: File, expected: String?, displayVersion: String? = null): AppUpdateIntegrityReport {
        val declared = expected?.trim()?.lowercase(Locale.US)?.takeIf { it.length == 64 }
        if (declared == null) {
            return AppUpdateIntegrityReport(
                status = AppUpdateIntegrityStatus.Unchecked,
                summary = "本次发布没有提供校验和，已改用安装包签名校验",
            )
        }
        val actual = sha256Of(file)
        if (actual == null) {
            // 读不出文件等同于"包不完整"，交给安装器那边报错更准确，这里不拦。
            return AppUpdateIntegrityReport(
                status = AppUpdateIntegrityStatus.Unchecked,
                summary = "安装包读不出来，请点「重新下载」",
                expectedSha256 = declared,
            )
        }
        return if (actual.equals(declared, ignoreCase = true)) {
            AppUpdateIntegrityReport(
                status = AppUpdateIntegrityStatus.Trusted,
                summary = "安装包校验通过（SHA-256 与发布说明一致）",
                expectedSha256 = declared,
                actualSha256 = actual,
            )
        } else {
            AppUpdateIntegrityReport(
                status = AppUpdateIntegrityStatus.Mismatch,
                summary = buildString {
                    append("安装包校验失败：与发布说明里的 SHA-256 不一致，已阻止安装。")
                    displayVersion?.takeIf { it.isNotBlank() }?.let {
                        append("请点「重新下载」重试 v")
                        append(it)
                        append("。")
                    } ?: append("请点「重新下载」重试。")
                },
                expectedSha256 = declared,
                actualSha256 = actual,
            )
        }
    }

    /** 展示用短哈希：完整 64 位塞进弹窗里没人看，前 12 位足够核对。 */
    fun shortHash(hash: String?): String? =
        hash?.trim()?.lowercase(Locale.US)?.takeIf { it.length >= 12 }?.substring(0, 12)

    private fun ByteArray.toHex(): String {
        val digits = "0123456789abcdef"
        val out = StringBuilder(size * 2)
        for (byte in this) {
            val value = byte.toInt() and 0xFF
            out.append(digits[value ushr 4])
            out.append(digits[value and 0x0F])
        }
        return out.toString()
    }
}
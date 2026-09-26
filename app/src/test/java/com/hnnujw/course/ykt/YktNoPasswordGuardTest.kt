package com.hnnujw.course.ykt

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 护栏：**一卡通链路永远不接触用户密码**。
 *
 * ## 为什么需要这条护栏（用户明确提出的要求）
 *
 * 用户对"保存密码"这件事有明确的安全要求。而代码里的风险不是"现在存了密码"
 * ——现在没有——而是**将来有人顺手加上**：
 *
 * 一卡通只有 SSO（[YktStore.loginUrl]），登录全程在官方页面完成，
 * 本应用拿到的只是服务端下发的会话令牌。但只要代码里存在一个
 * `savePassword(...)`，后人为了让"令牌过期后自动续期"，就会很自然地去存用户口令，
 * 而**那个决定一旦做出就收不回来了**（用户以为从没交出口令）。
 *
 * 因此这里把边界写成可执行断言：
 * 1. 一卡通包内**不出现任何密码存取 API 的定义**；
 * 2. 一卡通包内**不出现 `CredentialStore.save` 调用**；
 * 3. 登录页（WebView）不出现密码输入/读取逻辑。
 *
 * ## 已知边界
 *
 * 这是**源码级**断言，不是语义分析：它靠标识符名与调用形状匹配，
 * 变量改名成 `passwd`、或把凭据存取藏进别的包会漏。
 * 但它挡住的正是"顺手加回来"这一最常见的退化路径，性价比最高。
 *
 * 反面证据（说明这条护栏有意义）：`YktStore` 历史上确实有过
 * `hasPassword` / `savePassword` / `loadPassword` 三个方法，
 * 调用点为零却一直留着 —— 就是这类"待被顺手用起来"的钩子。
 */
class YktNoPasswordGuardTest {

    @Test
    fun yktPackageDeclaresNoPasswordStorage() {
        val offenders = mutableListOf<String>()
        val files = yktSources()

        for (file in files) {
            val code = file.readText().stripComments()
            // 定义形如 `fun savePassword(context, accountKey, password: String)`
            for (line in code.lineSequence()) {
                val trimmed = line.trim()
                if (!trimmed.startsWith("fun ")) continue
                val name = trimmed.removePrefix("fun ").substringBefore('(').trim()
                if (name.contains("Password", ignoreCase = true) ||
                    name.contains("Passwd", ignoreCase = true) ||
                    name.contains("Pwd", ignoreCase = true)
                ) {
                    offenders += "${file.relativePath()}：定义了密码存取方法 `$name`"
                }
            }
            // 变量/属性形如 `val savedPassword: String`
            if (Regex("(?i)\\bval\\s+\\w*(password|passwd|pwd)\\w*\\s*:").containsMatchIn(code)) {
                offenders += "${file.relativePath()}：声明了密码字段"
            }
            // 明面上的凭据写入
            if (Regex("CredentialStore\\.(save|store|put)\\s*\\(").containsMatchIn(code)) {
                offenders += "${file.relativePath()}：调用了 CredentialStore 的写入接口"
            }
        }

        // 先自证不是空跑：扫不到文件时这条护栏等于没测，那种"通过"更危险
        assertTrue(
            "只扫到 ${files.size} 个一卡通源文件，护栏形同虚设" +
                "（工作目录 ${System.getProperty("user.dir")}）",
            files.size >= 5,
        )
        assertTrue(
            "一卡通链路里出现了密码存取，破坏了「永不接触用户一卡通密码」的边界：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /**
     * 登录页必须是 WebView + 令牌，**不能变成账号密码表单**。
     *
     * 判据：存在令牌落盘调用，且令牌来自页面（Web Storage / Cookie），
     * 且不存在密码输入控件。
     *
     * ## 为什么断言 `CAPTURE_TOKEN_JS`（而不是原来的 `readAuthCookie`）
     *
     * 令牌的主路径已从"读 Cookie"改成"读 `sessionStorage`"
     * （真机 + 反混淆双重确证，见 [YktClient.extractAuthFromStorageJson]）。
     * `readAuthCookie` 退为兜底、不再被登录页调用；护栏必须盯住**真正在用**
     * 的取令牌路径，否则会变成"测试在测一个没人调用的函数"。
     */
    @Test
    fun loginActivityCapturesTokenInsteadOfAskingForPassword() {
        val file = File(projectRoot(), "app/src/main/java/com/hnnujw/course/YktLoginActivity.kt")
        assertTrue("YktLoginActivity.kt 不见了：一卡通没有可用的登录入口", file.isFile)

        val code = file.readText()
        assertTrue(
            "登录页没有保存令牌（YktStore.saveToken）—— 登录成功也不会生效",
            code.contains("YktStore.saveToken"),
        )
        assertTrue(
            "登录页没有从页面里取令牌 —— 站点只有 SSO，不能靠表单换 token",
            code.contains("CAPTURE_TOKEN_JS"),
        )
        assertTrue(
            "登录页没有解析页面令牌（YktClient.extractAuthFromStorageJson）",
            code.contains("extractAuthFromStorageJson"),
        )
        assertTrue(
            "登录页出现了密码输入控件：一卡通只有 SSO，不应收集用户在应用内输入的一卡通密码",
            !code.contains("PasswordVisualTransformation") && !code.contains("savePassword"),
        )
    }

    /**
     * 一卡通 Store 不应再有 `hasPassword` 之类"待被顺手用起来"的钩子。
     */
    @Test
    fun storeKeepsNoDormantPasswordHook() {
        val file = File(projectRoot(), "app/src/main/java/com/hnnujw/course/ykt/YktStore.kt")
        val code = file.readText()
        for (name in listOf("hasPassword", "savePassword", "loadPassword")) {
            assertTrue(
                "YktStore 里又出现了 `$name` —— 这是个一旦存在就会被用来存用户口令的钩子，" +
                    "请改用 refresh_token 做续期（见 YktStore 内的说明）",
                !code.contains("fun $name"),
            )
        }
    }

    /**
     * 只读的官方页面查看器 [com.hnnujw.course.YktWebActivity] **不导出任何凭据**。
     *
     * ## 为什么单独守它
     *
     * `ykt/` 包扫不到 `YktWebActivity`（它在 `com.hnnujw.course` 顶层），
     * 而它**与登录页共用 `ykt` 存储目录**（这样才能免二次认证看到已登录的账单页）。
     * 共存储 + 同域名，使它成为"顺手把令牌导出逻辑也复制过来"的高风险点。
     *
     * 边界保持不变：**浏览页只打开外部给的 URL，不读、不采、不落盘任何令牌**。
     */
    @Test
    fun readOnlyWebViewActivityExportsNoCredentials() {
        val file = File(projectRoot(), "app/src/main/java/com/hnnujw/course/YktWebActivity.kt")
        assertTrue("YktWebActivity.kt 不见了：消费明细没有可打开的官方页面入口", file.isFile)

        val code = file.readText().stripComments()
        // 不得采集/落盘令牌
        assertTrue(
            "只读浏览页里出现了令牌采集/落盘：它不应接触任何凭据（那是登录页的职责）",
            !code.contains("CAPTURE_TOKEN_JS") &&
                !code.contains("saveToken") &&
                !code.contains("extractAuthFromStorageJson") &&
                !code.contains("readAuthCookie"),
        )
        // 不得出现密码控件
        assertTrue(
            "只读浏览页里出现了密码相关逻辑",
            !code.contains("PasswordVisualTransformation") && !code.contains("savePassword"),
        )
        // 自证不是空跑：它至少得是个真的 WebView 载体
        assertTrue(
            "YktWebActivity 里没有 WebView —— 护栏形同虚设",
            code.contains("WebView(") && code.contains("EXTRA_URL"),
        )
    }

    /**
     * 级联取数失败时**不得丢失真实原因**，也不得把原因写死成"网络"。
     *
     * ## 为什么守这条（2026-09-25 用户报"校区列表加载失败，是否没有位置权限"）
     *
     * 用户的困惑暴露了两个真问题：
     * 1. 旧实现把失败一律写成"校区列表加载失败"，**丢掉了** `YktException.message`
     *    （"登录已失效，请重新登录" / "HTTP 500" / "返回了无法识别的数据"）。
     *    三种完全不同的处置方式被压成同一句话，用户只能猜。
     * 2. 弹窗里写死一句"请检查网络后重试"，把"登录失效"也归因成网络 ——
     *    用户会去切 WiFi 复重试，而正确动作是重新登录。
     *
     * 另外：**这条链路与位置权限毫无关系**（纯 HTTP 查询接口，校区来自服务端
     * `comboxCampus`，Manifest 里也没有任何定位权限）。错误文案既不该暗示
     * "权限不足"，也不该暗示"一定是网络"。
     */
    @Test
    fun pickerFailureKeepsRealReasonAndNeverBlamesNetworkOrPermission() {
        val screen = File(projectRoot(), "app/src/main/java/com/hnnujw/course/ui/screen/YktScreen.kt")
        assertTrue("YktScreen.kt 不见了：级联选择入口无从校验", screen.isFile)

        val code = screen.readText().stripComments()
        // 必须把原始失败原因透出来，而不是只印一句写死的标签
        assertTrue(
            "级联取数失败没有带上真实原因（YktStore.handleFailure）—— " +
                "用户无法区分「登录失效」与「网络/服务端故障」",
            code.contains("fun failureText(") && code.contains("YktStore.handleFailure("),
        )
        // 令牌缺失要明确提示登录，而不是混进"加载失败"
        assertTrue(
            "令牌缺失时没有给出「先登录」的明确提示",
            code.contains("登录已失效，请先登录"),
        )
        // 不得出现"权限"归因（这条链路不需要任何系统权限）
        for (bad in listOf("位置权限", "定位权限", "没有权限", "permission")) {
            assertTrue(
                "级联失败文案里出现了权限相关表述（`$bad`）—— 这条路是纯 HTTP 查询，" +
                    "不存在权限问题，写出来只会误导用户去改系统设置",
                !code.contains(bad),
            )
        }

        val dialog = File(
            projectRoot(),
            "app/src/main/java/com/hnnujw/course/ui/system/SceneWheelPickerDialog.kt",
        )
        assertTrue("SceneWheelPickerDialog.kt 不见了", dialog.isFile)
        val dialogCode = dialog.readText().stripComments()
        assertTrue(
            "选择弹窗把失败原因写死成「检查网络」—— 会把「登录失效」误导成网络问题",
            !dialogCode.contains("请检查网络后重试"),
        )
    }

    private fun yktSources(): List<File> =
        File(projectRoot(), "app/src/main/java/com/hnnujw/course/ykt")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    private fun File.relativePath(): String = relativeTo(projectRoot()).path

    /**
     * 去掉注释与 KDoc。
     *
     * `YktStore` 里**故意**写了大量解释"为什么不存密码"的注释，
     * 其中包含 `savePassword` 这样的字面量 —— 那是文档不是代码，
     * 不剥掉会把护栏自己判成违规。
     */
    private fun String.stripComments(): String = lineSequence()
        .filterNot { line ->
            val t = line.trimStart()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }
        .joinToString("\n")

    /** Gradle 单测工作目录是模块目录（app/），向上找工程根。 */
    private fun projectRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "用户手册.md").isFile) return dir
            dir = dir.parentFile
        }
        error("找不到工程根（当前工作目录 ${System.getProperty("user.dir")}）")
    }
}

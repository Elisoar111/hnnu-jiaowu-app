package com.hnnujw.course.review

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 随包内置题库（`assets/review/` 下的 4 份 JSON）的首次自动导入。
 *
 * 设计要点（每一条都对应一个真实踩过的坑）：
 *
 * 1. **只在「一个题库都没有」时导入**。判定条件是 `overview().bankCount == 0`，
 *    而不是「内置库缺失就补」——因为 [ReviewDatabase.importBank] 对同名库是
 *    **覆盖语义**：会连带 `records` / `wrong_book` / `favorites` 一起删。
 *    如果按「缺失就补」在用户答题途中触发，用户记录会被无声清空。
 *    额外再加一道 [ReviewDatabase.bankExists] 前置检查，双保险。
 *
 * 2. **必须走 [Dispatchers.IO]**。四个 JSON 合计约 700KB，最大单份 255KB，
 *    `readBytes` + `JSONTokener` 解析 + 1059 次事务插入放主线程会 ANR。
 *
 * 3. **显式 UTF-8 解码**。用 `assets.open(name).readBytes().toString(Charsets.UTF_8)`，
 *    不用 `bufferedReader().readText()` —— 后者按平台默认编码读，
 *    部分设备（默认 GBK 的旧 ROM）会把中文题干解成乱码。
 *
 * 4. **每个库独立 `runCatching`**。单个库 JSON 损坏 / 解析异常不能拖垮整个首页，
 *    其余库照常导入。
 *
 * 隐私：日志只记库名与条数，**题干内容绝不进日志**。
 */
object BundledBanks {

    /** 一个内置题库的清单项。 */
    data class Spec(
        /** assets 内的相对路径，如 `review/mao_ze_lishi.json`。 */
        val assetPath: String,
        /** 题库名；必须与 JSON 内的 `name` 完全一致，否则 [ReviewDatabase.bankExists] 判不出重复。 */
        val bankName: String,
        /** 随包时的题数，仅用于日志与自检，不参与导入逻辑。 */
        val expectedCount: Int
    )

    /**
     * 四个内置库，共 1059 题。
     *
     * 名称与各 JSON 内的 `name` 字段逐字一致（含全角括号），
     * 改名字会让 [pendingSpecs] 的去重失效，进而触发覆盖导入。
     */
    val SPECS: List<Spec> = listOf(
        Spec("review/mao_zhuyi_principles.json", "马克思主义基本原理", 300),
        Spec("review/sixiang_dedao_fazhi.json", "思想道德与法治", 258),
        Spec("review/mao_ze_lishi.json", "中国近现代史纲要", 320),
        Spec("review/mao_gai_dan_xuan.json", "毛泽东思想和中国特色社会主义理论体系概论", 181)
    )

    /**
     * 内置库改名对照（旧名 → 新名，2026-10 起启用正式课名）。
     *
     * 老设备上已按旧名导入的库不会因改名被重复导入（导入门槛是「一个库都没有」），
     * 但库名会停留在旧名 —— 由 [ReviewDatabase.migrateBundledBankNames] 在首次取库时
     * 一次性改名（幂等，新名被占用时跳过）。**改 SPECS 名字时必须同步在这里登记旧名**。
     */
    val RENAMED_BANKS: Map<String, String> = mapOf(
        "毛概（2023版）" to "毛泽东思想和中国特色社会主义理论体系概论",
        "马克思主义基本原理（选择题）" to "马克思主义基本原理"
    )

    /** 随包内置题目的总数，用于「已内置 N 题」提示。 */
    const val TOTAL_QUESTION_COUNT: Int = 1059

    /**
     * 从已有题库名里挑出**尚未导入**的内置库。
     *
     * 纯函数，不碰 Android API，可 JVM 单测。存在的库一律跳过：
     * 宁可「这次少导一个库」，也不要覆盖掉用户的答题记录。
     *
     * @param existingNames 库里已有的题库名（[ReviewDatabase.listBanks] 的 name 集合）。
     */
    fun pendingSpecs(existingNames: Set<String>): List<Spec> {
        val existing = existingNames.map { it.trim() }.toSet()
        return SPECS.filter { it.bankName !in existing }
    }

    /**
     * 导入所有尚未存在的内置题库，返回实际写入的题目总数。
     *
     * 已在库里的库会被跳过（不覆盖）；单库失败只记日志，不影响其余库。
     * 整体已在 [Dispatchers.IO] 上执行，调用方无需再包一层。
     */
    suspend fun importAll(context: Context, db: ReviewDatabase): Int = withContext(Dispatchers.IO) {
        importAllBlocking(context, db)
    }

    /**
     * [importAll] 的同步实现，便于在测试或非协程场景直接调用。
     * **调用方负责保证不在主线程调用。**
     */
    fun importAllBlocking(context: Context, db: ReviewDatabase): Int {
        val existingNames = db.listBanks().map { it.name }.toSet()
        val pending = pendingSpecs(existingNames)
        if (pending.isEmpty()) {
            Log.i(TAG, "importAll: 无待导入的内置库（已有 ${existingNames.size} 个题库）")
            return 0
        }
        var total = 0
        pending.forEach { spec ->
            val imported = runCatching {
                val text = readAsset(context, spec.assetPath)
                val bank = parseJsonBank(text, fallbackName = spec.bankName)
                require(bank.questions.isNotEmpty()) {
                    bank.errors.firstOrNull() ?: "题库里没有可用的题目"
                }
                // 双保险：解析完到落库前再确认一次同名库没被别的线程插进来。
                if (db.bankExists(spec.bankName)) {
                    Log.i(TAG, "importAll: 「${spec.bankName}」已存在，跳过以免覆盖答题记录")
                    return@runCatching 0
                }
                db.importBank(bank.name.ifBlank { spec.bankName }, bank.questions)
                bank.questions.size
            }.onFailure { e ->
                // 只记 message，不记题干 / 解析内容。
                Log.w(TAG, "importAll: 内置库「${spec.bankName}」导入失败：${e.message}")
            }.getOrDefault(0)
            total += imported
            if (imported > 0) {
                // 注意不逐条打印 bank.errors —— 那些描述里含 id 与字段值，量大且没必要。
                Log.i(TAG, "importAll: 「${spec.bankName}」导入 $imported 题")
            }
        }
        Log.i(TAG, "importAll: 本次共导入 $total 题")
        return total
    }

    /**
     * 读一个 assets 文件为 UTF-8 文本。
     *
     * 刻意**不用** `bufferedReader().readText()`：那个重载按平台默认字符集解码，
     * 中文题库在默认 GBK 的设备上会整片乱码。
     */
    private fun readAsset(context: Context, assetPath: String): String =
        context.assets.open(assetPath).use { input ->
            input.readBytes().toString(Charsets.UTF_8)
        }

    private const val TAG = "BundledBanks"
}
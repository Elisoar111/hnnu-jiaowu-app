package com.hnnujw.course.review

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 随包内置题库（`assets/review/` 下的 4 份 JSON）。
 *
 * 🔴 2026-10 起**不再自动全量导入**。上一版的行为是"首次进复习页且一个题库都没有时，
 * 把 4 个内置库全部导入"，结果是用户一进来就被塞了 1059 道题，没有选择余地。
 * 现在内置库只作为**题库仓库里的可选项**出现（见 [pendingSpecs]），用户点「取出」
 * 才真正导入（[importOne]）。
 *
 * 设计要点（每一条都对应一个真实踩过的坑）：
 *
 * 1. **导入前必须查重**。判定走 [ReviewDatabase.bankExists]，因为
 *    [ReviewDatabase.importBank] 对同名库是**覆盖语义**：会连带
 *    `records` / `wrong_book` / `favorites` 一起删。若在用户答题途中对已存在的库
 *    再导一次，用户记录会被无声清空。
 *
 * 2. **必须走 [Dispatchers.IO]**。四个 JSON 合计约 700KB，最大单份 255KB，
 *    `readBytes` + `JSONTokener` 解析 + 几百次事务插入放主线程会 ANR。
 *
 * 3. **显式 UTF-8 解码**。用 `assets.open(name).readBytes().toString(Charsets.UTF_8)`，
 *    不用 `bufferedReader().readText()` —— 后者按平台默认编码读，
 *    部分设备（默认 GBK 的旧 ROM）会把中文题干解成乱码。
 *
 * 4. **每个库独立 `runCatching`**。单个库 JSON 损坏 / 解析异常不能拖垮整个首页，
 *    其余库照常可用。
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
     *
     * 🔴 演示题库（[DEMO_SPEC]）**刻意不在这里** —— 它在仓库里的存在形式是"待取出的
     * 内置库"，而演示题库要一进来就在「我的题库」里，见 [ensureDemoBank]。
     */
    val SPECS: List<Spec> = listOf(
        Spec("review/mao_zhuyi_principles.json", "马克思主义基本原理", 300),
        Spec("review/sixiang_dedao_fazhi.json", "思想道德与法治", 258),
        Spec("review/mao_ze_lishi.json", "中国近现代史纲要", 320),
        Spec("review/mao_gai_dan_xuan.json", "毛泽东思想和中国特色社会主义理论体系概论", 181)
    )

    /**
     * 随包**演示题库**：两章、每章 4 题，用来让人一进模块就有东西可练。
     *
     * 与 [SPECS] 里的四个库有三点不同，每一点都是刻意的：
     *
     *  1. **不进题库仓库**。它是"新手指引"，让用户先看到一个能点的东西；
     *     放进仓库反而要求用户先学会"取出"才看得到题。所以它不参与 [pendingSpecs]，
     *     由 [ensureDemoBank] 直接导入并**取出**。
     *  2. **只播种一次**。播种过就写一个标记（[ReviewPrefs.isDemoBankSeeded]），
     *     用户删掉它之后**不会再长出来** —— 否则删了又出现，像个删不掉的流氓软件。
     *  3. **可以随便删**。它就是个普通题库，在「题库仓库 → 题库管理」里点「删除」即可。
     */
    val DEMO_SPEC: Spec = Spec("review/demo.json", "演示题库", 8)

    /**
     * 首次进入题库练习模块时播种演示题库。返回新导入的 bankId；无需播种 / 失败返回 null。
     *
     * 幂等且廉价：已播种时只读一次 SharedPreferences 就返回。
     * 调用方可以直接在每次进首页时无脑调一次。
     */
    suspend fun ensureDemoBank(context: Context, db: ReviewDatabase): String? {
        if (ReviewPrefs.isDemoBankSeeded(context)) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val id = if (db.bankExists(DEMO_SPEC.bankName)) {
                    null
                } else {
                    importOne(context, db, DEMO_SPEC)
                }
                // 本来就有、或这次导入成功 ⇒ 记下"已播种"。
                // 导入失败则**不记**，下次进模块再试一次（坏资产 / 磁盘满都属可恢复）。
                if (id != null || db.bankExists(DEMO_SPEC.bankName)) {
                    ReviewPrefs.setDemoBankSeeded(context, true)
                }
                id
            }.onFailure { e ->
                Log.w(TAG, "ensureDemoBank: 演示题库播种失败：${e.message}")
            }.getOrNull()
        }
    }

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
     * 从已有题库名里挑出**尚未导入**的内置库 —— 即题库仓库里"还能取出来的内置项"。
     *
     * 纯函数，不碰 Android API，可 JVM 单测。存在的库一律跳过：
     * 宁可「少显示一项」，也不要覆盖掉用户的答题记录。
     *
     * @param existingNames 库里已有的题库名（[ReviewDatabase.listBanks] 的 name 集合）。
     */
    fun pendingSpecs(existingNames: Set<String>): List<Spec> {
        val existing = existingNames.map { it.trim() }.toSet()
        return SPECS.filter { it.bankName !in existing }
    }

    /**
     * 把一个内置题库导入并**取出**（出现在首页「我的题库」里）。返回 bankId；失败返回 null。
     *
     * 整体在 [Dispatchers.IO] 上执行，调用方无需再包一层。
     * 已存在同名库时**不覆盖**，直接返回 null 并记日志 —— 用户的答题记录比"重新导一遍"重要。
     */
    suspend fun importOne(context: Context, db: ReviewDatabase, spec: Spec): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                if (db.bankExists(spec.bankName)) {
                    Log.i(TAG, "importOne: 「${spec.bankName}」已存在，跳过以免覆盖答题记录")
                    return@runCatching null
                }
                val text = readAsset(context, spec.assetPath)
                val bank = parseJsonBank(text, fallbackName = spec.bankName)
                require(bank.questions.isNotEmpty()) {
                    bank.errors.firstOrNull() ?: "题库里没有可用的题目"
                }
                // 解析完到落库前再确认一次同名库没被别的线程插进来。
                if (db.bankExists(spec.bankName)) {
                    Log.i(TAG, "importOne: 「${spec.bankName}」已存在，跳过以免覆盖答题记录")
                    return@runCatching null
                }
                val bankId = db.importBank(
                    bank.name.ifBlank { spec.bankName },
                    bank.questions,
                    selected = true
                )
                // 注意不打印 bank.errors —— 那些描述里含 id 与字段值，量大且没必要。
                Log.i(TAG, "importOne: 「${spec.bankName}」导入 ${bank.questions.size} 题")
                bankId
            }.onFailure { e ->
                // 只记 message，不记题干 / 解析内容。
                Log.w(TAG, "importOne: 内置库「${spec.bankName}」导入失败：${e.message}")
            }.getOrNull()
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
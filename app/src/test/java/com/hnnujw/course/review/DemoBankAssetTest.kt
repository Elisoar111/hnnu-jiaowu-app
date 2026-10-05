package com.hnnujw.course.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 随包题库资产的**对账测试**：把 `app/src/main/assets/review/` 下的 JSON 与
 * `BundledBanks` 里的清单逐项对上。
 *
 * 为什么需要它：这份资产是**构建期才进包**的，代码里只有一个路径字符串。
 * 路径写错、文件名改了忘同步、JSON 里多加了个坏题 —— 编译全都通，单测也全绿，
 * 只有用户点了「取出」才会看到一个"取出失败"。尤其演示题库：它由
 * [BundledBanks.ensureDemoBank] 在首次进模块时静默导入，**失败时不弹任何提示**
 * （失败不写播种标记、下次再试），坏了根本没人知道。
 *
 * 另外钉住两条容易被顺手改坏的口径：
 *  - 演示题库**不在** [BundledBanks.SPECS] 里 ⇒ 不会出现在「题库仓库」；
 *  - 演示题库 JSON 里的 `name` 与 `DEMO_SPEC.bankName` **逐字一致** ⇒
 *    `bankExists` 判重有效，不会重复导入。
 */
class DemoBankAssetTest {

    // ── 演示题库 ────────────────────────────────────────────────────────

    @Test
    fun `演示题库资产存在且能被解析`() {
        val bank = parseJsonBank(loadAsset(BundledBanks.DEMO_SPEC.assetPath))
        assertTrue("不应有被跳过的题：${bank.errors}", bank.errors.isEmpty())
        assertEquals(
            "题数与 DEMO_SPEC.expectedCount 不一致",
            BundledBanks.DEMO_SPEC.expectedCount,
            bank.questions.size
        )
    }

    @Test
    fun `演示题库的名字与 DEMO_SPEC 逐字一致`() {
        // 判重口径就是名字（ReviewDatabase.bankExists）。差一个字就会重复导入，
        // 而 importBank 对同名库是**覆盖**语义，会把用户的答题记录清掉。
        val bank = parseJsonBank(loadAsset(BundledBanks.DEMO_SPEC.assetPath))
        assertEquals(BundledBanks.DEMO_SPEC.bankName, bank.name)
    }

    @Test
    fun `演示题库正好两章且每章三到五题`() {
        val questions = parseJsonBank(loadAsset(BundledBanks.DEMO_SPEC.assetPath)).questions
        val byChapter = questions.groupBy { it.chapter }
        assertEquals("应正好两章：${byChapter.keys}", 2, byChapter.size)
        byChapter.forEach { (chapter, list) ->
            assertTrue("「$chapter」只有 ${list.size} 题，少于 3 题", list.size >= 3)
            assertTrue("「$chapter」有 ${list.size} 题，多于 5 题", list.size <= 5)
        }
    }

    @Test
    fun `演示题库的题号唯一且题型合法`() {
        val questions = parseJsonBank(loadAsset(BundledBanks.DEMO_SPEC.assetPath)).questions
        assertEquals("题号不能重复", questions.size, questions.map { it.id }.distinct().size)
        questions.forEach { q ->
            assertTrue(
                "题型只能是 single / multi，实际 ${q.type}（id=${q.id}）",
                q.type == ReviewQuestion.TYPE_SINGLE || q.type == ReviewQuestion.TYPE_MULTI
            )
            // validate() 已经过了（errors 为空），这里再钉一次"多选至少两个答案"，
            // 因为它是本库唯一会被人工手改的数据。
            if (q.isMulti) {
                assertTrue("多选题至少要有两个答案：id=${q.id}", q.answer.length >= 2)
            }
        }
    }

    @Test
    fun `演示题库不在 SPECS 里所以不会出现在题库仓库`() {
        assertFalse(
            "演示题库若进了 SPECS 就会被 pendingSpecs 列进「题库仓库」，与设计相反",
            BundledBanks.SPECS.any { it.assetPath == BundledBanks.DEMO_SPEC.assetPath }
        )
        assertFalse(
            "演示题库的名字也不该与正式库撞名",
            BundledBanks.SPECS.any { it.bankName == BundledBanks.DEMO_SPEC.bankName }
        )
    }

    // ── 四份正式内置库 ──────────────────────────────────────────────────

    @Test
    fun `四份正式内置库的资产都存在且题数对得上`() {
        assertEquals(4, BundledBanks.SPECS.size)
        BundledBanks.SPECS.forEach { spec ->
            val bank = parseJsonBank(loadAsset(spec.assetPath))
            assertEquals("「${spec.bankName}」有被跳过的题：${bank.errors}", 0, bank.errors.size)
            assertEquals("「${spec.bankName}」题数与清单不一致", spec.expectedCount, bank.questions.size)
            // 名字必须一致，否则 pendingSpecs 的去重失效 ⇒ 触发覆盖导入 ⇒ 清掉用户记录。
            assertEquals("「${spec.bankName}」JSON 里的 name 与清单不一致", spec.bankName, bank.name)
        }
    }

    @Test
    fun `TOTAL_QUESTION_COUNT 等于四份正式库之和`() {
        val sum = BundledBanks.SPECS.sumOf { it.expectedCount }
        assertEquals(BundledBanks.TOTAL_QUESTION_COUNT, sum)
        // 演示题库**不计入**这个数：它是新手指引，不是"内置题库"的规模。
        // 若哪天有人把它并进 SPECS 又忘了改常量，这里立刻挂。
        assertTrue(
            "TOTAL_QUESTION_COUNT 看起来把演示题库算进去了",
            BundledBanks.TOTAL_QUESTION_COUNT != sum + BundledBanks.DEMO_SPEC.expectedCount
        )
    }

    private fun loadAsset(path: String): String {
        val file = File(moduleDir(), "src/main/assets/$path")
        assertTrue("找不到题库资产：${file.absolutePath}", file.isFile)
        // 显式 UTF-8：与 BundledBanks.readAsset 同一口径，中文题干在默认 GBK 的机器上不会乱码。
        return file.readText(Charsets.UTF_8)
    }

    /** Gradle 单测的工作目录是模块目录（app/），这里向上找，换个跑法也不会挂。 */
    private fun moduleDir(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "version.properties").isFile) return dir
            dir = dir.parentFile
        }
        error("找不到模块目录（当前工作目录 ${System.getProperty("user.dir")}）")
    }
}

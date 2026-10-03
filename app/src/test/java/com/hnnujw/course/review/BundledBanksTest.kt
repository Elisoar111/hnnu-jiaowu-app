package com.hnnujw.course.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BundledBanks.pendingSpecs] 的行为约束。
 *
 * 这个函数是「自动导入不会清掉用户答题记录」的最后一道闸：
 * [ReviewDatabase.importBank] 对同名库是覆盖语义（连带 records / wrong_book /
 * favorites 一起删），所以已存在的库必须被排除在待导入列表之外。
 *
 * 这些断言只覆盖纯逻辑，不碰 SQLite —— 项目没有 Robolectric，真库跑不了。
 */
class BundledBanksTest {

    @Test
    fun `内置库清单是四个且总题数为 1059`() {
        assertEquals(4, BundledBanks.SPECS.size)
        assertEquals(1059, BundledBanks.TOTAL_QUESTION_COUNT)
        assertEquals(
            "SPECS 的 expectedCount 之和应与 TOTAL_QUESTION_COUNT 一致",
            BundledBanks.TOTAL_QUESTION_COUNT,
            BundledBanks.SPECS.sumOf { it.expectedCount }
        )
    }

    @Test
    fun `库名互不重复`() {
        val names = BundledBanks.SPECS.map { it.bankName }
        assertEquals("存在重名库会导致第二份被跳过", names.size, names.distinct().size)
    }

    @Test
    fun `asset 路径互不重复`() {
        val paths = BundledBanks.SPECS.map { it.assetPath }
        assertEquals(paths.size, paths.distinct().size)
    }

    @Test
    fun `空库时全部待导入`() {
        assertEquals(BundledBanks.SPECS, BundledBanks.pendingSpecs(emptySet()))
    }

    @Test
    fun `已存在的库被跳过_这是防止覆盖答题记录的关键断言`() {
        val first = BundledBanks.SPECS.first()
        val pending = BundledBanks.pendingSpecs(setOf(first.bankName))
        assertEquals(
            "已存在的库绝不能出现在待导入列表里",
            BundledBanks.SPECS.size - 1,
            pending.size
        )
        assertTrue(pending.none { it.bankName == first.bankName })
    }

    @Test
    fun `全部已存在时无待导入`() {
        val all = BundledBanks.SPECS.map { it.bankName }.toSet()
        assertTrue(BundledBanks.pendingSpecs(all).isEmpty())
    }

    @Test
    fun `用户自建库不算内置库_不会误跳过`() {
        // 用户自己导入的题库名跟内置库不同，不该让内置库被跳过。
        val pending = BundledBanks.pendingSpecs(setOf("我的题库", "考研英语"))
        assertEquals(BundledBanks.SPECS.size, pending.size)
    }

    @Test
    fun `库名比较忽略首尾空白`() {
        val first = BundledBanks.SPECS.first()
        assertTrue(BundledBanks.pendingSpecs(setOf("  ${first.bankName}  ")).none { it.bankName == first.bankName })
    }

    @Test
    fun `库名与 JSON name 逐字一致才判得出重复`() {
        // 改名后的「马克思主义基本原理」少一个字就匹配不上 → 覆盖导入（清答题记录）。
        val target = BundledBanks.SPECS.first { it.bankName.startsWith("马克思主义") }
        assertEquals("马克思主义基本原理", target.bankName)
        assertEquals("review/mao_zhuyi_principles.json", target.assetPath)
        assertTrue(BundledBanks.pendingSpecs(setOf(target.bankName)).none { it.assetPath == target.assetPath })
    }

    @Test
    fun `改名对照的新名都在 SPECS 且旧名已不在`() {
        // RENAMED_BANKS 是存量设备改名的依据：新名必须与 SPECS 逐字一致，
        // 否则迁移会把库改成和内置清单对不上的名字；旧名再出现会导致重复导入。
        BundledBanks.RENAMED_BANKS.forEach { (oldName, newName) ->
            assertTrue(
                "新名「$newName」必须存在于 SPECS",
                BundledBanks.SPECS.any { it.bankName == newName }
            )
            assertFalse(
                "旧名「$oldName」不应再出现在 SPECS",
                BundledBanks.SPECS.any { it.bankName == oldName }
            )
        }
    }
}
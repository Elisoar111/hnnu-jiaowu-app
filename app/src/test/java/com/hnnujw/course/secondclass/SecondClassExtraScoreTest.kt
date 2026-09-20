package com.hnnujw.course.secondclass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 综测附加分算法回归。数字全部取自学校《成绩信息-附加分计算》表的真实行，
 * 改动权重表或封顶值时必须重算这里的期望值。
 */
class SecondClassExtraScoreTest {

    private fun modules(vararg pairs: Pair<String, Double>): List<SecondClassModule> =
        pairs.map { SecondClassModule(it.first, it.second, 0.0, 0.0) }

    /** 表格第 2 行（周钰杰）：32 / 33.2 / 28 / 16 / 16 / 9 / 0 → 20.68 */
    @Test
    fun matchesOfficialSheetFirstRow() {
        val score = SecondClassExtraScore.compute(
            modules(
                "思想政治素养" to 32.0,
                "社会责任担当" to 33.2,
                "实践实习能力" to 28.0,
                "创新创业能力" to 16.0,
                "文体素质拓展" to 16.0,
                "菁英成长履历" to 9.0,
                "技能培训认证" to 0.0,
            )
        )
        assertEquals(20.68, score!!, 1e-9)
    }

    /** 表格第 38 行（葛涵香）：97 / 50.3 / 43.5 / 85.5 / 131.5 / 2 / 5 → 67.545，含单项封顶 */
    @Test
    fun capsEachModuleAtOneHundred() {
        val score = SecondClassExtraScore.compute(
            modules(
                "思想政治素养" to 97.0,
                "社会责任担当" to 50.3,
                "实践实习能力" to 43.5,
                "创新创业能力" to 85.5,
                "文体素质拓展" to 131.5,
                "菁英成长履历" to 2.0,
                "技能培训认证" to 5.0,
            )
        )
        assertEquals(67.545, score!!, 1e-9)
    }

    /** 七项都 ≥100 时正好 100 分 —— 也顺带验证权重合计是 1.00。 */
    @Test
    fun fullMarksIsExactlyOneHundred() {
        val all = SecondClassExtraScore.weights.map { it.first to 150.0 }.toTypedArray()
        assertEquals(100.0, SecondClassExtraScore.compute(modules(*all))!!, 1e-9)
    }

    @Test
    fun weightsSumToOne() {
        assertEquals(1.0, SecondClassExtraScore.weights.sumOf { it.second }, 1e-9)
    }

    /** 站点有时给模块名带"…类/…模块"后缀，包含匹配要能认出来。 */
    @Test
    fun toleratesModuleNameSuffix() {
        assertEquals(3.0, SecondClassExtraScore.compute(modules("创新创业能力类" to 10.0))!!, 1e-9)
    }

    /** 站点没返回的模块按 0 计（与表格空单元格同义），不影响其它模块加权。 */
    @Test
    fun missingModulesCountAsZero() {
        assertEquals(6.0, SecondClassExtraScore.compute(modules("思想政治素养" to 40.0))!!, 1e-9)
    }

    /** 一个模块都认不出来时返回 null —— 宁可不显示，也不给假的 0 分。 */
    @Test
    fun unknownModulesProduceNoScore() {
        assertNull(SecondClassExtraScore.compute(modules("某个没见过的模块" to 88.0)))
        assertNull(SecondClassExtraScore.compute(emptyList()))
    }
}

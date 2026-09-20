package com.hnnujw.course.secondclass

/**
 * 综测附加分：第二课堂各模块积分 → 学生综合素质测评的附加分。
 *
 * 算法与学校《成绩信息-附加分计算》表 S 列的公式完全一致：
 *
 * ```
 * 附加分 = Σ min(模块积分, 100) × 模块权重
 * ```
 *
 * 七项权重合计 1.00，单项封顶 100 分（对应表里的 `MIN(单元格*1,100)`），
 * 因此理论满分就是 100 分。表格列顺序：
 * 思想政治素养 / 社会责任担当 / 实践实习能力 / 创新创业能力 /
 * 文体素质拓展 / 菁英成长履历 / 技能培训认证。
 *
 * 第二课堂站点的 `tags[].name` 与表格表头同名，这里直接按名字对齐；
 * 站点没返回的模块按 0 计（与表格里空单元格同义）。
 */
object SecondClassExtraScore {

    /** 单项封顶分，对应表格公式里的 `MIN(x*1,100)`。 */
    const val CAP: Double = 100.0

    /**
     * 模块名 → 权重。顺序与官方表格一致，便于对照核对。
     *
     * 注意：这里是**学校综测口径**，不要按第二课堂模块的学分上限去改；
     * 权重合计必须保持 1.00，否则满分就不是 100 分。
     */
    val weights: List<Pair<String, Double>> = listOf(
        "思想政治素养" to 0.15,
        "社会责任担当" to 0.15,
        "实践实习能力" to 0.10,
        "创新创业能力" to 0.30,
        "文体素质拓展" to 0.15,
        "菁英成长履历" to 0.10,
        "技能培训认证" to 0.05,
    )

    /**
     * 按各模块积分计算综测附加分。
     *
     * 返回 `null` 表示一个能识别的模块都没拿到——此时界面**不应该**显示这个框，
     * 否则会用一个假的「0 分」误导用户。
     */
    fun compute(modules: List<SecondClassModule>): Double? {
        if (modules.isEmpty()) return null
        var sum = 0.0
        var matched = 0
        for ((expected, weight) in weights) {
            val module = modules.firstOrNull { matches(it.name, expected) } ?: continue
            matched++
            sum += module.mine.coerceIn(0.0, CAP) * weight
        }
        return if (matched == 0) null else sum
    }

    /**
     * 站点有时会在模块名后带「类 / 模块」等后缀，先全等匹配，再退化到包含匹配。
     */
    private fun matches(actual: String, expected: String): Boolean {
        val name = actual.trim()
        return name == expected || name.replace(" ", "").contains(expected)
    }
}

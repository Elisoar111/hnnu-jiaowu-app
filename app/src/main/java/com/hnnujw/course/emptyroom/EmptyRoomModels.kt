package com.hnnujw.course.emptyroom

/**
 * 一间空闲场地（正方 `cdjy/cdjy_cxKxcdlb.html` 返回的一行）。
 *
 * 字段名来自服务端 JSON 的原始键（见 [EmptyRoomParser]）：
 * `cd_id / cdbh / cdmc / cdlbmc / jxlmc / lch / xqmc / zws / kszws1`。
 * 服务端还会塞一堆与查询无关的字段（`date`/`userModel`/`queryModel`…），
 * 这里一个都不收 —— 它们不是场地属性。
 */
data class EmptyRoom(
    /** 场地主键（`cd_id`），用于后续跳转/去重。 */
    val id: String,
    /** 场地编号（`cdbh`），如 `12102`。 */
    val code: String,
    /** 场地名称（`cdmc`），如 `朝阳物理楼-102`。 */
    val name: String,
    /** 场地类别名称（`cdlbmc`），如 `普通教室`。 */
    val category: String,
    /** 教学楼名称（`jxlmc`），如 `物理楼`。 */
    val building: String,
    /** 楼层（`lch`）。服务端给的是字符串，可能为空。 */
    val floor: String,
    /** 校区名称（`xqmc`）。 */
    val campus: String,
    /** 座位数（`zws`）。 */
    val seats: Int,
    /** 考试座位数（`kszws1`），与 [seats] 常常不同。 */
    val examSeats: Int,
) {
    /** 列表副标题：楼栋 · 楼层 · 类别。空段自动省略，不留分隔符。 */
    val subtitle: String
        get() = listOf(
            building.takeIf { it.isNotBlank() } ?: campus,
            floor.takeIf { it.isNotBlank() }?.let { "$it 层" },
            category.takeIf { it.isNotBlank() },
        ).filterNotNull().joinToString(" · ")

    /** 座位文案。0 表示服务端没给座位数，不显示"0 座"（会被读成"没座位"）。 */
    val seatText: String
        get() = if (seats > 0) "$seats 座" else ""
}

/** 下拉项（`{name,value}` / `<option value=…>label</option>` 统一成这一种）。 */
data class EmptyRoomOption(val value: String, val label: String)

/**
 * 某个校区下的可选项（来自 `cdjy/cdjy_cxXqjc.html`）。
 *
 * ## 为什么必须按校区重取，不能沿用页面直出的那份
 *
 * 查询页里的 `#lh`（楼号）是服务端**按默认校区**渲染的：默认是朝阳校区时它给的是
 * 朝阳教学楼/物理楼…。网页在 `#xqh_id` 的 change 里调 `hqjcList()` 把 `#lh` 整个
 * 清空重建（见 `kxcdlb.js`），说明**楼号是校区私有数据**。
 * 沿用旧列表的后果不是"少几个选项"，而是**拿朝阳的楼号去查泉山** —— 服务端不会报错，
 * 只会返回一个空集或别的楼，用户看到的是"这个校区没有空教室"。
 *
 * 节次同理：不同校区的节次集合与名称并不一致（`jcList`），
 * 所以 [periods] 也跟着校区走；取不到时才退回课表设置里的节次数。
 */
data class EmptyRoomCampusOptions(
    /** 楼号（`lhList`，值取 `JXLDM`、名取 `JXLMC`）。 */
    val buildings: List<EmptyRoomOption> = emptyList(),
    /** 节次（`jcList`，值取 `JCMC`、名取 `RSDJCMC`，无则退回 `JCMC`）。 */
    val periods: List<EmptyRoomOption> = emptyList(),
) {
    companion object {
        val EMPTY = EmptyRoomCampusOptions()
    }
}

/**
 * 查询表单的**可选项**，全部来自查询页本身（服务端直出的 `<select>` 与周次表头）。
 *
 * 刻意不写死：校区/楼栋/场地类别都是学校配置出来的，写死等于把应用绑死在
 * 某一学期的某一份配置上。周次也随学期变（有的学期 20 周、有的 18 周）。
 */
data class EmptyRoomFilters(
    /** 学年学期，值形如 `2026-3`（`xnm-xqm`）。 */
    val terms: List<EmptyRoomOption> = emptyList(),
    val campuses: List<EmptyRoomOption> = emptyList(),
    val buildings: List<EmptyRoomOption> = emptyList(),
    val categories: List<EmptyRoomOption> = emptyList(),
    /** 可选周次（`#selectTR_ZC` 里 class=selectTH 的表头）。 */
    val weeks: List<Int> = emptyList(),
) {
    val isEmpty: Boolean get() = campuses.isEmpty() && terms.isEmpty()

    /** 默认学年学期：服务端把当前学期标了 `selected`，解析后排在首位。 */
    val defaultTerm: String get() = terms.firstOrNull()?.value.orEmpty()

    companion object {
        val EMPTY = EmptyRoomFilters()
    }
}

/**
 * 查询条件。
 *
 * 时间条件用**周次 + 星期 + 节次**三张位图表达（`jyfs=0` 分支），
 * 这是正方「空闲场地查询」的主用法：问"第 3 周周一 1-2 节哪里空着"。
 * 另两种方式（按日期时段、按连续/间断借用）属于**场地借用申请**，
 * 本应用只做查询，不提供。
 */
data class EmptyRoomQuery(
    /** 学年学期值，形如 `2026-3`。 */
    val term: String = "",
    /** 校区（必选，服务端标了红星）。 */
    val campusId: String = "",
    /** 楼号，空串＝全部。 */
    val buildingId: String = "",
    /** 场地类别，空串＝全部。 */
    val categoryId: String = "",
    /** 场地名称/编号关键词，空串＝不限。 */
    val keyword: String = "",
    /** 座位数下限（字符串，空＝不限）。 */
    val minSeats: String = "",
    /** 座位数上限（字符串，空＝不限）。 */
    val maxSeats: String = "",
    val weeks: Set<Int> = emptySet(),
    val weekdays: Set<Int> = emptySet(),
    val periods: Set<Int> = emptySet(),
) {
    val hasTimeFilter: Boolean get() = weeks.isNotEmpty() && weekdays.isNotEmpty() && periods.isNotEmpty()
}

/**
 * 周次位掩码：`Σ 2^(周次-1)`。
 *
 * 与网页 `kxcdlb.js` 的算法逐字对应（`zcd += Math.pow(2, $(dom).attr("value") - 1)`），
 * 服务端按位读这个整数。用 [Long] 而不是 [Int]：JS 的 `Math.pow` 走双精度，
 * 周次超过 31 时 `1 shl 31` 在 Int 上会溢出成负数，而 JS 仍然给正数。
 */
internal fun weekMask(weeks: Iterable<Int>): Long =
    weeks.filter { it in 1..62 }.sumOf { 1L shl (it - 1) }

/** 节次位掩码：`Σ 2^(节次-1)`，与 [weekMask] 同一套算法。 */
internal fun periodMask(periods: Iterable<Int>): Long =
    periods.filter { it in 1..62 }.sumOf { 1L shl (it - 1) }

/**
 * 星期：服务端要的是逗号分隔的 1..7（`xqList.join(",")`），
 * **不是位掩码** —— 三张位图里只有星期是列表，别顺手也按位算。
 */
internal fun weekdayList(weekdays: Iterable<Int>): String =
    weekdays.filter { it in 1..7 }.distinct().sorted().joinToString(",")

/** 学年学期值 `2026-3` → (`xnm`=2026, `xqm`=3)。取不到时返回两个空串。 */
internal fun termParts(term: String): Pair<String, String> {
    val parts = term.split("-")
    if (parts.size < 2) return "" to ""
    val year = parts[0].trim()
    val half = parts[1].trim()
    // `year.isNotEmpty()` 不能省：`"".all(Char::isDigit)` 是 true，
    // 少了这一句 `-3` 会被拆成 ("", "3")，随后以"空年份 + 第 3 学期"发出去。
    val validYear = year.isNotEmpty() && year.all(Char::isDigit)
    return if (validYear && half.isNotEmpty()) year to half else "" to ""
}

/**
 * 提交前的本地校验。
 *
 * 规则与网页端一致（校区红星 + 周次/星期/节次各至少一项，
 * 见 `cdjy_cxKxcdlb.html` 里对 `#selectTR_ZC/#selectTR_XQJ/#selectTR_JC` 的 wrapError 检查）：
 * 三项时间条件缺任何一项，服务端都不知道"哪段时间"，查询没有意义。
 * 返回 null 表示可以提交；否则返回给用户看的一句话。
 */
internal fun validateQuery(query: EmptyRoomQuery): String? = when {
    termParts(query.term).second.isBlank() -> "请选择学年学期"
    query.campusId.isBlank() -> "请选择校区"
    query.weeks.isEmpty() -> "请选择周次"
    query.weekdays.isEmpty() -> "请选择星期"
    query.periods.isEmpty() -> "请选择节次"
    seatRangeInvalid(query.minSeats, query.maxSeats) -> "座位数：起始不能大于结束"
    else -> null
}

/**
 * 座位数区间是否矛盾。
 *
 * 只在**两端都能解析成整数**时才算矛盾：用户只填一端、或填了半截
 *（输入法中间态）都不该拦住提交 —— 服务端对空值本来就是"不限"。
 */
internal fun seatRangeInvalid(minSeats: String, maxSeats: String): Boolean {
    val min = minSeats.trim().toIntOrNull() ?: return false
    val max = maxSeats.trim().toIntOrNull() ?: return false
    return min > max
}

/** "全部"选项。楼号与场地类别都靠空值表示不限，界面必须显式给出这一项。 */
internal val ALL_OPTION = EmptyRoomOption("", "全部")

/**
 * 楼号候选。
 *
 * 三份来源的取舍标准不是"哪个更新"，而是**哪份确定属于当前校区**：
 * 1. 已成功拉到、且校区对得上的那份；
 * 2. 否则，当前就是页面默认校区时，用页面直出的那份（查询页只渲染默认校区的楼号）；
 * 3. 否则只剩"全部"。
 *
 * 第 3 条是刻意的：拿 A 校区的楼号去查 B 校区，服务端**不会报错**，
 * 只会返回空集或别的楼，用户看到的是"这个校区没有空教室" —— 静默错误比缺选项严重得多。
 */
internal fun buildingOptionsFor(
    campusId: String,
    defaultCampusId: String,
    pageBuildings: List<EmptyRoomOption>,
    loadedCampusId: String,
    loadedBuildings: List<EmptyRoomOption>,
): List<EmptyRoomOption> = when {
    loadedCampusId.isNotBlank() && loadedCampusId == campusId && loadedBuildings.isNotEmpty() ->
        listOf(ALL_OPTION) + loadedBuildings

    campusId.isNotBlank() && campusId == defaultCampusId && pageBuildings.isNotEmpty() ->
        pageBuildings

    else -> listOf(ALL_OPTION)
}

/**
 * 节次候选。校区给不出时退回课表设置里的节次数（[fallbackCount]）。
 *
 * 与楼号同一套归属判据：`loadedCampusId` 与当前校区不符就一律不用。
 */
internal fun periodOptionsFor(
    campusId: String,
    loadedCampusId: String,
    loadedPeriods: List<EmptyRoomOption>,
    fallbackCount: Int,
): List<EmptyRoomOption> =
    if (loadedCampusId.isNotBlank() && loadedCampusId == campusId && loadedPeriods.isNotEmpty()) {
        loadedPeriods
    } else {
        (1..fallbackCount.coerceAtLeast(1)).map { EmptyRoomOption(it.toString(), it.toString()) }
    }

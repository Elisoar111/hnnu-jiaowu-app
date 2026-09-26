package com.hnnujw.course.schedule

/**
 * 星期数字（1 = 周一 … 7 = 周日）到中文标签的换算。
 *
 * ## 为什么要抽出来
 *
 * 这条映射原先在三个地方各写了一遍 `"一二三四五六日".getOrElse(day - 1) { '?' }`
 * （补课弹窗、补课结果提示、课程详情页），而且都靠"下标 = 星期 - 1"这个不变量活着。
 * 一旦有人改成从 0 开始数、或者把字符串写成六个字，三处会同时静默地退化成 `?` ——
 * 界面上只表现为一个问号，很难看出是标签算错了。
 *
 * 抽成纯函数后，边界（7 = 周日、0 / 8 = 越界）由 `ScheduleWeekdayLabelTest` 盯住。
 * 这里**不做 Android / Compose 依赖**，所以能直接在 JVM 单测里跑。
 */

/** 星期数字对应的单个汉字。越界返回 `'?'`（渲染层不该崩，但也不该猜一个看起来合理的字）。 */
internal fun scheduleWeekdayChar(day: Int): Char =
    "一二三四五六日".getOrElse(day - 1) { '?' }

/** 短标签：`周一` / `周日`。用于星期条、补课弹窗的折叠选择器。 */
internal fun scheduleWeekdayShort(day: Int): String = "周" + scheduleWeekdayChar(day)

/** 长标签：`星期一` / `星期日`。用于正文句子（"星期一没有课"）。 */
internal fun scheduleWeekdayLong(day: Int): String = "星期" + scheduleWeekdayChar(day)

package com.hnnujw.course.academic

import com.hnnujw.course.model.SchoolConfig

data class AcademicSystemSupport(val system: AcademicSystem, val name: String, val login: String, val limits: String)

object AcademicCapabilities {
    const val ACCOUNT_LIMIT = "本应用只支持淮南师范学院，同一设备最多绑定 3 个学生账号；同一时间只运行当前账号的选课任务。"
    const val SCHOOL_LIMIT = "选课时间、名额、学分、年级专业及课程冲突由学校决定。学校未开放的操作不能提交，未公布的数据不会补造。"
    val systems = listOf(
        AcademicSystemSupport(AcademicSystem.ZF, "正方教务（淮南师范学院）",
            "支持账密登录、图片验证码与网页登录；定制统一认证以学校登录流程为准。",
            "支持串行或最多 2 门课程并行尝试。筛选项、成绩分项与可选教学班取自学校响应。")
    )
    val selectableSystems: List<AcademicSystem> = systems.map { it.system }
    fun selectionIndex(id: String?): Int = selectableSystems.indexOf(system(id)).coerceAtLeast(0)
    fun selectionLabel(type: AcademicSystem): String = name(type.id)
    fun selectedTypeId(currentId: String, selected: AcademicSystem): String = selected.id
    fun system(id: String?): AcademicSystem = AcademicSystem.fromId(id) ?: AcademicSystem.ZF
    fun support(id: String?): AcademicSystemSupport? = systems.firstOrNull { it.system == system(id) }
    fun name(id: String?): String = support(id)?.name ?: "正方教务（淮南师范学院）"
    fun supportsParallel(@Suppress("UNUSED_PARAMETER") school: SchoolConfig): Boolean = true
    fun queueLimit(@Suppress("UNUSED_PARAMETER") school: SchoolConfig): String =
        "当前账号可串行或最多 2 门并行尝试；学校选课规则始终生效。"
}

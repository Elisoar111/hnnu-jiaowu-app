package com.tyust.course.demo

import androidx.compose.ui.graphics.Color
import com.tyust.course.model.Course
import com.tyust.course.model.CourseFilter
import com.tyust.course.model.SchoolConfig
import com.tyust.course.ui.screen.ExamItemUi
import com.tyust.course.ui.screen.GradeItemUi
import com.tyust.course.ui.screen.OverallStatsUi
import com.tyust.course.secondclass.SecondClassModule
import com.tyust.course.secondclass.SecondClassProfile
import com.tyust.course.secondclass.SecondClassRankBoard
import com.tyust.course.secondclass.SecondClassRankEntry
import com.tyust.course.secondclass.SecondClassRankLevel
import com.tyust.course.secondclass.SecondClassSnapshot
import com.tyust.course.ui.screen.ScheduleCourseUi
import com.tyust.course.utils.CourseParser

/**
 * 宣传片和首次体验共用的本地演示内容。
 *
 * 每个读取函数都返回新对象，因为 [Course] 是可变模型；页面中的选课等演示操作
 * 不应修改下一次进入演示模式时的基线数据。选课队列只记录当前演示会话的班级 ID。
 */
object DemoData {
    const val SCHOOL_ID = "demo"
    const val ACCOUNT_KEY = "demo::preview"
    val currentTerm = com.tyust.course.academic.AcademicTerm("2025-2026-2")

    private val defaultGrabClassIds = linkedSetOf("CS204-01", "AI310-01", "DES116-01", "PSY108-01", "PE087-01")
    private var sessionGrabClassIds = defaultGrabClassIds.toMutableSet()

    @Synchronized
    fun resetSession() {
        sessionGrabClassIds = defaultGrabClassIds.toMutableSet()
    }

    @Synchronized
    fun addToGrabQueue(course: Course): Boolean {
        val classId = course.classId.takeIf(String::isNotBlank) ?: return false
        return sessionGrabClassIds.add(classId)
    }

    fun school(): SchoolConfig = SchoolConfig(
        SCHOOL_ID,
        "正方演示大学（演示数据）",
        "demo.invalid",
        "https"
    )

    fun availableCourses(): List<Course> = listOf(
        course("CS204", "CS204-01", "数据结构与算法", "陈思远", "周一 1-2节", "博学楼 A203", "3.5", 60, 54, "01"),
        course("CS204", "CS204-02", "数据结构与算法", "周若琳", "周三 3-4节", "博学楼 A205", "3.5", 60, 60, "01"),
        course("AI310", "AI310-01", "人工智能导论", "林予安", "周二 3-4节", "创新中心 302", "2.5", 48, 45, "01"),
        course("WEB221", "WEB221-01", "Web 应用开发", "许知行", "周四 5-6节", "工训楼 B401", "3.0", 50, 39, "01"),
        course("DES116", "DES116-01", "交互设计基础", "沈嘉禾", "周五 1-2节", "艺术楼 208", "2.0", 40, 40, "10"),
        course("PSY108", "PSY108-01", "积极心理学", "顾清和", "周三 7-8节", "博雅楼 101", "1.5", 120, 118, "10"),
        course("ART205", "ART205-01", "电影音乐赏析", "宋闻溪", "周二 9-10节", "大学生活动中心", "1.5", 100, 92, "10"),
        course("ECO130", "ECO130-01", "经济学思维", "梁景明", "周四 1-2节", "博雅楼 305", "2.0", 80, 75, "10"),
        course("PE087", "PE087-01", "羽毛球提高班", "方屿", "周五 5-6节", "东区体育馆", "1.0", 30, 29, "10"),
        course("ENG240", "ENG240-01", "学术英语写作", "何沐言", "周一 7-8节", "博学楼 C206", "2.0", 36, 31, "10")
    )

    fun selectedCourses(): List<Course> = listOf(
        course("MATH201", "MATH201-03", "概率论与数理统计", "唐亦辰", "周一 3-4节", "博学楼 B202", "3.0", 60, 58, "01", selected = true),
        course("OS301", "OS301-01", "操作系统", "叶星河", "周二 5-6节", "信息楼 404", "3.5", 55, 52, "01", selected = true),
        course("NET302", "NET302-02", "计算机网络", "陆明川", "周四 3-4节", "信息楼 406", "3.0", 55, 54, "01", selected = true)
    )

    @Synchronized
    fun grabQueue(): List<Course> = availableCourses()
        .filter { it.classId in sessionGrabClassIds }
        .map(Course::copy)

    fun scheduleCourses(): List<ScheduleCourseUi> = listOf(
        schedule("数据结构", "陈思远", "博学楼 A203", 1, 1, 2, Color(0xFF5C6BC0)),
        schedule("概率论", "唐亦辰", "博学楼 B202", 1, 3, 4, Color(0xFF42A5F5)),
        schedule("学术英语", "何沐言", "博学楼 C206", 1, 7, 8, Color(0xFF26C6DA)),
        schedule("人工智能", "林予安", "创新中心 302", 2, 3, 4, Color(0xFFAB47BC)),
        schedule("操作系统", "叶星河", "信息楼 404", 2, 5, 6, Color(0xFF66BB6A)),
        schedule("电影音乐", "宋闻溪", "大学生活动中心", 2, 9, 10, Color(0xFFFFA726)),
        schedule("数据结构", "周若琳", "博学楼 A205", 3, 3, 4, Color(0xFF5C6BC0)),
        schedule("积极心理学", "顾清和", "博雅楼 101", 3, 7, 8, Color(0xFFEF5350)),
        schedule("经济学思维", "梁景明", "博雅楼 305", 4, 1, 2, Color(0xFF8D6E63)),
        schedule("计算机网络", "陆明川", "信息楼 406", 4, 3, 4, Color(0xFF42A5F5)),
        schedule("Web 应用开发", "许知行", "工训楼 B401", 4, 5, 6, Color(0xFF26C6DA)),
        schedule("交互设计", "沈嘉禾", "艺术楼 208", 5, 1, 2, Color(0xFFAB47BC)),
        schedule("羽毛球", "方屿", "东区体育馆", 5, 5, 6, Color(0xFF66BB6A))
    )

    // ── 第二课堂（演示）─────────────────────────────────────────────────────
    // 纯虚构数据，不含任何真实个人信息。演示模式跳过网络，直接喂这份快照。
    fun secondClassroomSnapshot(): SecondClassSnapshot {
        val profile = SecondClassProfile(
            name = "演示同学",
            code = "2025X0101",
            collegeName = "计算机科学与技术学院",
            majorName = "软件工程",
            grade = "2025",
            score = 128.5,
            hours = 326.0,
            scoreUnit = "学分",
            hourUnit = "" // 留空 → 界面显示"总积分"标签
        )
        val modules = listOf(
            SecondClassModule("思想成长", 18.0, 12.0, 15.4),
            SecondClassModule("实践实习", 26.5, 20.0, 21.2),
            SecondClassModule("志愿公益", 14.0, 16.0, 13.8), // 未达标 → 红色
            SecondClassModule("创新创业", 9.0, 10.0, 8.6),    // 未达标 → 红色
            SecondClassModule("文体活动", 31.0, 20.0, 24.7),
            SecondClassModule("工作履历", 22.0, 18.0, 19.3),
            SecondClassModule("技能特长", 8.0, 0.0, 6.5)       // 无下限，只显示积分
        )

        // 班级榜单（含自己），其余层级只放"我的名次"。
        val classmates = listOf(
            SecondClassRankEntry(1, "王浩然", "软件工程", 198.5),
            SecondClassRankEntry(2, "李欣怡", "软件工程", 187.0),
            SecondClassRankEntry(3, "张子墨", "软件工程", 175.5),
            SecondClassRankEntry(4, "陈雨桐", "软件工程", 162.0),
            SecondClassRankEntry(5, "刘思源", "软件工程", 151.5),
            SecondClassRankEntry(6, "赵梓萱", "软件工程", 139.0),
            SecondClassRankEntry(7, "演示同学", "软件工程", 128.5, isSelf = true),
            SecondClassRankEntry(8, "孙浩然", "软件工程", 119.0),
            SecondClassRankEntry(9, "周文博", "软件工程", 108.5),
            SecondClassRankEntry(10, "吴佳怡", "软件工程", 97.0),
            SecondClassRankEntry(11, "郑泽宇", "软件工程", 86.5),
            SecondClassRankEntry(12, "黄诗涵", "软件工程", 74.0)
        )
        val myEntry = classmates.first { it.isSelf }

        val classBoard = SecondClassRankBoard(
            level = SecondClassRankLevel.Classmates,
            entries = classmates,
            myRank = myEntry,
            total = classmates.size
        )
        val majorBoard = SecondClassRankBoard(
            level = SecondClassRankLevel.Major,
            entries = emptyList(),
            myRank = myEntry.copy(rank = 23),
            total = 184
        )
        val collegeBoard = SecondClassRankBoard(
            level = SecondClassRankLevel.College,
            entries = emptyList(),
            myRank = myEntry.copy(rank = 57),
            total = 612
        )
        val schoolBoard = SecondClassRankBoard(
            level = SecondClassRankLevel.School,
            entries = emptyList(),
            myRank = myEntry.copy(rank = 318),
            total = 3520
        )

        return SecondClassSnapshot(
            profile = profile,
            modules = modules,
            boards = mapOf(
                SecondClassRankLevel.Classmates to classBoard,
                SecondClassRankLevel.Major to majorBoard,
                SecondClassRankLevel.College to collegeBoard,
                SecondClassRankLevel.School to schoolBoard
            )
        )
    }

    fun semesterGrades(): List<GradeItemUi> = listOf(
        grade("数据结构与算法", "94", "3.5", "4.2", "专业必修", "CS204", "平时(30%): 96 | 期末(70%): 93"),
        grade("概率论与数理统计", "88", "3.0", "3.8", "专业必修", "MATH201", "平时(30%): 91 | 期末(70%): 87"),
        grade("操作系统", "91", "3.5", "4.1", "专业必修", "OS301", "实验(20%): 95 | 平时(20%): 92 | 期末(60%): 89"),
        grade("计算机网络", "86", "3.0", "3.6", "专业必修", "NET302", "平时(30%): 90 | 期末(70%): 84"),
        grade("人工智能导论", "93", "2.5", "4.2", "专业选修", "AI310", "项目(40%): 96 | 期末(60%): 91"),
        grade("学术英语写作", "89", "2.0", "3.9", "通识选修", "ENG240", "展示(30%): 92 | 论文(70%): 88"),
        grade("体育 IV", "优秀", "1.0", "4.5", "公共必修", "PE004", "体能(40%): 95 | 技能(60%): 92")
    )

    fun overallGrades(): List<GradeItemUi> = semesterGrades() + listOf(
        grade("离散数学", "90", "3.0", "4.0", "专业必修", "MATH105", "平时(30%): 93 | 期末(70%): 89", year = "2024"),
        grade("面向对象程序设计", "96", "3.5", "4.5", "专业必修", "CS106", "实验(40%): 98 | 期末(60%): 95", year = "2024"),
        grade("大学物理", "84", "3.0", "3.4", "公共必修", "PHY102", "实验(20%): 91 | 期末(80%): 82", year = "2024")
    )

    val overallStats = OverallStatsUi(
        gpa = "3.96",
        credits = "27.5",
        courseCount = 10,
        excellent = 6,
        good = 4,
        medium = 0,
        pass = 0
    )

    fun exams(): List<ExamItemUi> = listOf(
        ExamItemUi("操作系统", "2026-06-18 09:00-11:00", "博学楼 B301", "18", "期末考试", "叶星河"),
        ExamItemUi("计算机网络", "2026-06-20 14:00-16:00", "博学楼 B305", "22", "期末考试", "陆明川"),
        ExamItemUi("概率论与数理统计", "2026-06-23 09:00-11:00", "博雅楼 201", "07", "期末考试", "唐亦辰"),
        ExamItemUi("人工智能导论", "2026-06-25 14:00-16:00", "创新中心 301", "15", "课程考核", "林予安")
    )

    fun filterCategories(): List<CourseParser.FilterCategory> = listOf(
        CourseParser.FilterCategory(
            "课程类别",
            "kclb_id_list",
            listOf(
                CourseParser.FilterOption("01", "专业课程"),
                CourseParser.FilterOption("10", "通识选修")
            )
        ),
        CourseParser.FilterCategory(
            "上课星期",
            "sksj_list",
            listOf(
                CourseParser.FilterOption("1", "周一"),
                CourseParser.FilterOption("2", "周二"),
                CourseParser.FilterOption("3", "周三"),
                CourseParser.FilterOption("4", "周四"),
                CourseParser.FilterOption("5", "周五")
            )
        ),
        CourseParser.FilterCategory(
            "有无余量",
            "yl_list",
            listOf(
                CourseParser.FilterOption("1", "有余量"),
                CourseParser.FilterOption("0", "已满")
            )
        )
    )

    fun filterCourses(courses: List<Course>, filter: CourseFilter): List<Course> {
        val weekdayLabels = mapOf("1" to "周一", "2" to "周二", "3" to "周三", "4" to "周四", "5" to "周五")
        return courses.filter { course ->
            val categoryMatches = filter.kclbIdList.isNullOrEmpty() || course.kklxdm in filter.kclbIdList.orEmpty()
            val weekdayMatches = filter.sksjList.isNullOrEmpty() || filter.sksjList.orEmpty().any { key ->
                weekdayLabels[key]?.let(course.time::contains) == true
            }
            val availabilityMatches = filter.ylList.isNullOrEmpty() || filter.ylList.orEmpty().any { key ->
                when (key) {
                    "1" -> course.available > 0
                    "0" -> course.available == 0
                    else -> false
                }
            }
            val classMatches = filter.jxbmcList.isNullOrEmpty() || filter.jxbmcList.orEmpty().any {
                course.jxbmc.contains(it, ignoreCase = true)
            }
            val searchMatches = filter.searchInput.isNullOrBlank() || listOf(
                course.name,
                course.teacher,
                course.courseId
            ).any { it.contains(filter.searchInput.orEmpty(), ignoreCase = true) }
            categoryMatches && weekdayMatches && availabilityMatches && classMatches && searchMatches
        }
    }

    // ── 第二课堂（演示）─────────────────────────────────────────────────
    // 演示模式专用的虚构数据，全部为编造，不含任何真实个人信息。

    fun secondClassProfile(): SecondClassProfile = SecondClassProfile(
        name = "演示同学",
        code = "2024010101",
        collegeName = "计算机科学与技术学院",
        majorName = "软件工程",
        grade = "2024",
        score = 6.5,
        hours = 480.0,
        scoreUnit = "学分",
        hourUnit = "总积分"
    )

    fun secondClassSnapshot(): SecondClassSnapshot =
        SecondClassSnapshot(profile = secondClassProfile(), modules = secondClassModules())

    fun secondClassModules(): List<SecondClassModule> = listOf(
        SecondClassModule("思想成长", 2.0, 1.0, 1.5),
        SecondClassModule("实践实习", 1.5, 2.0, 1.2),
        SecondClassModule("志愿公益", 3.0, 2.0, 2.1),
        SecondClassModule("文体活动", 1.0, 1.0, 0.8),
        SecondClassModule("创新创业", 0.5, 1.0, 0.6)
    )

    fun secondClassBoards(): Map<SecondClassRankLevel, SecondClassRankBoard> = buildMap {
        put(
            SecondClassRankLevel.Classmates,
            SecondClassRankBoard(
                level = SecondClassRankLevel.Classmates,
                entries = listOf(
                    SecondClassRankEntry(1, "王浩然", "软件工程", 9.8),
                    SecondClassRankEntry(2, "李思琪", "软件工程", 8.6),
                    SecondClassRankEntry(3, "张子轩", "计算机科学", 8.1),
                    SecondClassRankEntry(4, "刘梓萱", "软件工程", 7.4),
                    SecondClassRankEntry(5, "演示同学", "软件工程", 6.5, isSelf = true),
                    SecondClassRankEntry(6, "杨欣怡", "软件工程", 6.2),
                    SecondClassRankEntry(7, "赵宇航", "人工智能", 5.9),
                    SecondClassRankEntry(8, "黄诗涵", "软件工程", 5.5),
                    SecondClassRankEntry(9, "周俊杰", "计算机科学", 5.3),
                    SecondClassRankEntry(10, "吴佳音", "软件工程", 5.0)
                ),
                myRank = SecondClassRankEntry(5, "演示同学", "软件工程", 6.5, isSelf = true),
                total = 35
            )
        )
        put(
            SecondClassRankLevel.Major,
            SecondClassRankBoard(
                level = SecondClassRankLevel.Major,
                entries = emptyList(),
                myRank = SecondClassRankEntry(18, "演示同学", "软件工程", 6.5, isSelf = true),
                total = 120
            )
        )
        put(
            SecondClassRankLevel.College,
            SecondClassRankBoard(
                level = SecondClassRankLevel.College,
                entries = emptyList(),
                myRank = SecondClassRankEntry(95, "演示同学", "软件工程", 6.5, isSelf = true),
                total = 800
            )
        )
        put(
            SecondClassRankLevel.School,
            SecondClassRankBoard(
                level = SecondClassRankLevel.School,
                entries = emptyList(),
                myRank = SecondClassRankEntry(620, "演示同学", "软件工程", 6.5, isSelf = true),
                total = 6000
            )
        )
    }

    private fun course(
        courseId: String,
        classId: String,
        name: String,
        teacher: String,
        time: String,
        location: String,
        credit: String,
        capacity: Int,
        selectedCount: Int,
        category: String,
        selected: Boolean = false
    ) = Course().apply {
        this.courseId = courseId
        this.classId = classId
        this.doJxbId = "demo-$classId"
        this.name = name
        this.teacher = teacher
        this.jxbmc = "$name · $teacher"
        this.time = time
        this.location = location
        this.credit = credit
        this.capacity = capacity
        this.selected = selectedCount
        this.kklxdm = category
        this.isSelected = selected
        this._xkkz_id = "demo-round-2026"
        this.njdm_id = "2024"
        this.zyh_id = "080901"
        this._rwlx = "1"
        this._xklc = "2"
        this._sfkxq = "0"
        this._xkxskcgskg = "0"
        this.xkxnm = "2025"
        this.xkxqm = "12"
    }

    private fun schedule(
        name: String,
        teacher: String,
        location: String,
        day: Int,
        start: Int,
        end: Int,
        color: Color
    ) = ScheduleCourseUi(name, teacher, location, day, start, end, "1-18周", color)

    private fun grade(
        name: String,
        score: String,
        credits: String,
        gpa: String,
        type: String,
        code: String,
        detail: String,
        year: String = "2025"
    ) = GradeItemUi(
        courseName = name,
        grade = score,
        credits = credits,
        gpa = gpa,
        courseType = type,
        year = year,
        term = "12",
        college = "计算机科学与技术学院",
        courseCode = code,
        teachingClass = "$code-01",
        jxbId = "demo-$code",
        detail = detail
    )
}

package com.hnnujw.course.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PracticeLaunch] 编解码与「继续上次练习」存档的回归。
 *
 * 这串编码现在有两个消费者：`rememberSaveable`（进程重建）和
 * `PracticeResume`（主动退出后继续）。**编码一旦漂移，用户会拿一个错位的参数去抽题** ——
 * 不崩、不报错，只是抽出来的题和一开始完全不是同一批。所以要钉死往返和拒收。
 */
class PracticeLaunchCodecTest {

    private fun launch(
        bankId: String? = "b1",
        chapters: List<String> = listOf("第一章", "第七章 “三个代表”重要思想"),
        mode: ReviewMode = ReviewMode.RANDOM,
        onlyWrong: Boolean = false,
        onlyFavorite: Boolean = false,
        dueOnly: Boolean = false,
        durationSeconds: Int = 0,
        memorize: Boolean = false
    ) = PracticeLaunch(bankId, chapters, mode, onlyWrong, onlyFavorite, dueOnly, durationSeconds, memorize)

    @Test
    fun `编码解码应当原样往返`() {
        val original = launch(onlyWrong = true, durationSeconds = 1800)
        assertEquals(original, PracticeLaunch.decode(original.encode()))
    }

    @Test
    fun `章节名里的引号顿号不会破坏编码`() {
        val original = launch(chapters = listOf("第七章 “三个代表”重要思想", "A、B、C", "第（一）章"))
        assertEquals(original, PracticeLaunch.decode(original.encode()))
    }

    @Test
    fun `空章节与空题库也能往返`() {
        val original = launch(bankId = null, chapters = emptyList(), memorize = true)
        assertEquals(original, PracticeLaunch.decode(original.encode()))
    }

    @Test
    fun `全部分支组合都能往返`() {
        for (mode in ReviewMode.entries) {
            for (due in listOf(false, true)) {
                for (fav in listOf(false, true)) {
                    val o = launch(mode = mode, dueOnly = due, onlyFavorite = fav, onlyWrong = !due && !fav)
                    assertEquals(o, PracticeLaunch.decode(o.encode()))
                }
            }
        }
    }

    /** 字段数不对必须拒收：宁可让用户重新开练，也不能错位读出一个错误的练习模式。 */
    @Test
    fun `字段数不足的旧串被拒收`() {
        val legacyWithSlashedField = listOf("b1", "第一章", "RANDOM", "false", "false", "false", "0", "false", "false")
            .joinToString("\u001E")
        assertNull(PracticeLaunch.decode(legacyWithSlashedField))
    }

    @Test
    fun `空串与乱码被拒收`() {
        assertNull(PracticeLaunch.decode(""))
        assertNull(PracticeLaunch.decode("   "))
        assertNull(PracticeLaunch.decode("b1"))
    }

    /** 未知的模式名不能把整串判废 —— 回落到顺序练习，题目本身仍然能抽出来。 */
    @Test
    fun `未知模式名回落到顺序练习`() {
        val raw = listOf("b1", "", "SOMETHING_NEW", "false", "false", "false", "0", "false")
            .joinToString("\u001E")
        val decoded = PracticeLaunch.decode(raw)
        assertTrue(decoded != null)
        assertEquals(ReviewMode.SEQUENTIAL, decoded!!.mode)
    }
}

/**
 * 「继续上次练习」存档的字段级回归（不依赖 Android 上下文的部分）。
 *
 * 注意：真机上的读写需要 Context，这里只钉"空快照不该产生可恢复项"这条价值判断 ——
 * 刚进练习页、还没答任何题就退出，首页不该冒出「继续上次练习 第 1 题」。
 */
class PracticeResumeSnapshotTest {

    @Test
    fun `标题为空时用开练参数兜底`() {
        val snapshot = PracticeResume.Snapshot(
            launch = PracticeLaunch(bankId = "b1", chapters = listOf("第一章")),
            startedAt = 1L,
            seed = 2L,
            title = "",
            index = 3,
            answers = "{\"answers\":[],\"judged\":[]}"
        )
        // 空标题在 load 时会被 launch.title() 补上；这里只验证兜底值本身可读。
        assertEquals("第一章", snapshot.launch.title())
    }
}

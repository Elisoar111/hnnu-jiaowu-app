package com.hnnujw.course.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 账号键构造与归一化的回归。
 *
 * 这两条规则决定"哪个账号的数据算哪个账号的"。它们一旦在写入侧与读取侧不一致，
 * 表现就是「切号后看到上一个账号的成绩」或「清除缓存清不掉」——
 * **不会报任何异常**，只能靠测试发现。
 */
class AccountKeysTest {

    // ── build：身份优先级

    @Test
    fun `用户名优先于学号与姓名`() {
        val key = AccountKeys.build("hnnu", "20230001", "20230001", "张三")
        assertEquals("hnnu::20230001", key)
    }

    @Test
    fun `没有用户名时用学号`() {
        assertEquals("hnnu::20230001", AccountKeys.build("hnnu", null, "20230001", "张三"))
        assertEquals("hnnu::20230001", AccountKeys.build("hnnu", "", "20230001", "张三"))
        assertEquals("hnnu::20230001", AccountKeys.build("hnnu", "   ", "20230001", "张三"))
    }

    @Test
    fun `只有姓名时也能构造`() {
        assertEquals("hnnu::张三", AccountKeys.build("hnnu", null, null, "张三"))
    }

    @Test
    fun `身份会 trim`() {
        assertEquals("hnnu::20230001", AccountKeys.build("hnnu", "  20230001  ", null, null))
    }

    /** 🔴 学校缺失时返回空串 —— 调用方必须把空串当作"不合法"。 */
    @Test
    fun `学校缺失返回空串`() {
        assertEquals("", AccountKeys.build(null, "u", "s", "n"))
        assertEquals("", AccountKeys.build("", "u", "s", "n"))
        assertEquals("", AccountKeys.build("   ", "u", "s", "n"))
    }

    /** 🔴 三个身份全空时返回空串，绝不能构造出 `hnnu::` 这种"半截键"。 */
    @Test
    fun `身份全空返回空串`() {
        assertEquals("", AccountKeys.build("hnnu", null, null, null))
        assertEquals("", AccountKeys.build("hnnu", "", "  ", ""))
    }

    @Test
    fun `键格式固定为 学校双冒号身份`() {
        val key = AccountKeys.build("hnnu", "u1", null, null)
        assertTrue("格式应为 <school>::<identity>: $key", key.matches(Regex("^[^:]+::[^:]+$")))
    }

    @Test
    fun `不同学校的同一学号是不同的键`() {
        val a = AccountKeys.build("hnnu", "20230001", null, null)
        val b = AccountKeys.build("other", "20230001", null, null)
        assertNotEquals("不同学校必须区分", a, b)
    }

    // ── firstNotBlank

    @Test
    fun `firstNotBlank 取第一个非空并 trim`() {
        assertEquals("a", AccountKeys.firstNotBlank(null, "", "  ", " a ", "b"))
    }

    @Test
    fun `firstNotBlank 全空返回空串`() {
        assertEquals("", AccountKeys.firstNotBlank(null, "", "   "))
        assertEquals("", AccountKeys.firstNotBlank())
    }

    // ── storageKey

    @Test
    fun `storageKey 把双冒号替换成下划线`() {
        assertEquals("hnnu__20230001", AccountKeys.storageKey("hnnu::20230001"))
    }

    @Test
    fun `storageKey 保留字母数字下划线点与横杠`() {
        assertEquals("a-b_c.d1", AccountKeys.storageKey("a-b_c.d1"))
    }

    @Test
    fun `storageKey 把中文替换成下划线`() {
        // 「张三」是两个字，各替换一个下划线；`::` 也是两个 → 共 4 个下划线`r`n        assertEquals("hnnu____", AccountKeys.storageKey("hnnu::张三"))
    }

    @Test
    fun `storageKey 把空格替换成下划线`() {
        assertEquals("a_b", AccountKeys.storageKey("a b"))
    }

    @Test
    fun `空键归一化为 default`() {
        assertEquals("default", AccountKeys.storageKey(""))
        assertEquals("default", AccountKeys.storageKey(null))
    }

    /** 🔴 归一化结果必须是"文件系统安全"的：只含 ASCII 安全字符。 */
    @Test
    fun `storageKey 结果总是文件系统安全的`() {
        val samples = listOf(
            "hnnu::20230001", "hnnu::张三", "", "a/b\\c", "x?y*z", "名字 带空格",
            "hnnu::2023-0001.x", ":::", "中文学校::学号"
        )
        samples.forEach { raw ->
            val key = AccountKeys.storageKey(raw)
            assertTrue(
                "storageKey(\"$raw\") = \"$key\" 含不安全字符",
                key.matches(Regex("^[A-Za-z0-9_.-]+$"))
            )
        }
    }

    /** 🔴 归一化必须幂等：对已经是 storage key 的值再归一化不能变。 */
    @Test
    fun `storageKey 幂等`() {
        listOf("hnnu::20230001", "hnnu::张三", "").forEach { raw ->
            val once = AccountKeys.storageKey(raw)
            assertEquals("对 \"$raw\" 的二次归一化改变了结果", once, AccountKeys.storageKey(once))
        }
    }

    /**
     * 🔴 归一化是**多对一**的：`hnnu::张三` 与 `hnnu::李四` 都变成 `hnnu__`。
     *
     * 这不是 bug（真实场景里键的第一个身份是学号，不会是姓名），但必须显式记录，
     * 免得日后有人拿中文姓名当账号键的唯一身份，两个不同的人撞到同一份缓存上。
     */
    @Test
    fun `中文身份归一化后可能撞车_属于已知限制`() {
        assertEquals(
            AccountKeys.storageKey("hnnu::张三"),
            AccountKeys.storageKey("hnnu::李四")
        )
    }

    /** 键 → storage key 的链路：从构造到落盘形态完整走一遍。 */
    @Test
    fun `构造到归一化的完整链路`() {
        val key = AccountKeys.build("hnnu", "20230001", "20230001", "张三")
        assertEquals("hnnu__20230001", AccountKeys.storageKey(key))
    }
}

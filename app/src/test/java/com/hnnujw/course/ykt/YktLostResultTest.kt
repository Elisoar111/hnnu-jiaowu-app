package com.hnnujw.course.ykt

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 挂失/解挂**返回结果**解析的单测。
 *
 * ## 为什么这一层值得单独测
 *
 * [YktBalance.parseLostResult] 的输出直接驱动 UI 的成功/失败提示，
 * 而它的取值口径来自官方前端，有几个**反直觉**但必须尊重的点：
 *
 * 1. `retcode` 与 `errmsg` 可能挂在**顶层**，也可能嵌在 `data` 里
 *    （官方对不同网关路径的处理不一致），两层都要看；
 * 2. `"60007"`（已是挂失态）**只在挂失方向**算成功——
 *    解挂时收到它意味着"这卡本来就没挂失"，操作并没有生效，
 *    报成功会让用户以为解挂好了；
 * 3. 字面量字符串 `"null"` 与 JSON `null` 都要归一成空串，否则
 *    UI 会显示"错误码 null"这种莫名其妙的内容。
 *
 * 这三条没有一条会引发编译错误，错了也只能靠人眼在真机上发现——
 * 属于必须被单测钉死的静默语义。
 */
class YktLostResultTest {

    // ── 成功路径 ─────────────────────────────────────────────────────────

    /** 最常见的成功形态：顶层 `retcode == "0"`。 */
    @Test
    fun `顶层成功码算成功`() {
        val result = YktBalance.parseLostResult(
            JSONObject("""{"retcode":"0","errmsg":""}"""),
            isLostOperation = true,
        )
        assertTrue(result.success)
        assertEquals("0", result.retcode)
    }

    /**
     * `retcode` 嵌在 `data` 里时同样要认。
     *
     * 官方前端对两条路径都做了取值，说明服务端确实两种都产生过。
     * 只读顶层会让"实际成功"被报成失败，用户于是重复点、重复提交。
     */
    @Test
    fun `data 内层成功码也算成功`() {
        val json = JSONObject("""{"data":{"retcode":"0"}}""")
        val result = YktBalance.parseLostResult(json, isLostOperation = false)
        assertTrue(result.success)
        assertEquals("0", result.retcode)
    }

    /**
     * 挂失时 `"60007"`（已经是挂失态）视为成功。
     *
     * 用户的目标是"让这张卡不能用"，卡已经在挂失态时目标已达成。
     * 报失败会促使他去卡务中心白跑一趟。
     */
    @Test
    fun `挂失方向的 60007 算成功`() {
        val result = YktBalance.parseLostResult(
            JSONObject("""{"retcode":"60007"}"""),
            isLostOperation = true,
        )
        assertTrue("已是挂失态对用户而言即成功", result.success)
        assertEquals("60007", result.retcode)
    }

    // ── 失败路径 ─────────────────────────────────────────────────────────

    /**
     * **解挂方向收到 `60007` 必须算失败**。
     *
     * 该码语义是"卡已处于挂失态"。在解挂请求上收到它，说明解挂**没有生效**
     * ——卡依然是冻结的。若与挂失方向共用成功判定，用户会以为已解挂、
     * 到食堂才发现刷不了。
     *
     * 这是本类里最容易被"统一处理"改写掉的一条，故单独钉死。
     */
    @Test
    fun `解挂方向的 60007 不算成功`() {
        val result = YktBalance.parseLostResult(
            JSONObject("""{"retcode":"60007"}"""),
            isLostOperation = false,
        )
        assertFalse("60007 在解挂方向意味着卡仍处于挂失态，操作未生效", result.success)
    }

    /** 其它非零码一律失败，且要把错误信息带出来给用户看。 */
    @Test
    fun `非零码算失败并保留错误信息`() {
        val result = YktBalance.parseLostResult(
            JSONObject("""{"retcode":"1","errmsg":"密码错误"}"""),
            isLostOperation = true,
        )
        assertFalse(result.success)
        assertEquals("1", result.retcode)
        assertEquals("密码错误", result.message)
    }

    /** `errmsg` 嵌在 `data` 里同样要取到。 */
    @Test
    fun `data 内层错误信息也能取到`() {
        val result = YktBalance.parseLostResult(
            JSONObject("""{"data":{"errmsg":"该卡已被冻结"}}"""),
            isLostOperation = true,
        )
        assertFalse(result.success)
        assertEquals("该卡已被冻结", result.message)
    }

    /** 空 JSON 对象：无码即失败，不能误判成成功。 */
    @Test
    fun `空对象算失败`() {
        val result = YktBalance.parseLostResult(JSONObject("{}"), isLostOperation = true)
        assertFalse("没有成功码就不能当成功", result.success)
        assertEquals("", result.retcode)
    }

    /**
     * `null` 输入要给出**可读**的失败原因，而不是抛异常或返回空串。
     *
     * 返回空串会让 UI 退化成"操作未成功"这种无信息量的提示。
     */
    @Test
    fun `null 输入返回可读的失败原因`() {
        val result = YktBalance.parseLostResult(null, isLostOperation = true)
        assertFalse(result.success)
        assertTrue("应给出非空的失败说明", result.message.isNotBlank())
    }

    // ── 脏值归一 ─────────────────────────────────────────────────────────

    /**
     * 字面量字符串 `"null"` 必须归一成空串。
     *
     * 服务端序列化时常把 Java 的 `null` 写成字符串 `"null"`。若直接透传，
     * UI 会显示"挂失失败：错误码 null"——用户完全无法据此行动。
     */
    @Test
    fun `字面量 null 字符串归一为空`() {
        val json = JSONObject("""{"retcode":"null","errmsg":"null"}""")
        val result = YktBalance.parseLostResult(json, isLostOperation = true)
        assertFalse(result.success)
        assertEquals("", result.retcode)
        assertEquals("", result.message)
    }

    /** JSON 原生 `null` 同样归一为空串。 */
    @Test
    fun `JSON null 归一为空`() {
        val json = JSONObject("""{"retcode":null,"errmsg":null}""")
        val result = YktBalance.parseLostResult(json, isLostOperation = true)
        assertFalse(result.success)
        assertEquals("", result.retcode)
        assertEquals("", result.message)
    }

    /** 码值两侧的空白不应影响判定（服务端偶发带空格）。 */
    @Test
    fun `成功码带空白仍算成功`() {
        val result = YktBalance.parseLostResult(
            JSONObject("""{"retcode":" 0 "}"""),
            isLostOperation = true,
        )
        assertTrue(result.success)
    }

    /** 常量本身也在契约里：改了它们就等于改了成功判据。 */
    @Test
    fun `成功码常量与官方一致`() {
        assertEquals("0", YktBalance.RETCODE_OK)
        assertEquals("60007", YktBalance.RETCODE_ALREADY_LOST)
    }
}

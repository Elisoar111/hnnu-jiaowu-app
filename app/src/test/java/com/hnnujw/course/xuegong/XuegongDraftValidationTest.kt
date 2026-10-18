package com.hnnujw.course.xuegong

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 学工系统「时间解析」与「提交前校验」的回归。
 *
 * 这两处此前**零测试**（整个 `xuegong` 包 1,109 行、0 个测试文件），
 * 而它们直接影响一次**真实提交**：校验漏了会提交出无效请假单，
 * 时间解析错了会把起止时间算反。
 *
 * 纯 JVM 可测：只依赖 `JSONObject`（测试里用真实的 org.json）与 `SimpleDateFormat`。
 */
class XuegongDraftValidationTest {

    // ── parseSiteTime：站点两种时间写法都要认

    @Test
    fun `斜杠格式能被解析`() {
        assertNotNull(parseSiteTime("2026/10/01 08:00"))
    }

    /**
     * 🔴 回归：现行 PC/H5 下发的是「横杠 + 整点 + 无分钟」（`2026-10-01 08`）。
     *
     * 注释里记着：之前只认斜杠格式，导致表单骨架下发的默认时间解析失败、
     * 「共计 N 小时」算不出来。这条钉住那个修复。
     */
    @Test
    fun `横杠整点无分钟格式能被解析`() {
        assertNotNull(parseSiteTime("2026-10-01 08"))
    }

    @Test
    fun `带秒的格式能被解析`() {
        assertNotNull(parseSiteTime("2026-10-01 08:00:00"))
    }

    @Test
    fun `只有日期也能被解析`() {
        assertNotNull(parseSiteTime("2026-10-01"))
    }

    @Test
    fun `同一时刻的斜杠与横杠写法解析结果相同`() {
        val a = parseSiteTime("2026/10/01 08:00")
        val b = parseSiteTime("2026-10-01 08:00")
        assertEquals("两种写法必须等价", a, b)
    }

    @Test
    fun `前后空白不影响解析`() {
        assertNotNull(parseSiteTime("  2026-10-01 08  "))
    }

    @Test
    fun `空串与空白返回 null`() {
        assertNull(parseSiteTime(""))
        assertNull(parseSiteTime("   "))
    }

    @Test
    fun `乱写的字符串返回 null 而不是抛异常`() {
        assertNull(parseSiteTime("明天早上"))
        assertNull(parseSiteTime("2026-13-45 99:99"))
    }

    /** 非宽松解析：`2026-02-30` 不该被"顺延"成 3 月 2 日。 */
    @Test
    fun `不存在的日期返回 null`() {
        assertNull(parseSiteTime("2026-02-30 08:00"))
    }

    @Test
    fun `后一个时刻大于前一个时刻`() {
        val begin = parseSiteTime("2026-10-01 08")!!
        val end = parseSiteTime("2026-10-02 08")!!
        assertTrue(end > begin)
    }

    // ── 请假草稿校验

    private fun leaveDraft(): XuegongLeaveDraft {
        // 骨架里 ApplyInfo 的所有键都能缺省；用空对象即可，字段都在 data class 上。
        val form = JSONObject().put("ApplyInfo", JSONObject())
        return XuegongLeaveDraft.of(form)
    }

    /** 把一份最小可提交的请假单填好。 */
    private fun validLeave() = leaveDraft().apply {
        beginTime = "2026-10-01 08"
        endTime = "2026-10-02 08"
        reason = "事假"
        reasonDetail = "家里有事"
        stuMoveTel = "13800000000"
        isTellGuarder = "0"
        isOut = "0"
        isCompanion = "0"
    }

    @Test
    fun `填全必填项后校验通过`() {
        assertNull(validLeave().validate())
    }

    @Test
    fun `缺开始时间被拦下`() {
        val d = validLeave().apply { beginTime = "" }
        assertNotNull(d.validate())
        assertTrue(d.validate()!!.contains("开始时间"))
    }

    @Test
    fun `缺结束时间被拦下`() {
        val d = validLeave().apply { endTime = "" }
        assertNotNull(d.validate())
    }

    /** 🔴 结束时间不晚于开始时间必须被拦下 —— 提交上去就是一张无效请假单。 */
    @Test
    fun `结束时间早于开始时间被拦下`() {
        val d = validLeave().apply {
            beginTime = "2026-10-02 08"
            endTime = "2026-10-01 08"
        }
        val err = d.validate()
        assertNotNull(err)
        assertTrue("应提示先后顺序: $err", err!!.contains("大于"))
    }

    @Test
    fun `结束时间等于开始时间被拦下`() {
        val d = validLeave().apply {
            beginTime = "2026-10-01 08"
            endTime = "2026-10-01 08"
        }
        assertNotNull(d.validate())
    }

    @Test
    fun `时间格式不合法被拦下`() {
        val d = validLeave().apply { beginTime = "不是时间" }
        assertNotNull(d.validate())
    }

    @Test
    fun `缺请假原因被拦下`() {
        val d = validLeave().apply { reason = "" }
        assertNotNull(d.validate())
    }

    @Test
    fun `缺详细说明被拦下`() {
        val d = validLeave().apply { reasonDetail = "" }
        assertNotNull(d.validate())
    }

    @Test
    fun `缺本人电话被拦下`() {
        val d = validLeave().apply { stuMoveTel = "" }
        assertNotNull(d.validate())
    }

    // ── 条件必填

    @Test
    fun `已告知监护人但没填监护人电话被拦下`() {
        val d = validLeave().apply { isTellGuarder = "1"; guarderTel = "" }
        val err = d.validate()
        assertNotNull(err)
        assertTrue("应提示监护人电话: $err", err!!.contains("监护人"))
    }

    @Test
    fun `告知监护人且填了电话就通过`() {
        val d = validLeave().apply { isTellGuarder = "1"; guarderTel = "13900000000" }
        assertNull(d.validate())
    }

    @Test
    fun `离校时缺外出地点被拦下`() {
        val d = validLeave().apply { isOut = "1"; outAddress = "" }
        assertNotNull(d.validate())
    }

    @Test
    fun `离校时缺外出方式被拦下`() {
        val d = validLeave().apply {
            isOut = "1"
            outAddress = "某地"
            outGoTime = "2026-10-01 08"
            outBackTime = "2026-10-02 08"
            outGoVehicle = ""
        }
        assertNotNull(d.validate())
    }

    @Test
    fun `离校信息填全后通过`() {
        val d = validLeave().apply {
            isOut = "1"
            outAddress = "某地"
            outGoTime = "2026-10-01 08"
            outBackTime = "2026-10-02 08"
            outGoVehicle = "火车"
            outBackVehicle = "火车"
        }
        assertNull(d.validate())
    }

    @Test
    fun `结伴同行时缺同行人姓名被拦下`() {
        val d = validLeave().apply { isCompanion = "1"; companionName = "" }
        val err = d.validate()
        assertNotNull(err)
        assertTrue("应提示同行人: $err", err!!.contains("同行"))
    }

    // ── 去向登记草稿校验

    private fun whereaboutsDraft(): XuegongWhereaboutsDraft =
        XuegongWhereaboutsDraft.of(JSONObject().put("ApplyInfo", JSONObject()))

    private fun validWhereabouts() = whereaboutsDraft().apply {
        leaveType = "1"
        beginTime = "2026-10-01 08"
        endTime = "2026-10-02 08"
        reason = "回家"
        outGoVehicle = "火车"
        comeWhere = "家"
        outContacts = "张三"
        outContactsRelationship = "父亲"
        outContactsMoveTel = "13800000000"
        stuMoveTel = "13900000000"
    }

    @Test
    fun `去向登记填全后通过`() {
        assertNull(validWhereabouts().validate())
    }

    @Test
    fun `留校类型不要求外出字段`() {
        val d = validWhereabouts().apply {
            leaveType = "2"
            stayBeginTime = "2026-10-01 08"
            stayEndTime = "2026-10-02 08"
            reason = ""
            outGoVehicle = ""
            comeWhere = ""
            outContacts = ""
            outContactsRelationship = ""
            outContactsMoveTel = ""
        }
        assertNull("留校只查时间段与本人电话: ${d.validate()}", d.validate())
    }

    @Test
    fun `留校缺开始时间被拦下`() {
        val d = validWhereabouts().apply { leaveType = "2"; stayBeginTime = "" }
        assertNotNull(d.validate())
    }

    @Test
    fun `事由超过 400 字被拦下`() {
        val d = validWhereabouts().apply { reason = "啊".repeat(401) }
        val err = d.validate()
        assertNotNull(err)
        assertTrue("应提示字数上限: $err", err!!.contains("400"))
    }

    @Test
    fun `事由正好 400 字可以通过`() {
        val d = validWhereabouts().apply { reason = "啊".repeat(400) }
        assertNull(d.validate())
    }

    @Test
    fun `离校缺联系人电话被拦下`() {
        val d = validWhereabouts().apply { outContactsMoveTel = "" }
        assertNotNull(d.validate())
    }

    @Test
    fun `任何人缺本人电话都被拦下`() {
        val d = validWhereabouts().apply { stuMoveTel = "" }
        assertNotNull(d.validate())
    }
}

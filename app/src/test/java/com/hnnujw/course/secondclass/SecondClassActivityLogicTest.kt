package com.hnnujw.course.secondclass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 活动模块纯逻辑回归。
 *
 * 这里不碰网络：扫码载荷的解析与"能不能提交"的判定都是纯函数，
 * 而它们正好是**写操作前的唯一闸门**，所以必须钉死在单测里。
 *
 * 所有期望值都来自 2026-09-21 线上构建的反解结果
 * （见 docs/adaptation/2026-09-18-hnnu-second-classroom.md），不是猜的。
 */
class SecondClassActivityLogicTest {

    private val yiban = "?yiban=yiban_scan_result"

    // ── 扫码载荷解析 ──────────────────────────────────────────────────────

    @Test
    fun parsesSignerCodeWithYibanSuffix() {
        val raw = "qutuo://sign?activityId=7358&userId=10086&sp=1787823794093$yiban"
        val payload = SecondClassScanCodec.parse(raw)

        assertTrue(payload is SecondClassScanPayload.SignCode)
        payload as SecondClassScanPayload.SignCode
        assertEquals(7358, payload.activityId)
        assertEquals("10086", payload.userId)
        assertEquals("1787823794093", payload.sp)
        assertFalse(payload.waitSign)
        // 站点：indexOf("sign") > -1 → type = 2
        assertEquals(2, payload.submitType)
    }

    @Test
    fun parsesWaitSignCodeAsTypeOne() {
        val raw = "qutuo://waitSign?activityId=7358&userId=10086&sp=1787823794093$yiban"
        val payload = SecondClassScanCodec.parse(raw) as SecondClassScanPayload.SignCode

        assertTrue(payload.waitSign)
        // 站点：indexOf("waitSign") > -1 → type = 1
        assertEquals(1, payload.submitType)
    }

    /**
     * 站点是 `i.replace("?yiban=yiban_scan_result","")` 之后 `i.split("?")[1]`。
     * 若后缀黏在最后一个参数上（本站真实形态），`sp` 不能被带脏。
     */
    @Test
    fun keepsSpCleanWhenSuffixGluesToLastParam() {
        val payload = SecondClassScanCodec.parse(
            "qutuo://waitSign?activityId=1&userId=2&sp=1700000000000$yiban"
        ) as SecondClassScanPayload.SignCode

        assertEquals("1700000000000", payload.sp)
    }

    @Test
    fun parsesOrganizerActivityUrl() {
        // 站点：Encrypts 是恒等函数，所以两个参数都是明文（app.js 模块 6Y3k）
        val payload = SecondClassScanCodec.parse(
            "https://ekta.hnnu.edu.cn/?sourceName=schActivityCode@Xj&activityid=7358"
        )
        assertEquals(SecondClassScanPayload.ActivityCode(7358), payload)
    }

    @Test
    fun parsesOrganizationCode() {
        val payload = SecondClassScanCodec.parse("qutuo://joinOrganization?id=42$yiban")
        assertEquals(SecondClassScanPayload.OrganizationCode("42"), payload)
    }

    @Test
    fun fallsBackToTextForUnknownContent() {
        assertEquals(SecondClassScanPayload.Text("hello"), SecondClassScanCodec.parse("hello"))
        assertEquals(SecondClassScanPayload.Text(""), SecondClassScanCodec.parse(""))
    }

    @Test
    fun builtWaitSignCodeRoundTrips() {
        val code = SecondClassScanCodec.buildWaitSignCode(activityId = 7358, userId = "10086", sp = 1700000000000L)

        assertTrue(code.startsWith(SecondClassScanCodec.WAIT_SIGN_PREFIX))
        assertTrue(code.endsWith(yiban))

        val payload = SecondClassScanCodec.parse(code) as SecondClassScanPayload.SignCode
        assertEquals(7358, payload.activityId)
        assertEquals("10086", payload.userId)
        assertEquals("1700000000000", payload.sp)
        assertTrue(payload.waitSign)
    }

    // ── 提交前的安全闸门 ──────────────────────────────────────────────────

    /**
     * 最关键的一条：学生扫**签到员出示的码**（`qutuo://sign`，码内 userId 是签到员的）
     * 是站点设计的主路径，必须放行。早期误判为"userId 不是我就拒绝"会把这条路堵死。
     */
    @Test
    fun allowsScanningSignersCodeWhenEnrolled() {
        val payload = SecondClassScanCodec.parse("qutuo://sign?activityId=7358&userId=999&sp=1$yiban")
        val decision = SecondClassScanCodec.evaluateSignScan(
            payload = payload,
            myUserId = "10086",
            enrolled = true,
            isSigner = false,
        )
        assertEquals(SecondClassScanCodec.SignScanDecision.Allow(2, "签到 / 签退"), decision)
    }

    @Test
    fun blocksSignCodeWhenNotEnrolled() {
        val payload = SecondClassScanCodec.parse("qutuo://sign?activityId=7358&userId=999&sp=1$yiban")
        val decision = SecondClassScanCodec.evaluateSignScan(payload, myUserId = "10086", enrolled = false, isSigner = false)
        assertTrue(decision is SecondClassScanCodec.SignScanDecision.Blocked)
    }

    @Test
    fun blocksSignCodeWithoutSp() {
        val payload = SecondClassScanCodec.parse("qutuo://sign?activityId=7358&userId=10086")
        val decision = SecondClassScanCodec.evaluateSignScan(payload, myUserId = "10086", enrolled = true, isSigner = false)
        assertTrue(decision is SecondClassScanCodec.SignScanDecision.Blocked)
    }

    /** 同学的 waitSign 码（码内 userId 是同学的）不能被普通学生提交 —— 那是代签。 */
    @Test
    fun blocksOtherStudentsWaitSignCodeForPlainStudent() {
        val payload = SecondClassScanCodec.parse("qutuo://waitSign?activityId=7358&userId=20000&sp=1$yiban")
        val decision = SecondClassScanCodec.evaluateSignScan(payload, myUserId = "10086", enrolled = true, isSigner = false)

        assertTrue(decision is SecondClassScanCodec.SignScanDecision.Blocked)
        assertTrue((decision as SecondClassScanCodec.SignScanDecision.Blocked).reason.contains("代他人签到"))
    }

    /** 但同样的码，如果我是本活动签到员，就是正当的代为补签。 */
    @Test
    fun allowsOtherStudentsWaitSignCodeForSigner() {
        val payload = SecondClassScanCodec.parse("qutuo://waitSign?activityId=7358&userId=20000&sp=1$yiban")
        val decision = SecondClassScanCodec.evaluateSignScan(payload, myUserId = "10086", enrolled = true, isSigner = true)
        assertEquals(SecondClassScanCodec.SignScanDecision.Allow(1, "你是本活动签到员，为该同学补签"), decision)
    }

    @Test
    fun allowsMyOwnWaitSignCode() {
        val payload = SecondClassScanCodec.parse("qutuo://waitSign?activityId=7358&userId=10086&sp=1$yiban")
        val decision = SecondClassScanCodec.evaluateSignScan(payload, myUserId = "10086", enrolled = true, isSigner = false)
        assertEquals(SecondClassScanCodec.SignScanDecision.Allow(1, "确认本人签到"), decision)
    }

    @Test
    fun blocksNonSignPayloads() {
        val myUserId = "10086"
        listOf(
            SecondClassScanPayload.ActivityCode(7358),
            SecondClassScanPayload.OrganizationCode("42"),
            SecondClassScanPayload.Text("nonsense"),
        ).forEach { payload ->
            assertTrue(
                "应拒绝：" + payload,
                SecondClassScanCodec.evaluateSignScan(payload, myUserId, enrolled = true, isSigner = true)
                    is SecondClassScanCodec.SignScanDecision.Blocked,
            )
        }
    }

    // ── 报名状态映射 ──────────────────────────────────────────────────────

    @Test
    fun mapsEnrollStateFromServerCodes() {
        // 站点文案实测：1=报名待审核，2=报名已通过，3=报名被驳回
        assertEquals(SecondClassEnrollState.NotEnrolled, enrollStateOf(0, null))
        assertEquals(SecondClassEnrollState.Pending, enrollStateOf(1, null))
        assertEquals(SecondClassEnrollState.Approved, enrollStateOf(2, null))
        assertEquals(SecondClassEnrollState.Rejected, enrollStateOf(3, null))
        // cancelStatus 非 null 一律"已取消"，优先于 applyStatus
        assertEquals(SecondClassEnrollState.Canceled, enrollStateOf(2, 1))
        // 未知取值不猜，界面据此禁用报名
        assertEquals(SecondClassEnrollState.Unknown, enrollStateOf(99, null))
    }

    @Test
    fun onlyApprovedCountsAsEnrolled() {
        assertTrue(SecondClassEnrollState.Approved.isEnrolled)
        assertFalse(SecondClassEnrollState.Pending.isEnrolled)
        assertFalse(SecondClassEnrollState.Unknown.isEnrolled)
    }

    // ── 阶段 / 签到能力 ───────────────────────────────────────────────────

    @Test
    fun derivesPhaseFromTimestamps() {
        val now = 1_700_000_000_000L
        assertEquals(SecondClassActivityPhase.Unknown, SecondClassActivityPhase.of(0L, 0L, now))
        assertEquals(SecondClassActivityPhase.NotStarted, SecondClassActivityPhase.of(now + 1000, now + 2000, now))
        assertEquals(SecondClassActivityPhase.Running, SecondClassActivityPhase.of(now - 1000, now + 1000, now))
        assertEquals(SecondClassActivityPhase.Ended, SecondClassActivityPhase.of(now - 2000, now - 1000, now))
    }

    /**
     * `signWay` 是字符串位掩码：含 "1"/"2" = 支持码签到；含 "3" = 只支持定位打卡。
     * 站点用完全相同的 `indexOf` 判断决定是否显示「扫一扫」。
     */
    @Test
    fun readsSignWayBitmask() {
        val base = SecondClassActivityDetail(id = 1, name = "x", signSwitch = 1)
        assertTrue(base.copy(signWay = "1").supportsCodeSign)
        assertTrue(base.copy(signWay = "2").supportsCodeSign)
        assertTrue(base.copy(signWay = "12").supportsCodeSign)
        assertFalse(base.copy(signWay = "3").supportsCodeSign)
        assertTrue(base.copy(signWay = "3").supportsLocationSign)
        // 没开签到，任何方式都不成立
        assertFalse(base.copy(signWay = "1", signSwitch = 0).supportsCodeSign)
    }

    @Test
    fun computesQuotaProgressOnlyWhenLimited() {
        val activity = SecondClassActivity(id = 1, name = "x", peopleLimit = 0, joinMemberCount = 3)
        assertTrue(activity.quotaProgress == null)

        val limited = activity.copy(peopleLimit = 10, joinMemberCount = 4)
        assertEquals(0.4f, limited.quotaProgress!!, 0.0001f)

        // 超员不炸（服务端可能给 joinMemberCount > peopleLimit）
        assertEquals(1f, limited.copy(joinMemberCount = 50).quotaProgress!!, 0.0001f)
    }

    @Test
    fun enrollmentWindowRespectsOpenAndCloseTimes() {
        val now = 1_700_000_000_000L
        val detail = SecondClassActivityDetail(id = 1, name = "x")
        assertTrue(detail.enrollmentOpenOf(now))

        assertFalse(detail.copy(enrollStartTime = now + 1000).enrollmentOpenOf(now))
        assertFalse(detail.copy(enrollEndTime = now - 1000).enrollmentOpenOf(now))
        assertTrue(detail.copy(enrollStartTime = now - 1000, enrollEndTime = now + 1000).enrollmentOpenOf(now))
    }

    /**
     * 列表剔除「报名已截止」的判据。只认截止这一头：还没开始报名的活动必须留在列表里
     * （详情页会显示禁用的「报名未开始」），时间缺省按"不限"处理。
     */
    @Test
    fun listActivityDetectsEndedEnrollment() {
        val now = 1_700_000_000_000L
        val activity = SecondClassActivity(id = 1, name = "x")

        // 缺省 = 不限，不算截止
        assertFalse(activity.enrollmentEndedOf(now))
        // 边界：正好等于截止时刻不算过期（与详情侧 enrollmentOpenOf 的 `now > end` 保持一致）
        assertFalse(activity.copy(enrollEndTime = now).enrollmentEndedOf(now))
        assertTrue(activity.copy(enrollEndTime = now - 1).enrollmentEndedOf(now))
        assertFalse(activity.copy(enrollEndTime = now + 1).enrollmentEndedOf(now))
        // 还没开始报名 ≠ 已截止：不能跟着被剔除
        assertFalse(activity.copy(enrollStartTime = now + 1000, enrollEndTime = now + 2000).enrollmentEndedOf(now))
    }

    /**
     * 「本院系可报」筛选的判据。
     *
     * 只用活动自己的 `collegeLimit`（院系原始配置）判定，**不碰 `isAbleEnroll`**——
     * 后者是站点把年级/诚信分/名额/时间揉在一起的复合判定，实测有
     * `collegeLimit="0"`（对全校开放）却 `isAbleEnroll=0` 的活动，用它筛会整片误杀。
     */
    @Test
    fun collegeFilterUsesCollegeLimitNotAbleEnroll() {
        val open = SecondClassActivity(id = 1, name = "全校活动")
        val mine = SecondClassActivity(id = 2, name = "本院系活动", collegeLimit = "7")
        val multiple = SecondClassActivity(id = 3, name = "多院系活动", collegeLimit = "3, 7 , 9")
        val others = SecondClassActivity(id = 4, name = "别的院系", collegeLimit = "3")
        val otherLimitList = SecondClassActivity(id = 5, name = "别的院系二", collegeLimit = "3,9")

        val myCollegeId = 7
        assertTrue(open.enrollableForCollege(myCollegeId))       // 缺省 = 不限院系
        assertTrue(mine.enrollableForCollege(myCollegeId))
        assertTrue(multiple.enrollableForCollege(myCollegeId))   // 列表里的空白也要能 trim 掉
        assertFalse(others.enrollableForCollege(myCollegeId))
        assertFalse(otherLimitList.enrollableForCollege(myCollegeId))

        // 显式 "0" 也是不限院系（站点建活动表单的 collegeLimitTemp="0"）
        assertTrue(SecondClassActivity(id = 6, name = "x", collegeLimit = "0").enrollableForCollege(myCollegeId))

        // isAbleEnroll 不参与判定：给它 false 也不影响"不限院系"的活动
        assertTrue(SecondClassActivity(id = 7, name = "x", isAbleEnroll = false).enrollableForCollege(myCollegeId))

        // 拿不到本人院系（0）= 一律放行：宁可多显示，也不要把列表筛空
        assertTrue(others.enrollableForCollege(0))
        assertTrue(otherLimitList.enrollableForCollege(0))
    }

    @Test
    fun buildsEnrollAnswerWithSiteFieldNames() {
        val json = SecondClassEnrollAnswer(key = "f_1", value = "张三", title = "姓名").toJson()
        assertEquals("f_1", json.getString("key"))
        assertEquals("张三", json.getString("value"))
        // 站点的拼写是 filedValueTitle（不是 fieldValueTitle），照抄
        assertEquals("姓名", json.getString("filedValueTitle"))
    }

    @Test
    fun pagedResultKnowsWhetherMoreRemain() {
        val full = SecondClassActivityPage(items = List(20) { SecondClassActivity(it, "a") }, total = 100, lastPage = false)
        assertTrue(full.hasMore)

        assertFalse(full.copy(lastPage = true).hasMore)
        assertFalse(full.copy(items = emptyList()).hasMore)
        assertFalse(full.copy(items = List(3) { SecondClassActivity(it, "a") }, total = 3).hasMore)
    }
}

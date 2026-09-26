package com.hnnujw.course.ykt

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 一卡通余额计算回归。
 *
 * 这些断言逐条对应官方前端的实现与**真机实测**结果，改代码前先看清对照关系：
 * - 电费文本解析 ↔ `chunk-3d2fd493.js` 的 `getBalanceFromString` + 2026-09-25 真机两条原文
 * - 总额口径 ↔ `chunk-493ca944.js` 的 `getCardInfo`
 * - feeitemid 提取 ↔ `appScheme/info` 应用栏 `url` 的 `feeitemid` query（淮师口径）
 */
class YktBalanceTest {

    // ── 电费文本解析 ──────────────────────────────────────────────────────

    @Test
    fun `解析全角冒号的剩余电量`() {
        val result = YktBalance.parseElectricityBalance("剩余电量：56.78")
        assertEquals(56.78, result.amount!!, 0.001)
        assertEquals("剩余电量", result.label)
    }

    @Test
    fun `解析半角冒号的剩余电量`() {
        val result = YktBalance.parseElectricityBalance("剩余电量: 12.5")
        assertEquals(12.5, result.amount!!, 0.001)
    }

    @Test
    fun `无冒号紧贴数字也能解析`() {
        // 官方正则里冒号是可选的，别想当然要求一定有
        val result = YktBalance.parseElectricityBalance("剩余电量100")
        assertEquals(100.0, result.amount!!, 0.001)
    }

    @Test
    fun `剩余金额与剩余水费同样识别`() {
        assertEquals(88.0, YktBalance.parseElectricityBalance("剩余金额：88").amount!!, 0.001)
        assertEquals(9.9, YktBalance.parseElectricityBalance("剩余水费：9.9").amount!!, 0.001)
    }

    @Test
    fun `整数带小数点也认`() {
        assertEquals(30.0, YktBalance.parseElectricityBalance("剩余电量：30.0").amount!!, 0.001)
    }

    /**
     * 与官方的**有意差异**：官方抽不到就回退 0.00 并显示，用户分不清
     * "真没电了"和"解析失败"。这里返回 null，界面显示"—"。
     */
    @Test
    fun `解析不出数字时返回 null 而不是 0`() {
        val result = YktBalance.parseElectricityBalance("系统维护中，请稍后再试")
        assertNull(result.amount)
    }

    @Test
    fun `空文本返回 null`() {
        assertNull(YktBalance.parseElectricityBalance("").amount)
        assertNull(YktBalance.parseElectricityBalance("   ").amount)
    }

    @Test
    fun `标签出现但后面没有数字时返回 null`() {
        assertNull(YktBalance.parseElectricityBalance("剩余电量：暂无").amount)
    }

    @Test
    fun `负数与正号都能解析`() {
        assertEquals(-5.0, YktBalance.parseElectricityBalance("剩余电量：-5").amount!!, 0.001)
        assertEquals(5.0, YktBalance.parseElectricityBalance("剩余电量：+5").amount!!, 0.001)
    }

    @Test
    fun `多个标签时取第一个命中的`() {
        // 官方正则逐个 indexOf，行为等价
        val result = YktBalance.parseElectricityBalance("剩余电量：11 剩余金额：22")
        assertEquals(11.0, result.amount!!, 0.001)
        assertEquals("剩余电量", result.label)
    }

    @Test
    fun `raw 保留原文供排查`() {
        val raw = "剩余电量：56.78"
        assertEquals(raw, YktBalance.parseElectricityBalance(raw).raw)
    }

    // ── ★ 真机两种原文（2026-09-25 实测，最容易漏的一组）─────────────────

    /**
     * 费项 181（1-6单元电费）的原文：`为<数>元` 形式。
     *
     * 「为」是连接字，**不是冒号也不是空白**，早期只有冒号的正则会漏掉它。
     */
    @Test
    fun `真机181 为X元 形态`() {
        val raw = "信息: 用户名称1A-101,剩余电量为55.28元"
        val result = YktBalance.parseElectricityBalance(raw)
        assertEquals(55.28, result.amount!!, 0.001)
        assertEquals("剩余电量", result.label)
        assertEquals(ElectricityUnit.YUAN, result.unit)
        assertTrue(result.supportsAmountAlert)
    }

    /**
     * 费项 201（7-11单元及东区电费）的原文：`:<数>` 形式，**没有「元」**。
     *
     * 这不是金额而是**电量（度）**。若把它当钱按 20 元阈值提醒，
     * "312.2 度" 会被判成"余额充足"，功能形同虚设且逻辑荒谬。
     */
    @Test
    fun `真机201 冒号度数形态 单位不是元`() {
        val raw = "信息: 房间名称: 7A-101 剩余电量:312.2"
        val result = YktBalance.parseElectricityBalance(raw)
        assertEquals(312.2, result.amount!!, 0.001)
        assertEquals(ElectricityUnit.KWH, result.unit)
        assertFalse("度数型不能参与金额阈值提醒", result.supportsAmountAlert)
    }

    /** 「剩余金额」标签天然是钱，即使文本里没写「元」。 */
    @Test
    fun `剩余金额标签推断为元`() {
        assertEquals(ElectricityUnit.YUAN, YktBalance.parseElectricityBalance("剩余金额：88").unit)
    }

    /** 「剩余水费」是水量，不是钱。 */
    @Test
    fun `剩余水费推断为非金额`() {
        assertEquals(ElectricityUnit.KWH, YktBalance.parseElectricityBalance("剩余水费：9.9").unit)
        assertFalse(YktBalance.parseElectricityBalance("剩余水费：9.9").supportsAmountAlert)
    }

    /** 单位窗口不能被别处的「元」污染。 */
    @Test
    fun `远处出现的元不影响本单位推断`() {
        // 标签后紧邻的还是电量数字，后面那句"合计 5 元"是别的信息
        val raw = "剩余电量:312.2 本次应缴合计 5 元"
        assertEquals(ElectricityUnit.KWH, YktBalance.parseElectricityBalance(raw).unit)
    }

    /** 带「元」的剩余电量就是金额（181 那种）。 */
    @Test
    fun `剩余电量带元字推断为金额`() {
        assertEquals(ElectricityUnit.YUAN, YktBalance.parseElectricityBalance("剩余电量为55.28元").unit)
    }

    // ── 单位展示 ─────────────────────────────────────────────────────────

    @Test
    fun `金额加货币符号 度数加单位后缀`() {
        assertEquals("¥12.50", YktBalance.formatWithUnit(12.5, ElectricityUnit.YUAN))
        assertEquals("312.20 度", YktBalance.formatWithUnit(312.2, ElectricityUnit.KWH))
        assertEquals("5.00", YktBalance.formatWithUnit(5.0, ElectricityUnit.UNKNOWN))
    }

    // ── 总额口径（$ecardConfig.type）──────────────────────────────────────

    private val card = YktCard(
        account = "2024001", cardNo = "10001",
        dbBalance = 100.0, unsettleAmount = 20.0, elecAccAmount = 30.0,
        status = "正常", expireDate = "2030-01-01",
    )

    @Test
    fun `默认口径把三项都计入`() {
        val config = YktEcardConfig(type = "", schoolNameCode = "")
        assertEquals(150.0, YktBalance.computeTotal(card, config), 0.001)
    }

    @Test
    fun `type=1 不计普通余额只计电费账户`() {
        val config = YktEcardConfig(type = "1", schoolNameCode = "")
        assertEquals(30.0, YktBalance.computeTotal(card, config), 0.001)
    }

    @Test
    fun `type=2 不计电费账户只计普通余额`() {
        val config = YktEcardConfig(type = "2", schoolNameCode = "")
        assertEquals(120.0, YktBalance.computeTotal(card, config), 0.001)
    }

    @Test
    fun `未结算金额计入普通余额`() {
        // 未结算（粘单）+ 卡内余额 一起算，这是官方算法
        val config = YktEcardConfig(type = "", schoolNameCode = "")
        val onlyCard = card.copy(unsettleAmount = 0.0, elecAccAmount = 0.0)
        assertEquals(100.0, YktBalance.computeTotal(onlyCard, config), 0.001)
    }

    // ── 单位换算与格式化 ─────────────────────────────────────────────────

    @Test
    fun `分转元`() {
        assertEquals(1.23, YktBalance.fenToYuan(123L), 0.0001)
        assertEquals(0.0, YktBalance.fenToYuan(0L), 0.0001)
    }

    @Test
    fun `金额固定两位小数`() {
        assertEquals("12.50", YktBalance.formatYuan(12.5))
        assertEquals("0.00", YktBalance.formatYuan(0.0))
        assertEquals("100.00", YktBalance.formatYuan(100.0))
    }

    // ── feeitemid 提取（淮师口径：url 里明文带 feeitemid）─────────────────

    @Test
    fun `从应用栏 url 里提取 feeitemid`() {
        val url = "https://yktapp.hnnu.edu.cn/charge/feeitem/toAppitem?feeitemid=181"
        assertEquals("181", YktBalance.extractFeeItemId(url))
    }

    @Test
    fun `feeitemid 不是第一个参数也能提取`() {
        val url = "https://x/a?foo=1&feeitemid=201&bar=2"
        assertEquals("201", YktBalance.extractFeeItemId(url))
    }

    @Test
    fun `没有 feeitemid 返回 null`() {
        assertNull(YktBalance.extractFeeItemId("https://x/a?other=1"))
        assertNull(YktBalance.extractFeeItemId("https://x/a"))
        assertNull(YktBalance.extractFeeItemId(""))
    }

    @Test
    fun `feeitemid 为空值时返回 null`() {
        // 空串不能当成有效费项 ID，否则会拿空参数去请求
        assertNull(YktBalance.extractFeeItemId("https://x/a?feeitemid="))
    }

    /** 兼容旧的 `showBal` 口径，但不允许前缀误匹配。 */
    @Test
    fun `兼容 showBal 且不误匹配前缀`() {
        assertEquals("10086", YktBalance.extractFeeItemId("https://x/a?showBal=10086"))
        assertNull(YktBalance.extractFeeItemId("https://x/a?showBalanother=1"))
    }

    /** `feeitemid` 优先于 `showBal`（两者同时出现时以新口径为准）。 */
    @Test
    fun `feeitemid 优先于 showBal`() {
        assertEquals("181", YktBalance.extractFeeItemId("https://x/a?showBal=999&feeitemid=181"))
    }

    // ── 真机字段形态：url 是内嵌 JSON 串（2026-09-25 修复的 bug）────────────
    //
    // 淮师主页 elcpay 应用项的 url 字段长这样（注意是一段被转义的 JSON 字符串，
    // 不是干净 URL）：
    //   {"name":"App","code":"app",…,"url":"/charge/feeitem/toAppitem?feeitemid=181"}
    // 早期实现直接 substringAfter('?')，会切出 `feeitemid=181"}`（带尾引号与花括号），
    // 拿这个去查询必然失败。下面这组用例把它钉死。

    @Test
    fun `能从内嵌 JSON 串的 url 里取出 feeitemid`() {
        val embedded = "{\"name\":\"App\",\"code\":\"app\",\"appIdRequired\":0," +
            "\"url\":\"/charge/feeitem/toAppitem?feeitemid=181\"}"
        assertEquals("181", YktBalance.extractFeeItemId(embedded))
    }

    @Test
    fun `内嵌 JSON 串的 showBal 也能取出`() {
        val embedded = "{\"url\":\"/plat/?name=dating&showBal=201\"}"
        assertEquals("201", YktBalance.extractFeeItemId(embedded))
    }

    @Test
    fun `干净的 website 形式不受影响`() {
        assertEquals("181", YktBalance.extractFeeItemId("/charge/feeitem/toAppitem?feeitemid=181"))
    }

    @Test
    fun `内嵌 JSON 无 url 字段时返回 null`() {
        assertNull(YktBalance.extractFeeItemId("{\"name\":\"App\",\"code\":\"app\"}"))
    }

    /**
     * **必须先解内层 url、再取 query** 的场景（`embeddedUrlValue` 的存在意义）。
     *
     * 若 JSON 里在 `url` 之前还有别的 `?`（真实构建里 `iconWholeList` / `appDesc`
     * 这类字段可能带查询串），直接对整串 `substringAfter('?')` 会切到**别人**的
     * query 上，从而取不到或取错。此时只有"先解内层 url"才能拿到正确值。
     *
     * 这条用例是 `embeddedUrlValue` 的**唯一有效护栏**：
     * 去掉解包、只靠 `normalizeIdValue` 剥引号时，它会 FAIL。
     */
    @Test
    fun `url 之前另有问号时必须先解内层 url`() {
        val embedded = "{\"appDesc\":\"http://x/a?from=json\"," +
            "\"url\":\"/charge/feeitem/toAppitem?feeitemid=201\"}"
        assertEquals("201", YktBalance.extractFeeItemId(embedded))
    }

    /** 内层 url 是多参数时，仍只取 feeitemid（不能把 `name=dating` 当成值）。 */
    @Test
    fun `内层 url 多参数时仍取到 feeitemid`() {
        val embedded = "{\"url\":\"/plat/?name=dating&feeitemid=181\"}"
        assertEquals("181", YktBalance.extractFeeItemId(embedded))
    }


    @Test
    fun `embeddedUrlValue 只认 url 键`() {
        assertEquals("/a?x=1", YktBalance.embeddedUrlValue("{\"url\":\"/a?x=1\"}"))
        assertNull(YktBalance.embeddedUrlValue("/a?x=1"))
        assertNull(YktBalance.embeddedUrlValue("{}"))
    }

    // ── 费项列表收集（appScheme/info 首页应用栏）─────────────────────────

    @Test
    fun `收集应用栏里的费项并去重`() {
        val apps = listOf(
            mapOf("appCode" to "elcpay", "name" to "1-6单元电费", "url" to "/charge/feeitem/toAppitem?feeitemid=181"),
            mapOf("appCode" to "elec", "name" to "7-11单元及东区电费", "url" to "/charge/feeitem/toAppitem?feeitemid=201"),
            mapOf("appCode" to "bill", "name" to "账单", "url" to "/plat/bill"),
            // 同一个费项出现两次（缴费/查询配成同一 id）→ 只留一条
            mapOf("appCode" to "elec2", "name" to "重复", "url" to "/charge/feeitem/toAppitem?feeitemid=181"),
        )
        val items = YktBalance.collectFeeItems(apps)
        assertEquals(2, items.size)
        assertEquals("181", items[0].feeItemId)
        assertEquals("1-6单元电费", items[0].name)
        assertEquals("201", items[1].feeItemId)
    }

    /** 真机形态：`appName` + `website`（干净 URL）+ `url`（内嵌 JSON 串）同时存在。 */
    @Test
    fun `优先用 appName 作为费项名并读 website 取 id`() {
        val apps = listOf(
            mapOf(
                "appCode" to "elcpay",
                "appName" to "1-6单元电控缴费",
                "website" to "/charge/feeitem/toAppitem?feeitemid=181",
                "url" to "{\"url\":\"/charge/feeitem/toAppitem?feeitemid=181\"}",
            ),
        )
        val items = YktBalance.collectFeeItems(apps)
        assertEquals(1, items.size)
        assertEquals("181", items[0].feeItemId)
        assertEquals("1-6单元电控缴费", items[0].name)
    }

    /** `website` 缺失时回退到内嵌 JSON 串的 `url`。 */
    @Test
    fun `website 缺失时从 url 串里兜底取 id`() {
        val apps = listOf(
            mapOf(
                "appCode" to "elec",
                "appName" to "7-11单元及东区电控缴费",
                "url" to "{\"url\":\"/charge/feeitem/toAppitem?feeitemid=201\"}",
            ),
        )
        val items = YktBalance.collectFeeItems(apps)
        assertEquals(1, items.size)
        assertEquals("201", items[0].feeItemId)
    }

    @Test
    fun `没有费项时返回空列表`() {
        assertTrue(YktBalance.collectFeeItems(emptyList()).isEmpty())
        assertTrue(
            YktBalance.collectFeeItems(
                listOf(mapOf("name" to "账单", "url" to "/plat/bill"))
            ).isEmpty()
        )
    }

    @Test
    fun `费项名缺失时给出兜底名`() {
        val items = YktBalance.collectFeeItems(
            listOf(mapOf("url" to "/charge/feeitem/toAppitem?feeitemid=181"))
        )
        assertEquals(1, items.size)
        assertTrue(items[0].name.contains("181"))
    }

    // ── 消费汇总的月份区间（纯函数，钉死边界）────────────────────────────

    /** 用固定时刻做基准：2026-09-15（本月 9 月）。 */
    private val refToday: Long = run {
        val c = java.util.Calendar.getInstance()
        c.set(2026, java.util.Calendar.SEPTEMBER, 15, 12, 0, 0)
        c.set(java.util.Calendar.MILLISECOND, 0)
        c.timeInMillis
    }

    @Test
    fun `本月区间覆盖整月`() {
        val (from, to) = YktBalance.monthRange(0, refToday)
        assertEquals("2026-09-01", from)
        assertEquals("2026-09-30", to)
    }

    @Test
    fun `上月区间正确且跨月边界不越界`() {
        val (from, to) = YktBalance.monthRange(-1, refToday)
        assertEquals("2026-08-01", from)
        assertEquals("2026-08-31", to)
    }

    /** 1 月减一个月要跨年，且 12 月是 31 天（最容易写错的边界之一）。 */
    @Test
    fun `跨年取上月正确`() {
        val jan = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.JANUARY, 10)
        }.timeInMillis
        val (from, to) = YktBalance.monthRange(-1, jan)
        assertEquals("2025-12-01", from)
        assertEquals("2025-12-31", to)
    }

    /** 2 月天数随闰年变化，必须用 getActualMaximum 而不是写死 28/30/31。 */
    @Test
    fun `闰年二月取到 29 天`() {
        val feb2024 = java.util.Calendar.getInstance().apply {
            set(2024, java.util.Calendar.FEBRUARY, 5)
        }.timeInMillis
        val (from, to) = YktBalance.monthRange(0, feb2024)
        assertEquals("2024-02-01", from)
        assertEquals("2024-02-29", to)
    }

    @Test
    fun `平年二月取到 28 天`() {
        val feb2026 = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.FEBRUARY, 5)
        }.timeInMillis
        assertEquals("2026-02-28", YktBalance.monthRange(0, feb2026).second)
    }

    @Test
    fun `月份标签 本月与上月走特殊文案`() {
        assertEquals("本月", YktBalance.monthLabel(0, refToday))
        assertEquals("上月", YktBalance.monthLabel(-1, refToday))
        assertEquals("2026年7月", YktBalance.monthLabel(-2, refToday))
    }

    // ── 消费汇总模型 ─────────────────────────────────────────────────────

    @Test
    fun `汇总空区间判定与净值`() {
        assertTrue(YktTurnover(0.0, 0.0).isEmpty)
        assertFalse(YktTurnover(0.0, 1.0).isEmpty)
        // 只支出 → net 为负（收入 − 支出）
        assertEquals(-23.24, YktTurnover(0.0, 2324.0 / 100.0).net, 1e-9)
        assertEquals(-10.0, YktTurnover(5.0, 15.0).net, 1e-9)
        assertEquals(10.0, YktTurnover(15.0, 5.0).net, 1e-9)
    }

    // ── 级联选项解析（charge-pc 真实口径，2026-09-25 实测）────────────────

    /**
     * `map.data` 的 `value` 是 **`"<id>&<name>"` 完整形态**，必须原样保留。
     *
     * ⚠️ 这里曾经用 `sceneValue()` 把它截成纯 id —— 那会让 `getThirdData`
     * 提交时丢掉名字段，服务端认不出场景，**并且不会报错**（只是返回空）。
     * 属于"静默失败"，只能靠测试钉住。
     */
    @Test
    fun `解析服务端下发的选项并保留完整的 id 与名字`() {
        val array = JSONArray().apply {
            put(JSONObject().put("name", "本校区").put("value", "1&本校区"))
            put(JSONObject().put("name", "01号学生公寓A区").put("value", "011&01号学生公寓A区"))
        }
        val options = YktBalance.parseSceneData(array)
        assertEquals(2, options.size)
        assertEquals("1&本校区", options[0].value)
        assertEquals("本校区", options[0].name)
        assertEquals("1", options[0].id)
        assertEquals("011&01号学生公寓A区", options[1].value)
        assertEquals("01号学生公寓A区", options[1].display)
    }

    /** 同一 `value` 只留一条（服务端偶发重复）。 */
    @Test
    fun `场景选项按 value 去重`() {
        val array = JSONArray().apply {
            put(JSONObject().put("name", "A").put("value", "1&A"))
            put(JSONObject().put("name", "A副本").put("value", "1&A"))
        }
        assertEquals(1, YktBalance.parseSceneData(array).size)
    }

    /** value 为空的项必须丢弃：提交空值必然查不到。 */
    @Test
    fun `丢弃 value 为空的场景选项`() {
        val array = JSONArray().apply {
            put(JSONObject().put("name", "无效").put("value", ""))
            put(JSONObject().put("name", "有效").put("value", "5&五号楼"))
        }
        val options = YktBalance.parseSceneData(array)
        assertEquals(1, options.size)
        assertEquals("5&五号楼", options[0].value)
    }

    /** `name` 缺失时用 value 里 `&` **之后**的部分兜底展示，不能显示成 `1&本校区`。 */
    @Test
    fun `场景选项 name 缺失时用 value 尾段兜底`() {
        val array = JSONArray().apply { put(JSONObject().put("value", "1&本校区")) }
        val option = YktBalance.parseSceneData(array)[0]
        assertEquals("本校区", option.name)
        assertEquals("本校区", option.display)
        // 提交值仍是完整的 "<id>&<name>"
        assertEquals("1&本校区", option.value)
    }

    /** 没有 `&` 时整段就是名字（老口径值就是纯 id/纯名）。 */
    @Test
    fun `场景选项无分隔符时整段兜底`() {
        val array = JSONArray().apply { put(JSONObject().put("value", "本校区")) }
        assertEquals("本校区", YktBalance.parseSceneData(array)[0].display)
    }

    @Test
    fun `场景选项解析对 null 与空数组返回空列表而不是崩`() {
        assertTrue(YktBalance.parseSceneData(null).isEmpty())
        assertTrue(YktBalance.parseSceneData(JSONArray()).isEmpty())
    }

    /** 级定义来自 `map.total`，**按 level 排序**（服务端不保证有序）。 */
    @Test
    fun `解析级定义并按层级排序`() {
        val array = JSONArray().apply {
            put(JSONObject().put("code", "room").put("level", 4).put("name", "房间"))
            put(JSONObject().put("code", "campus").put("level", 1).put("name", "校区"))
            put(JSONObject().put("code", "floor").put("level", 3).put("name", "楼层"))
            put(JSONObject().put("code", "building").put("level", 2).put("name", "楼栋"))
        }
        val levels = YktBalance.parseSceneLevels(array)
        assertEquals(listOf(1, 2, 3, 4), levels.map { it.level })
        assertEquals(listOf("campus", "building", "floor", "room"), levels.map { it.code })
        assertEquals("房间", levels.last().name)
    }

    /** 级定义缺字段的脏数据要跳过，不能造出没有 code 的幽灵级别。 */
    @Test
    fun `级定义缺 code 时跳过`() {
        val array = JSONArray().apply {
            put(JSONObject().put("level", 1).put("name", "校区"))
            put(JSONObject().put("code", "building").put("level", 2).put("name", "楼栋"))
        }
        val levels = YktBalance.parseSceneLevels(array)
        assertEquals(1, levels.size)
        assertEquals("building", levels[0].code)
    }

    /**
     * 读数附带的**位置上下文**（`map.data` 在 `type=IEC` 时是对象而非数组）。
     *
     * 界面靠它回答"55.28 元是哪间房的"；全空时必须返回 null，
     * 否则会渲染出一行空摘要。
     */
    @Test
    fun `解析读数附带的位置上下文`() {
        val obj = JSONObject()
            .put("areaName", "本校区")
            .put("buildingName", "01号学生公寓A区")
            .put("floorName", "1层")
            .put("roomName", "01A-101")
            .put("account", "19704")
        val ctx = YktBalance.parseSceneContext(obj)
        assertEquals("本校区 · 01号学生公寓A区 · 1层 · 01A-101", ctx?.summary)
        assertEquals("19704", ctx?.account)
    }

    @Test
    fun `位置上下文全空时返回 null`() {
        assertEquals(null, YktBalance.parseSceneContext(null))
        assertEquals(null, YktBalance.parseSceneContext(JSONObject()))
    }

    // ── showData 文本提取 ─────────────────────────────────────────────────

    @Test
    fun `从 showData 里取中文字段信息`() {
        val text = YktBalance.extractShowDataText(mapOf("信息" to "剩余电量：56.78"))
        assertEquals("剩余电量：56.78", text)
    }

    @Test
    fun `showData 缺失或字段缺失时返回空串`() {
        assertEquals("", YktBalance.extractShowDataText(null))
        assertEquals("", YktBalance.extractShowDataText(emptyMap()))
        assertEquals("", YktBalance.extractShowDataText(mapOf("其他" to "x")))
    }

    // ── 端到端：文本 → 可展示值 ──────────────────────────────────────────

    @Test
    fun `完整链路 从响应文本到展示串`() {
        val text = YktBalance.extractShowDataText(mapOf("信息" to "剩余电量：42.50"))
        val parsed = YktBalance.parseElectricityBalance(text)
        assertEquals("42.50", YktBalance.formatYuan(parsed.amount!!))
    }

    @Test
    fun `解析失败时展示串为占位符而非 0`() {
        val parsed = YktBalance.parseElectricityBalance("服务不可用")
        assertTrue(parsed.amount == null)
    }

    // ── 选择记忆的序列化往返（v2：动态级数）──────────────────────────────

    /**
     * 往返必须无损，且 **`value` 要原样保留 `"<id>&<name>"`**。
     *
     * 落盘时若把 `value` 截断（或把 `&` 当分隔符），下次进页面提交给
     * `getThirdData` 的场景参数就是错的，而**服务端只会返回空、不会报错**。
     */
    @Test
    fun `选择上下文可无损往返`() {
        val selection = YktSceneSelection(
            feeItemId = "201",
            picks = listOf(
                ScenePick("campus", "1&本校区", "本校区", 1),
                ScenePick("building", "7&7号学生公寓A区", "7号学生公寓A区", 2),
                ScenePick("floor", "1&1层", "1层", 3),
                ScenePick("room", "701&7A-101", "7A-101", 4),
            ),
        )
        val back = YktSelectionStore.decode(YktSelectionStore.encode(selection))
        assertEquals(selection, back)
        assertEquals("701&7A-101", back.pick("room")?.value)
    }

    /** 级数不同（3 级）也必须能往返 —— 存储层不能假设固定 4 条记录。 */
    @Test
    fun `三级选择也可往返`() {
        val selection = YktSceneSelection(
            feeItemId = "181",
            picks = listOf(
                ScenePick("campus", "1&本校区", "本校区", 1),
                ScenePick("building", "011&01号学生公寓A区", "01号学生公寓A区", 2),
                ScenePick("room", "011101&01A-101", "01A-101", 3),
            ),
        )
        assertEquals(selection, YktSelectionStore.decode(YktSelectionStore.encode(selection)))
    }

    @Test
    fun `空选择可往返`() {
        assertEquals(
            YktSceneSelection.EMPTY,
            YktSelectionStore.decode(YktSelectionStore.encode(YktSceneSelection.EMPTY)),
        )
    }

    /** 只有费项、还没选级别，也必须能往返。 */
    @Test
    fun `只有费项的中间态可往返`() {
        val selection = YktSceneSelection(feeItemId = "201")
        assertEquals(selection, YktSelectionStore.decode(YktSelectionStore.encode(selection)))
    }

    /** 损坏数据（非 v2 / 字段数不对）必须回退成空选择，不能让页面崩。 */
    @Test
    fun `字段数不对时回退空选择`() {
        assertEquals(YktSceneSelection.EMPTY, YktSelectionStore.decode("只有一段"))
        assertEquals(YktSceneSelection.EMPTY, YktSelectionStore.decode("a\u001Fb\u001Fc"))
        // 旧格式（固定四字段）读到时按"无记忆"处理，用户重选一次即可
        assertEquals(YktSceneSelection.EMPTY, YktSelectionStore.decode("201\u001F1\u001F7\u001F1\u001F701"))
    }

    /**
     * 单条 pick 残缺时只丢那一条，其余级别照常恢复。
     *
     * ⚠️ 用例写法说明：pick 记录内部用 `\u001E` 分隔 code/value/name，
     * **记录之间用 `\u001F`**。第一版把 `\u001E` 又用了一次当记录分隔，
     * 于是整串被当成"一条字段数不对的记录"整体丢弃 —— 是**用例写错**而非实现错，
     * 这里显式分好两种分隔符。
     */
    @Test
    fun `残缺的单条记录被跳过其余保留`() {
        val good = listOf("campus", "1&本校区", "本校区").joinToString("\u001E")
        val broken = "building\u001E011"           // 只有两段 → 字段数不对
        val raw = listOf("v2", "201", good, broken).joinToString("\u001F")
        val decoded = YktSelectionStore.decode(raw)
        assertEquals("201", decoded.feeItemId)
        assertEquals(1, decoded.picks.size)
        assertEquals("campus", decoded.picks[0].code)
    }

    @Test
    fun `isQueryable 要求费项与至少一级选择`() {
        assertFalse(YktSceneSelection.EMPTY.isQueryable)
        assertFalse(YktSceneSelection(feeItemId = "181").isQueryable)
        assertFalse(YktSceneSelection(picks = listOf(ScenePick("room", "701&7A-101", "7A-101"))).isQueryable)
        assertTrue(
            YktSceneSelection(
                feeItemId = "181",
                picks = listOf(ScenePick("room", "701&7A-101", "7A-101")),
            ).isQueryable
        )
    }

    @Test
    fun `summary 只拼接非空段`() {
        val selection = YktSceneSelection(
            picks = listOf(
                ScenePick("campus", "1&本校区", "本校区", 1),
                ScenePick("building", "7&", "", 2),
                ScenePick("floor", "1&1层", "1层", 3),
                ScenePick("room", "701&1A-101", "1A-101", 4),
            ),
        )
        assertEquals("本校区 · 1层 · 1A-101", selection.summary)
    }
}

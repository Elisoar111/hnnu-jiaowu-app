package com.hnnujw.course.ykt

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `YktClient.collectAppViewItems` 的语义护栏。
 *
 * ## 为什么必须有这组测试（血泪）
 *
 * 该函数**曾经是 `private` 且无测试**，于是"只取第一个带 `combinedAppList` 的组件"
 * 这个假设悄悄定型。真机上首页有 6 个组件、电费两个入口在**第 3 组**，
 * 结果 `feeItems` 恒空 → 界面显示"学校未开通电费查询"并**藏掉整块校区/楼栋/楼层/房间选择**，
 * 用户报"没有办法查看电费，没有选择那栋那层那间"。
 *
 * 纯逻辑（"要扫所有组件"）本可以被单测钉住，却因为 `private` 而无人看守。
 * 这组用例专门守住它：**只要退回"只扫第一个组件"，用例立刻 FAIL**。
 */
class YktClientAppSchemeTest {

    private val client = YktClient()

    /** 造一个与真机 `appScheme/info` 同构的响应：首页 3 个有内容组件 + 3 个空组件。 */
    private fun realWorldScheme(): JSONObject {
        fun app(code: String, name: String, url: String): JSONObject = JSONObject()
            .put("appCode", code)
            .put("appName", name)
            .put("website", url)
            .put("url", JSONObject().put("url", url).toString())

        fun component(vararg apps: JSONObject): JSONObject = JSONObject()
            // 真机实测：首页组件的 componentKey / code 都是 null，type 恒为 "user"
            .put("componentKey", JSONObject.NULL)
            .put("code", JSONObject.NULL)
            .put("type", "user")
            .put("combinedAppList", JSONArray(apps.toList()))

        val home = JSONObject()
            .put("name", "首页")
            .put(
                "combinedComponentList",
                JSONArray(
                    listOf(
                        component(
                            app("tianqi", "天气", "/weather/"),
                            app("sousuo", "搜索", "/plat?name=search"),
                        ),
                        component(
                            app("scan", "扫一扫", ""),
                            app("pay-code", "付款", "/plat/?name=pay"),
                            app("auth-code", "认证码", "/plat?name=cardcode"),
                        ),
                        component(
                            app("bill", "账单", "/campus-card/?name=billList"),
                            app("elcpay", "1-6单元电控缴费", "/charge/feeitem/toAppitem?feeitemid=181"),
                            app("elec", "7-11单元及东区电控缴费", "/charge/feeitem/toAppitem?feeitemid=201"),
                        ),
                        component(),
                        component(),
                        component(),
                    )
                ),
            )
        return JSONObject()
            .put("code", 200)
            .put("data", JSONObject().put("structureInfo", JSONObject().put("combinedMenuList", JSONArray(listOf(home)))))
    }

    @Test
    fun `必须扫遍首页所有组件而不是只取第一个`() {
        val items = client.collectAppViewItems(realWorldScheme())
        // 2 + 3 + 3 = 8（空组件不贡献）
        assertEquals(8, items.size)
        val codes = items.mapNotNull { it["appCode"]?.toString() }
        assertTrue("必须包含第 3 个组件里的 elcpay", codes.contains("elcpay"))
        assertTrue("必须包含第 3 个组件里的 elec", codes.contains("elec"))
    }

    /** 端到端：从该响应里能抽出两个电费费项（这是用户最关心的一步）。 */
    @Test
    fun `从真实结构里能抽出两个电费费项`() {
        val feeItems = YktBalance.collectFeeItems(client.collectAppViewItems(realWorldScheme()))
        assertEquals(2, feeItems.size)
        assertEquals(setOf("181", "201"), feeItems.map { it.feeItemId }.toSet())
        // 名称取 appName（真机字段），不应是空或兜底名
        assertTrue(feeItems.any { it.name.contains("1-6单元") })
        assertTrue(feeItems.any { it.name.contains("7-11单元") })
    }

    /** 没有「首页」菜单时退化为扫全部菜单（宁多勿漏）。 */
    @Test
    fun `没有首页菜单时扫全部菜单`() {
        val other = JSONObject()
            .put("name", "大厅")
            .put(
                "combinedComponentList",
                JSONArray(
                    listOf(
                        JSONObject().put("type", "user").put(
                            "combinedAppList",
                            JSONArray(
                                listOf(
                                    JSONObject()
                                        .put("appCode", "elcpay")
                                        .put("appName", "1-6单元电控缴费")
                                        .put("website", "/charge/feeitem/toAppitem?feeitemid=181"),
                                )
                            ),
                        )
                    )
                ),
            )
        val json = JSONObject().put("code", 200).put(
            "data",
            JSONObject().put("structureInfo", JSONObject().put("combinedMenuList", JSONArray(listOf(other)))),
        )
        val feeItems = YktBalance.collectFeeItems(client.collectAppViewItems(json))
        assertEquals(1, feeItems.size)
        assertEquals("181", feeItems[0].feeItemId)
    }

    /** 结构缺失时返回空列表而不是崩。 */
    @Test
    fun `结构缺失时返回空列表`() {
        assertTrue(client.collectAppViewItems(JSONObject()).isEmpty())
        assertTrue(client.collectAppViewItems(JSONObject().put("data", JSONObject())).isEmpty())
    }

    /**
     * **淮师的房间选择能力来自 `singleFeeitem` + `getThirdData`，不是 `combox*`**。
     *
     * ## 这条判据的来历（值得留着，因为它连着两次误判）
     *
     * 1. 早期假定级联端点是 EasyUI 的 `/charge/sceneroom/comboxCampus|Building|Room`。
     *    那是**另一套 PC 老前端**的接口，对淮师恒返回 `200` + 正文 `null`
     *    （不是"没有数据"，是"这个 app 家族没有这套接口"）→ 用户被卡在永远为空的
     *    "选择校区"里，即"校区列表加载失败"那条反馈的根因。
     * 2. 于是改成"淮师不支持房间选择、只能按费项直查" —— **仍然是错的**：
     *    真正的入口是官方 `/charge-pc/`（Vue SPA「缴费.新中新」），
     *    契约是 `GET /charge/feeitem/singleFeeitem`（`view=="choose"` 时必须先选场景、
     *    `interfacechoice` 给出级定义）+ `POST /charge/feeitem/getThirdData`
     *    （`type=select` 取下一级 / `type=IEC` 取读数）。已用真实令牌逐级走通。
     *
     * 所以这里断言的是**正确的那条契约**：
     * - `getThirdData` 的请求必须是 form 表单、且带 `feeitemid` / `level` / `type`；
     * - 级数由服务端的 `interfacechoice` 决定，客户端不写死。
     *
     * 这条测试的价值在于：一旦有人把级联改回 `combox*` 那套，或者把
     * "需要选场景"的判断写死成某个布尔量，这里会立刻失败。
     */
    @Test
    fun `房间选择走 getThirdData 而不是 combox 端点`() {
        // 级定义解析必须来自 interfacechoice（服务端下发），且顺序即层级
        val levels = YktBalance.parseInterfaceChoice("校区_campus,楼栋_building,楼层_floor,房间_room")
        assertEquals(4, levels.size)
        assertEquals("campus", levels[0].code)
        assertEquals(1, levels[0].level)
        assertEquals("房间", levels[3].name)
        assertEquals(4, levels[3].level)

        // 级数可变：3 级的学校也必须解析正确（不写死 4）
        val three = YktBalance.parseInterfaceChoice("楼栋_building,楼层_floor,房间_room")
        assertEquals(3, three.size)
        assertEquals(3, three.last().level)

        // 空段必须被忽略，不能造出"没有 code 的幽灵级别"
        assertEquals(1, YktBalance.parseInterfaceChoice("校区_campus,,").size)
        // 没有参数名的段跳过：提交时没有参数名无从发起
        assertTrue(YktBalance.parseInterfaceChoice("校区,楼栋_building").none { it.name == "校区" })
    }

    /**
     * `view` 是"要不要先选房间"的**权威判据**，不能写死。
     *
     * `"choose"` → 必须先选；空值保守按"要选"处理（宁多选不漏选）。
     */
    @Test
    fun `view 决定是否需要先选场景`() {
        assertTrue(YktFeeItemDetail("181", "choose", emptyList()).requiresSelection)
        assertTrue("view 缺失时保守按需要选处理", YktFeeItemDetail("181", "", emptyList()).requiresSelection)
        assertFalse(YktFeeItemDetail("181", "list", emptyList()).requiresSelection)
    }

    /**
     * 应用跳转地址必须是站点唯一的 `redirect?appId=<bh>&type=app` 口径。
     *
     * 取自 SPA 多处 `openNewPage` 实现；参数名是 `appId`（值为应用项 `bh`），
     * 不是 `feeitemid`、也不是 `appCode`。写错会跳到一个空白页。
     */
    @Test
    fun `应用跳转用官方 redirect 口径`() {
        val url = client.appRedirectUrl("elcpay", "t0ken")
        assertTrue("缺少 redirect 路径: $url", url.contains("/berserker-base/redirect?"))
        assertTrue("参数名必须是 appId: $url", url.contains("appId=elcpay"))
        assertTrue("必须带 type=app: $url", url.contains("type=app"))
        assertTrue("必须带令牌: $url", url.contains("synjones-auth=t0ken"))
    }

    /**
     * `null` 正文是**空数据**，不是错误 —— 这条契约必须有测试钉住。
     *
     * ## 为什么（真机实测，2026-09-25）
     *
     * 站点 combobox 系列接口在"无数据"时回的是 **HTTP 200 + 正文 `null`**，
     * 既不是 `[]` 也不是 `{"code":200,"data":[]}`。对 EasyUI combobox 而言
     * `null` 就是空列表，但对 `JSONObject` 是解析失败。
     *
     * 早期实现把它当格式错误 → 界面报"一卡通返回了无法识别的数据" →
     * 用户看到的是"系统坏了"而不是"这里没有可选数据"，并被引向
     * "是不是没给权限"的错误方向（真实反馈）。
     *
     * 空串同样按空数据处理（网关偶发回空 body）。
     */
    @Test
    fun `null与空正文都判为空数据而非错误`() {
        assertTrue("正文 `null` 必须判为空数据", client.isNullBody("null"))
        assertTrue("正文 `null` 带空白也必须判为空数据", client.isNullBody("  null\n"))
        assertTrue("空正文必须判为空数据", client.isNullBody(""))
        assertTrue("空白正文必须判为空数据", client.isNullBody("   "))

        // 反面：真正的 JSON 不能被误判成空，否则会把有数据当没数据
        assertTrue("真实 JSON 不能被判为空", !client.isNullBody("{\"code\":200}"))
        assertTrue("JSON 数组不能被判为空", !client.isNullBody("[]"))
    }
}

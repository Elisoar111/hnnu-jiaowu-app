package com.hnnujw.course.ykt

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 淮南师范学院**一卡通**（yktapp.hnnu.edu.cn，新中新 Synjones / ZTrust）的只读客户端。
 *
 * ## 范围（用户明确收敛）
 *
 * **只做两件事：卡余额查看 + 宿舍电费查看。**
 * 充值与支付**不在本客户端内**，原因是硬性的（已对全站 95 个 chunk 全量核对）：
 * 全站只有 8 个写接口，**没有任何 recharge / topup / order / pay 端点**；
 * 充值本身是托管在另一台服务器上的远端 H5 子应用（`card-recharge`），
 * 本 SPA 只负责 `window.location.href = .../berserker-base/redirect?appId=` 跳过去。
 * 因此「交电费」的正确做法是 [rechargeUrl] 交给 WebView，而不是在这里复刻支付链路。
 *
 * ## 认证
 *
 * 一卡通使用独立的 `synjones-auth` 令牌，**与教务的 Cookie 会话、二课的 Bearer
 * 都不通用**，需单独登录（入口是统一身份认证）。令牌通过请求头下发，
 * 本客户端按 H5 的做法带在 [AUTH_HEADER] 上。
 *
 * ## 电费查询的真实口径（2026-09-25 用官方 charge-pc 页面**最终确证**）
 *
 * ⚠️ 这里前后错过两次，教训值得保留：
 *
 * 1. 最初按 EasyUI 老页面（`/charge/sceneroom/comboxCampus` 等）实现级联 —— 那是
 *    **另一套 PC 老前端**的接口，与 App 走的这套无关，真机上恒返回 `200 + null`。
 * 2. 于是误判"级联端点不存在" —— **错在搜错了 app 家族**：只搜了 `/plat/` SPA 的
 *    98 个 chunk，而级联属于官方 `/charge-pc/`（Vue SPA「缴费.新中新」）那套。
 *
 * **真正的口径**（`/charge-pc/js/app.cb5250f3.js` 定义，已用真机令牌逐级实测走通）：
 *
 * - `GET /charge/feeitem/singleFeeitem?feeitemid=<id>` → 单个费项详情。
 *   关键字段：`view`（`"choose"` 表示**必须先选房间**）、
 *   `interfacechoice`（形如 `校区_campus,楼栋_building,楼层_floor,房间_room`，
 *   即级联的**级定义**）、`impl_interface`（实现类）。
 * - `POST /charge/feeitem/getThirdData`（`application/x-www-form-urlencoded`）
 *   → **既做级联取数、也做最终读数**，靠 `type` 区分：
 *   - 首次 `{feeitemid, type:"select", level:0}` → `map.total` 是级定义，
 *     `map.data` 是**第一级**候选。
 *   - 选完第 N 级后 `{feeitemid, level:N, <code1>:<val1>, …, type: <N==级数?"IEC":"select">}`
 *     → `type=="select"` 时 `map.data` 是**下一级**候选；
 *     `type=="IEC"` 时 `map.showData.信息` 是**读数文本**、`map.data` 是位置上下文。
 *   - 各级提交值形态固定为 **`"<id>&<name>"`**（`1&本校区`、`011&01号学生公寓A区`），
 *     **必须原样回传**（见 [SceneOption.value]）。
 *
 * 淮师实测：费项 181「1-6单元电费」是 4 级（校区/楼栋/楼层/房间），
 * 末级读数形如 `用户名称1A-101,剩余电量为55.28元`。**级数由服务端决定，UI 不写死。**
 *
 * ⚠️ 一条判据：**HTTP 200 + 正文 `null` 是"空数据"，不是"错误"**
 *（见 [NULL_BODY_MESSAGE]）。把它当错误会报出"返回了无法识别的数据"，误导用户。
 *
 * ## 认证
 *
 * 一卡通使用独立的 `synjones-auth` 令牌，**与教务的 Cookie 会话、二课的 Bearer
 * 都不通用**，需单独登录（入口是统一身份认证）。业务接口认**请求头**
 *（实测：假令牌走 header → `401 请求未授权`；走 Cookie/无认证 → `401 缺失令牌`），
 * 本客户端按 H5 的做法带在 [AUTH_HEADER] 上。`/charge/` 与 `/charge-pc/` 的部分
 * 入口也接受 **query 令牌**（`?synjones-auth=`），两者都可用。
 *
 * ## 已知契约要点
 *
 * - `queryCard` 无入参，取 `data.card[0]`；金额单位是**分**。
 * - `getThirdData` 返回的读数**是一段带中文标签的文本**，必须正则解析
 *   （见 [YktBalance.parseElectricityBalance]），没有结构化数字字段；
 *   且**两个费项的文本格式不同**（181 带"元"、201 是度数），单位要跟着文本走。
 * - 余额展示口径受 `$ecardConfig.type` 控制（`"1"` 不含卡余额、`"2"` 不含电费账户），
 *   照官方算法走，别硬编码。
 * - 应用项跳转统一走 `/berserker-base/redirect?appId=<bh>&type=app&synjones-auth=<token>`
 *   （SPA 里全部应用入口共用的口径，见 [appRedirectUrl]）。
 */
class YktClient(
    baseUrl: String = BASE_URL,
    private val client: OkHttpClient = defaultClient(),
) {
    private val base = baseUrl.trim().trimEnd('/')

    // ── 查询 ──────────────────────────────────────────────────────────────

    /**
     * 卡账户信息：`GET /berserker-app/ykt/tsm/queryCard`。
     *
     * 无入参。响应 `{code:200, data:{card:[{account, cardNo, db_balance,
     * unsettle_amount, elec_accamt, status, expireDate, ...}]}}`。
     *
     * @throws YktException 未持卡（`card` 为空）时抛出可展示文案
     */
    suspend fun queryCard(token: String): YktCard {
        val json = request(base + "/berserker-app/ykt/tsm/queryCard", token)
        val card = json.optJSONObject("data")
            ?.optJSONArray("card")
            ?.optJSONObject(0)
            ?: throw YktException("没有查询到校园卡信息，请确认已领卡")
        return parseCard(card)
    }

    /**
     * 电费费项列表：从 `GET /berserker-app/appScheme/info?type=user&serviceType=h5` 取。
     *
     * **这是"学校有没有电费功能"的权威判据之一**（官方 `App.getMenu()` 用的就是它）。
     * 淮师实测：首页 6 个组件里，`combinedAppList` 分别有 2/3/9/0/0/0 项，
     * **电费两个入口在组件 #3（9 项那组）**，与"天气/搜索"不同组件。
     *
     * ⚠️ 见 [collectAppViewItems] 的说明：**不能只取第一个组件**。
     *
     * @return 费项列表；学校未配置时为空列表
     */
    suspend fun feeItems(token: String): List<YktFeeItem> {
        val json = request(
            base + "/berserker-app/appScheme/info?type=user&serviceType=h5",
            token,
        )
        return YktBalance.collectFeeItems(collectAppViewItems(json))
    }

    /**
     * 一段时间内的消费/充值**汇总**：
     * `GET /berserker-search/statistics/turnover/count?timeFrom=&timeTo=`。
     *
     * ## 这是 SPA 里唯一的消费统计接口（全站 95 chunk 核对过）
     *
     * 返回 `{"code":200,"data":{"income":0.0,"expenses":2324.0}}`，**单位是分**。
     * 真机实测（2026-09-25，账号 19704）：9 月区间 → `expenses:2324.0`（¥23.24）。
     *
     * 逐笔明细不在此接口，也不在 SPA 里（见 [YktTurnover] 的说明）——
     * 明细请用 [billUrl] 打开官方页面。
     *
     * @param from 起始日期 `yyyy-MM-dd`（含）
     * @param to 结束日期 `yyyy-MM-dd`（含）
     */
    suspend fun turnoverSummary(token: String, from: String, to: String): YktTurnover {
        val query = "timeFrom=" + enc(from) + "&timeTo=" + enc(to)
        val json = request(base + "/berserker-search/statistics/turnover/count?$query", token)
        val data = json.optJSONObject("data") ?: return YktTurnover(0.0, 0.0, "")
        return YktTurnover(
            income = YktBalance.fenToYuan(data.optLong("income", 0L)),
            // 字段名是复数 `expenses`（服务端契约如此，别写成单数）
            expense = YktBalance.fenToYuan(data.optLong("expenses", 0L)),
            raw = data.toString(),
        )
    }

    /**
     * 逐笔消费/充值流水：`GET /berserker-search/search/personal/turnover`。
     *
     * ## 这是**第三套前端**（`/campus-card-pc/`）的接口
     *
     * 曾经断言"逐笔明细无法在本客户端复刻" —— **是错的**，原因同电费那次：
     * 只翻了 `/plat/` SPA 的 98 个 chunk，没发现官方还有
     * `/campus-card-pc/`（「校园一卡通.新中新」v1.07.1.3）这套独立 Vue 前端，
     * 而账单页 `/billing`、`/billList`、`/billDetails` 全在它里面。
     * 真机实测（2026-09-25，账号 19704）：一次返回 `total:1002`，可用。
     *
     * 参数（均来自官方 `getBillList` 调用点）：
     * - `size` / `current` 分页（**必带**，服务端默认页很小）
     * - `type`：`1`=充值、`2`=消费、**不传=全部**
     * - `account`：卡号（可选；不传则按当前登录账号）
     * - `orderId`：传它则只返回该笔（详情模式）
     * - `info`：关键词搜索（配合 `highlightFieldsClass`）
     * - `typeId`：消费分类（字典来自 `/berserker-search/search/turnoverType`）
     * - `sortFields` / `sortType`：排序（如 `tranamt` + `desc`）
     *
     * ## ⚠️ 该接口**没有日期范围参数**（2026-09-25 核过官方 bundle）
     *
     * 官方 `/campus-card-pc/` 的账单页只有**一个**筛选器：
     * `filterData = [{ name: "typeId", title: "分类", list: [...] }]`
     * （`list` 由 `getTurnoverType` 填充）。页面**按月切换的月份选择器**
     * （`form.dateStr` / `form.dateType`）只喂给 `getBillDate`
     * （`/statistics/turnover/sum/user`，画图用）与 `getBillCount`
     * （`/statistics/turnover/count`，区间汇总用），**不进 `getBillList`**。
     * 所以官方页面本身也是"拉全量流水、前端按需展示"，月度口径只体现在汇总。
     *
     * ⇒ 本 App 的 UI 必须遵循同一口径：**汇总按月、明细是全量历史**，
     * 不能把全量明细挂在月份标题下（那会让用户以为"这个月只花了这些"）。
     *
     * @param type 见上；默认 `null` 表示不传（全部）
     * @param keyword 关键词，非空时按 `info` 搜索
     * @param typeId 消费分类 ID，非空时按分类过滤
     */
    suspend fun billRecords(
        token: String,
        page: Int = 1,
        size: Int = DEFAULT_BILL_PAGE_SIZE,
        type: Int? = null,
        account: String = "",
        keyword: String = "",
        orderId: String = "",
        typeId: String = "",
    ): YktBillPage {
        val params = LinkedHashMap<String, String>()
        params["size"] = size.toString()
        params["current"] = page.coerceAtLeast(1).toString()
        if (type != null) params["type"] = type.toString()
        if (account.isNotBlank()) params["account"] = account
        if (keyword.isNotBlank()) {
            params["info"] = keyword
            params["highlightFieldsClass"] = "text-primary"
        }
        if (typeId.isNotBlank()) params["typeId"] = typeId
        if (orderId.isNotBlank()) params["orderId"] = orderId
        val query = params.entries.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }
        val json = request(base + "/berserker-search/search/personal/turnover?$query", token)
        return YktBalance.parseBillPage(json)
    }

    /** 取单笔详情（官方 `billDetails` 就是按 `orderId` 反查）。 */
    suspend fun billRecord(token: String, orderId: String): YktBillRecord? {
        if (orderId.isBlank()) return null
        return billRecords(token, page = 1, size = 1, orderId = orderId).records.firstOrNull()
    }

    // ── 挂失 / 解挂（写操作，不可逆）──────────────────────────────────────

    /**
     * **挂失**校园卡：`POST /berserker-app/ykt/tsm/lostCard`。
     *
     * ## ⚠️ 这是本模块唯一的写操作，且不可逆
     *
     * 挂失后卡立即冻结、无法消费。调用方**必须先做二次确认**
     * （见 `YktLostConfirm`），本方法不做任何确认，拿参数就发。
     *
     * ## 密码策略：**一律带上**
     *
     * 官方是否要求密码由服务端配置 `frontConfig.lockFlag` 决定
     * （`"1"` 才要密码）。但 `frontConfig` 是**服务端注入到 HTML 页面里**的，
     * 不在任何 JSON 接口中——客户端**无法在运行期可靠读取**它。
     *
     * 因此这里采取保守策略：**始终要求并提交密码**。
     * - 若学校配了要密码：满足之。
     * - 若学校没配：服务端忽略 `pwd` 字段，不影响结果。
     *
     * 反过来（需要密码却不带）会被服务端拒绝，用户会以为"挂失失败"——
     * 那是最糟的失败模式（用户以为卡挂了、其实没挂）。所以宁多勿少。
     *
     * ## 密码形态（照抄官方）
     *
     * `pwd = "1$1$" + 明文密码 + "$1$" + 键盘会话UUID`，同时 `pwdType = 1`。
     * 官方页面的 `keyboardUuid` 由安全键盘组件生成；本实现没有该组件，
     * 传空串——**实测口径见 [YktLostResult] 的说明**，若服务端强校验该 UUID
     * 会在 `retcode` 上体现，用户会看到真实失败原因而不是假成功。
     *
     * ## 密码绝不落盘
     *
     * 明文密码只在本方法的**调用栈**里存在，用完即弃；
     * 本类不写任何存储、不记日志。这是本项目"不保存一卡通密码"红线的延伸。
     *
     * @param account 卡账号（`queryCard` 返回的 `account`）
     * @param password 明文密码（一次性）
     */
    suspend fun lostCard(token: String, account: String, password: String): YktLostResult {
        if (account.isBlank()) return YktLostResult(false, "", "缺少卡账号，请刷新后重试")
        return postCardOperation("/berserker-app/ykt/tsm/lostCard", token, account, password)
    }

    /**
     * **解挂**（取消挂失）：`POST /berserker-app/ykt/tsm/unlostCard`。
     *
     * 入参与密码策略同 [lostCard]（官方由 `frontConfig.unlockFlag` 控制，
     * 这里同样一律带上密码）。
     */
    suspend fun unlostCard(token: String, account: String, password: String): YktLostResult {
        if (account.isBlank()) return YktLostResult(false, "", "缺少卡账号，请刷新后重试")
        return postCardOperation("/berserker-app/ykt/tsm/unlostCard", token, account, password)
    }

    /**
     * 挂失/解挂的公共 POST。**把密码按官方口径编码后一次性发出**。
     *
     * 成功判定与官方一致：`retcode == "0"`，或（仅挂失）`"60007"`。
     * 这里对两个接口统一接受 `60007`——它表示"卡已在目标状态"，
     * 对用户而言效果与成功相同，官方挂失路径同样当成功处理。
     */
    private suspend fun postCardOperation(
        path: String,
        token: String,
        account: String,
        password: String,
    ): YktLostResult {
        val form = LinkedHashMap<String, String>()
        form["account"] = account
        if (password.isNotEmpty()) {
            // 官方拼法："1$1$" + 明文 + "$1$" + keyboardUuid（无安全键盘组件时为空）
            form["pwd"] = "1\$1\$$password\$1\$"
            form["pwdType"] = "1"
        }
        return try {
            val json = requestForm(path, form, token)
            YktBalance.parseLostResult(json, isLostOperation = path.contains("lostCard"))
        } catch (e: YktException) {
            // 网络/会话类失败：原样把原因交回 UI（此时并未挂失成功）
            YktLostResult(success = false, retcode = "", message = e.message.orEmpty())
        }
    }

    /**
     * 官方**账单页**地址（第三套前端 `/campus-card-pc/`）。
     *
     * 与 [billUrl]（老 `/campus-card/` jQuery 子应用）的区别：这个是
     * 「校园一卡通.新中新」v1.07 PC 版，**带 `/billDetails` 详情页**，
     * 且实测可用。默认用它。
     */
    fun billPcUrl(token: String, appId: String = DEFAULT_BILL_APP_ID): String {
        val auth = java.net.URLEncoder.encode(token, "UTF-8")
        val app = enc(appId)
        return "$base/campus-card-pc/billing?name=billing&visitor=0&appId=$app" +
            "&synAccessSource=pc&source=pc&type=app&synjones-auth=$auth"
    }

    /**
     * 逐笔消费明细的**官方页面**地址（旧口径）。
     *
     * 形如 `{base}/campus-card/?name=billList&synjones-auth=<token>`（`bill` 应用，
     * 2026-02 更新过的那个入口）。明细列表在服务端 jQuery 子应用里，
     * 不带令牌直接访问是 401，所以只能由官方页面自己带令牌渲染——
     * 这与"交电费交给官方页面"是同一合规策略：本 App 不复制、不抓取支付/账单数据。
     */
    fun billUrl(token: String): String {
        val auth = java.net.URLEncoder.encode(token, "UTF-8")
        return "$base/campus-card/?name=billList&synjones-auth=$auth"
    }

    /**
     * 从 `appScheme/info` 响应里挖出**全部**应用项（各菜单 × 各组件）。
     *
     * ## 为什么必须全扫（2026-09-25 真机修正，此前有 bug）
     *
     * 早期实现只取**第一个带 `combinedAppList` 的组件**，假设首页只有一个应用栏。
     * 真机 `appScheme/info` 实测：
     * ```
     * 首页.combinedComponentList = [
     *   #1 天气/搜索(2项)   #2 扫一扫/付款/认证码(3项)   #3 …含 elcpay/elec(9项)
     *   #4 #5 #6 各 0 项
     * ]
     * ```
     * 首页的组件 `componentKey`/`code` 全是 `null`，`type` 恒为 `user`，
     * 所以"按 key 含 appView 匹配"的分支**永远不命中**，只能落到"第一个有
     * combinedAppList 的组件"——于是拿到的是**天气/搜索**，电费项被判空，
     * 界面显示"学校未开通电费查询"并**隐藏了校区/楼栋/楼层/房间选择**。
     *
     * 现在改为**遍历所有菜单的所有组件**，把每组的 `combinedAppList` 收集起来；
     * 去重与费项判定交给 [YktBalance.collectFeeItems]（按 feeitemid 去重）。
     * 不写死索引、不依赖 `componentKey`，换一所学校/换一套首页布局也成立。
     *
     * 可见性为 `internal` 是为了让单测能直接钉住"**必须扫所有组件**"这条语义
     *（此前是 `private`，无任何测试保护 —— 正是它悄悄退化成"只取第一个组件"
     * 才导致用户"看不到电费"）。
     */
    internal fun collectAppViewItems(json: JSONObject): List<Map<String, Any?>> {
        val structure = json.optJSONObject("data")?.optJSONObject("structureInfo")
            ?: json.optJSONObject("structureInfo")
            ?: return emptyList()

        // 优先「首页」菜单；找不到就退回所有菜单（宁多勿漏，费项判定会兜底去重）
        val menus = structure.optJSONArray("combinedMenuList") ?: return emptyList()
        val allMenus = (0 until menus.length()).mapNotNull { menus.optJSONObject(it) }
        if (allMenus.isEmpty()) return emptyList()
        val targets = allMenus.filter { it.optString("name").contains("首页") }
            .ifEmpty { allMenus }

        val collected = mutableListOf<Map<String, Any?>>()
        for (menu in targets) {
            val components = menu.optJSONArray("combinedComponentList") ?: continue
            for (i in 0 until components.length()) {
                val component = components.optJSONObject(i) ?: continue
                val apps = component.optJSONArray("combinedAppList") ?: continue
                for (j in 0 until apps.length()) {
                    val app = apps.optJSONObject(j) ?: continue
                    collected += app.keys().asSequence().associateWith { key -> app.opt(key) as Any? }
                }
            }
        }
        return collected
    }

    /**
     * 单个费项详情：`GET /charge/feeitem/singleFeeitem?feeitemid=`。
     *
     * ## 这是"这个费项要不要选房间"的权威判据
     *
     * 关键字段（官方 `charge-pc` 用它决定走不走级联）：
     * - `view`：`"choose"` 表示**必须先选场景（房间）**才能查；其它值可直接出数。
     * - `interfacechoice`：级联的**级定义**，形如
     *   `校区_campus,楼栋_building,楼层_floor,房间_room`
     *   （`<中文名>_<参数名>`，逗号分隔）。**级数不固定**。
     * - `impl_interface` / `implinterfaceStatus`：服务端实现类（排查用）。
     * - `bindinfo` / `bindStatus`：是否要求先绑定房间。
     *
     * 实测（真机 2026-09-25，费项 181）：`view="choose"`，
     * `interfacechoice="校区_campus,楼栋_building,楼层_floor,房间_room"`。
     *
     * @return 解析出的 [YktFeeItemDetail]；`view` 为空时按"需选择"处理（保守）
     */
    suspend fun feeItemDetail(token: String, feeItemId: String): YktFeeItemDetail {
        if (feeItemId.isBlank()) throw YktException("学校未配置电费查询项目")
        val url = base + "/charge/feeitem/singleFeeitem?feeitemid=" + enc(feeItemId)
        val json = request(url, token)
        val item = json.optJSONObject("feeitem")
        return YktFeeItemDetail(
            feeItemId = feeItemId,
            view = json.optString("view").trim(),
            levels = YktBalance.parseInterfaceChoice(item?.optString("interfacechoice").orEmpty()),
            implInterface = item?.optString("impl_interface").orEmpty().trim(),
            bindInfo = item?.optInt("bindinfo", 0) ?: 0,
        )
    }

    /**
     * 场景级联取数 / 最终读数：`POST /charge/feeitem/getThirdData`。
     *
     * ## 一个接口干两件事（官方口径，实测走通）
     *
     * - 取**下一级候选**：`type = "select"`，`level = 已选级数`；
     * - 取**最终读数**：`type = "IEC"`，`level = 总级数`。
     *
     * 首次调用传 `level = 0`（此时无任何场景参数），响应里 `map.total` 给出级定义、
     * `map.data` 给出第一级候选。**级数由服务端返回决定**，调用方不要假设 4 级。
     *
     * ## 参数形态（**最易错**）
     *
     * 每个已选级别的参数名是它的 `code`（`campus`/`building`/`floor`/`room`），
     * 值是 `SceneOption.value`（**`<id>&<name>` 原样**，不能只传 id）。
     *
     * @param picks 已选级别：`code -> SceneOption.value`（**`<id>&<name>` 原样**）
     * @param level 本次要取的层级（0 = 第一级候选；N = 选完第 N 级后取下一级/读数）
     * @param type `"select"` 取候选 / `"IEC"` 取读数。**必须由调用方显式给出**
     *   （由它掌握"总共几级"，本方法不猜）；默认 `"select"` 只用于 `level=0`。
     */
    suspend fun thirdData(
        token: String,
        feeItemId: String,
        picks: Map<String, String> = emptyMap(),
        level: Int = 0,
        type: String = TYPE_SELECT,
    ): YktThirdData {
        if (feeItemId.isBlank()) throw YktException("请先选择电费项目")
        val form = LinkedHashMap<String, String>()
        form["feeitemid"] = feeItemId
        // 已选级别：参数名 = 级别 code，值 = 完整 "<id>&<name>"
        for ((code, value) in picks) {
            if (code.isNotBlank() && value.isNotBlank()) form[code] = value
        }
        form["level"] = level.toString()
        form["type"] = type
        return parseThirdData(requestForm("/charge/feeitem/getThirdData", form, token))
    }

    /**
     * 把 `getThirdData` 的响应转成 [YktThirdData]。
     *
     * 响应形状：`{code:200, map:{ total:[级定义], data:[下一级候选], showData:{信息:…}, ... }}`。
     * 注意 `map.data` 在不同 `type` 下**语义不同**：`select` 时是候选数组，
     * `IEC` 时是**位置上下文对象**（淮师实测）。这里两种都认。
     */
    private fun parseThirdData(json: JSONObject): YktThirdData {
        val map = json.optJSONObject("map") ?: return YktThirdData()
        val levels = YktBalance.parseSceneLevels(map.optJSONArray("total"))
        val dataArray = map.optJSONArray("data")
        val options = YktBalance.parseSceneData(dataArray)
        val context = YktBalance.parseSceneContext(map.optJSONObject("data"))
        val showData = map.optJSONObject("showData")
        val reading = if (showData == null) null else {
            YktBalance.parseElectricityBalance(YktBalance.extractShowDataText(jsonObjectToMap(showData)))
        }
        return YktThirdData(
            levels = levels,
            options = options,
            reading = reading,
            context = context,
        )
    }

    /** 把 `JSONObject` 浅转 `Map<String, Any?>`（仅取值用，够 [YktBalance.extractShowDataText]）。 */
    private fun jsonObjectToMap(obj: JSONObject): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = obj.opt(key)
            result[key] = if (value === JSONObject.NULL) null else value
        }
        return result
    }

    // ── 级联下拉（场景字典：动态 N 级）────────────────────────────────────

    /**
     * 应用项的**官方跳转地址**（SPA 全部应用入口共用的口径）。
     *
     * ```js
     * `${base}/berserker-base/redirect?appId=${item.bh}&type=app&synjones-auth=${token}`
     * ```
     * 取自 `chunk-002d1406` / `chunk-1b36ef3b` 等多处 `openNewPage` / `toCitys` 实现，
     * 是站点唯一的应用打开方式。**参数名是 `appId`，值取应用项的 `bh`**（不是 `appCode`、
     * 也不是 `website` 里的 `feeitemid`）。
     *
     * 用它而不是 App 自绘缴费界面，与"充值/缴费交官方页面"的合规口径一致。
     */
    fun appRedirectUrl(appBh: String, token: String): String {
        val auth = java.net.URLEncoder.encode(token, "UTF-8")
        return "$base/berserker-base/redirect?appId=${enc(appBh)}&type=app&synjones-auth=$auth"
    }

    /**
     * 官方电费缴费页（`/charge-pc/pays/<feeitemid>`），**令牌走 query**。
     *
     * 这是用户实际在用的那个「缴费.新中新」页面（Vue SPA），也是本 App 里
     * 「去官方页面缴费」按钮的正确去处 —— 比 [appRedirectUrl] 更直接：
     * 它直接落到**指定费项**的缴费页，而 `appRedirectUrl` 还要经过应用清单跳一层。
     *
     * 读数与选房间本客户端已自实现（见 [thirdData]）；缴费/下单仍只在官方页面完成。
     */
    fun paysUrl(token: String, feeItemId: String): String {
        val auth = java.net.URLEncoder.encode(token, "UTF-8")
        return "$base/charge-pc/pays/${enc(feeItemId)}?synjones-auth=$auth"
    }

    private fun enc(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")



    /**
     * 前端配置 `GET /berserker-app/app/getAppConfig`。
     *
     * 返回里含 `ecardConfig`（余额展示口径）。取不到时**降级为默认口径**
     * （两项都计入，等价于 `type` 既非 "1" 也非 "2"）——这是官方算法在
     * 配置缺失时的自然行为。
     */
    suspend fun ecardConfig(token: String): YktEcardConfig {
        val json = runCatching { request(base + "/berserker-app/app/getAppConfig", token) }
            .getOrNull()
        val config = json?.optJSONObject("data")?.optJSONObject("ecardConfig")
            ?: json?.optJSONObject("ecardConfig")
        return YktEcardConfig(
            type = config?.optString("type").orEmpty().trim(),
            schoolNameCode = config?.optString("schoolNameCode").orEmpty().trim(),
        )
    }

    /**
     * 一次性拉齐首页所需数据：卡账户 + 配置，并按官方口径算好总额。
     *
     * **不含电费**：电费需要房间上下文（见类注释），由 UI 层用
     * [feeItems] + 级联接口单独驱动，选完房间后再调 [electricityBalanceAt]。
     * [YktOverview.electricity] 恒为 null，电费展示走独立状态。
     */
    suspend fun overview(token: String): YktOverview {
        val card = queryCard(token)
        val config = ecardConfig(token)
        return YktOverview(
            card = card,
            electricity = null,
            config = config,
            totalAmount = YktBalance.computeTotal(card, config),
        )
    }

    // ── 官方充值入口（不自建支付）──────────────────────────────────────────

    /**
     * 官方充值页地址，供 WebView 打开。
     *
     * 形如 `{base}/berserker-base/redirect?appId=<card-recharge 的 bh>&type=app&synjones-auth=<token>`。
     * 这是唯一合规的「交电费」途径：金额校验、订单创建、支付渠道全在校方页面上完成，
     * 本 App 不接触任何支付要素。
     *
     * @param appId 充值应用 ID（应用清单里 `appCode == "card-recharge"` 那项的 `bh`）
     */
    fun rechargeUrl(token: String, appId: String): String {
        val id = java.net.URLEncoder.encode(appId, "UTF-8")
        val auth = java.net.URLEncoder.encode(token, "UTF-8")
        return "$base/berserker-base/redirect?appId=$id&type=app&synjones-auth=$auth"
    }

    // ── 解析 ──────────────────────────────────────────────────────────────

    private fun parseCard(card: JSONObject): YktCard = YktCard(
        account = card.pick("account"),
        cardNo = card.pick("cardNo", "cardno"),
        // 服务端一律给分，转元
        dbBalance = YktBalance.fenToYuan(card.optLong("db_balance", 0L)),
        unsettleAmount = YktBalance.fenToYuan(card.optLong("unsettle_amount", 0L)),
        elecAccAmount = YktBalance.fenToYuan(card.optLong("elec_accamt", 0L)),
        status = card.pick("status", "cardStatus"),
        expireDate = card.pick("expireDate", "expire"),
        lostFlag = card.pick("lostflag", "lostFlag"),
    )

    // ── 传输 ──────────────────────────────────────────────────────────────

    private suspend fun request(url: String, token: String): JSONObject {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .header("X-Requested-With", "XMLHttpRequest")
            .header(AUTH_HEADER, token)
            .get()
        return send(builder)
    }

    /**
     * POST `application/x-www-form-urlencoded` 请求（`getThirdData` 用的是这种）。
     *
     * 该接口**只接受 form 表单体**：把参数放进 query string 服务端读不到
     * （官方 `charge-pc` 也是 `Content-Type: application/x-www-form-urlencoded`）。
     */
    private suspend fun requestForm(
        path: String,
        form: Map<String, String>,
        token: String,
    ): JSONObject {
        val body = form.entries
            .joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }
        val builder = Request.Builder()
            .url(base + path)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .header(AUTH_HEADER, token)
            .post(body.toRequestBody(FORM_MEDIA_TYPE))
        return send(builder)
    }

    /** 统一发送 + 状态码/异常归一。 */
    private suspend fun send(builder: Request.Builder): JSONObject {
        val code: Int
        val text: String
        try {
            execute(builder.build()).use { response ->
                code = response.code
                text = response.body?.string().orEmpty()
            }
        } catch (e: IOException) {
            throw YktException("无法连接一卡通系统，请检查网络或稍后重试")
        }

        if (code == 401 || code == 403) {
            throw YktException("一卡通登录已失效，请重新登录", sessionExpired = true)
        }
        if (code !in 200..299) {
            throw YktException("一卡通系统暂时不可用（HTTP $code），请稍后重试")
        }
        return parse(text)
    }

    /**
     * 判断响应正文是否是服务端"无数据"的 `null`。
     *
     * 抽成 `internal` 顶层可见性是为了让单测钉死这条契约：**`null` 是空数据、
     * 不是错误**。它此前没有测试保护，而"把 null 当错误"会报出
     * "返回了无法识别的数据"，把用户推向"是不是系统坏了"的错误方向
     *（真机反馈"校区列表加载失败"就是这个误导）。
     *
     * 同时覆盖空串：网关偶发回空 body，语义与 `null` 相同（都是"没内容"）。
     */
    internal fun isNullBody(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.isEmpty() || trimmed == "null"
    }

    private fun parse(text: String): JSONObject {
        val trimmed = text.trim()
        // 未登录/令牌失效时网关直接吐 HTML 登录页，不是 JSON 错误体
        if (trimmed.startsWith("<")) {
            throw YktException("一卡通登录已失效，请重新登录", sessionExpired = true)
        }
        // 服务端"无数据"时会回 **正文就是 `null`** 的 200（真机实测 combobox 系列）。
        // 这不是格式错误，调用方要能区分（见 sceneBox），所以用专门文案标记。
        if (isNullBody(text)) {
            throw YktException(NULL_BODY_MESSAGE)
        }
        val json = try {
            JSONObject(trimmed)
        } catch (e: Exception) {
            throw YktException("一卡通返回了无法识别的数据")
        }
        // 站点自有 code：200 才是成功。10001 一类是令牌失效。
        val code = json.optInt("code", -1)
        if (code != 200) {
            val message = json.pick("msg", "message")
            val expired = code == 10001 || code == 401 ||
                message.contains("失效") || message.contains("重新登")
            throw YktException(
                message.ifBlank { "一卡通请求失败（code=$code）" },
                sessionExpired = expired,
            )
        }
        return json
    }

    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) else response.close()
            }
        })
    }

    /**
     * 取文本，按候选键依次尝试，把 JSON `null` / 空串归一化成空串。
     *
     * 不用 `optString`：它对 `JSONObject.NULL` 会返回字符串 `"null"`，
     * 于是"服务端没给这个字段"会变成界面上真的显示一个 `null`。
     */
    private fun JSONObject.pick(vararg keys: String): String {
        for (key in keys) {
            val value = opt(key) ?: continue
            if (value === JSONObject.NULL) continue
            val rendered = value.toString().trim()
            if (rendered.isEmpty() || rendered == "null" || rendered == "undefined") continue
            return rendered
        }
        return ""
    }

    companion object {
        /** 一卡通站点根地址（H5 的部署前缀是 /plat/，接口不带该前缀）。 */
        const val BASE_URL: String = "https://yktapp.hnnu.edu.cn"

        /** 新中新网关的令牌请求头。 */
        const val AUTH_HEADER: String = "synjones-auth"

        /** `getThirdData` 的表单体类型（官方 charge-pc 用的就是这个）。 */
        private val FORM_MEDIA_TYPE: okhttp3.MediaType =
            "application/x-www-form-urlencoded; charset=utf-8".toMediaType()

        /** `getThirdData` 的 `type=select`：取下一级候选。 */
        const val TYPE_SELECT: String = "select"

        /** `getThirdData` 的 `type=IEC`：取最终读数。 */
        const val TYPE_IEC: String = "IEC"

        /** 账单分页默认每页条数（官方 PC 账单页用的是 10，这里放大到 20 减少翻页）。 */
        const val DEFAULT_BILL_PAGE_SIZE: Int = 20

        /**
         * 账单页默认的 `appId`。
         *
         * 取自用户实际使用的官方地址
         * `/campus-card-pc/billing?name=billing&visitor=0&appId=24&...`，
         * 是"校园卡"这个应用在清单里的编号。
         */
        const val DEFAULT_BILL_APP_ID: String = "24"

        /** 流水的 `type` 口径：充值（收入）。 */
        const val BILL_TYPE_RECHARGE: Int = 1

        /** 流水的 `type` 口径：消费（支出）。 */
        const val BILL_TYPE_CONSUME: Int = 2

        /**
         * "服务端返回了 `null` 正文"的标记文案（真机实测的合法空态）。
         *
         * 站点自己的 combobox 接口在**无数据**时回的是 **HTTP 200 + 正文 `null`**，
         * 既不是 `[]` 也不是 `{"code":200,"data":[]}`。对 EasyUI 的 combobox 而言
         * `null` 就是"空列表"，但对 `JSONObject` 是解析失败。
         *
         * 这里用一个**可识别的文案**把它与真正的格式错误分开，供 [sceneBox] 判别。
         * 之所以不新增异常子类：`YktException` 已被大量调用点捕获展示，
         * 加子类需要改动所有 `as? YktException` 判断，收益不抵风险。
         */
        const val NULL_BODY_MESSAGE: String = "__ykt_null_body__"

        /**
         * 楼层回退候选个数。
         *
         * 淮师官方页的楼层就是 1层…N层，没有独立接口。取 30 是覆盖 6 层~30 层的常见宿舍楼；
         * 选不到的楼层在真实数据里本来就不存在，多列几个不会误导（房间列表才是真判据）。
         */
        const val DEFAULT_FLOOR_COUNT: Int = 30

        /**
         * 统一身份认证（CAS）服务器。
         *
         * ⚠️ **与业务域不同域**：CAS 在 `xxmh.hnnu.edu.cn`，一卡通业务在
         * `yktapp.hnnu.edu.cn`。登录过程必然跨域。
         */
        const val CAS_HOST: String = "https://xxmh.hnnu.edu.cn"

        /**
         * 一卡通登录入口（**实测确认**，供 WebView 打开）。
         *
         * ## 为什么不是首页地址
         *
         * 曾经用 `/plat/shouyeUser?appId=1` 当入口，假设"未登录会自动跳 CAS" ——
         * **实测是错的**：该地址恒返回 `200` + SPA 空壳，**不触发认证**，
         * 用户在 WebView 里只会看到一片空白，永远登不上。
         *
         * 真正的认证跳转由**服务端网关**发起：
         * ```
         * GET /berserker-auth/cas/login/wisedu?targetUrl=<中转页>
         *   → 302 https://xxmh.hnnu.edu.cn/cas/?service=<本网关自身>
         * ```
         *
         * ## 三层参数链（逐层解码，勿简化）
         *
         * ```
         * CAS 页 : https://xxmh.hnnu.edu.cn/cas/login?service=<网关>
         * 网关   : https://yktapp.hnnu.edu.cn/berserker-auth/cas/login/wisedu?targetUrl=<中转页>
         * 中转页 : https://yktapp.hnnu.edu.cn/plat?name=loginTransit
         * ```
         *
         * `targetUrl` 必须是 `/plat?name=loginTransit`（登录**中转**页）：
         * 换票据、把 `access_token` 写入 `sessionStorage` 的动作在它那里完成
         * （见 [extractAuthFromStorageJson] 的 root cause 说明 —— **不是写 Cookie**）。
         * 把它换成首页地址会让票据无处兑换 → 登录后仍拿不到令牌。
         *
         * 入口本身只需给出**网关地址 + targetUrl**，CAS 那一层由服务端 302 自动补上。
         */
        fun loginEntryUrl(): String {
            val transit = BASE_URL + "/plat?name=loginTransit"
            val encodedTransit = java.net.URLEncoder.encode(transit, "UTF-8")
            return "$BASE_URL/berserker-auth/cas/login/wisedu?targetUrl=$encodedTransit"
        }

        /**
         * Cookie 串里抽 `synjones-auth` 的值。**纯函数**，便于 JVM 单测。
         *
         * ## 为什么不能直接把整个 Cookie 串当令牌
         *
         * 业务请求头是 `synjones-auth: <值>`，而浏览器给的是一整串
         * `k1=v1; k2=v2; synjones-auth="bearer xxx"`。必须：
         *
         * 1. 只挑出 `synjones-auth` 这一段；
         * 2. **去掉两侧的引号** —— 官方前端 `auth_new()` 就是
         *    `getCookie(...).replace(/"/g, '')`，不去引号服务端会认不出；
         * 3. 值里可能自带 `=`（令牌是 base64/JWT 形态），所以只能按**第一个** `=`
         *    切分，不能用 `split("=")` 取第二段。
         *
         * 抽不到时返回空串（**不返回原始串**）：宁可让上层提示"未登录"，
         * 也不能拿一整串无关 Cookie 去当令牌用。
         */
        fun extractAuthFromCookie(cookie: String): String {
            val raw = cookie.trim()
            if (raw.isEmpty()) return ""
            // Cookie 用 "; " 分隔，但个别环境下是 ";"，两种都认
            for (part in raw.split(';')) {
                val item = part.trim()
                if (item.isEmpty()) continue
                val separator = item.indexOf('=')
                if (separator <= 0) continue
                val name = item.substring(0, separator).trim()
                if (!name.equals(AUTH_HEADER, ignoreCase = true)) continue
                val value = item.substring(separator + 1).trim()
                return value.trim('"').trim('\'').trim()
            }
            return ""
        }

        /**
         * 读取 `synjones-auth` 时应该去问哪些 URL（按优先级）。
         *
         * 站点把该 Cookie 的 scope 写成 `/charge`，而登录完成时浏览器的
         * 落点可能在业务域的任意路径（`/plat/…`、`/berserker-auth/…` 等）。
         * 不同 WebView 实现对"按路径取 Cookie"的宽容度不一致，
         * 所以**原地址**与**站点根的 `/charge`** 两个候选都问一遍，先命中先用。
         *
         * ## ⚠️ origin 必须按 **host** 推，不能找 `/plat` 子串
         *
         * 早期版本写 `url.substringBefore("/plat")` —— 那只对旧登录入口
         * （`/plat/shouyeUser?…`）成立。**换登录入口后该子串不存在**，
         * `substringBefore` 会返回**整个 URL**，于是：
         * - `origin == trimmed` ⇒ 那里判 `origin != trimmed` 为假 ⇒ **只剩一个候选**；
         * - 永远问不到 `/charge` ⇒ 拿不到令牌 ⇒ **点"我已登录完成"毫无反应**
         *   （只弹"还没检测到登录状态"）。
         *
         * 这是"改了入口、忘记改依赖它的推导"的典型案例。现在改为
         * **从 URL 里取 scheme://host[:port]**，与路径彻底解耦，换任何入口都成立。
         *
         * 提成纯函数是为了能在 JVM 单测里锁住取值顺序 —— 顺序错了会
         * 拿到空值，而**空值不报错**，只表现为"登录成功却仍未登录"。
         */
        internal fun authCookieUrls(url: String): List<String> {
            val trimmed = url.trim()
            if (trimmed.isEmpty()) return emptyList()
            val origin = originOf(trimmed)
            return buildList {
                add(trimmed)
                if (origin.isNotBlank()) add("$origin/charge")
            }.distinct()
        }

        /**
         * 取 `scheme://host[:port]` 形式的站点根（纯函数）。
         *
         * 用 [java.net.URI] 解析而非字符串切分：host 里出现 `/plat`、
         * `?`、`#` 等情况都不会误判。解析失败时返回空串（调用方会跳过该候选）。
         */
        internal fun originOf(url: String): String {
            val value = url.trim()
            if (value.isEmpty()) return ""
            val uri = try {
                java.net.URI(value)
            } catch (e: Exception) {
                return ""
            }
            val scheme = uri.scheme ?: return ""
            val host = uri.host ?: return ""
            if (host.isBlank()) return ""
            val port = if (uri.port > 0) ":" + uri.port else ""
            return scheme + "://" + host + port
        }

        /**
         * 从 WebView 的 CookieManager 里读 `synjones-auth`。
         *
         * 依次尝试 [authCookieUrls] 给出的候选地址，取到第一个非空值。
         *
         * ## ⚠️ 实测：本站的令牌 **不在 Cookie 里**
         *
         * 真机确证（见 [STORAGE_ACCESS_KEY] 的注释）：业务首页已登录，
         * 但两个候选地址的 `CookieManager.getCookie()` 里都**没有** `synjones-auth`。
         * 该函数保留用于兜底（个别版本可能同时写 Cookie），**主路径已改为
         * 读 Web Storage**，见 [extractAuthFromStorageJson] 与
         * `YktLoginActivity` 的 `captureTokenAsync()`。
         */
        fun readAuthCookie(cookieManager: android.webkit.CookieManager, url: String): String {
            for (target in authCookieUrls(url)) {
                val token = extractAuthFromCookie(cookieManager.getCookie(target).orEmpty())
                if (token.isNotBlank()) return token
            }
            return ""
        }

        /** SPA 存 `access_token` 的 Web Storage 键名（`Login` mutation 里写死）。 */
        const val STORAGE_ACCESS_KEY: String = "access_token"

        /** `access_token` 的 camelCase 变体（不同构建可能改写；两种都认）。 */
        private const val STORAGE_ACCESS_KEY_CAMEL: String = "accessToken"

        /** SPA 存 `token_type` 的 Web Storage 键名（缺省按 `bearer` 处理）。 */
        const val STORAGE_TYPE_KEY: String = "token_type"

        /** `token_type` 的 camelCase 变体。 */
        private const val STORAGE_TYPE_KEY_CAMEL: String = "tokenType"

        /**
         * 从 Web Storage 的键值 JSON 里拼出**完整的** `synjones-auth` 请求头值。
         *
         * ## 为什么必须读 Web Storage（真机确证，别再改回只读 Cookie）
         *
         * 反混淆 `login.70990e50.js` + `ykt_app.js` 得到 SPA 的真实逻辑：
         * ```js
         * // getFlagAfter(t) / getTokenAfter(t)：拿到 oauth/token 响应后
         * this.$store.dispatch("LoginAction", { token_type:"bearer", token: t.access_token })
         *
         * // store 的 Login mutation：
         * Login(e,t){ t.noCatch||(sessionStorage.setItem("access_token",t.token),
         *                          sessionStorage.setItem("token_type",t.token_type)),
         *             e.token=t.token, e.token_type=t.token_type }
         * ```
         * ⇒ 令牌落在 **`sessionStorage`** 的 `access_token` / `token_type` 两个键，
         * **既不是 Cookie，也不是 localStorage**。所以按 Cookie 读必然读空。
         *
         * ## 拼接规则（与站点 `auth_new()` 一致）
         *
         * 站点业务头是 `synjones-auth: <token_type> <access_token>`
         * （例：`bearer eyJhbGci…`）。`token_type` 缺失时按小写 `bearer` 兜底；
         * 若 `access_token` 已经是完整形态（以 `bearer ` 开头）则原样返回，
         * 避免拼成 `bearer bearer xxx`。
         *
         * 参数是**已展开的键值对**（不是原始 JSON 串），便于 JVM 单测直接喂值。
         * 抽不到 `access_token` 时返回**空串**（绝不返回半截值）。
         */
        internal fun composeAuthFromStorageValues(accessToken: String, tokenType: String): String {
            val token = accessToken.trim().trim('"').trim('\'')
            if (token.isEmpty()) return ""
            // 已经是 "bearer xxx" / "Basic xxx" 这类完整形态，别再套一层前缀
            if (token.startsWith("bearer ", ignoreCase = true) ||
                token.startsWith("basic ", ignoreCase = true)
            ) {
                return token
            }
            val type = tokenType.trim().trim('"').trim('\'').ifBlank { "bearer" }
            return "$type $token"
        }

        /**
         * 从 `sessionStorage`/`localStorage` 的键值 JSON 里抽令牌。**纯函数**。
         *
         * 入参形如 `{"access_token":"eyJ…","token_type":"bearer","userInfo":"…"}`，
         * 由 `YktLoginActivity` 注入的 JS 采集得到。这里只做字符串解析，
         * 不依赖 `org.json`（它与 Android 耦合，纯函数才好单测）：
         * 用极简的"找键 + 取下一个字符串字面量"策略，够用且无副作用。
         *
         * 键名同时接受 snake_case（`access_token`，站点当前用法）与 camelCase
         * （`accessToken`，防不同构建改名），并做大小写不敏感匹配；
         * 抽不到返回空串。
         */
        internal fun extractAuthFromStorageJson(storageJson: String): String {
            val json = storageJson.trim()
            if (json.isEmpty()) return ""
            val token = jsonStringValue(json, skKeyVariants(STORAGE_ACCESS_KEY)) ?: return ""
            val type = jsonStringValue(json, skKeyVariants(STORAGE_TYPE_KEY)).orEmpty()
            return composeAuthFromStorageValues(token, type)
        }

        /** 一个逻辑键对应的候选写法（snake_case + camelCase）。 */
        private fun skKeyVariants(key: String): List<String> = when (key) {
            STORAGE_ACCESS_KEY -> listOf(STORAGE_ACCESS_KEY, STORAGE_ACCESS_KEY_CAMEL)
            STORAGE_TYPE_KEY -> listOf(STORAGE_TYPE_KEY, STORAGE_TYPE_KEY_CAMEL)
            else -> listOf(key)
        }

        /**
         * 从形如 `{"k":"v",…}` 的 JSON 里取某个键的字符串值。
         *
         * 只处理 `"key":"value"` 这一种形态（站点写的就是这种）。
         * 接受多个候选键名（见 [skKeyVariants]，snake_case / camelCase），
         * 任一命中即返回；都找不到返回 `null`（与"值本身是空串"区分开）。
         */
        private fun jsonStringValue(json: String, keys: List<String>): String? {
            for (key in keys) {
                val found = jsonStringValue(json, key)
                if (found != null) return found
            }
            return null
        }

        /** 单个键名的大小写不敏感查找。 */
        private fun jsonStringValue(json: String, key: String): String? {
            val quotedKey = "\"$key\""
            var from = 0
            while (true) {
                val keyIndex = json.indexOf(quotedKey, from, ignoreCase = true)
                if (keyIndex < 0) return null
                var cursor = keyIndex + quotedKey.length
                // 跳过 key 与 value 之间的 `:` 及空白
                while (cursor < json.length && (json[cursor] == ':' || json[cursor].isWhitespace())) cursor++
                if (cursor >= json.length || json[cursor] != '"') {
                    // 不是字符串值（可能是数字/对象/数组），继续往后找同名键
                    from = keyIndex + quotedKey.length
                    continue
                }
                cursor++ // 跳过开引号
                val sb = StringBuilder()
                while (cursor < json.length) {
                    val ch = json[cursor]
                    if (ch == '\\' && cursor + 1 < json.length) {
                        sb.append(json[cursor + 1])
                        cursor += 2
                        continue
                    }
                    if (ch == '"') break
                    sb.append(ch)
                    cursor++
                }
                return sb.toString()
            }
        }

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        private fun defaultClient() = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            // 与二课/学工同样的理由：不让 OkHttp 自动跟随 302
            // （它会把 POST 降级成 GET 并丢掉请求体），重定向自行处理。
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

package com.hnnujw.course.ykt

/**
 * 一卡通（yktapp.hnnu.edu.cn，新中新 Synjones / ZTrust）相关的数据模型与异常。
 *
 * 金额口径：服务端一律以**分**返回（`db_balance` / `unsettle_amount` / `elec_accamt`），
 * 本模块统一在解析处除以 100 转成元，UI 层拿到的就是可直接展示的元。
 */

/** 一卡通接口失败。[sessionExpired] 为 true 表示 token 失效，需要重新登录。 */
class YktException(
    message: String,
    val sessionExpired: Boolean = false,
) : Exception(message)

/**
 * 校园卡账户状态（来自 `GET /berserker-app/ykt/tsm/queryCard` 的 `data.card[0]`）。
 *
 * @param account 卡账号（既是展示字段，也是后续挂失/详情的入参）
 * @param cardNo 卡号
 * @param dbBalance 卡内余额（元，已入账）
 * @param unsettleAmount 未结算金额（元，即"粘单"）
 * @param elecAccAmount 电费子账户余额（元）
 * @param status 卡状态原文
 * @param expireDate 有效期原文
 * @param lostFlag 是否已挂失（服务端 `lostflag`：`1`=已挂失、`0`=正常）。
 *   **注意是数字字符串**，官方前端就是拿它跟 `1` 比较（`1===lostflag`）。
 */
data class YktCard(
    val account: String,
    val cardNo: String,
    val dbBalance: Double,
    val unsettleAmount: Double,
    val elecAccAmount: Double,
    val status: String,
    val expireDate: String,
    val lostFlag: String = "",
) {
    /**
     * 是否处于**已挂失**状态。
     *
     * 判据与官方一致：`lostflag == "1"` 才算已挂失。
     * 其它值（`"0"` / 空 / 未知）一律按**未挂失**处理——宁可让用户看到"正常"，
     * 也不要因为字段缺失就把一张正常卡显示成"已挂失"（那会吓到人）。
     */
    val isLost: Boolean get() = lostFlag.trim() == "1"
}

/**
 * 一卡通前端配置（`$ecardConfig`）。
 *
 * [type] 决定余额展示口径，**必须尊重，不可硬编码**：
 * - `"1"` → 不计入普通余额（`db_balance + unsettle_amount`）
 * - `"2"` → 不计入电费账户（`elec_accamt`）
 *
 * 官方前端就是靠它决定首页那个总额怎么算的，照抄才与官方页面金额一致。
 */
data class YktEcardConfig(
    val type: String,
    val schoolNameCode: String,
) {
    /** 是否需要把普通余额（卡内余额 + 未结算）计入总额。 */
    val includesCardBalance: Boolean get() = type != "1"

    /** 是否需要把电费账户余额计入总额。 */
    val includesElectricity: Boolean get() = type != "2"
}

/**
 * 电费数值的单位口径。
 *
 * 推断依据是**文本形式**而不是费项 ID——因为费项 ID 是学校可配的，
 * 而文本里的「元」或冒号形式是服务端渲染时带出来的，更贴近实际语义。
 */
enum class ElectricityUnit {
    /** 元（文本里出现"元"，或标签是"剩余金额"）。 */
    YUAN,

    /** 度（文本是「剩余电量:X」这种无"元"的紧凑形式）。 */
    KWH,

    /** 无法判断（既没"元"也不像电量）。提醒时应保守跳过。 */
    UNKNOWN,
}

/**
 * 电费（缴费平台费项）余额。
 *
 * 该接口返回的是**一段带中文标签的文本**而非结构化数字，[raw] 保留原文供排查，
 * [amount] 是正则抽出的数值；抽不到时为 null（界面需据此显示"—"而不是 0）。
 *
 * ## 单位口径（2026-09-25 真机实测，**极易踩坑**）
 *
 * 同一个「剩余电量」标签，在两个费项上的语义**不一样**：
 * - `feeitemid=181`（1-6单元电费）→ `信息: 用户名称1A-101,剩余电量为55.28元` → **元（金额）**
 * - `feeitemid=201`（7-11单元及东区电费）→ `信息: 房间名称: 7A-101 剩余电量:312.2` → **度（电量）**
 *
 * 因此 [unit] 必须跟着文本走：**金额型才能套"低于 X 元提醒"的阈值**，
 * 度数型套金额阈值会得出荒谬结论（"312.2 度低于 20 元"）。
 *
 * @param amount 余额数值（元或度，看 [unit]）；null = 返回文本里没有可识别的数值
 * @param label 命中的标签（"剩余电量" / "剩余金额" / "剩余水费"），未命中为空
 * @param unit 数值单位，由文本推断（见 [ElectricityUnit]）
 * @param raw 服务端返回的原文，供排查与界面兜底展示
 */
data class ElectricityBalance(
    val amount: Double?,
    val label: String,
    val unit: ElectricityUnit,
    val raw: String,
) {
    /** 是否可按"金额阈值"提醒。度数型与未知型都不行。 */
    val supportsAmountAlert: Boolean get() = unit == ElectricityUnit.YUAN
}

/**
 * 级联选择中的一项（校区 / 楼栋 / 楼层 / 房间通用）。
 *
 * 对应 `getThirdData` 返回的 `{name, value}`：`value` 是**提交给接口的原值**，
 * `name` 是展示文本。
 *
 * ## ⚠️ 真实形态是 `<id>&<name>`，不是纯 id（2026-09-25 实测）
 *
 * 官方 `charge-pc` SPA 与后端约定：每一级选中值必须**原样回传**
 * `"<id>&<name>"`（如 `campus=1&本校区`、`building=011&01号学生公寓A区`、
 * `room=011101&01A-101`）。只传 `<id>` 服务端会认为上下文不完整。
 *
 * 因此本模型**保留完整 `value`**（含 `&name`），另外把 `id` 单独切出来仅供展示/记忆；
 * 提交时必须用 [value]，**不要用 [id]**。
 */
data class SceneOption(
    /** 提交给接口的完整值（`<id>&<name>`），**这是要传参的那个**。 */
    val value: String,
    /** 展示文本（`name`）。 */
    val name: String,
) {
    /** 值与名字里的 `<id>` 段（`1&本校区` → `1`）；无 `&` 时就是本身。 */
    val id: String get() = value.substringBefore('&')

    val display: String get() = name.ifBlank { value }
}

/**
 * 「场景字典」的一级定义（`getThirdData` 响应里的 `map.total[]`）。
 *
 * **级数是动态的**：淮师电费实测是 4 级（校区/楼栋/楼层/房间），
 * 但别的费项/学校可能是 2 级或 3 级。UI **不得写死 4**，一律按本列表渲染。
 *
 * @param code 提交时用的参数名（`campus` / `building` / `floor` / `room`）
 * @param level 层级序号，从 1 开始
 * @param name 展示名（"校区" / "楼栋" …）
 */
data class YktSceneLevel(
    val code: String,
    val level: Int,
    val name: String,
)

/**
 * `getThirdData` 的一次响应结果。
 *
 * 该接口**既是级联取数、也是最终读数**，靠 `type` 区分：
 * - `type == "select"` → [options] 是**下一级**的候选；[levels] 是完整级定义
 * - `type == "IEC"` → [reading] 是最终读数（剩余电量文本），[options] 为空
 *
 * @param levels 级定义（每次都返回，用于 UI 决定要渲染几列）
 * @param options 下一级候选（`select` 时有值）
 * @param reading 最终读数（`IEC` 时有值）
 * @param context 读数附带的结构化上下文（校区/楼栋/楼层/房间名 + account）
 */
data class YktThirdData(
    val levels: List<YktSceneLevel> = emptyList(),
    val options: List<SceneOption> = emptyList(),
    val reading: ElectricityBalance? = null,
    val context: YktSceneContext? = null,
) {
    /** 级数（UI 按它渲染列数）。 */
    val levelCount: Int get() = levels.size
}

/**
 * 读数响应里 `map.data` 的结构化上下文（淮师实测字段）。
 *
 * 用于把「剩余电量 55.28 元」和「这是哪个房间」一起展示，
 * 避免用户看到数字却不知道查的是哪间。
 */
data class YktSceneContext(
    val areaName: String = "",
    val buildingName: String = "",
    val floorName: String = "",
    val roomName: String = "",
    val account: String = "",
) {
    /** 界面上回显的位置摘要。 */
    val summary: String
        get() = listOf(areaName, buildingName, floorName, roomName)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
}

/**
 * 单个费项详情（来自 `GET /charge/feeitem/singleFeeitem`）。
 *
 * ## `view` 决定界面形态
 *
 * - `"choose"` → **必须先选场景（房间）**：界面渲染 [levels] 描述的 N 级选择器，
 *   逐级调 `getThirdData` 取候选，选满后出读数。
 * - 其它值（或空）→ 视为"可直接出数"，不展示选择器。
 *
 * ## [levels] 是**动态**的
 *
 * 由 `feeitem.interfacechoice` 解析而来（`校区_campus,楼栋_building,楼层_floor,房间_room`），
 * 级数不固定。**UI 一律按它渲染，不得写死 4 级。**
 */
data class YktFeeItemDetail(
    val feeItemId: String,
    val view: String,
    val levels: List<YktSceneLevel>,
    val implInterface: String = "",
    val bindInfo: Int = 0,
) {
    /** 是否需要先选场景才能查。空 `view` 时保守地按"需要选"处理。 */
    val requiresSelection: Boolean get() = view.isBlank() || view == "choose"

    /** 是否已在服务端绑定过房间（`bindinfo != 0` 通常表示已绑定）。 */
    val bound: Boolean get() = bindInfo != 0
}



/**
 * 一段时间的**消费/充值汇总**（来自 `GET /berserker-search/statistics/turnover/count`）。
 *
 * 返回区间内的**收入 / 支出总额**；**逐笔明细**用 [YktBillRecord] +
 * [YktClient.billRecords]。两者同属 `/berserker-search/` 服务，同一令牌即可。
 *
 * ⚠️ 曾有结论说"明细在 jQuery 子应用里、本客户端无法复刻"——那是**搜错了前端**：
 * 明细接口 `/berserker-search/search/personal/turnover` 属于第三套前端
 * `/campus-card-pc/`（「校园一卡通.新中新」v1.07.1.3），
 * 已用真实令牌实测走通（2026-09-25，账号 19704 有 1002 条流水）。
 *
 * @param income 区间内收入（元）
 * @param expense 区间内支出（元）
 * @param raw 原始响应中的 `data` 片段，供排查
 */
data class YktTurnover(
    val income: Double,
    val expense: Double,
    val raw: String = "",
) {
    /** 该区间是否一笔流水都没有（收入和支出都是 0）。 */
    val isEmpty: Boolean get() = income == 0.0 && expense == 0.0

    /** 净流水（收入 − 支出）；负数表示净消费。 */
    val net: Double get() = income - expense
}

/**
 * 一笔**消费/充值流水**（来自 `GET /berserker-search/search/personal/turnover`）。
 *
 * ## 单位口径（**最易错**）
 *
 * `tranamt` / `cardBalance` / `ebagamt` 服务端一律给**分**，展示前要除 100。
 * 真机实测：`tranamt=1` 是 **0.01 元**（4G 净水一笔），不是 1 元。
 *
 * ## 收支方向的判据是 `typeFrom`，不是金额符号
 *
 * `typeFrom == "1"` → 收入（充值），`"2"` → 支出（消费）。
 * 服务端给的是**正整数金额 + 方向字段**，所以界面上"+"、"-"由 [isIncome] 决定，
 * 绝不能拿 `tranamt` 的正负去猜（它永远是正的）。
 *
 * @param orderId 订单号（服务端主键；详情页用 `orderId=` 反查单条）
 * @param tradeTime 交易时间（`jndatetimeStr`，形如 `2026-09-25 19:57:00`）
 * @param effectTime 入账时间（`effectdateStr`）——可能晚于交易时间（延迟入账）
 * @param amountCent 交易金额（**分**，原样保留，展示用 [amountYuan]）
 * @param title 摘要（`resume`，如"学生公寓4G净水-电子账户消费"）
 * @param merchant 对方/商户名（`toMerchant`，可能为空）
 * @param payName 支付方式（`payName`，如"电子账户消费"）
 * @param typeFrom `"1"` 收入 / `"2"` 支出
 * @param cardBalanceCent 该笔之后的卡余额（**分**，可为空）
 * @param location 消费地点（`locationName`，如 `4-019`，可能为空）
 * @param remark 明细备注（可能很长，列表里不展示）
 * @param icon 图标标识（`consume` 等，仅作分类用）
 */
data class YktBillRecord(
    val orderId: String = "",
    val tradeTime: String = "",
    val effectTime: String = "",
    val amountCent: Long = 0L,
    val title: String = "",
    val merchant: String = "",
    val payName: String = "",
    val typeFrom: String = "",
    val cardBalanceCent: Long? = null,
    val location: String = "",
    val remark: String = "",
    val icon: String = "",
) {
    /** 是否收入（充值）。`typeFrom == "1"` 才是收入，其余按支出。 */
    val isIncome: Boolean get() = typeFrom == "1"

    /** 金额（元），**恒为非负**；正负号由 [isIncome] 决定。 */
    val amountYuan: Double get() = amountCent / 100.0

    /** 带符号的展示串（如 `-0.01` / `+30.00`）。 */
    val signedAmount: String
        get() = (if (isIncome) "+" else "-") + YktBalance.formatYuan(amountYuan)

    /** 该笔之后的卡余额（元）；服务端未给时为 null（**不是 0**）。 */
    val cardBalanceYuan: Double? get() = cardBalanceCent?.let { it / 100.0 }

    /** 列表主标题：优先摘要，退回商户，再退回支付方式。 */
    val displayTitle: String
        get() = title.ifBlank { merchant.ifBlank { payName.ifBlank { "交易" } } }

    /** 交易日期（`jndatetimeStr` 的日期段），用于按日分组。 */
    val tradeDate: String get() = tradeTime.substringBefore(' ').trim()

    /** 交易时刻（时:分），列表右侧展示。 */
    val tradeClock: String
        get() = tradeTime.substringAfter(' ', "").take(5).trim()
}

/**
 * 一页流水（`data` 的整体结构）。
 *
 * @param records 当页记录
 * @param total 总条数（服务端给的，用于"共 N 笔"）
 * @param current 当前页（1-based）
 * @param pages 总页数
 */
data class YktBillPage(
    val records: List<YktBillRecord> = emptyList(),
    val total: Int = 0,
    val current: Int = 1,
    val pages: Int = 0,
) {
    val isEmpty: Boolean get() = records.isEmpty()

    /** 是否还有下一页。 */
    val hasMore: Boolean get() = current < pages
}

/**
 * 学校的电费费项（来自 `appScheme/info` 首页应用栏）。
 *
 * @param feeItemId 提交给 `/charge/feeitem/` 系列接口的费项 ID（真机上就是 `181` / `201`）
 * @param name 费项展示名（如"1-6单元电费"）
 * @param appCode 应用标识（`elcpay` / `elec` 等），仅用于区分"缴费"与"查询"入口
 */
data class YktFeeItem(
    val feeItemId: String,
    val name: String,
    val appCode: String,
)

/**
 * 电费查询的完整上下文（**记忆选择**用的那个）。
 *
 * 电费**不能只靠 `feeitemid` 查**——必须先选到房间（见 [YktFeeItemDetail.requiresSelection]），
 * `getThirdData` 才有上下文。这份上下文会被持久化（[YktSelectionStore]），
 * 下次打开直接复用，省掉重复选择。
 *
 * ## 为什么是"动态列表"而不是固定的 campus/building/floor/room 四字段
 *
 * 真实级联的**级数是服务端决定的**（`map.total`），淮师电费是 4 级，
 * 别的费项可能是 2 级或 3 级。若把四级写死成字段，遇到 2 级的费项就会带着
 * 两个空参数去请求。所以这里存**有序的 [picks] 列表**（第 i 项对应第 i+1 级）。
 *
 * @param feeItemId 费项（181/201）
 * @param picks 已选各级，**顺序即层级顺序**；每项含提交值（`<id>&<name>`）与展示名
 */
data class YktSceneSelection(
    val feeItemId: String = "",
    val picks: List<ScenePick> = emptyList(),
) {
    /** 是否已经选到"能查"的程度——必须选满到最后一级（房间）。 */
    val isQueryable: Boolean get() = feeItemId.isNotBlank() && picks.isNotEmpty()

    /** 提交给 `getThirdData` 的参数表：`code -> value`。 */
    val pickMap: Map<String, String> get() = picks.associate { it.code to it.value }

    /** 界面上回显的位置摘要（"本校区 · 01号学生公寓A区 · 1层 · 01A-101"）。 */
    val summary: String
        get() = picks.map { it.name }.filter { it.isNotBlank() }.joinToString(" · ")

    /** 取某一级的已选值（按 code）。 */
    fun pick(code: String): ScenePick? = picks.firstOrNull { it.code == code }

    companion object {
        val EMPTY = YktSceneSelection()
    }
}

/**
 * 级联中**一级的选择结果**。
 *
 * @param code 该级的参数名（`campus`/`building`/`floor`/`room`）
 * @param value 提交给接口的完整值（`"<id>&<name>"`），**原样回传**
 * @param name 展示文本
 * @param level 层级序号（从 1 开始），用于回显顺序
 */
data class ScenePick(
    val code: String,
    val value: String,
    val name: String,
    val level: Int = 0,
)


/**
 * 一卡通首页的一次性聚合结果：卡账户 + 电费 + 展示用总额。
 *
 * @param totalAmount 按 [YktEcardConfig] 口径算出的可用总额（元）
 */
data class YktOverview(
    val card: YktCard,
    val electricity: ElectricityBalance?,
    val config: YktEcardConfig,
    val totalAmount: Double,
)

/**
 * 挂失 / 解挂的**服务端结果**。
 *
 * ## 为什么不能只看 HTTP 200
 *
 * 官方前端（`/campus-card-pc/` 的 `cardOperation`）在 HTTP 200 时仍要再判一层
 * **业务码 `retcode`**：
 * - 挂失：`"0"` 或 `"60007"` 都算成功（`60007` 是"该卡已是挂失态"，
 *   对用户而言结果一致，官方也提示成功）；
 * - 解挂：只有 `"0"` 算成功；
 * - 其它 `retcode`：多为密码错误，官方会**重新弹出键盘**并把 `errmsg` 显示出来。
 *
 * 所以这里把业务码与文案都带回来，让 UI 能按真实结果提示，
 * **不做乐观更新**（挂失是不可逆操作，不能"先显示成功"）。
 *
 * @param success 业务上是否成功
 * @param retcode 服务端业务码原文（排查用）
 * @param message 服务端给的提示文案（成功/失败都可能给）
 */
data class YktLostResult(
    val success: Boolean,
    val retcode: String,
    val message: String,
)

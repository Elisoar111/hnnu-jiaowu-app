package com.hnnujw.course.ui.screen

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hnnujw.course.ui.system.GlassPageScaffold
import com.hnnujw.course.ui.system.GlassToaster
import com.hnnujw.course.ui.system.MarkdownText
import com.hnnujw.course.ui.system.PagePadding
import com.hnnujw.course.ui.system.SceneWheelPickerDialog
import com.hnnujw.course.ui.system.SystemCard
import com.hnnujw.course.ui.system.SystemDivider
import com.hnnujw.course.ui.system.SystemEmptyState
import com.hnnujw.course.ui.system.SystemIconButton
import com.hnnujw.course.ui.system.SystemLoadingState
import com.hnnujw.course.ui.system.SystemPrimaryButton
import com.hnnujw.course.ui.system.SystemSectionHeader
import com.hnnujw.course.ui.system.SystemStatusBadge
import com.hnnujw.course.ui.system.SystemTone
import com.hnnujw.course.ykt.ElectricityBalance
import com.hnnujw.course.ykt.ElectricityUnit
import com.hnnujw.course.ykt.SceneOption
import com.hnnujw.course.ykt.ScenePick
import com.hnnujw.course.ykt.YktAlertNotifier
import com.hnnujw.course.ykt.YktAlertSettings
import com.hnnujw.course.ykt.YktBackgroundPermission
import com.hnnujw.course.ykt.YktBalance
import com.hnnujw.course.ykt.YktBillPage
import com.hnnujw.course.ykt.YktBillRecord
import com.hnnujw.course.ykt.YktClient
import com.hnnujw.course.ykt.YktFeeItem
import com.hnnujw.course.ykt.YktFeeItemDetail
import com.hnnujw.course.ykt.YktOverview
import com.hnnujw.course.ykt.YktSceneContext
import com.hnnujw.course.ykt.YktSceneLevel
import com.hnnujw.course.ykt.YktSceneSelection
import com.hnnujw.course.ykt.YktSelectionStore
import com.hnnujw.course.ykt.YktStore
import com.hnnujw.course.ykt.YktTurnover
import kotlinx.coroutines.launch

/**
 * 一卡通页面：**卡余额 + 宿舍电费查看**。
 *
 * 范围是用户明确收敛过的：只读展示，**不做充值/支付**。
 * 交电费需要跳官方充值页（远端子应用），这里不提供入口 —— 详见
 * [com.hnnujw.course.ykt.YktClient] 的类注释说明为什么。
 *
 * ## 电费为什么要选四级
 *
 * 淮师的电费**按宿舍房间计量**，接口必须先拿到房间上下文才有数据
 *（真机实测确认：不选房间时页面只显示「请选择房间后确认信息」）。
 * 所以这里依次让用户选 费项 → 校区 → 楼栋 → 楼层 → 房间，
 * 并把选择**记忆**下来（[YktSelectionStore]），下次进页面直接复用，不用重选。
 */
@Composable
fun YktScreen(
    accountKey: String,
    onBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var overview by remember { mutableStateOf<YktOverview?>(null) }
    var error by remember { mutableStateOf("") }

    // 是否处于"未登录"状态。与普通加载失败分开：未登录要给出**可操作**的登录按钮，
    // 而不是只显示一句"请先在设置中登录"（设置里此前根本没有这个入口）。
    var needLogin by remember { mutableStateOf(false) }

    // 顶栏跟手折叠。此前本页走 GlassPageScaffold 的默认值（collapseFraction = 0f），
    // 于是内容滚动时「一卡通」大标题**始终不变**——而课表/成绩/设置/二课都会收成
    // 细玻璃条。同一个手势在不同页面表现不一致，这里对齐到同一判据（滚动 96dp 收满）。
    val scrollState = rememberScrollState()
    val headerCollapse by remember {
        derivedStateOf { (scrollState.value / 96f).coerceIn(0f, 1f) }
    }

    // ── 电费：费项 + 级联选择 + 余额 ─────────────────────────────────────
    var feeItems by remember { mutableStateOf<List<YktFeeItem>>(emptyList()) }
    var selection by remember { mutableStateOf(YktSceneSelection.EMPTY) }
    var electricity by remember { mutableStateOf<ElectricityBalance?>(null) }
    // 读数附带的"这是哪间房"上下文（来自 getThirdData 的 map.data）
    var electricityContext by remember { mutableStateOf<YktSceneContext?>(null) }
    var electricityLoading by remember { mutableStateOf(false) }
    var electricityError by remember { mutableStateOf("") }

    // 当前费项的详情（决定走不走选择器、几级）
    var feeDetail by remember { mutableStateOf<YktFeeItemDetail?>(null) }
    // 级定义（服务端返回，动态级数）
    var sceneLevels by remember { mutableStateOf<List<YktSceneLevel>>(emptyList()) }

    // 级联选择弹窗：用「第几级（1-based）」而不是固定枚举，以支持任意级数
    var pickerLevelIndex by remember { mutableStateOf<Int?>(null) }
    var pickerOptions by remember { mutableStateOf<List<SceneOption>>(emptyList()) }
    var pickerLoading by remember { mutableStateOf(false) }
    var pickerError by remember { mutableStateOf("") }

    // ── 消费记录（月度汇总 + 逐笔明细）────────────────────────────────────
    // 月份偏移：0 = 本月，-1 = 上月。汇总取区间总额，明细取逐笔流水。
    var monthOffset by remember { mutableStateOf(0) }
    var turnover by remember { mutableStateOf<YktTurnover?>(null) }
    var turnoverLoading by remember { mutableStateOf(false) }
    var turnoverError by remember { mutableStateOf("") }

    // 逐笔流水（分页累积）：bills 是**已加载的累计结果**，翻页时追加而不是替换
    var bills by remember { mutableStateOf<YktBillPage?>(null) }
    var billsLoading by remember { mutableStateOf(false) }
    var billsError by remember { mutableStateOf("") }
    // 明细默认**收起**：全量可能上千笔，展开会把下方卡片顶得很远
    var billsExpanded by remember { mutableStateOf(false) }

    // ── 挂失 / 解挂 ──────────────────────────────────────────────────────
    // 目标状态：true = 要挂失，false = 要解挂。null = 弹窗关闭。
    // 用"目标态"而不是"当前态 + 取反"，因为提交期间当前态还没变，
    // 用它算按钮文案会在中途闪一次。
    var lostTargetIsLost by remember { mutableStateOf<Boolean?>(null) }
    var lostSubmitting by remember { mutableStateOf(false) }

    /**
     * 提交挂失/解挂。
     *
     * **只在用户走完两段确认弹窗后才会被调用**——入口的唯一途径是
     * [YktLostConfirmDialog]，所以这里不再重复做"是否确认"的校验。
     *
     * 成功后重新拉一次 overview：卡片状态（[YktCard.isLost]）由服务端
     * 的 `lostflag` 决定，**不能本地乐观更新**——否则服务端没收下请求时，
     * 界面会显示"已挂失"而卡实际还能刷，这种不一致比"状态没变"危险得多。
     */
    suspend fun submitLostOperation(isLost: Boolean, password: String) {
        val token = YktStore.token(context, accountKey)
        if (token.isBlank()) {
            GlassToaster.show("请先登录一卡通")
            return
        }
        val account = overview?.card?.account.orEmpty()
        lostSubmitting = true
        try {
            val result = if (isLost) {
                YktStore.client().lostCard(token, account, password)
            } else {
                YktStore.client().unlostCard(token, account, password)
            }
            if (result.success) {
                GlassToaster.show(if (isLost) "已挂失" else "已解挂")
                // 重新拉取以拿到服务端确认后的状态（含挂失成功但返回码非 0 的情况）
                overview = runCatching { YktStore.client().overview(token) }.getOrNull() ?: overview
            } else {
                val reason = result.message.ifBlank {
                    if (result.retcode.isNotBlank()) "错误码 ${result.retcode}" else "操作未成功"
                }
                val hint = if (isLost) "挂失失败：$reason" else "解挂失败：$reason"
                GlassToaster.show(hint)
            }
        } finally {
            lostSubmitting = false
            lostTargetIsLost = null
        }
    }

    /** 拉取当前月份的消费汇总。 */
    suspend fun loadTurnover(offset: Int) {
        val token = YktStore.token(context, accountKey)
        if (token.isBlank()) {
            turnover = null
            turnoverError = ""
            return
        }
        turnoverLoading = true
        turnoverError = ""
        try {
            val (from, to) = YktBalance.monthRange(offset)
            turnover = YktStore.client().turnoverSummary(token, from, to)
        } catch (e: Throwable) {
            turnover = null
            turnoverError = YktStore.handleFailure(context, accountKey, e)
        } finally {
            turnoverLoading = false
        }
    }

    /**
     * 拉取逐笔流水。
     *
     * ## 累积语义（[append] 的用途）
     *
     * 「加载更多」是**追加**下一页，不是替换；首屏/切月/重试则是**重置**。
     * 早期若两者都替换，用户点一次「加载更多」会看到列表**缩短**回一页。
     *
     * @param append true = 追加到已有列表尾部；false = 从头重置
     */
    suspend fun loadBills(append: Boolean = false) {
        val token = YktStore.token(context, accountKey)
        if (token.isBlank()) {
            bills = null
            billsError = ""
            return
        }
        val nextPage = if (append) ((bills?.current ?: 0) + 1) else 1
        billsLoading = true
        billsError = ""
        try {
            val page = YktStore.client().billRecords(token = token, page = nextPage)
            bills = if (append && bills != null) {
                // 服务端回的 current/pages 覆盖旧值，records 用「旧 + 新」拼接
                bills!!.copy(
                    records = bills!!.records + page.records,
                    total = page.total,
                    current = page.current,
                    pages = page.pages,
                )
            } else {
                page
            }
        } catch (e: Throwable) {
            // 追加失败时**保留**已加载的记录，只把错误挂出来，避免整屏清空
            if (!append) bills = null
            billsError = YktStore.handleFailure(context, accountKey, e)
        } finally {
            billsLoading = false
        }
    }

    /**
     * 拉一轮电费读数。
     *
     * 真实口径（见 [YktClient] 类注释）：费项详情 `view=="choose"` 时必须先选满场景，
     * 再用 `getThirdData(type=IEC)` 取读数；否则不展示选择器。
     */
    suspend fun loadElectricity(current: YktSceneSelection, token: String) {
        if (current.feeItemId.isBlank()) {
            electricity = null
            electricityContext = null
            electricityError = ""
            return
        }
        val detail = feeDetail
        // 需要选场景但还没选满 → 这不是错误，只是"尚未选择"
        if (detail != null && detail.requiresSelection && !current.isQueryable) {
            electricity = null
            electricityContext = null
            electricityError = ""
            return
        }
        electricityLoading = true
        electricityError = ""
        try {
            val result = YktStore.client().thirdData(
                token = token,
                feeItemId = current.feeItemId,
                picks = current.pickMap,
                level = current.picks.size,
                type = YktClient.TYPE_IEC,
            )
            val balance = result.reading
            electricity = balance
            electricityContext = result.context
            // 界面拉到数就顺手判一次提醒：用户手动查到的余额与后台巡检应口径一致
            if (balance != null && balance.supportsAmountAlert) {
                YktAlertNotifier.evaluateAndNotify(
                    context, accountKey, balance.amount, YktAlertSettings.thresholdYuan(context),
                )
            }
            if (result.levels.isNotEmpty()) sceneLevels = result.levels
        } catch (e: Throwable) {
            electricity = null
            electricityContext = null
            electricityError = YktStore.handleFailure(context, accountKey, e)
        } finally {
            electricityLoading = false
        }
    }

    suspend fun load() {
        val token = YktStore.token(context, accountKey)
        if (token.isBlank()) {
            // 未登录：进**可操作**的登录态，而不是丢一句"请先登录"给用户自己找入口
            needLogin = true
            error = ""
            overview = null
            feeItems = emptyList()
            electricity = null
            turnover = null
            return
        }
        needLogin = false
        try {
            overview = YktStore.client().overview(token)
            error = ""
        } catch (e: Throwable) {
            // sessionExpired 时 Store 会顺手清掉失效令牌 → 下轮进登录态
            error = YktStore.handleFailure(context, accountKey, e)
            if (YktStore.token(context, accountKey).isBlank()) needLogin = true
            return
        }

        // 费项列表：失败不影响卡余额展示（电费是尽力而为）
        val client = YktStore.client()
        feeItems = runCatching { client.feeItems(token) }.getOrDefault(emptyList())

        // 恢复上次选择；费项若已失效（学校改了配置）则丢弃记忆，避免拿着死 id 去查
        val remembered = YktSelectionStore.read(context, accountKey)
        var reconciled = reconcileSelection(remembered, feeItems)
        if (reconciled.feeItemId.isBlank() && feeItems.isNotEmpty()) {
            // 默认选第一个费项（界面仍摆出徽章可切换）：否则用户进来只看到
            // "请先选择电费项目"而不知该点哪 —— 与之前"看不到电费"同类的死界面。
            reconciled = reconciled.copy(feeItemId = feeItems.first().feeItemId)
        }
        selection = reconciled

        // 费项详情：决定要不要选房间、级联几级。取不到时按"不需要选"处理（保守降级）。
        feeDetail = runCatching { client.feeItemDetail(token, reconciled.feeItemId) }.getOrNull()
        sceneLevels = feeDetail?.levels.orEmpty()
        // 记忆里的 picks 若级数与当前不符（学校改了配置），丢弃重选
        if (sceneLevels.isNotEmpty() && reconciled.picks.size > sceneLevels.size) {
            reconciled = reconciled.copy(picks = emptyList())
            selection = reconciled
        }
        YktSelectionStore.write(context, accountKey, reconciled)
        loadElectricity(reconciled, token)

        // 消费汇总：同样是尽力而为，失败不影响余额与电费展示
        loadTurnover(monthOffset)
        // 逐笔流水：与汇总同一口径（尽力而为），失败只影响明细区块
        loadBills()
    }

    LaunchedEffect(accountKey) {
        loading = true
        load()
        loading = false
    }

    // 登录页返回后自动重载：用户刚走完统一身份认证，不该再手动点一次刷新。
    // 用 ActivityResult 而不是 startActivity + onResume 轮询，边界更干净。
    // （声明位置必须在 load() 之后 —— Kotlin 局部函数不能先引用后声明。）
    val loginLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            scope.launch {
                loading = true
                load()
                loading = false
            }
        } else if (needLogin) {
            // 用户取消了登录：留在未登录态即可，不打扰
            GlassToaster.show("已取消一卡通登录")
        }
    }

    /**
     * 级联取数失败时的提示文案：**保留服务端/传输层的真实原因**。
     *
     * 旧实现一律只写死一句"校区列表加载失败"，把"登录已失效""HTTP 500"
     * "返回了无法识别的数据"这些**可操作**的信息全丢了 —— 用户看到同一句话，
     * 无从判断该重新登录、该换网络、还是该等服务器恢复。
     *
     * 令牌失效时顺手切到登录态（`handleFailure` 会清掉失效令牌），
     * 让界面直接给出可点的登录入口，而不是让用户自己找。
     *
     * 注意：声明位置必须在调用方 `openPicker` **之前** —— Kotlin 局部函数
     * 不允许先引用后声明。
     */
    fun failureText(error: Throwable, label: String): String {
        val reason = YktStore.handleFailure(context, accountKey, error)
        if (YktStore.token(context, accountKey).isBlank()) needLogin = true
        return "$label 加载失败：$reason"
    }

    /**
     * 打开**第 [levelIndex] 级（1-based）**的选项列表。
     *
     * 走真实口径：用 `getThirdData(level = levelIndex - 1, type = select)`
     * 取**下一级**候选（服务端按已选项返回 `map.data`）。
     * 已选项取 [selection] 里逐级累积的前 `levelIndex - 1` 项。
     */
    fun openPicker(levelIndex: Int) {
        pickerLevelIndex = levelIndex
        pickerOptions = emptyList()
        pickerError = ""
        val token = YktStore.token(context, accountKey)
        // 明确区分「令牌缺失」与「网络失败」。
        // 此前不区分，两者都只印"XX列表加载失败"，用户无法自行判断该重登还是该重试；
        // 令牌缺失还会连带把 needLogin 留在 false，使界面停在"已登录"假象里。
        if (token.isBlank()) {
            pickerError = "登录已失效，请先登录一卡通后重试。"
            needLogin = true
            return
        }
        val label = sceneLevels.getOrNull(levelIndex - 1)?.name ?: "选项"
        scope.launch {
            pickerLoading = true
            // 只带前 levelIndex-1 级已选值（本级及以上清空），否则服务端会按已选满返回读数
            val picks = selection.picks.take(levelIndex - 1).associate { it.code to it.value }
            pickerOptions = runCatching {
                YktStore.client().thirdData(
                    token = token,
                    feeItemId = selection.feeItemId,
                    picks = picks,
                    level = levelIndex - 1,
                    type = YktClient.TYPE_SELECT,
                )
            }.onFailure { pickerError = failureText(it, "$label 列表") }
                .getOrNull()
                ?.let { result ->
                    if (result.levels.isNotEmpty()) sceneLevels = result.levels
                    result.options
                }
                .orEmpty()
            pickerLoading = false
        }
    }

    GlassPageScaffold(
        title = "一卡通",
        subtitle = "余额、消费与宿舍电费",
        onBack = onBack,
        collapseFraction = headerCollapse,
        actions = {
            SystemIconButton(
                icon = Icons.Outlined.Refresh,
                contentDescription = "刷新",
                onClick = {
                    scope.launch {
                        refreshing = true
                        load()
                        refreshing = false
                    }
                },
            )
        }
    ) { padding ->
        when {
            loading -> SystemLoadingState(modifier = Modifier.fillMaxSize().padding(padding))

            needLogin -> LoginRequiredCard(
                modifier = Modifier.fillMaxSize().padding(padding),
                refreshing = refreshing,
                onLogin = {
                    loginLauncher.launch(
                        Intent(context, com.hnnujw.course.YktLoginActivity::class.java)
                    )
                },
                onRetry = {
                    scope.launch {
                        refreshing = true
                        load()
                        refreshing = false
                    }
                },
            )

            error.isNotBlank() && overview == null -> SystemEmptyState(
                title = "加载失败",
                message = error,
                modifier = Modifier.fillMaxSize().padding(padding),
            )

            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(
                        top = padding.calculateTopPadding(),
                        bottom = padding.calculateBottomPadding(),
                    )
                    .padding(horizontal = PagePadding, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                overview?.let { data ->
                    AccountCard(
                        data = data,
                        lostSubmitting = lostSubmitting,
                        onLostClick = { lostTargetIsLost = true },
                        onUnlostClick = { lostTargetIsLost = false },
                    )
                }

                // 消费记录紧跟「账户总额」。它和总额回答的是同一个问题（"我的钱怎么样了"），
                // 放一起才连得上；此前夹在电费后面，用户要先划过一整套校区/楼栋/房间选择器
                // 才能看到花销。电费是"另一个账户"，排在其后更合语义。
                ConsumptionCard(
                    monthOffset = monthOffset,
                    turnover = turnover,
                    loading = turnoverLoading,
                    error = turnoverError,
                    bills = bills,
                    billsLoading = billsLoading,
                    billsError = billsError,
                    billsExpanded = billsExpanded,
                    onToggleBills = { billsExpanded = !billsExpanded },
                    onShiftMonth = { delta ->
                        monthOffset += delta
                        // 切月：先发汇总与明细两个请求，明细按新月份重置
                        scope.launch {
                            loadTurnover(monthOffset)
                            loadBills()
                        }
                    },
                    onLoadMore = { scope.launch { loadBills(append = true) } },
                    onOpenDetail = {
                        val token = YktStore.token(context, accountKey)
                        if (token.isBlank()) {
                            GlassToaster.show("请先登录一卡通")
                        } else {
                            context.startActivity(
                                com.hnnujw.course.YktWebActivity.intent(
                                    context,
                                    YktStore.client().billUrl(token),
                                    title = "账单明细",
                                )
                            )
                        }
                    },
                )

                ElectricityCard(
                    feeItems = feeItems,
                    selection = selection,
                    balance = electricity,
                    context = electricityContext,
                    levels = sceneLevels,
                    detail = feeDetail,
                    loading = electricityLoading,
                    error = electricityError,
                    onPickLevel = { levelIndex -> openPicker(levelIndex) },
                    onSelectFeeItem = { item ->
                        // 换费项 = 换查询上下文：不同费项的级定义/楼栋集合不同，必须清空重选。
                        if (item.feeItemId != selection.feeItemId) {
                            val token = YktStore.token(context, accountKey)
                            scope.launch {
                                val next = YktSceneSelection(feeItemId = item.feeItemId)
                                selection = next
                                electricity = null
                                electricityContext = null
                                electricityError = ""
                                feeDetail = runCatching {
                                    YktStore.client().feeItemDetail(token, item.feeItemId)
                                }.getOrNull()
                                sceneLevels = feeDetail?.levels.orEmpty()
                                YktSelectionStore.write(context, accountKey, next)
                                // 需要选场景 → 直接弹第一级；否则即刻出数
                                if (feeDetail?.requiresSelection == true && sceneLevels.isNotEmpty()) {
                                    openPicker(1)
                                } else {
                                    loadElectricity(next, token)
                                }
                            }
                        }
                    },
                    onOpenOfficial = {
                        val token = YktStore.token(context, accountKey)
                        if (token.isBlank()) {
                            GlassToaster.show("请先登录一卡通")
                        } else {
                            // 优先直达官方电费缴费页（/charge-pc/pays/<feeitemid>）；
                            // 无费项时才退回应用清单跳转。
                            val feeItemId = selection.feeItemId
                                .ifBlank { feeItems.firstOrNull()?.feeItemId.orEmpty() }
                            val url = if (feeItemId.isNotBlank()) {
                                YktStore.client().paysUrl(token, feeItemId)
                            } else {
                                val item = feeItems.firstOrNull()
                                if (item == null || item.appCode.isBlank()) "" else {
                                    YktStore.client().appRedirectUrl(item.appCode, token)
                                }
                            }
                            if (url.isBlank()) {
                                GlassToaster.show("未找到该费项的官方入口")
                            } else {
                                context.startActivity(
                                    com.hnnujw.course.YktWebActivity.intent(
                                        context, url, title = "电费缴费",
                                    )
                                )
                            }
                        }
                    },
                )

                AlertSettingsCard(
                    balance = electricity,
                    onThresholdChanged = { threshold ->
                        electricity?.let {
                            if (it.supportsAmountAlert) {
                                YktAlertNotifier.evaluateAndNotify(
                                    context, accountKey, it.amount, threshold,
                                )
                            }
                        }
                    },
                )

                // 明确告知能力边界，避免用户以为这里能缴费
                SystemCard {
                    Text(
                        text = "关于充值",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                    HintMarkdown(
                        "本页仅提供余额与电费查询。充值、缴费需在校园卡官方渠道完成，" +
                            "**本应用不代收、不处理任何支付**。"
                    )
                }
            }
        }
    }

    // ── 级联选择弹窗（动态级数）─────────────────────────────────────────
    val levelIndex = pickerLevelIndex
    if (levelIndex != null) {
        val levelDef = sceneLevels.getOrNull(levelIndex - 1)
        SceneWheelPickerDialog(
            title = levelDef?.name ?: "选择",
            emptyHint = "该级暂无可选项，请返回上一级重新选择。",
            options = pickerOptions,
            loading = pickerLoading,
            error = pickerError,
            initialIndex = pickerOptions
                .indexOfFirst { it.value == selection.picks.getOrNull(levelIndex - 1)?.value }
                .coerceAtLeast(0),
            onConfirm = { picked ->
                pickerLevelIndex = null
                if (picked != null && levelDef != null) {
                    // 选完这一级就清空其下游：换楼栋后原来的房间已经不成立了。
                    // 不清的话会拿"新楼栋 + 旧房间"去查，服务端只会返回空。
                    val next = applyPick(levelIndex, levelDef, picked, selection)
                    selection = next
                    YktSelectionStore.write(context, accountKey, next)
                    val token = YktStore.token(context, accountKey)
                    // 还有下一级 → 自动串下一步（省掉用户再点一次）；
                    // 已是最后一级 → 直接拉读数
                    if (levelIndex < sceneLevels.size) {
                        openPicker(levelIndex + 1)
                    } else {
                        scope.launch { loadElectricity(next, token) }
                    }
                }
            },
            onDismiss = { pickerLevelIndex = null },
        )
    }

    // ── 挂失 / 解挂二次确认弹窗 ──────────────────────────────────────────
    // 挂失不可逆，入口只有用户亲手走完两段确认才会触发提交：
    // 第 1 段读后果，第 2 段要求**逐字输入**「确认挂失」/「确认解挂」+ 密码。
    // 判据在纯函数 [com.hnnujw.course.ykt.YktLostConfirm] 里，由单测钉死。
    lostTargetIsLost?.let { targetIsLost ->
        YktLostConfirmDialog(
            isLost = targetIsLost,
            cardNo = overview?.card?.cardNo.orEmpty(),
            submitting = lostSubmitting,
            onDismiss = { if (!lostSubmitting) lostTargetIsLost = null },
            onConfirm = { password ->
                scope.launch { submitLostOperation(targetIsLost, password) }
            },
        )
    }
}

/**
 * 用当前费项列表校验记忆下来的选择。
 *
 * 学校改配置（费项 id 变了）时记忆里的 `feeItemId` 会失效，
 * 拿着死 id 去查只会得到一句报错。这里直接丢弃整份记忆，
 * 当作"从没选过"，让用户重选一遍——比让用户面对一个永远查不出的界面好。
 */
internal fun reconcileSelection(
    remembered: YktSceneSelection,
    feeItems: List<YktFeeItem>,
): YktSceneSelection {
    if (remembered.feeItemId.isBlank()) return remembered
    if (feeItems.isEmpty()) return remembered
    val stillValid = feeItems.any { it.feeItemId == remembered.feeItemId }
    return if (stillValid) remembered else YktSceneSelection.EMPTY
}

/**
 * 把**第 [levelIndex] 级（1-based）**的选择写进上下文，并**清空其所有下游**。
 *
 * 这是级联选择最容易出错的地方：换了楼栋却留着旧房间号，
 * 请求会带着"新楼栋 + 旧房间"发出去，服务端返回空数据，
 * 用户看到的是"这个房间查不到电费"而不是"你需要重选房间"。
 *
 * 动态级数下"下游"= 下标 >= levelIndex 的所有已选项（第 levelIndex 级是 0-based 的
 * levelIndex-1，故保留前 levelIndex-1 项，再追加本次选择）。
 */
internal fun applyPick(
    levelIndex: Int,
    level: YktSceneLevel,
    picked: SceneOption,
    current: YktSceneSelection,
): YktSceneSelection {
    val kept = current.picks.take((levelIndex - 1).coerceAtLeast(0))
    val appended = ScenePick(
        code = level.code,
        value = picked.value,
        name = picked.display,
        level = level.level,
    )
    return current.copy(picks = kept + appended)
}

/**
 * 「未登录」态：**必须给出可点的登录入口**。
 *
 * 之前这里只显示一句"尚未登录一卡通，请先在设置中登录"，但设置里并没有
 * 对应的登录项 —— 提示指向了一个不存在的地方，用户无路可走。
 * 现在直接把登录按钮放在这里，点开就是 WebView 走学校统一身份认证。
 *
 * 同时显式说明**登录之后会得到什么**（后台电费提醒才可能生效），
 * 让用户明白这一步不是多余的。
 */
@Composable
private fun LoginRequiredCard(
    modifier: Modifier = Modifier,
    refreshing: Boolean,
    onLogin: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PagePadding, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SystemCard {
            Text(
                text = "需要登录一卡通",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "一卡通使用学校统一身份认证，与教务、第二课堂的登录状态互不通用，" +
                    "因此需要单独登录一次。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 19.sp,
            )
            Spacer(Modifier.height(10.dp))
            MarkdownText(
                text = "登录后可以：\n" +
                    "- 查看校园卡余额与未结算金额\n" +
                    "- 查看每月消费与充值汇总，并能跳官方账单看逐笔明细\n" +
                    "- 按宿舍房间查询剩余电费\n" +
                    "- 开启电费低于阈值的系统通知（后台自动巡检）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp,
            )
            Spacer(Modifier.height(14.dp))
            SystemPrimaryButton(
                text = "登录一卡通",
                onClick = onLogin,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            MarkdownText(
                text = "登录在学校统一身份认证页面完成，本应用**不接收、不保存**你的一卡通密码。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // 已登录却仍进到这一屏是罕见状态（如令牌刚被清掉但界面没刷新），
        // 给一个重新检测的出口，避免用户只能杀进程。
        TextButton(onClick = onRetry, enabled = !refreshing) {
            Text(if (refreshing) "检测中…" else "我已登录过，重新检测")
        }
    }
}

/**
 * 顶部账户卡片：**总额 + 卡面信息合并为一张**。
 *
 * ## 为什么合并（用户要求）
 *
 * 早期是两张卡（「账户总额」+「卡片信息」），同时在屏幕上：
 * 1. 卡号/账号/有效期本就是"这张卡"的属性，与总额同源（都来自 `queryCard`），
 *    拆成两卡需要用户自己把两处信息对应起来；
 * 2. 两卡都是静态信息，占两屏高度却只表达一件事，压缩了下方电费/账单项的空间。
 *
 * ## 为什么删掉「卡内余额 / 未结算」两行（用户要求）
 *
 * 这两项是**总额的组成部分**，展示总额后再逐项列加数会让人以为
 * "总额 = 卡内余额，未结算是另外一笔"。实际口径（[YktEcardConfig.type]）是
 * 是否计入总额本身就可能不同——列出来反而会被误读为"可用余额"。
 * 总额下方直接给状态徽章与卡面信息即可。
 *
 * ## 挂失 / 解挂
 *
 * 卡片底部给出挂失（或解挂）入口，**状态由服务端 `lostflag` 决定**：
 * `isLost` 时显示"解挂"，否则显示"挂失"。
 *
 * 挂失不可逆（卡立即冻结、不能在本应用内撤销），所以按钮**不做任何直接提交**，
 * 只把意图交给 [YktLostConfirmDialog] —— 两段确认（读后果 + 逐字输入指定文字 + 密码）
 * 都通过后才会真正发请求。详见该弹窗的注释。
 *
 * @param lostSubmitting 正在提交挂失/解挂时禁用按钮，避免连点产生并发请求
 * @param onLostClick 点击"挂失"（仅表达意图，不等同于已挂失）
 * @param onUnlostClick 点击"解挂"
 */
@Composable
private fun AccountCard(
    data: YktOverview,
    lostSubmitting: Boolean = false,
    onLostClick: () -> Unit = {},
    onUnlostClick: () -> Unit = {},
) {
    val isLost = data.card.isLost
    SystemCard {
        SystemSectionHeader(title = "账户总额", subtitle = "校园卡可用总额")
        Text(
            text = "¥${YktBalance.formatYuan(data.totalAmount)}",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        if (data.card.status.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            SystemStatusBadge(
                text = "状态：${data.card.status}",
                tone = if (data.card.status.contains("正常")) SystemTone.Success else SystemTone.Warning,
            )
        }
        // 挂失态单独给一枚醒目的徽章：卡号/有效期都还正常显示，
        // 只看"状态"那一行很容易漏掉"这张卡其实已经不能刷了"。
        if (isLost) {
            Spacer(Modifier.height(4.dp))
            SystemStatusBadge(text = "已挂失", tone = SystemTone.Warning)
        }
        SystemDivider()
        DetailRow("卡号", data.card.cardNo)
        DetailRow("账号", data.card.account)
        DetailRow("有效期", data.card.expireDate)
        DetailRow("电费账户", "¥${YktBalance.formatYuan(data.card.elecAccAmount)}")

        Spacer(Modifier.height(10.dp))
        SystemDivider()
        Spacer(Modifier.height(10.dp))
        MarkdownText(
            text = if (isLost) {
                "这张卡当前处于挂失状态，已无法消费。如果卡找回或已补办，可在此解挂。"
            } else {
                "挂失会立即冻结这张卡、保住余额，但**无法在本应用内自行撤销**；" +
                    "卡还在手上时请勿挂失。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 19.sp,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 挂失用 OutlinedButton 而非 PrimaryButton：这是破坏性操作，
            // 不给它"最显眼/最想点"的视觉权重。
            OutlinedButton(
                onClick = if (isLost) onUnlostClick else onLostClick,
                enabled = !lostSubmitting,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (isLost) "解挂校园卡" else "挂失校园卡")
            }
        }
    }
}

/**
 * 电费卡片：费项选择 + 动态分级选择（校区→楼栋→楼层→房间）+ 读数。
 *
 * ## 级数由服务端决定，界面不能写死四级
 *
 * `singleFeeitem` 返回的 `view` 决定要不要选：
 * - `view == "choose"` → **必须先选房间**，级数取 `interfacechoice` 的逗号段数
 *   （淮师电费是 4 级：校区/楼栋/楼层/房间），每级名称也来自服务端。
 * - `view` 为空 / 其它 → 直查费项，选完费项直接出数。
 *
 * 所以这里**不接收"是否级联"的布尔量**，而是接收服务端下发的 [levels] 列表，
 * 有几级就画几级 `SelectRow`。
 *
 * ⚠️ **两次踩坑的教训（必读）**
 * 1. 早期以为级联端点是 `/charge/sceneroom/comboxCampus` 等 EasyUI 接口，
 *    对淮师恒返回 `200`+`null` → 用户被卡在永远为空的"选择校区"里。根因是
 *    **只翻了 `/plat/` 这个前端，没发现淮师真正的电费前端是 `/charge-pc/`**。
 * 2. 修正后又一度得出"淮师不支持房间选择、只能按费项直查"的结论，仍是同一个
 *    错误：真正的契约是 `singleFeeitem` + `getThirdData`（`type=select/IEC`），
 *    已用真实令牌逐级走通并取到读数。**没走通的端点不能当作能力，走不通的也不能
 *    直接当成"不存在"——先确认自己翻的是不是同一个前端。**
 *
 * 状态机（**界面必须能区分这几种**）：
 * 1. 学校没配电费 → 「未开通」
 * 2. 还没选到房间（`view=choose` 且未选满）→ 引导用户逐级选
 * 3. 查到数但解析不出 → 「暂时无法获取」（不是 0）
 * 4. 正常 → 显示数值 + 单位
 *
 * @param context 服务端回传的房间上下文（楼栋/房间名等），用于让用户确认"查的是哪间"
 * @param levels 服务端下发的级定义（`map.total` / `interfacechoice`）
 * @param detail 费项详情，`requiresSelection` 决定要不要走选择流程
 * @param onPickLevel 点第 index 级（1 起）；调用方负责按已选前缀拉该级候选项
 */
@Composable
private fun ElectricityCard(
    feeItems: List<YktFeeItem>,
    selection: YktSceneSelection,
    context: YktSceneContext?,
    levels: List<YktSceneLevel>,
    detail: YktFeeItemDetail?,
    balance: ElectricityBalance?,
    loading: Boolean,
    error: String,
    onPickLevel: (Int) -> Unit,
    onSelectFeeItem: (YktFeeItem) -> Unit,
    onOpenOfficial: () -> Unit,
) {
    val needsSelection = detail?.requiresSelection == true
    SystemCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Bolt,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "宿舍电费",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        SystemDivider()

        if (feeItems.isEmpty()) {
            HintText("学校未开通电费查询，或当前账号未绑定宿舍电表。")
            return@SystemCard
        }

        // ── 费项选择 ──────────────────────────────────────────────────────
        // 多个电费入口时（淮师有两个：1-6单元 / 7-11单元）让用户明确选一个。
        // 切换费项等于换了整条级联（楼栋集合都不同），所以必须清空下游。
        if (feeItems.size > 1) {
            Text(
                text = "电费项目",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                feeItems.forEach { item ->
                    val selected = item.feeItemId == selection.feeItemId
                    SystemStatusBadge(
                        text = item.name,
                        tone = if (selected) SystemTone.Info else SystemTone.Neutral,
                        modifier = Modifier.clickable(enabled = !selected) {
                            onSelectFeeItem(item)
                        },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            SystemDivider()
        }

        // ── 动态分级选择（级数与名称均来自服务端；淮师电费是 4 级）──────────
        if (needsSelection && levels.isNotEmpty()) {
            levels.forEach { level ->
                val picked = selection.pick(level.code)
                // 第 1 级永远可选；第 N 级要求前 N-1 级都已选。
                val unlocked = selection.picks.size >= level.level - 1
                SelectRow(
                    label = level.name.ifBlank { "第${level.level}级" },
                    value = picked?.name.orEmpty(),
                    placeholder = if (unlocked) {
                        "请选择"
                    } else {
                        "请先选择${levels.getOrNull(level.level - 2)?.name ?: "上一级"}"
                    },
                    enabled = unlocked,
                    onClick = { onPickLevel(level.level) },
                )
            }
        }

        // ── 读数（含服务端回传的房间上下文）───────────────────────────────
        SystemDivider()
        when {
            error.isNotBlank() -> HintText(error)

            loading -> SystemLoadingState(text = "正在查询电费…")

            // 直查费项没有"选房间"这一步，所以这里不能再要求 isQueryable
            !needsSelection && selection.feeItemId.isBlank() ->
                HintText("请先选择电费项目。")

            needsSelection && !selection.isQueryable ->
                HintText(
                    "请依次选择" +
                        levels.joinToString("、") { it.name.ifBlank { "第${it.level}级" } } +
                        "后查询。"
                )

            balance == null -> HintText("已连接到电费接口，但未能识别余额数值，请稍后重试。")

            balance.amount == null -> HintText(
                "接口已返回但未识别到余额数值。" +
                    if (balance.raw.isNotBlank()) "原文：${balance.raw}" else ""
            )

            else -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // 先亮明"这是哪间房"，避免同名楼栋/相似房号时用户误读成自己宿舍。
                if (context?.summary?.isNotBlank() == true) {
                    Text(
                        text = context.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = balance.label.ifBlank { "剩余" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = YktBalance.formatWithUnit(balance.amount, balance.unit),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (balance.unit == ElectricityUnit.KWH) {
                    HintMarkdown("注意：该费项返回的是**电量（度）**而非金额，不参与金额阈值提醒。")
                }
                if (balance.raw.isNotBlank()) {
                    HintText("接口原文：${balance.raw}")
                }
            }
        }

        // ── 官方入口（直查路径下这条是主要出口）─────────────────────────────
        SystemDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenOfficial)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "在官方页面查看/缴纳电费",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Outlined.LocationOn,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        if (!needsSelection) {
            HintText(
                "该电费项目按费项直接返回读数，没有房间选择这一步；" +
                    "房间号以官方页面为准。"
            )
        }
    }
}

/**
 * 消费卡片：月度汇总 + **逐笔明细列表**（应用内原生渲染）。
 *
 * ## 明细是自绘的（不跳官方页）
 *
 * 早期这里只有汇总 + 「去官方页面看明细」，理由是"明细接口无法复刻"——
 * **那个结论是错的**，根因是只翻了 `/plat/` 那套 SPA，漏了官方第三套前端
 * `/campus-card-pc/`（「校园一卡通.新中新」v1.07.1.3）。
 * 真实接口 `GET /berserker-search/search/personal/turnover` 已实测可用
 * （账号 19704 一次返回 1002 条），所以现在**直接列表展示**。
 *
 * ## 金额单位
 *
 * 服务端给的是**分**，`tranamt=1` 就是 0.01 元。换算在 [YktBillRecord] 里做，
 * 这里只管展示。
 *
 * ## 官方页面入口仍然保留
 *
 * 自绘列表覆盖"看流水"这个主要诉求，但**退款、导出、标签**这些操作
 * 只在官方页有，所以底部保留一个入口（[onOpenDetail]）。
 */
@Composable
private fun ConsumptionCard(
    monthOffset: Int,
    turnover: YktTurnover?,
    loading: Boolean,
    error: String,
    bills: YktBillPage?,
    billsLoading: Boolean,
    billsError: String,
    billsExpanded: Boolean,
    onToggleBills: () -> Unit,
    onShiftMonth: (Int) -> Unit,
    onLoadMore: () -> Unit,
    onOpenDetail: () -> Unit,
) {
    SystemCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.CreditCard,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "消费记录",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
        SystemDivider()

        // ── 月度汇总 ──────────────────────────────────────────────────────
        // 月份导航**只属于汇总**：账单接口没有日期参数（见 YktClient.billRecords），
        // 翻月只影响这里的两行金额，不影响下方明细。所以把控件关在"汇总"这一小节内，
        // 而不是放在卡片标题旁 —— 否则用户会以为翻月也筛了明细。
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "月度汇总",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            SystemIconButton(
                icon = Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                contentDescription = "上一月",
                onClick = { onShiftMonth(-1) },
            )
            Text(
                text = YktBalance.monthLabel(monthOffset),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            SystemIconButton(
                icon = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = "下一月",
                onClick = { onShiftMonth(1) },
            )
        }
        Spacer(Modifier.height(4.dp))

        when {
            error.isNotBlank() -> HintText(error)

            loading -> SystemLoadingState(text = "正在查询消费汇总…")

            turnover == null -> HintText("暂无数据，可稍后重试。")

            turnover.isEmpty -> HintText("该区间没有消费与充值记录。")

            else -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TurnoverRow("支出", turnover.expense, MaterialTheme.colorScheme.error)
                TurnoverRow("收入", turnover.income, MaterialTheme.colorScheme.primary)
            }
        }

        SystemDivider()

        // ── 逐笔明细（**全量历史**，非本月，可折叠）───────────────────────
        // 必须显式标注"全部"：账单接口不支持按月筛选，这里是完整流水。
        // 若沿用"逐笔明细"四个字挂在月份下，用户会把"全量"读成"本月"，从而误判花销。
        //
        // 折叠的原因：全量可能上千笔，展开会把电费卡片与提醒设置挤到很下面；
        // 默认收起、标题行常驻（带总笔数），用户想翻流水时才展开。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggleBills() }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (billsExpanded) {
                    Icons.Outlined.ExpandLess
                } else {
                    Icons.Outlined.ExpandMore
                },
                contentDescription = if (billsExpanded) "收起明细" else "展开明细",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "逐笔明细（全部）",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            // 总条数由服务端给；拉取中不要显示旧数字造成"数量在跳"的错觉
            if (bills != null && bills.total > 0) {
                Text(
                    text = if (billsExpanded) "共 ${bills.total} 笔" else "共 ${bills.total} 笔 · 点击展开",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 收起时直接跳过明细正文（含 loading/错误态），避免折叠了还占位
        if (billsExpanded) {
            Spacer(Modifier.height(6.dp))
            when {
                billsError.isNotBlank() -> HintText(billsError)

                billsLoading && bills == null -> SystemLoadingState(text = "正在查询流水…")

                bills == null -> HintText("暂无流水数据，可稍后重试。")

                bills.isEmpty -> HintText("没有查询到流水记录。")

                else -> {
                    bills.records.forEach { record ->
                        BillRow(record)
                    }
                    if (bills.hasMore) {
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !billsLoading, onClick = onLoadMore)
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (billsLoading) {
                                SystemLoadingState(text = "加载中…")
                            } else {
                                Text(
                                    text = "加载更多（已显示 ${bills.records.size}/${bills.total}）",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    } else if (bills.records.size > 1) {
                        Spacer(Modifier.height(4.dp))
                        HintText("已显示全部 ${bills.records.size} 笔。")
                    }
                }
            }
        }

        SystemDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenDetail)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "在官方页面查看（退款 / 导出 / 标签）",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Outlined.LocationOn,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        HintMarkdown(
            "- 金额与流水来自一卡通账单接口。\n" +
                "- 本月汇总**只统计所选月份**，明细为全部历史。\n" +
                "- 退款、导出、标签等操作需在官方页面完成。"
        )
    }
}

/**
 * 一行流水：左标题 + 时间/地点，右侧带符号金额。
 *
 * ## 为什么金额颜色不用「红涨绿跌」
 *
 * 那是**股票**的口径，这里是**账目**：支出用 error 色、收入用主色，
 * 与上方汇总的配色保持一致（同一屏内两套语义色会让人读错）。
 */
@Composable
private fun BillRow(record: YktBillRecord) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = record.displayTitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 次要行：时间 + （有则带上）地点/支付方式，用 · 分隔，空段不占位
            val meta = listOf(
                record.tradeTime.ifBlank { record.tradeDate },
                record.location,
                record.payName,
            ).filter { it.isNotBlank() }.joinToString(" · ")
            if (meta.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = record.signedAmount,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (record.isIncome) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            // 该笔之后的余额：服务端没给就不显示（不显示 0，那会被误读成"钱花光了"）
            record.cardBalanceYuan?.let { balance ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "余额 ¥" + YktBalance.formatYuan(balance),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 汇总里的一行金额（左标签、右数值）。 */
@Composable
private fun TurnoverRow(label: String, amount: Double, color: androidx.compose.ui.graphics.Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "¥" + YktBalance.formatYuan(amount),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
    }
}

/** 一行可点的选择项：左侧标签、右侧值 + 箭头。 */
@Composable
private fun SelectRow(
    label: String,
    value: String,
    placeholder: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Text(
            text = value.ifBlank { placeholder },
            style = MaterialTheme.typography.bodyMedium,
            color = when {
                value.isNotBlank() -> MaterialTheme.colorScheme.onSurface
                enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            },
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.Outlined.LocationOn,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.4f),
        )
    }
}

/**
 * 电费低余额提醒设置：开关 + 阈值 + **后台运行权限引导**。
 *
 * 阈值存在 [YktAlertSettings]（全局，不按账号——这是"这台手机想被怎么提醒"的偏好）。
 * 改完立刻生效：下一次后台巡检即按新阈值判定。
 *
 * [onThresholdChanged] 让调用方在阈值变化时立刻用当前余额重判一次——
 * 用户改完就想知道"现在会不会响"。
 *
 * ## 为什么把"后台权限"放在这里
 *
 * 提醒的价值建立在**后台巡检能跑起来**之上。若系统把本 App 冻结，
 * 用户设置了阈值却收不到提醒，只会认为"功能坏了"。
 * 所以把判断与引导入口放在同一个卡片里，让用户一站式配好。
 */
@Composable
private fun AlertSettingsCard(
    balance: ElectricityBalance?,
    onThresholdChanged: (Double) -> Unit,
) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(YktAlertSettings.isEnabled(context)) }
    var threshold by remember { mutableStateOf(YktAlertSettings.thresholdYuan(context)) }
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    // 电池优化豁免状态：**不缓存**，每次进入该屏都重查（用户可能刚在系统设置里改过）。
    // 用 remember 只是为了让本屏重组时不重复读取；离开再回来会重新求值。
    var batteryExempt by remember { mutableStateOf(YktBackgroundPermission.isIgnoringBatteryOptimizations(context)) }

    SystemCard {
        SystemSectionHeader(
            title = "余额提醒",
            subtitle = "电费低于阈值时推送通知，避免欠费断电",
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "开启提醒",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = enabled, onCheckedChange = {
                enabled = it
                YktAlertSettings.setEnabled(context, it)
            })
        }
        if (enabled) {
            SystemDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "提醒阈值",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "¥${YktBalance.formatYuan(threshold)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                SystemIconButton(
                    icon = Icons.Outlined.Edit,
                    contentDescription = "修改阈值",
                    onClick = {
                        draft = YktBalance.formatYuan(threshold)
                        editing = true
                    },
                    chip = false,
                )
            }
            HintMarkdown(
                "- 默认 20 元，可设范围 " +
                    "${YktBalance.formatYuan(YktAlertSettings.MIN_THRESHOLD_YUAN)} ~ " +
                    "${YktBalance.formatYuan(YktAlertSettings.MAX_THRESHOLD_YUAN)} 元。\n" +
                    "- 余额低于阈值就会提醒：每日巡检一次，**不限提醒次数**。\n" +
                    "- 仅对显示为金额的费项生效。"
            )
            // 当前费项是度数型时明确说明"为什么不会响"，避免用户以为功能坏了
            if (balance != null && balance.unit == ElectricityUnit.KWH) {
                Spacer(Modifier.height(4.dp))
                HintMarkdown(
                    text = "当前电费项目返回的是**电量（度）**，无法按金额判断是否该提醒。",
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // ── 后台运行权限（提醒能否送达的前提）─────────────────────────
            SystemDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "后台运行权限",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = if (batteryExempt) {
                            "已允许后台运行，提醒可正常送达。"
                        } else {
                            "尚未允许后台运行，部分机型会收不到提醒。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (batteryExempt) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
                if (!batteryExempt) {
                    SystemStatusBadge(text = "待设置", tone = SystemTone.Warning)
                }
            }
            // 两个入口分开给：电池优化是标准 API 可判定；自启动各家 ROM 不同，
            // 只能"尽力跳转"，所以文案上说明这是需要用户手动确认的一步。
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = {
                    runCatching { context.startActivity(YktBackgroundPermission.batteryOptimizationIntent(context)) }
                        .onFailure { GlassToaster.show("无法打开系统设置，请手动在「电池」中允许后台运行") }
                }) { Text("电池优化白名单") }
                TextButton(onClick = {
                    runCatching { context.startActivity(YktBackgroundPermission.autostartIntent(context)) }
                        .onFailure { GlassToaster.show("无法打开自启动设置，请在系统「应用管理」中手动允许") }
                }) { Text("自启动设置") }
                TextButton(onClick = {
                    // 用户可能刚在系统设置里改过，回来手动刷新一次状态
                    batteryExempt = YktBackgroundPermission.isIgnoringBatteryOptimizations(context)
                }) { Text("刷新状态") }
            }
            HintMarkdown(
                "- 为了电费余额能及时提醒，请允许本应用后台运行。\n" +
                    "- 不同品牌入口名称不同（自启动 / 后台运行管理 / 省电策略等）。\n" +
                    "- 「电池优化白名单」与「自启动设置」都设好后最可靠。"
            )
        }
    }

    if (editing) {
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("设置提醒阈值") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("低于该金额时提醒（元）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val parsed = draft.trim().toDoubleOrNull()
                    if (parsed == null) {
                        GlassToaster.show("请输入有效的金额")
                    } else {
                        threshold = YktAlertSettings.clampThreshold(parsed)
                        YktAlertSettings.setThresholdYuan(context, threshold)
                        onThresholdChanged(threshold)
                        editing = false
                    }
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { editing = false }) { Text("取消") }
            },
        )
    }
}

/** 一行「标签 + 值」明细，值空则整行不渲染（服务端常给空串）。 */
@Composable
private fun DetailRow(label: String, value: String) {
    if (value.isBlank()) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun HintText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 与 [HintText] 同款排版，但内容按 Markdown 渲染（加粗、列表能看出来）。
 *
 * 分成两个函数、而不是让 [HintText] 一律走 Markdown，是因为 [HintText] 还要显示
 * **服务端回传的文本**（报错信息、「接口原文：…」）。那些内容不由我们控制，
 * 万一出现 `- ` / `**` 就会被当成排版指令，好端端一段报错突然换了版式。
 * 自己写的说明性文案才用这一个。
 */
@Composable
private fun HintMarkdown(
    text: String,
    lineHeight: TextUnit = TextUnit.Unspecified,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    MarkdownText(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        lineHeight = lineHeight,
        color = color,
    )
}

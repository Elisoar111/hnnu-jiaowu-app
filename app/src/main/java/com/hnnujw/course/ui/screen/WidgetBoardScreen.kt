package com.hnnujw.course.ui.screen

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.hnnujw.course.ui.system.GlassCircleButton
import com.hnnujw.course.ui.system.GlassToaster
import com.hnnujw.course.ui.system.GlassWindowHost
import com.hnnujw.course.ui.system.LocalAppOverlayBottomInset
import com.hnnujw.course.ui.system.LocalFloatingNotice
import com.hnnujw.course.ui.system.LocalNoticeAnchor
import com.hnnujw.course.ui.system.LocalWallpaperAppearanceColors
import com.hnnujw.course.ui.system.NoticeAnchorState
import com.hnnujw.course.ui.system.PagePadding
import com.hnnujw.course.ui.system.SystemDialog
import com.hnnujw.course.ui.system.SystemPrimaryButton
import com.hnnujw.course.ui.system.SystemSecondaryButton
import com.hnnujw.course.ui.system.SystemTopBar
import com.hnnujw.course.ui.theme.NeuPrimary
import com.hnnujw.course.widgetboard.CardWidget
import com.hnnujw.course.widgetboard.CardWidgetRenderer
import com.hnnujw.course.widgetboard.CardWidgetUpdater
import com.hnnujw.course.widgetboard.InAppWidgetRegistry
import com.hnnujw.course.widgetboard.InAppWidgetSpec
import com.hnnujw.course.widgetboard.WidgetBoardData
import com.hnnujw.course.widgetboard.WidgetBoardLayout
import com.hnnujw.course.widgetboard.WidgetBoardStore
import com.hnnujw.course.widgetboard.WidgetInstance
import com.hnnujw.course.widgetboard.WidgetSize
import java.util.UUID

/** 卡片右上角的编辑按钮，由 [WidgetCardShell] 在编辑态读取 —— 走 CompositionLocal 是为了不让 7 个卡片各自多传两个参数。 */
internal data class WidgetCardEditActions(
    val onResize: () -> Unit,
    val onDelete: () -> Unit,
    /** 把这一种卡片钉到手机桌面（系统 AppWidget）。 */
    val onPinToHome: () -> Unit,
)

internal val LocalWidgetCardEditActions = staticCompositionLocalOf<WidgetCardEditActions?> { null }

/**
 * 组件工作台：把散在各页的课上信息聚成一屏可自由摆放的卡片。
 *
 * ## 与手机桌面卡片的关系
 * 桌面（壁纸上面那一层）只能由系统 AppWidget 来画 —— RemoteViews 不支持动效、
 * Compose，也没法随登录态实时重排。所以同一张卡片有两套画法：
 *  - **工作台这里**是纯 Compose，随便摆、随便改，账号隔离与主题完全跟随 App；
 *  - **手机桌面上**是 RemoteViews（[com.hnnujw.course.widgetboard.CardWidgetRenderer]），
 *    尺寸档位由系统决定，内容只读本地缓存。
 * 两边用同一个卡片 id 对上（[CardWidget]），所以卡片右上角能直接把这一种「添加到桌面」。
 *
 * ## 布局
 * 4 列网格，卡片用 [WidgetSize] 占 2 列或 4 列（见其注释：不做二维占格）。
 * 编辑态可以拖动排序 —— 长按卡片后跟手移动，落点落在哪张卡上就与它换位；
 * 尺寸、添加到桌面、删除走右上角三个按钮，不用手势（小卡片上做手势命中率太低）。
 */
@Composable
fun WidgetBoardScreen(
    data: WidgetBoardData,
    onOpen: (sourceId: String, action: String) -> Unit,
    onCourseOpen: (courseId: String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var layout by remember { mutableStateOf(WidgetBoardStore.load(context)) }
    var editing by remember { mutableStateOf(false) }
    /** 正在打开的尺寸 / 添加抽屉。 */
    var resizeTarget by remember { mutableStateOf<String?>(null) }
    var showPicker by remember { mutableStateOf(false) }

    // 顶栏会 reportNoticeAnchor()，不隔离的话这一页的顶栏底边会写进主窗口的锚点状态。
    val noticeAnchorState = remember { NoticeAnchorState() }
    val gridState = rememberLazyGridState()
    val headerCollapse by remember {
        derivedStateOf {
            if (gridState.firstVisibleItemIndex > 0) 1f
            else (gridState.firstVisibleItemScrollOffset / 120f).coerceIn(0f, 1f)
        }
    }

    fun persist(next: WidgetBoardLayout) {
        layout = next
        WidgetBoardStore.save(context, next)
    }

    /**
     * 「添加到桌面」：把这一种卡片交给系统的组件管理器去钉到桌面。
     *
     * 卡片 id 就是组件 id（[CardWidget.of] 按它找 Provider），所以这里不用传"长什么样" ——
     * 桌面上那份由 RemoteViews 自己画，跟工作台里这张读的是同一批缓存。
     */
    fun pinToHome(cardId: String) {
        if (CardWidget.of(cardId) == null) {
            // 只会在"注册表里加了卡片、却没同步加组件"这种改漏的情况下出现。
            GlassToaster.show("这一种卡片还没有对应的桌面组件")
            return
        }
        CardWidgetUpdater.requestPin(context, cardId)
    }

    GlassWindowHost(modifier = Modifier.fillMaxSize()) {
        CompositionLocalProvider(
            LocalFloatingNotice provides null,
            LocalNoticeAnchor provides noticeAnchorState,
            // 主窗口给的是底栏高度；这个窗口没有底栏，不清零底部会空出一块。
            LocalAppOverlayBottomInset provides 0.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                SystemTopBar(
                    title = "组件工作台",
                    subtitle = if (editing) "拖动排序 · 右上角可改尺寸 / 加到桌面 / 移除" else "长按卡片进入编辑",
                    collapseFraction = headerCollapse,
                    navigationIcon = {
                        GlassCircleButton(
                            onClick = onClose,
                            icon = Icons.Default.Close,
                            contentDescription = "关闭",
                            size = 34.dp
                        )
                    },
                    actions = {
                        if (layout.instances.isNotEmpty()) {
                            Text(
                                text = if (editing) "完成" else "编辑",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = NeuPrimary,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        role = Role.Button,
                                        onClick = { editing = !editing }
                                    )
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                )

                Column(modifier = Modifier.weight(1f)) {
                    // 常驻说明放在滚动区之外：它讲的是"桌面组件"，与下面的卡片列表无关，
                    // 滚走就失去意义了（用户正是在翻卡片时才会想"怎么放到桌面"）。
                    WidgetBoardHomeHint()

                    Box(modifier = Modifier.weight(1f)) {
                        if (layout.instances.isEmpty()) {
                            BoardEmptyState(
                                onAdd = { showPicker = true },
                                modifier = Modifier.align(Alignment.Center)
                            )
                        } else {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(4),
                                state = gridState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(
                                    start = PagePadding,
                                    end = PagePadding,
                                    top = 8.dp,
                                    bottom = 24.dp
                                ),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                items(
                                    items = layout.instances,
                                    key = { it.id },
                                    span = { it.size.columns.let { n -> GridItemSpan(n) } }
                                ) { instance ->
                                    DraggableWidgetCell(
                                        instance = instance,
                                        layout = layout,
                                        editing = editing,
                                        data = data,
                                        gridState = gridState,
                                        onReorder = { persist(it) },
                                        onOpen = onOpen,
                                        onCourseOpen = onCourseOpen,
                                        onEnterEdit = { editing = true },
                                        onResize = { resizeTarget = instance.id },
                                        onPinToHome = { pinToHome(instance.sourceId) },
                                        onDelete = {
                                            persist(WidgetBoardLayout(layout.instances.filterNot { it.id == instance.id }))
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // 添加入口常驻底部：空态之外也要有一条始终看得见的「再加一张」路径。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = PagePadding, end = PagePadding, top = 4.dp, bottom = 18.dp)
                ) {
                    SystemPrimaryButton(
                        text = "添加组件",
                        onClick = { showPicker = true },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            val target = layout.instances.firstOrNull { it.id == resizeTarget }
            if (target != null) {
                ResizeSheet(
                    instance = target,
                    onPick = { size ->
                        persist(
                            WidgetBoardLayout(
                                layout.instances.map { if (it.id == target.id) it.copy(size = size) else it }
                            )
                        )
                        resizeTarget = null
                    },
                    onDismiss = { resizeTarget = null }
                )
            }

            if (showPicker) {
                WidgetPickerSheet(
                    data = data,
                    existing = layout.instances.map { it.sourceId }.toSet(),
                    onPick = { spec ->
                        // id 用随机串：同一种卡片将来若允许摆两张，也不会撞 key。
                        persist(
                            WidgetBoardLayout(
                                layout.instances + WidgetInstance(
                                    id = "w-" + UUID.randomUUID().toString().take(8),
                                    sourceId = spec.id,
                                    size = spec.defaultSize
                                )
                            )
                        )
                        showPicker = false
                    },
                    onPinToHome = { spec ->
                        pinToHome(spec.id)
                        showPicker = false
                    },
                    onDismiss = { showPicker = false }
                )
            }
        }
    }
}

/**
 * 一个卡片格子 + 编辑态下的拖动排序。
 *
 * 拖动规则：长按后卡片跟手，**指针中心落到哪张卡上就与那张卡换位**。换位后卡片
 * 在列表里挪了位置，视觉上会突然跳一段，所以把这一段跳量从 [Offset] 里扣掉
 * （见 onDrag 里的注释），卡片就始终贴在手指下面。
 */
@Composable
private fun DraggableWidgetCell(
    instance: WidgetInstance,
    layout: WidgetBoardLayout,
    editing: Boolean,
    data: WidgetBoardData,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    onReorder: (WidgetBoardLayout) -> Unit,
    onOpen: (String, String) -> Unit,
    onCourseOpen: (courseId: String) -> Unit,
    onEnterEdit: () -> Unit,
    onResize: () -> Unit,
    onPinToHome: () -> Unit,
    onDelete: () -> Unit,
) {
    var dragOffset by remember(instance.id) { mutableStateOf(Offset.Zero) }
    var dragging by remember(instance.id) { mutableStateOf(false) }

    val dragModifier = if (!editing) {
        Modifier
    } else {
        Modifier.pointerInput(instance.id) {
            detectDragGesturesAfterLongPress(
                onDragStart = {
                    dragging = true
                    dragOffset = Offset.Zero
                },
                onDragEnd = {
                    dragging = false
                    dragOffset = Offset.Zero
                },
                onDragCancel = {
                    dragging = false
                    dragOffset = Offset.Zero
                },
                onDrag = { change, amount ->
                    change.consume()
                    val next = dragOffset + amount
                    dragOffset = next
                    val info = gridState.layoutInfo.visibleItemsInfo
                        .firstOrNull { it.key == instance.id } ?: return@detectDragGesturesAfterLongPress
                    val centerX = info.offset.x + info.size.width / 2f + next.x
                    val centerY = info.offset.y + info.size.height / 2f + next.y
                    val target = gridState.layoutInfo.visibleItemsInfo.firstOrNull { other ->
                        other.key != instance.id &&
                            centerX >= other.offset.x && centerX <= other.offset.x + other.size.width &&
                            centerY >= other.offset.y && centerY <= other.offset.y + other.size.height
                    } ?: return@detectDragGesturesAfterLongPress
                    val from = layout.instances.indexOfFirst { it.id == instance.id }
                    val to = layout.instances.indexOfFirst { it.id == target.key }
                    if (from < 0 || to < 0 || from == to) return@detectDragGesturesAfterLongPress
                    onReorder(
                        WidgetBoardLayout(
                            layout.instances.toMutableList().apply { add(to, removeAt(from)) }
                        )
                    )
                    // 换位后这张卡的"自然位置"变成了对方的旧落点，于是它会在屏幕上
                    // 瞬移 (target - info)。把这段位移从 offset 里扣掉，视觉位置不动。
                    dragOffset = next - Offset(
                        (target.offset.x - info.offset.x).toFloat(),
                        (target.offset.y - info.offset.y).toFloat()
                    )
                }
            )
        }
    }

    Box(
        modifier = Modifier
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer {
                if (dragging) {
                    translationX = dragOffset.x
                    translationY = dragOffset.y
                    scaleX = 1.03f
                    scaleY = 1.03f
                }
            }
            .then(dragModifier)
    ) {
        CompositionLocalProvider(
            LocalWidgetCardEditActions provides
                if (editing) {
                    WidgetCardEditActions(onResize = onResize, onDelete = onDelete, onPinToHome = onPinToHome)
                } else {
                    null
                }
        ) {
            WidgetBoardCard(
                instance = instance,
                data = data,
                editing = editing,
                onOpen = onOpen,
                onLongPress = onEnterEdit,
                onCourseOpen = onCourseOpen,
                modifier = Modifier.scale(if (editing) 0.98f else 1f)
            )
        }
    }
}

/**
 * 常驻提示：告诉用户"手机桌面上也能放"，以及去哪儿找。
 *
 * ## 为什么需要它
 *
 * 这一页是 **App 内**的 Compose 卡片，和手机桌面上那层 AppWidget 是两套东西
 * （见文件头的说明）。卡片右上角的「添加到桌面」只在**卡片已存在**时才出现，
 * 空态进来的用户根本看不到这个入口，也无从知道桌面上有这七种组件 ——
 * 于是"桌面组件"这个功能实际上只被已经知道它的人用得到。
 *
 * 所以这里给一条与工作台内容无关的常驻说明：入口路径写全（各家系统叫法不同）、
 * 并预先说明"有些手机不支持" —— 否则用户在系统组件列表里翻不到，会以为是 App 坏了。
 *
 * 刻意**不做可关闭**：这条说明的价值恰恰在于"每次进来都能看见"，
 * 关掉之后就没有第二次机会了，而它只有两行。
 */
@Composable
private fun WidgetBoardHomeHint(modifier: Modifier = Modifier) {
    val appearance = LocalWallpaperAppearanceColors.current
    // 这一页跑在 GlassWindowHost 里，MaterialTheme 的色板在这里不是可靠来源 ——
    // 背景与描边一律取自 LocalWallpaperAppearanceColors（见 SystemUi 的同款写法）。
    val shape = RoundedCornerShape(14.dp)
    val surfaceColor = appearance.surface.copy(alpha = maxOf(appearance.surface.alpha, 0.30f))
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = PagePadding, end = PagePadding, top = 8.dp)
            .clip(shape)
            .background(surfaceColor)
            .border(1.dp, appearance.border, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.Outlined.Home,
            contentDescription = null,
            tint = NeuPrimary,
            modifier = Modifier.size(16.dp)
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "手机桌面上也能放这些组件",
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = appearance.onSurface
            )
            Text(
                text = "长按桌面空白处 →「卡片」→ 卡片中心 → 一直往下滑到最底部 →「插件」→ 选「校园助理」，" +
                    "就能添加下一节课、今日课表、考试倒计时等七种组件。" +
                    "桌面菜单里若直接有「小组件 / 桌面工具」，从那里进也一样。",
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = appearance.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun BoardEmptyState(onAdd: () -> Unit, modifier: Modifier = Modifier) {
    val appearance = LocalWallpaperAppearanceColors.current
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = PagePadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "工作台还是空的",
            fontSize = 16.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Medium,
            color = appearance.onSurface
        )
        Text(
            text = "添加课表、考试倒计时、成绩这些卡片，摆成一屏自己顺手的样子。",
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = appearance.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        SystemPrimaryButton(text = "选择组件", onClick = onAdd, modifier = Modifier.fillMaxWidth())
    }
}

/** 尺寸选择：只给注册表里声明支持的档位，不给用户摆出一个"画不出来"的组合。 */
@Composable
private fun ResizeSheet(
    instance: WidgetInstance,
    onPick: (WidgetSize) -> Unit,
    onDismiss: () -> Unit,
) {
    val spec = remember(instance.sourceId) { InAppWidgetRegistry.find(instance.sourceId) }
    SystemDialog(
        onDismissRequest = onDismiss,
        title = { Text("调整尺寸") }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "${spec?.title.orEmpty()} · 当前「${instance.size.label}」。小尺寸只放最关键的一行，大尺寸才铺得开列表。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            spec?.sizes.orEmpty().forEach { size ->
                val selected = size == instance.size
                SystemSecondaryButton(
                    text = if (selected) "${size.label}（当前）" else size.label,
                    onClick = { onPick(size) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * 组件抽屉：列出全部卡片，已经摆上的标「已添加」。
 *
 * 每条都带一个**真实数据的实时预览** —— 用该卡片自己的渲染器画一遍，所见即所得，
 * 不是示意图。
 *
 * 每条两个去处：「添加X」摆到工作台上，「添加到桌面」直接钉到手机桌面（系统组件）。
 * 后者不要求先在 工作台上摆一张 —— 桌面卡片与工作台卡片本来就是同一份数据的两种画法，
 * 用户想要哪个是两件独立的事。
 */
/** 桌面组件的最小尺寸兜底（dp）。拿不到 `AppWidgetProviderInfo` 时用 —— 2×1 的常见值。 */
private const val FallbackWidgetPreviewWidthDp = 110
private const val FallbackWidgetPreviewHeightDp = 60

/**
 * 组件抽屉里的「桌面上最小尺寸的样子」。
 *
 * 抽屉里那张卡片是 **App 内**的 Compose 画法，与手机桌面上的 `RemoteViews` 是两套渲染。
 * 只给 Compose 预览的话，用户是在**添加之前**看预览的 —— 预览里好看、桌面上缺行，
 * 代价是加完才发现不对、再删一次。
 *
 * 所以这里直接拿 [CardWidgetRenderer] 出图再截成位图：同一份 `cardDraft`、同一套
 * 尺寸决策，改渲染预览就跟着变，不可能不一致。
 *
 * 尺寸取桌面声明的 `minWidth` / `minHeight`（`AppWidgetProviderInfo` 里就是 dp），
 * 也就是"刚放上去、还没拉大"的紧凑形态 —— 这正是用户第一眼会看到的那个样子。
 *
 * 没有对应桌面组件、或渲染失败时**整段不出现**（而不是显示一个空框）：
 * 桌面上没有的东西，抽屉里不该先给一个占位。
 */
@Composable
private fun WidgetHomePreview(spec: InAppWidgetSpec, data: WidgetBoardData) {
    val context = LocalContext.current
    val preview = remember(spec.id, data) {
        val widget = CardWidget.of(spec.id) ?: return@remember null
        val info = runCatching {
            AppWidgetManager.getInstance(context).installedProviders
                .firstOrNull { it.provider == ComponentName(context, widget.provider) }
        }.getOrNull()
        CardWidgetRenderer.previewBitmap(
            context, spec.id, data,
            info?.minWidth?.takeIf { it > 0 } ?: FallbackWidgetPreviewWidthDp,
            info?.minHeight?.takeIf { it > 0 } ?: FallbackWidgetPreviewHeightDp,
        )
    }
    if (preview == null) return
    val density = LocalDensity.current.density
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "桌面上最小尺寸的样子",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Image(
            bitmap = preview.asImageBitmap(),
            contentDescription = "${spec.title}组件在手机桌面上的紧凑形态",
            modifier = Modifier
                .width((preview.width / density).dp)
                .height((preview.height / density).dp)
        )
    }
}

@Composable
private fun WidgetPickerSheet(
    data: WidgetBoardData,
    existing: Set<String>,
    onPick: (InAppWidgetSpec) -> Unit,
    onPinToHome: (InAppWidgetSpec) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    SystemDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加组件") }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 为什么要写这段：ColorOS（OPPO / 一加 / realme）把桌面组件的入口藏得很深 —— 长按桌面的
            // 菜单里**没有**「小组件」，要先点「卡片」进卡片中心，再一直滑到列表最底部（滚过 A–Z 索引
            // 那一段）才有一条「插件」，本应用的微件在那一页里。2026-09 在 ColorOS 13 真机上实测确认：
            // requestPinAppWidget 会拉起系统确认页却在 55ms 内自行关闭、卡片落不下来；而从「插件」里
            // 点一下那张卡片就能立刻加上。所以必须把这条确切路径写在最前面，不能让用户照着
            // "长按 → 小组件"白找一趟（那个菜单项压根不存在），也不能让他一直点"添加到桌面"等一个
            // 永远不会出现的确认框。
            Text(
                text = "「添加到桌面」在多数系统上会弹出确认框，点「添加」卡片就落到桌面上了。" +
                    "OPPO / 一加 / realme 的 ColorOS 不弹这个框，请改用桌面自己的入口：" +
                    "长按桌面空白处 →「卡片」→ 卡片中心 → 一直往下滑到最底部 →「插件」→ 选「校园助理」。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            InAppWidgetRegistry.all.forEach { spec ->
                val added = spec.id in existing
                // 抽屉每次打开都是新的一次组合，所以这里读到的"在不在桌面"就是当前真相。
                // 不去缓存它：用户随时可能在桌面上删掉，缓存下来的只会是过期的说法。
                val onHomeScreen = remember(spec.id) { CardWidgetUpdater.isOnHomeScreen(context, spec.id) }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = spec.icon,
                            contentDescription = null,
                            tint = NeuPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            text = spec.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Text(
                        text = spec.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // 预览用 Wide，不支持 Wide 的卡片退回它的最小尺寸。
                    val previewSize = if (WidgetSize.Wide in spec.sizes) WidgetSize.Wide else spec.sizes.first()
                    WidgetBoardCard(
                        instance = WidgetInstance("preview-${spec.id}", spec.id, previewSize),
                        data = data,
                        editing = false,
                        // 预览不接动作：点上去没有反应，比"点了跳到别的页"更不意外。
                        onOpen = { _, _ -> },
                        onLongPress = {},
                        onCourseOpen = {},
                        modifier = Modifier.fillMaxWidth()
                    )
                    WidgetHomePreview(spec = spec, data = data)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SystemPrimaryButton(
                            text = if (added) "已在工作台" else "添加${spec.title}",
                            // 已经在工作台上时按钮必须变灰：留成"可点但点了没反应"的样子，
                            // 用户会以为自己没点中，然后在同一处反复点。
                            enabled = !added,
                            onClick = { onPick(spec) },
                            modifier = Modifier.weight(1f)
                        )
                        SystemSecondaryButton(
                            text = if (onHomeScreen) "已在桌面" else "添加到桌面",
                            // 已经在桌面上了就别再弹一次系统确认框 —— 那只会多一个副本。
                            enabled = !onHomeScreen,
                            onClick = { onPinToHome(spec) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

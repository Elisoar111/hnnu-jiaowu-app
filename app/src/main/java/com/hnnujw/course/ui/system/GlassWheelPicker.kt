package com.hnnujw.course.ui.system

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.hnnujw.course.ui.system.glass.LocalGlassLensAnchor
import com.hnnujw.course.ui.system.glass.chromaticFringe
import com.hnnujw.course.ui.system.glass.glassLens
import com.hnnujw.course.ui.system.glass.glassLensOpticsFrom
import com.hnnujw.course.ui.system.glass.glassRim
import com.hnnujw.course.ui.system.glass.lensCornerRadiusPx
import com.hnnujw.course.ui.system.glass.resolvePhysicalLens
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 玻璃滚轮单列：iOS 风格 3D 滚筒，惯性滚动 + 逐行吸附。
 * 行随距中心距离产生透视旋转、缩放与淡出；中心行由 [WheelCenterLens] 指示。
 */
@Composable
fun GlassWheelColumn(
    items: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    itemHeight: Dp = 40.dp,
    visibleCount: Int = 5,
    /**
     * 是否在列内自画中心指示片。多列并排时外部会通铺一条，
     * 此时传 false —— 否则每列各画一份，列间隙处两个圆角贴上，
     * 看着像三片而不是一整片玻璃。
     */
    showCenterIndicator: Boolean = true
) {
    require(visibleCount % 2 == 1) { "visibleCount 应为奇数" }
    val density = LocalDensity.current
    val itemHeightPx = with(density) { itemHeight.toPx() }
    val padCount = visibleCount / 2
    val state = rememberLazyListState(
        initialFirstVisibleItemIndex = selectedIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    )
    val flingBehavior = rememberSnapFlingBehavior(state)
    val scope = rememberCoroutineScope()

    // 吸附中心行索引（滚动中实时更新）
    val centerIndex by remember(state) {
        derivedStateOf {
            val offsetRows = (state.firstVisibleItemScrollOffset / itemHeightPx).roundToInt()
            (state.firstVisibleItemIndex + offsetRows).coerceIn(0, (items.size - 1).coerceAtLeast(0))
        }
    }
    LaunchedEffect(centerIndex) {
        if (centerIndex != selectedIndex) onSelect(centerIndex)
    }

    Box(
        modifier = modifier.height(itemHeight * visibleCount),
        contentAlignment = Alignment.Center
    ) {
        // 中心行指示条：真正的液态玻璃片（折射 + 色散），见 [WheelCenterLens]。
        // 它必须画在【列表之前】：折射结果是它自己的背景，文字要叠在它上面；
        // 而且它不吃手势，滚动的触摸事件要能穿过去落到 LazyColumn 上。
        if (showCenterIndicator) {
            WheelCenterLens(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(itemHeight)
                    .align(Alignment.Center)
            )
        }
        LazyColumn(
            state = state,
            flingBehavior = flingBehavior,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = itemHeight * padCount)
        ) {
            items(items.size, key = { it }) { index ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(itemHeight)
                        .graphicsLayer {
                            val layoutInfo = state.layoutInfo
                            val info = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
                            if (info != null) {
                                val viewportCenter =
                                    (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2f
                                val itemCenter = info.offset + info.size / 2f
                                val distRows = (itemCenter - viewportCenter) / info.size
                                rotationX = -distRows * 16f
                                alpha = (1f - 0.24f * abs(distRows)).coerceIn(0.2f, 1f)
                                val scale = 1f - 0.055f * abs(distRows)
                                scaleX = scale
                                scaleY = scale
                            }
                        }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            scope.launch { state.animateScrollToItem(index) }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    val isCenter = index == centerIndex
                    Text(
                        text = items[index],
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isCenter) FontWeight.SemiBold else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )
                }
            }
        }
        // 上下渐隐罩，增强滚筒纵深
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(itemHeight * padCount)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.30f),
                            Color.Transparent
                        )
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(itemHeight * padCount)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.30f)
                        )
                    )
                )
        )
    }
}

/**
 * 滚轮中心行的液态玻璃指示片。
 *
 * ## 为什么不能再用一块半透明灰
 *
 * 原来这里是一枚 `0xFF787880` @12% 的胶囊 —— 那是一块"染灰的玻璃"，
 * 只有颜色没有光学行为。用户要的是底栏那种材质：**光穿过玻璃被折射、
 * 边缘出现色散**。所以这里换成真正的折射管线，与底栏指示器同源：
 *
 * ```
 * vibrancy → blur → lens(height, amount, chromaticAberration)
 * ```
 *
 * 三条路径，判定与 [adaptiveGlassChip] 完全一致：
 *
 * - **API 33+**：`isRuntimeLensEnabled()` 为 true，折射由库的 AGSL `lens()` 直接画。
 * - **API 31/32**：`glassLens(anchor)` 走自家的 ES 2.0 离屏折射。锚点从
 *   [LocalGlassLensAnchor] 取 —— 弹窗内容区由 `SystemDialog` 建了一份
 *   `combined(页面, 卡片自身)` 的区域，取景框正好托得住这条指示片。
 * - **API ≤ 30 / 无 backdrop**：两边都没有，退回原来的静态胶囊。
 *   主动补一层模糊撑住质感：没有折射时再没有模糊，这块就真的只是一块灰。
 *
 * ## 静止也要色散
 *
 * [chromaticAberrationAtRest] 给 true。底栏指示器是"压下去才出彩边"，
 * 因为按压是它的主交互；滚轮不按压，滚动本身才是主交互，静止不给色散
 * 就永远看不到用户点名要的那道"色散"。静止值 0.55 是弱档，不是满额，
 * 所以不会糊成一条彩虹。
 */
@Composable
fun WheelCenterLens(modifier: Modifier = Modifier) {
    val backdrop = LocalAppBackdrop.current ?: LocalControlBackdrop.current
    val usableBackdrop = backdrop?.takeIf { isBackdropSupported() }
    val shape = RoundedCornerShape(WheelLensCornerDp.dp)

    if (usableBackdrop == null) {
        Box(
            modifier = modifier
                .clip(shape)
                .background(WheelLensFallbackColor)
        )
        return
    }

    val accessibility = rememberGlassAccessibilityMode()
    val density = LocalDensity.current
    val lensAnchor = LocalGlassLensAnchor.current
    val material = GlassMaterials.resolve(
        role = GlassMaterialRole.Interactive,
        accessibility = accessibility
    )
    // 底栏那一档：折射高度 10dp / 位移 14dp。取同一份配方，
    // 滚轮片的观感才和"下方的导航栏"是同一种玻璃，而不是第二种效果。
    val lensMaterial = material.copy(
        refractionHeightDp = WheelLensRefractionHeightDp,
        refractionAmountDp = WheelLensRefractionAmountDp,
        optics = material.optics.copy(chromaticAberration = true)
    )

    Box(
        modifier = modifier
            // 31/32：折射由自家着色器画在 drawBackdrop **上游**，它提供背景。
            .glassLens(
                anchor = lensAnchor,
                shape = shape,
                optics = { w, h ->
                    glassLensOpticsFrom(
                        material = lensMaterial,
                        density = density,
                        cornerRadiusPx = lensCornerRadiusPx(shape, w, h, density),
                        minDimensionPx = minOf(w, h),
                        // 静止面板：不随按压变化
                        pressScalesRefraction = false,
                        // 静止保留弱色散 —— 用户要的那道彩边
                        chromaticAberrationAtRest = true
                    )
                }
            )
            .drawBackdrop(
                backdrop = usableBackdrop,
                shape = { shape },
                effects = {
                    vibrancy()
                    val params = resolvePhysicalLens(
                        scope = this,
                        material = lensMaterial,
                        minCornerRadiusPx = lensCornerRadiusPx(shape, size.width, size.height, density),
                        minDimensionPx = size.minDimension,
                        pressScalesRefraction = false,
                        chromaticAberrationAtRest = true
                    )
                    if (params.blurPx > 0f && params.useLens) blur(params.blurPx)
                    if (params.useLens) {
                        lens(
                            refractionHeight = params.refractionHeightPx,
                            refractionAmount = params.refractionAmountPx,
                            chromaticAberration = params.chromaticAberration
                        )
                    } else if (lensAnchor == null) {
                        // 真的没有折射（API ≤ 30）才补模糊：这里是唯一的质感来源。
                        blur(WheelLensFallbackBlurDp.dp.toPx())
                        if (params.fringePx > 0f) chromaticFringe(params.fringePx)
                    }
                },
                onDrawBackdrop = { drawBackdrop ->
                    // 31/32 上背景已由 glassLens 折射画过，不能再平铺一遍
                    if (lensAnchor == null) drawBackdrop()
                },
                onDrawSurface = {
                    // 表面白雾压得很低：这是块"能看穿"的玻璃片，
                    // 白一重就把底下滚动的文字糊掉了。
                    drawRect(Color.White.copy(alpha = if (accessibility.highContrast) 0.14f else 0.06f))
                }
            )
            .glassRim(shape = shape, intensity = WheelLensRimIntensity, isLightTheme = !rememberGlassDarkTheme())
    )
}

/** 滚轮指示片的圆角。与底栏指示器一致：一颗圆角矩形，不是胶囊。 */
private const val WheelLensCornerDp = 10f

/** 与 `CapsuleNavigationBar` 指示器同一份配方（lens(10dp, 14dp)）。 */
private const val WheelLensRefractionHeightDp = 10f
private const val WheelLensRefractionAmountDp = 14f

/** 边缘光强度。比底栏弱一档：滚轮片是"指示"，不是"当前选中项"。 */
private const val WheelLensRimIntensity = 0.7f

/** 无折射平台上的补偿模糊。 */
private const val WheelLensFallbackBlurDp = 8f

/** 完全无法采样 backdrop 时的静态胶囊，沿用原 systemFill 底色。 */
private val WheelLensFallbackColor = Color(0xFF787880).copy(alpha = 0.12f)

/**
 * 玻璃滚轮日期选择弹窗：年 / 月 / 日 三列，替代 MaterialDatePicker。
 * 返回本地时区当日零点的时间戳。
 */
@Composable
fun GlassDatePickerDialog(
    onConfirm: (timeInMillis: Long) -> Unit,
    onDismiss: () -> Unit,
    initialMillis: Long = System.currentTimeMillis(),
    title: String = "选择日期"
) {
    val initialCal = remember(initialMillis) {
        Calendar.getInstance().apply { timeInMillis = initialMillis }
    }
    val thisYear = remember { Calendar.getInstance().get(Calendar.YEAR) }
    val years = remember(thisYear) { (thisYear - 2..thisYear + 1).toList() }

    var yearIndex by remember {
        mutableIntStateOf(years.indexOf(initialCal.get(Calendar.YEAR)).coerceAtLeast(0))
    }
    var monthIndex by remember {
        mutableIntStateOf(initialCal.get(Calendar.MONTH))
    }
    var dayIndex by remember {
        mutableIntStateOf(initialCal.get(Calendar.DAY_OF_MONTH) - 1)
    }

    val daysInMonth = remember(yearIndex, monthIndex) {
        Calendar.getInstance().apply {
            clear()
            set(years[yearIndex], monthIndex, 1)
        }.getActualMaximum(Calendar.DAY_OF_MONTH)
    }
    if (dayIndex > daysInMonth - 1) dayIndex = daysInMonth - 1

    SystemDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        dismissButton = {
            SystemSecondaryButton(
                text = "取消",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            SystemPrimaryButton(
                text = "确定",
                onClick = {
                    val cal = Calendar.getInstance().apply {
                        clear()
                        set(years[yearIndex], monthIndex, dayIndex + 1)
                    }
                    onConfirm(cal.timeInMillis)
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // 三列共用一条中心指示条。每列自己再画一份的话，三块的圆角会在
            // 列与列的间隙处连起来，看着像三片而不是一片。
            WheelCenterLens(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .align(Alignment.Center)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlassWheelColumn(
                    items = years.map { "${it}年" },
                    selectedIndex = yearIndex,
                    onSelect = { yearIndex = it },
                    modifier = Modifier.weight(1.1f),
                    // 这一行的指示条是外面那条通铺的，列内不再各画一份
                    showCenterIndicator = false
                )
                GlassWheelColumn(
                    items = (1..12).map { "${it}月" },
                    selectedIndex = monthIndex,
                    onSelect = { monthIndex = it },
                    modifier = Modifier.weight(0.9f),
                    showCenterIndicator = false
                )
                Box(modifier = Modifier.weight(0.9f)) {
                    key(daysInMonth) {
                        GlassWheelColumn(
                            items = (1..daysInMonth).map { "${it}日" },
                            selectedIndex = dayIndex.coerceIn(0, daysInMonth - 1),
                            onSelect = { dayIndex = it },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

/**
 * 玻璃滚轮日期时间选择弹窗：日期（近 60 天）+ 时 + 分 三列。
 * 替代原生 DatePickerDialog/TimePickerDialog，跟随 SystemDialog 玻璃体系。
 */
@Composable
fun GlassDateTimePickerDialog(
    onConfirm: (timeInMillis: Long) -> Unit,
    onDismiss: () -> Unit,
    initialMillis: Long = System.currentTimeMillis(),
    title: String = "选择时间"
) {
    val dayCount = 60
    val baseCalendar = remember {
        Calendar.getInstance().apply {
            timeInMillis = System.currentTimeMillis()
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }
    val dayLabels = remember(baseCalendar) {
        val fmt = SimpleDateFormat("M月d日 E", Locale.CHINA)
        (0 until dayCount).map { offset ->
            if (offset == 0) {
                "今天"
            } else {
                val cal = baseCalendar.clone() as Calendar
                cal.add(Calendar.DAY_OF_YEAR, offset)
                fmt.format(cal.time)
            }
        }
    }
    val hourLabels = remember { (0..23).map { "%02d".format(it) } }
    val minuteLabels = remember { (0..59).map { "%02d".format(it) } }

    val initialCal = remember(initialMillis) {
        Calendar.getInstance().apply { timeInMillis = initialMillis }
    }
    val initialDayOffset = remember(initialCal) {
        val diff = ((initialCal.timeInMillis - baseCalendar.timeInMillis) /
            (24L * 60L * 60L * 1000L)).toInt()
        diff.coerceIn(0, dayCount - 1)
    }

    var dayIndex by remember { mutableIntStateOf(initialDayOffset) }
    var hourIndex by remember {
        mutableIntStateOf(initialCal.get(Calendar.HOUR_OF_DAY))
    }
    var minuteIndex by remember {
        mutableIntStateOf(initialCal.get(Calendar.MINUTE))
    }

    SystemDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        dismissButton = {
            SystemSecondaryButton(
                text = "取消",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            SystemPrimaryButton(
                text = "确定",
                onClick = {
                    val cal = baseCalendar.clone() as Calendar
                    cal.add(Calendar.DAY_OF_YEAR, dayIndex)
                    cal.set(Calendar.HOUR_OF_DAY, hourIndex)
                    cal.set(Calendar.MINUTE, minuteIndex)
                    onConfirm(cal.timeInMillis)
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // 通铺一条中心指示片，列内不再各画一份（见 GlassWheelColumn 的同名参数）
            WheelCenterLens(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .align(Alignment.Center)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlassWheelColumn(
                    items = dayLabels,
                    selectedIndex = dayIndex,
                    onSelect = { dayIndex = it },
                    modifier = Modifier.weight(1.6f),
                    showCenterIndicator = false
                )
                GlassWheelColumn(
                    items = hourLabels,
                    selectedIndex = hourIndex,
                    onSelect = { hourIndex = it },
                    modifier = Modifier.weight(0.7f),
                    showCenterIndicator = false
                )
                Text(
                    text = ":",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                GlassWheelColumn(
                    items = minuteLabels,
                    selectedIndex = minuteIndex,
                    onSelect = { minuteIndex = it },
                    modifier = Modifier.weight(0.7f),
                    showCenterIndicator = false
                )
            }
        }
    }
}

/**
 * 玻璃滚轮时间段选择弹窗：开始 时:分 / 结束 时:分 四列。
 *
 * 存在的理由是课表节次时间原本让用户手打 "HH:MM" 到 OutlinedTextField 里——
 * 格式错了没有任何提示，是那一页最差的一处交互。分钟按 5 步进：节次时间
 * 不需要分钟级精度，步进后一屏能扫完，比 60 行滚轮快得多。
 *
 * @param onConfirm 回调 "HH:mm" 形式的开始与结束时间
 */
@Composable
fun GlassTimeRangePickerDialog(
    initialStart: String,
    initialEnd: String,
    onConfirm: (start: String, end: String) -> Unit,
    onDismiss: () -> Unit,
    title: String = "编辑节次时间",
    minuteStep: Int = 5
) {
    val hourLabels = remember { (0..23).map { "%02d".format(it) } }
    val minuteValues = remember(minuteStep) { (0 until 60 step minuteStep).toList() }
    val minuteLabels = remember(minuteValues) { minuteValues.map { "%02d".format(it) } }

    var startHour by remember { mutableIntStateOf(parseHour(initialStart, 8)) }
    var startMinute by remember {
        mutableIntStateOf(nearestMinuteIndex(parseMinute(initialStart, 0), minuteValues))
    }
    var endHour by remember { mutableIntStateOf(parseHour(initialEnd, 8)) }
    var endMinute by remember {
        mutableIntStateOf(nearestMinuteIndex(parseMinute(initialEnd, 45), minuteValues))
    }

    SystemDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        dismissButton = {
            SystemSecondaryButton(
                text = "取消",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            SystemPrimaryButton(
                text = "保存",
                onClick = {
                    onConfirm(
                        "%02d:%02d".format(startHour, minuteValues[startMinute]),
                        "%02d:%02d".format(endHour, minuteValues[endMinute])
                    )
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            listOf(
                Triple("开始", startHour to startMinute, true),
                Triple("结束", endHour to endMinute, false)
            ).forEach { (label, value, isStart) ->
                Box(modifier = Modifier.weight(1f)) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.TopCenter)
                    )
                    Box(modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) {
                        WheelCenterLens(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                                .align(Alignment.Center)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            GlassWheelColumn(
                                items = hourLabels,
                                selectedIndex = value.first,
                                onSelect = { if (isStart) startHour = it else endHour = it },
                                modifier = Modifier.weight(1f),
                                visibleCount = 3,
                                showCenterIndicator = false
                            )
                            Text(
                                text = ":",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            GlassWheelColumn(
                                items = minuteLabels,
                                selectedIndex = value.second,
                                onSelect = { if (isStart) startMinute = it else endMinute = it },
                                modifier = Modifier.weight(1f),
                                visibleCount = 3,
                                showCenterIndicator = false
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 单列玻璃滚轮选择弹窗。用于「每天节数」这类选项不多但也不适合分段控件的场合
 * （9 个选项塞进分段控件会挤成一排细条，而 LiquidPicker 是 56dp 的表单字段，
 * 放进 inset grouped 行里就成了"表单里嵌表单"）。
 */
@Composable
fun GlassOptionWheelDialog(
    options: List<String>,
    selectedIndex: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
    title: String = "请选择"
) {
    if (options.isEmpty()) return
    var index by remember {
        mutableIntStateOf(selectedIndex.coerceIn(0, options.lastIndex))
    }

    SystemDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        dismissButton = {
            SystemSecondaryButton(
                text = "取消",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            SystemPrimaryButton(
                text = "确定",
                onClick = { onConfirm(index) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // 单列的指示片直接交给列自己画（宽度就等于整片宽度）
            GlassWheelColumn(
                items = options,
                selectedIndex = index,
                onSelect = { index = it },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

private fun parseHour(value: String, fallback: Int): Int =    value.substringBefore(':').trim().toIntOrNull()?.coerceIn(0, 23) ?: fallback

private fun parseMinute(value: String, fallback: Int): Int =
    value.substringAfter(':', "").trim().toIntOrNull()?.coerceIn(0, 59) ?: fallback

/** 存档里的分钟可能不在步进点上（例如 08:47），取最近的一格而不是丢弃。 */
private fun nearestMinuteIndex(minute: Int, values: List<Int>): Int {
    var best = 0
    values.forEachIndexed { index, value ->
        if (abs(value - minute) < abs(values[best] - minute)) best = index
    }
    return best
}

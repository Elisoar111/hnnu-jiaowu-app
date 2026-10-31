# BiliPai 参考项目分析：界面材质 · 液态玻璃 · 扫码 · 界面风格

> 分析对象：`D:\AAAAAAAAAA\参考项目\BiliPai`（BiliPai 0.6.9，第三方哔哩哔哩 Android 客户端）
> 结论性质：源码静态阅读，未编译运行。行号对应当前快照。

---

## 0. 总体架构速览

| 项目 | 值 |
| --- | --- |
| 语言 / UI | Kotlin + Jetpack Compose（100% Compose，无 XML 布局） |
| 最低版本 | Android 8.0（API 26），compileSdk 37，JDK 21 |
| 关键三方 | `miuix 0.9.4-153920c7-SNAPSHOT`（`top.yukonga.miuix.kmp`）、`dev.chrisbanes.haze`、CameraX、ZXing、`materialkolor` |
| 模块数 | 15：`:app :app-tv :core-data :core-player :design-system :design-tokens :brand-motion :baselineprofile :network-core :plugin-sdk :settings-core :danmaku-engine :dolby-ffmpeg-decoder :miuix-navigation :ci-lint` |

三层分工非常清晰，这是它做得比一般 Compose 项目好的地方：

```
design-tokens  → 纯常量（颜色/字阶/形状/间距数值），零依赖
design-system  → 令牌语义层 + 材质基元（blur/、AppSurfaceTokens、AppShapes、UiPreset）
app            → 业务装配（Theme.kt、UnifiedBlur、FloatingDockChrome、各 feature）
```

材质与风格的"真源"在 `design-system`，而**所有性能降级策略**集中在 `app/.../core/ui/blur/` 与 `core/ui/performance/`，业务组件只调一个 `Modifier.unifiedBlur(...)`。

---

## 1. 界面风格（主题体系）

### 1.1 双风格运行时：`AppUiStyle`

`design-system/src/main/java/com/android/purebilibili/core/theme/UiPreset.kt`

```kotlin
enum UiPreset(value, label) { IOS(0, "iOS"), MD3(1, "安卓原生") }
enum AndroidNativeVariant(value, label) { MATERIAL3(0, "Material 3"), MIUIX(1, "Miuix") }
enum class AppUiStyle { MATERIAL3, MIUIX }            // 运行时只有两值
fun fromLegacyValues(rawUiPreset, rawAndroidNativeVariant): AppUiStyle
fun resolveUiStyle(uiPreset, androidNativeVariant): AppUiStyle
val LocalAppUiStyle = staticCompositionLocalOf { MATERIAL3 }
val LocalDynamicColorActive
val LocalSettingsLiquidGlassEnabled
```

要点：
- **历史迁移路径**：老版本三选一（iOS / 安卓原生-Material3 / 安卓原生-Miuix）被压成两值。`IOS → MIUIX` 是**单向迁移**——因为 Miuix 本身就是仿 iOS 视觉，作者把 iOS 风格合并进 Miuix，避免维护两套仿 iOS 皮肤。
- `staticCompositionLocalOf`：切换风格会**整棵子树重组**，这正确——风格切换本来就该全量重建，且切换频率极低。

`grep -rln "AppUiStyle.MIUIX"` 命中 **197 个文件**，说明风格分支是"逐个组件 if 判定"而非"两套组件树"。这是它能同时保持 Material3 原生感和 Miuix 仿 iOS 感的实际手法。

### 1.2 主题装配管线：`Theme.kt`（1130 行）

`app/src/main/java/com/android/purebilibili/core/theme/Theme.kt:856`

```kotlin
fun PureBiliBiliTheme(
    uiStyle: AppUiStyle = MATERIAL3,
    themeMode: AppThemeMode = FOLLOW_SYSTEM,
    dynamicColor: Boolean = false,
    amoledDarkTheme: Boolean = false,
    themeColorIndex: Int = 0,
    md3ColorSource: Md3ColorSource = CUSTOM,          // FOLLOW_WALLPAPER / CUSTOM / WALLPAPER_RETONED
    md3CustomColorHex: String = "#007AFF",
    themeRoleOverrides: ThemeRoleOverrides,
    wallpaperPaletteOverrides: WallpaperPaletteOverrides,
    seedPaletteStyle, colorStyle: PaletteStyle = TonalSpot,
    colorSpec: ColorSpec.SpecVersion = SPEC_2025,
    colorContrast: Contrast = Default,
    fontSizePreset: AppFontSizePreset = DEFAULT,
    appFontWeightPreset, appFontFileName,
    appIconStyle, appListItemStyle,
    liquidGlassEnabled: Boolean = true,
    themeTransitionEnabled: Boolean = true,
    content: @Composable () -> Unit
)
```

管线顺序（每次主题帧执行）：

1. `resolveEffectiveDynamicColorEnabled(...)` — 动态取色是否生效（AMOLED / Miuix 会禁用）
2. `resolveMd3ThemeSeedColor(source, customColorHex, themeColorIndex, wallpaperSeed)` — 解出**种子色**
3. 按 `md3ColorSource` 分支：
   - `FOLLOW_WALLPAPER` + API 31+ → `createBiliPaiStyleColorScheme(seed,...)` / `readSeedWallpaperColorScheme` / `dynamicLightColorScheme(context)`（三级回退）
   - `CUSTOM` → 用 25 色预设板 `ThemeColors[themeColorIndex]`
4. `createBiliPaiStyleColorScheme(seedColor, isDark, params)` — materialkolor 生成（可换 `PaletteStyle` / `ColorSpec.SPEC_2025` / `Contrast`）
5. **双向桥接**：`createMiuixMaterialBridge(colorScheme)` / `resolveMaterialColorSchemeFromMiuixBridge(...)` / `resolveNativeMiuixColors(...)` / `alignMaterialSurfacesWithMiuix(...)` / `resolveMiuixColorsFromMaterialBridge(...)` —— Material3 与 Miuix 两套 ColorScheme **互为输入**，保证两风格下同一语义色一致
6. `CompositionLocalProvider(...)` 注入：`LocalAppUiStyle`、`LocalAppThemeConfig`、`LocalAppIconStyle`、`LocalAppListItemStyle`、`LocalCornerRadiusScale`

有一个很值得学的注释（Theme.kt:873-879 附近）：

```kotlin
// key() only groups composition; it does not cache these resource-backed
// palettes. A new ColorScheme on each theme frame changes the transition
// endpoint by identity and can keep the entire app recomposing indefinitely.
```

即：`ColorScheme` 每帧新建就会让主题过渡的**端点身份**变化，导致无限重组。所以每个 scheme 都套 `remember(...)` 且 key 精确到 `(context, systemWallpaperRefreshToken, seedPaletteStyle, compatibilitySeed, colorStyle, colorSpec, colorContrast)`。这类"Compose 陷阱"注释在项目里很密集，说明作者踩过。

### 1.3 语义色令牌：`AppSurfaceTokens`（267 行）

`design-system/src/main/java/com/android/purebilibili/core/ui/AppSurfaceTokens.kt`

```kotlin
val OutlineWidth = 1.dp
const val FrostedScrimAlpha = 0.65f
```

设计原则写在文件头：**业务代码不直接读 `MaterialTheme.colorScheme.xxx`**，一律走语义令牌：

| 令牌 | Material3 | Miuix |
| --- | --- | --- |
| `card()` | `surfaceContainer` | `miuix surfaceContainer` |
| `background()` / `groupedList()` / `chrome()` | `background` | `background` |
| `divider()` | `outlineVariant` | `outlineVariant` |
| `searchContainer()` | `surfaceContainerHigh` | 同名 |
| `searchContent()` | `onSurfaceVariant` | 同名 |
| `disabledContent()` | `onSurface` + disabled alpha | `disabledOnSurface` |

```kotlin
fun resolveMiuixSemanticColorComposable(miuixColor, materialFallback): Color
fun resolveSelectionContainer(accent, pageSurface, uiStyle, liquidGlassEnabled): Color
```

`resolveSelectionContainer` 是个有信息量的函数：**Miuix 非玻璃模式**下，`accent.copy(alpha = 0.14f)` 必须**先合成到不透明的 pageSurface 上**，否则半透明选中色叠在磨砂层上会因采样抖动而变色。玻璃模式反之必须保留 alpha（让透镜采样透过去）。

### 1.4 形状令牌：`AppShapes` + squircle

`design-system/src/main/java/com/android/purebilibili/core/ui/AppShapes.kt`

```kotlin
internal data class MiuixContainerShape(val radius: Dp) : Shape {
    override fun createOutline(size, layoutDirection, density) =
        Outline.Generic(Path().apply { addSquircleRect(size.width, size.height, radius.toPx()) })
}
```

关键：**Miuix 模式下圆角不是圆角，是"方圆形 squircle"**（`miuix-squircle` 的 `addSquircleRect`），用超椭圆路径而非 `RoundedCornerShape`。这是 iOS 图标/卡片曲率观感的核心差异，也是 `RoundedCornerShape(N.dp)` 无法模拟的。

圆角分级（`resolveContainerCornerDp`）：

| ContainerLevel | MIUIX | MATERIAL3 |
| --- | --- | --- |
| `Card` | 16dp | `baseCornerDp × scale` |
| `MediaCover` | 12dp | 同上 |
| `ProminentCard` | 20dp | 同上 |
| `Chip` | 10dp | 同上 |
| `Dialog` | 32dp | 同上 |
| `Pill` | `resolveAndroidNativeChromeTokens(uiStyle).pillCornerRadiusDp` | 同 |
| `Floating` | —（用 `AppShapes.container(Floating)`） | — |

`theme/Shape.kt` 里 Material3 槽位：`extraSmall / small / medium 16 / large 24 / extraLarge 28`（`MiuixAlignedShapes` 则为 8/12/16/20/28）——作者把 `large` 从官方 16 抬到 24 以对齐自己的 chrome 容器，`small/extraSmall` 保持官方值以免输入框、芯片观感回退。

### 1.5 字阶：`Type.kt`（230 行）

- `BiliTypography`：**已 `@Deprecated`** 的历史 iOS 规格字阶（34sp/28sp/22sp headline，17sp title，负字距 `(-0.41).sp` 追 SF Pro）
- 现行：`Md3Typography`（M3 标准）+ `BiliMiuixTypography`（Miuix 紧凑），业务层统一读 `MaterialTheme.typography`，由 `resolveMaterialTypography(uiStyle, liquidGlassEnabled)` 自动选
- 玻璃开启时字阶**还会变**（`liquidGlassEnabled` 作为 typography 的 key）：玻璃上的文字需要更大的字重/字号补偿背景透光

### 1.6 色板：`design-tokens/.../theme/Color.kt`（187 行）

- 品牌：`BiliPink 0xFFFA7298`、`BiliPinkDim 0xFFE6688C`、`BiliPinkLight 0xFFFFEBF0`
- 25 色预设种子 `ThemeColors` + 中文名 `ThemeColorNames`（经典蓝 / 樱花粉 / 天空蓝 / 薄荷绿 / 梦幻紫 / 活力橙 / 静谧蓝灰 / 珊瑚红 / 靛蓝 / 翡翠青 / 炽焰红 / 绯樱粉 / 星云紫 / 暮影紫 / 靛空蓝 / 晴空蓝 / 极光青 / 海沫绿 / 新叶绿 / 日光黄 / 琥珀金 / …）
  - 注释明确：**索引是持久化的用户设置，顺序不可变**
- 保留 `iOS*` 前缀命名（iOSSystemBlue/Yellow/…、iOSSystemGray1-6 及 Dark 变体），注释说明"仅供固定色板与明确的领域配色使用"

### 1.7 动效令牌

`design-system` 的 `brand-motion` / `AppMotionTokens`：`standardSpec()`、`expressiveSpec()` 两档，全项目统一（返回动画用 expressive，进入用 standard）。另有 `AppMotionTokens` 配合 `MotionTier`（Normal / Reduced）做无障碍降级。

---

## 2. 界面材质（Material / Surface 体系）

### 2.1 三档材质的顶层抽象

`design-system/src/main/java/com/android/purebilibili/core/ui/AppThemeConfig.kt`

```kotlin
enum class AppChromeMaterial { SOLID, BLUR, LIQUID_GLASS }
fun resolveAppChromeMaterial(liquidGlassEnabled: Boolean, blurEnabled: Boolean, blurAvailable: Boolean): AppChromeMaterial

data class AppThemeConfig(
    blurIntensity: BlurIntensity = THIN,
    headerBlurEnabled: Boolean = true,
    bottomBarBlurEnabled: Boolean = false,
    progressiveTopBlurEnabled: Boolean = true,
    progressiveTopFadeEnabled: Boolean = true,
    hapticFeedbackEnabled: Boolean = true,
    globalTextTapCopyEnabled: Boolean = false,
    uiEntranceAnimationEnabled: Boolean = true,
    globalDragFeedbackEnabled: Boolean = true,
    runtimeVisualGuardEnabled: Boolean = true,
    nativeMiuixPopupsEnabled: Boolean = true,
    liquidGlassEnabled: Boolean = false,
    liquidControlsUseThemeColor: Boolean = false,
)
fun isMiuixNonGlassEnabled(uiStyle, liquidGlassEnabled) = uiStyle == MIUIX && !liquidGlassEnabled
```

`AppChromeMaterial` 是根：**同一个组件在三种材质下的渲染路径完全不同**，但对外 API 不变。

### 2.2 模糊分级：`BlurIntensity`

```kotlin
enum class BlurIntensity { THIN, THICK, APPLE_DOCK }
```

`BlurIntensityVisualPolicy.kt`：

| 强度 | Haze 材质 | 底色 alpha |
| --- | --- | --- |
| `THIN` | `HazeMaterials.ultraThin` | 0.15f |
| `THICK` | `HazeMaterials.thin` | 0.40f |
| `APPLE_DOCK` | `HazeMaterials.thick` | 0.60f |

注释：三档**模糊半径统一 24dp**，差异只在 tint——这是 Miuix 上游的做法，也解释了为什么它看起来不像"三档模糊度"而像"三档浓度"。

`blurIntensityLevelOrder = listOf(THICK, THIN, APPLE_DOCK)`，配合 `resolveBudgetedBlurIntensity(preferred, budget)` 做**预算内降级**。

### 2.3 性能预算：`BlurBudgetPolicy`

这是整个材质体系里最有工程价值的一段。`design-system/.../blur/BlurBudgetPolicy.kt`：

```kotlin
enum BlurSurfaceType { HEADER, BOTTOM_BAR, DRAWER_OR_SHEET, OVERLAY, GENERIC }
data class BlurBudget(maxBlurLevel: Int, backgroundAlphaMultiplier: Float, allowRealtime: Boolean)
fun resolveBlurBudget(surfaceType, motionTier, isScrolling, isTransitionRunning, forceLowBudget = false): BlurBudget
fun resolveBlurInputScale(budget, surfaceType): Float
```

| surfaceType | maxBlurLevel | alpha 倍率 | 非实时采样缩放 |
| --- | --- | --- | --- |
| `HEADER` | 2 | 1.00 | 0.88 |
| `DRAWER_OR_SHEET` | 2 | 1.00 | 0.84 |
| `BOTTOM_BAR` | 1 | 0.95 | 0.82 |
| `OVERLAY` | 1 | 0.92 | 0.84 |
| `GENERIC` | 1 | 0.95 | 0.84 |

- `MotionTier.Reduced` ⇒ `maxBlurLevel = 0`、倍率 ×0.9、`allowRealtime = false`
- `forceLowBudget` 同 Reduced
- 最终 coerce：`maxBlurLevel ∈ [0,2]`、倍率 `∈ [0.70, 1.10]`

**两条注释是这套设计的灵魂**：

```
// 滚动/转场期间绝对不允许改变输入采样网格，否则会出现"清晰度跳变"（clarity pop）。
// 宁可固定低分辨率，也不要动态切换分辨率。
```

也就是说：降级发生在**进入滚动/转场之前**（用 `isScrolling` 布尔值决定），一旦进入就锁定该采样率直到结束。这避免了 Haze 每帧重建 render node。

### 2.4 统一入口：`Modifier.unifiedBlur`

`app/src/main/java/com/android/purebilibili/core/ui/blur/UnifiedBlur.kt`（175 行）

```kotlin
@Composable
fun Modifier.unifiedBlur(
    hazeState: HazeState,
    enabled: Boolean = true,
    shape: Shape? = null,
    surfaceType: BlurSurfaceType = GENERIC,
    motionTier: MotionTier = Normal,
    isScrolling: Boolean = false,
    isTransitionRunning: Boolean = false,
    forceLowBudget: Boolean = false,
    blurStyleOverride: HazeBlurStyle? = null,
    preferWindowBackdrop: Boolean = false,
): Modifier
```

内部决策链（按顺序）：

1. `shouldApplyUnifiedBlur(enabled, surfaceType, globalHeaderBlurEnabled, globalBottomBarBlurEnabled)`
   —— HEADER 要 `headerBlurEnabled`，BOTTOM_BAR 要 `bottomBarBlurEnabled`，其余不拦
2. `shouldAllowRenderEffectBackedHazeEffect(Build.VERSION.SDK_INT)`（API 31+）
3. **运行时视觉守卫**（`RecoverableVisualEffects.kt`）取更保守的一侧：
   ```kotlin
   effectiveMotionTier = minMotionTier(motionTier, guard.effectiveMotionTier)
   effectiveForceLowBudget = forceLowBudget || guard.forceLowBlurBudget
   ```
   注释：调用方只能往更保守方向推，不能覆盖守卫——防止组件作者"私自开高质量"。
4. `BlurStyles.getBlurStyle(resolveBudgetedBlurIntensity(currentUnifiedBlurIntensity(), budget))`
5. `performanceMode = if (inputScale >= 1f) Quality else Fixed(inputScale)`
6. 输入源二选一：
   ```kotlin
   useWindowBackdrop = preferWindowBackdrop && !effectiveForceLowBudget && recoverableEnabled
   input = if (useWindowBackdrop) HazeInput.Backdrop(fallback = HazeInput.Sources(state))
           else HazeInput.Sources(state)
   ```
7. `effectiveStyle = HazeBlurStyle { blurEnabled(recoverableEnabled); blurredEdgeTreatment(edgeTreatment) }
                      .then(blurStyle)`
8. 返回 `(shape?.let { clip(it) } ?: this).hazeBlur(input, effectiveStyle, performanceMode)`

守卫相关 API（`core/ui/blur/RecoverableVisualEffects.kt`）：
```kotlin
fun recoverableBlurEnabled(state: HazeState): Boolean
fun recoverableBlurEnabledState(state: HazeState): State<Boolean>
fun rememberAppScaffoldHazeHandle(userEnabled): AdaptiveScaffoldHazeHandle?
fun Modifier.hazeSourceCompat(state: HazeState): Modifier
internal fun shouldAllowRuntimeShaderBackedHazeEffect(...)
internal fun shouldAllowRenderEffectBackedHazeEffect(...)
internal fun shouldAllowDirectHazeLiquidGlassFallback(...)
internal fun shouldAllowHomeChromeLiquidGlass(...)
fun rememberRecoverableHazeState(...)
```
`core/ui/performance/RuntimeVisualGuardLocals.kt`：`isLowBlurBudgetForced(explicitlyForced = false)`、`ProvideRuntimeVisualGuard(...)`。

> 这套"能力探测 + 预算 + 守卫"三层，是整个项目最值得直接抄的结构。绝大多数 Compose 项目做玻璃只有 `Modifier.hazeEffect(state)`，不处理降级。

### 2.5 背景采样三件套（关键实现）

#### ① `ChromeBackdropSource` — 显示列表录制，而非位图快照

`design-system/.../blur/ChromeBackdropSource.kt`（84 行）

```kotlin
@Stable
class ChromeBackdropSource internal constructor(
    val backdrop: Backdrop, val modifier: Modifier, private val recorded: State<Boolean>
) { val isReady: Boolean get() = recorded.value }

@Composable
fun rememberChromeBackdropSource(
    drawContentToScreen: Boolean = true,
    recordingBackground: Color = Color.Unspecified,
): ChromeBackdropSource
```

实现：

```kotlin
val contentLayer = rememberGraphicsLayer()
val backdrop = rememberLayerBackdrop(onDraw = {
    drawLayer(contentLayer)
    firstRecording.complete(Unit)      // 先真正录到，再对外宣布 ready；绝不靠 delay 猜
})
modifier = Modifier
    .layerBackdrop(backdrop)
    .drawWithContent {
        contentLayer.record {
            background?.let { drawRect(it) }
            this@drawWithContent.drawContent()   // 子页面自己管裁剪
        }
        if (drawContentToScreen) drawLayer(contentLayer)
    }
```

为什么不直接用 Miuix 原生 `layerBackdrop`？文件头注释说明：

> Miuix 的源是"先把子内容画到屏幕，再在 onDraw 里重录一遍"。
> 这里改成"只录一次，屏幕和 backdrop 都重放同一份 display list"。
> 这不是位图快照或帧缓存：子层失效仍会录新内容。
> 重放层不加 RenderEffect / alpha / clip，避免多要求一次离屏栅格化。

对嵌套首页/导航、以及 dock 的"隐藏玻璃采样层"尤其重要——它避免了内容被录两次（一次到屏幕，一次到 backdrop），省一半栅格化开销。

`drawContentToScreen = false` 是给隐藏光学校准层用的：录但不能画（画一个 alpha=0 的层会让渲染器直接跳过）。

#### ② `ContinuousBackdrop` — 连续坐标空间采样

`design-system/.../blur/ContinuousBackdrop.kt`（42 行）

```kotlin
class ContinuousBackdrop(private val source: Backdrop) : Backdrop {
    override fun DrawScope.drawBackdrop(density, coordinates, layerBlock, downscaleFactor) {
        val factor = downscaleFactor.coerceAtLeast(1)
        if (factor == 1) with(source) { drawBackdrop(density, coordinates, layerBlock, 1) }
        else scale(1f / factor, 1f / factor, Offset.Zero) {
            with(source) { drawBackdrop(density, coordinates, layerBlock, 1) }
        }
    }
}
```

解决什么问题？注释：

> 在共同的连续坐标系中采样，再缩入调用方的降采样目标。
> 源不再分别把坐标对齐到低分辨率网格，因此各层没有待补偿的小数余量。

即：多个消费方（顶栏、底栏、指示器）各自有不同 `downscaleFactor`，如果各自把坐标吸附到自身网格，它们采到的像素格里会出现**亚像素错位**——玻璃边缘会"抖"。这里先统一在 factor=1 的连续空间定位，再统一缩放，代价只有一次 `scale` 变换，不产生额外图层或全尺寸位图。

#### ③ `SurfaceOverlayBackdrop` — 完全遮挡时短路

```kotlin
class SurfaceOverlayBackdrop(private val content: Backdrop?, private val surfaceColor: Color) : Backdrop {
    override val offsetResidualX get() = if (surfaceColor.alpha >= 1f) 0f else content?.offsetResidualX ?: 0f
    override fun DrawScope.drawBackdrop(...) {
        if (surfaceColor.alpha < 1f && content != null) content.drawBackdrop(...)
        drawRect(surfaceColor)
    }
}
```
不透明实色顶栏时**完全不采样底层**（省掉一次背景录制），并且把 `offsetResidual` 归零——否则残留的小数余量会让实色栏与页面内容错开半像素。

#### ④ `StableBackdrop` / `CombinedBackdrop`

- `StableBackdrop(source: State<Backdrop>)`：预热源替换 fallback 时，**保持消费方的 Backdrop 身份不变**，只转发当前录制。注释："绝不保留已 dispose 的层、不混合祖先捕获、不冻结旧像素。"
- `CombinedBackdrop(first, second)`：把"页面 backdrop"和"染色 tab 捕获"当成**一个采样源**（各自走自己的 ContinuousBackdrop，不借用第一层的余量补第二层）。

### 2.6 渐进淡出：`ProgressiveFade`（Telegram 的非线性 alpha 阶梯）

`design-system/.../blur/ProgressiveFade.kt`（135 行）

线性渐变的问题：**感知亮度变化不均匀 → 马赫带效应（Mach-Band Effect）**，视觉上出现生硬"切面"。解法是 5 阶非线性停靠点：

```kotlin
val ALPHA_FACTORS = floatArrayOf(1.0f, 232f/255f, 176f/255f, 96f/255f, 0.0f)
// 位置：0.00 / 0.25 / 0.50 / 0.75 / 1.00
// 即 0xFF / 0xE8 / 0xB0 / 0x60 / 0x00
```

```kotlin
fun createStops(baseColor: Color, protectedFraction: Float = 0f): List<Pair<Float, Color>>
fun createVerticalBrush(baseColor, startY = 0f, endY): Brush
fun Modifier.topSolidProgressiveFade(surfaceColor, fadeHeight: Dp = 80.dp, enabled = true): Modifier
@Composable fun TopSolidProgressiveFadeOverlay(surfaceColor, modifier, fadeHeight = 80.dp, enabled, protectedFraction = 0f)
```

`protectedFraction` 的用法很巧妙：在顶部保留一段**纯色平台**（拒绝内容透过），只让尾端渐隐。实现是 `listOf(0f to baseColor) + stops.map { (plateauEnd + (1-plateauEnd)*position) to color }`，`plateauEnd` 被 coerce 到 `[0, 0.85]`。

默认 `fadeHeight = 80.dp`。`topSolidProgressiveFade` 用 `drawWithCache` 在**内容绘制之后**覆盖矩形，所以列表向上滚动时会自然"融化"进背景。

### 2.7 窗口级模糊：两种 API

`design-system/.../blur/AppWindowBlurBehind.kt`（142 行）

```kotlin
@Composable
fun ModalWindowBlurBehindEffect(enabled: Boolean = true, radius: Dp = 24.dp, progress: () -> Float = { 1f })
```

API 31+ 两条路径同时处理：

1. **同 Activity 内的 Dialog/Sheet**：找到宿主 Activity 的 content view，对它施加
   `RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP)`
   通过私有 `object ModalContentBlur` 管理（`WeakHashMap<View, Map<owner, radius>>` 引用计数：`acquire`/`release`，**多个模态同时打开取最大半径**，`appliedRadii` 缓存避免重复设置）；半径每帧由 `ViewTreeObserver.OnPreDrawListener` 刷新（支持 `progress` 动画）
2. **`DialogWindowProvider` 独立窗口**：设 `FLAG_BLUR_BEHIND` + `blurBehindRadius`，并通过 `addCrossWindowBlurEnabledListener` 监听系统"跨窗口模糊"开关，dispose 时**恢复原始 flags/radius**

`progress: () -> Float` 而不是 `Float`，是为避免每帧重组——只读 lambda，在 preDraw 里取值。

### 2.8 自适应 Scaffold Haze

`design-system/.../blur/AdaptiveScaffoldHaze.kt`（67 行）

```kotlin
class AdaptiveScaffoldHazeHandle(val state: HazeState, val blurEnabled: State<Boolean>)
val LocalAdaptiveScaffoldHazeFactory        // app 注入；默认 null = 无模糊能力
val LocalAdaptiveScaffoldHaze
fun shouldAllowScaffoldHazeEffect(sdkInt) = sdkInt >= Build.VERSION_CODES.S
internal Modifier.adaptiveTopBarHazeBlur(handle): Modifier
```

顶栏固定 `HazePerformanceMode.Quality`，注释："顶栏面积小；降采样会造成亮度闪烁"。输入用 `HazeInput.Backdrop(fallback = HazeInput.Sources(state))`（官方 backdrop 渲染器 + 源码回退）。

---

## 3. 液态玻璃（Liquid Glass）

### 3.1 光学配方：三层叠加

所有玻璃表面最终都是这一个配方（`FloatingDockChrome.kt:368` `rememberFloatingDockBackdropEffects`）：

```kotlin
{
    if (effectPaddingPx > 0f) padding = maxOf(padding, effectPaddingPx)
    vibrancy(saturation)               // ① 饱和/对比（colorControls）
    blur(blurRadiusPx, blurRadiusPx)   // ② 背景模糊
    if (shouldDrawLens) lens(           // ③ 边缘折射透镜
        refractionHeight = refractionHeightPx,
        refractionAmount = refractionAmountPx,
        chromaticAberration = chromaticAberration,
    )
}
```

之后 `drawBackdrop(backdrop, shape, effects, highlight, layerBlock, onDrawSurface)` 再叠加：
- `highlight` — 边缘高光（`Highlight` + `BloomStroke`）
- `onDrawSurface` — 表面染色（见下）
- `layerBlock` — 按下形变（横向拉伸）

`onDrawSurface` 的绘制顺序（`FloatingDockChrome.kt:245`）：

```kotlin
if (tint != Color.Unspecified) {
    drawRect(tint, blendMode = BlendMode.Hue)   // 主题色"只染色调不染明度"
    drawRect(tint.copy(alpha = 0.75f))
}
drawRect(surfaceColor)                          // containerColor × surfaceAlpha
if (scrimAlpha > 0f) drawRect(readabilityScrimColor.copy(alpha = scrimAlpha))  // 可读性薄纱
interactionState?.drawHighlight(this)           // 弹性按下高光
```

`BlendMode.Hue` 这一步是关键技巧：**主题染色时保留玻璃的明度层次**，否则染成主题色就变成"半透明色块"，失去玻璃感。

### 3.2 自有 AGSL Shader：`LiquidGlassShader.kt`

项目**没有完全依赖 Miuix 的 lens**，而是自己写了一个轻量 AGSL 边缘折射 shader（`LIQUID_GLASS_SHADER_KEY = "bilipai_liquid_glass"`）：

```glsl
uniform shader img;
uniform float2 resolution, center, size;
uniform float4 radius;                 // 四角半径
uniform float thickness, refract_index, refract_intensity;
uniform float4 foreground_color_premultiplied;

half sdfRect(half2 p, half4 r) {       // 圆角矩形 SDF
  r.xy = (p.x > 0.0) ? r.xy : r.zw;
  r.x  = (p.y > 0.0) ? r.x  : r.y;
  half2 q = abs(p) - size + r.x;
  return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r.x;
}

half4 srcOver(half4 src, half4 dst) {   // 正确的 premultiplied over
  half3 outRGB = (src.rgb + dst.rgb * (1.0 - src.a));
  float outA   = src.a + (1.0 - src.a) * dst.a;
  return half4(outRGB, outA);
}

half4 main(in float2 fragCoord) {
  half2 p = fragCoord - center;
  half sd = sdfRect(p, radius);
  half2 uv = fragCoord;

  if (sd < 0.0 && thickness > 0.0 && refract_intensity > 0.0) {
    half edge     = clamp((thickness + sd) / thickness, 0.0, 1.0);  // 0=边缘 1=内部
    half strength = edge * edge * refract_intensity;                 // 二次衰减：只在边缘一圈折射
    half2 direction = normalize(p / max(size, half2(1.0, 1.0)));     // 从中心向外
    uv += direction * strength * max(refract_index - 1.0, 0.0);
  }
  return srcOver(half4(foreground_color_premultiplied), img.eval(uv));
}
```

设计取舍（注释明确）：
- **只做边缘采样偏移**，底色由调用方 `onDrawSurface` 单独绘制，避免"双重染色"
- `edge * edge` 让折射只集中在贴边的一圈（`thickness` 内），内部完全不偏折——这是"液态玻璃"与"放大镜"的区别
- `max(refract_index - 1.0, 0.0)` 物理化：折射率 1.0 = 不偏折

### 3.3 参数调校：`LiquidGlassTuning`（179 行）

`app/.../feature/home/components/LiquidGlassTuning.kt`

```kotlin
data class LiquidGlassTuning(
    readabilityMode, mode, progress, strength,
    backdropBlurRadius, progressiveBlurRadius, progressiveBlurStartFraction,
    progressiveBlurEndFraction, progressiveBlurCurve,
    surfaceAlpha, whiteOverlayAlpha, saturation,
    refractionAmount, refractionHeight,
    indicatorTintAlpha, indicatorLensBoost, indicatorEdgeWarpBoost, indicatorChromaticBoost,
    contentReadabilityBoost, contentReadabilityScrimAlpha, contentDistortionScale,
    shellChromaticAberrationAmount, indicatorChromaticAberrationAmount, scrollCoupledRefractionAmount,
)
```

**三档模式**由单一 `progress` 切分：

| | CLEAR | BALANCED | FROSTED |
| --- | --- | --- | --- |
| progress | `< 0.34` | `0.34–0.68` | `≥ 0.68` |
| `surfaceAlpha` | 0.40 | 0.40 | 0.54 |
| `whiteOverlayAlpha` | 0.04 | 0.04 | 0.14 |
| `saturation` | 1.5 | 1.5 | 1.24 |
| `refractionAmount` | 24 | 24 | 8 |
| `refractionHeight` | 24 | 24 | 8 |
| `indicatorTintAlpha` | 0.20 | 0.28 | 0.38 |
| `backdropBlurRadius` | 0 dp | 4 dp | 10 dp |

常量：
```kotlin
UPSTREAM_BALANCED_CHROMATIC_CONTROL    = 0.56f
UPSTREAM_INDICATOR_CHROMATIC_ABERRATION = 0.5f
BALANCED_CONTENT_DISTORTION           = 0.45f
MAX_CONTENT_DISTORTION_SCALE          = 1.8f
LIQUID_GLASS_BALANCED_BACKDROP_BLUR_RADIUS_DP = 4f
LIQUID_GLASS_FROSTED_BACKDROP_BLUR_RADIUS_DP  = 10f
```

插值用**分段线性**（`midpointLerp(start, mid, stop, fraction)`，fraction≤0.5 走 start→mid，否则 mid→stop），保证三档锚点数值精确落位。

注释里的调校理由（这批注释是全项目最有价值的产出）：

- **FROSTED 封顶 10dp**：避免落到 Miuix 的 8×/16× 交叉淡化区间（σ≈20 / σ≈44），那会每帧渲染两次完整模糊级联，导致拖动实时滑杆时卡顿。即"宁可少一点磨砂，保住单次 4× pass"。
- **CLEAR 端刻意保留 `surfaceAlpha 0.40` 且不做背景模糊**：这是"水晶玻璃"配方——不模糊、但靠 tint + 饱和 + 折射保证在移动内容上可读。
- **指示器透镜照搬 HyperIsland `LiquidGlassNavigationBar`**：`refractionHeight 10dp / refractionAmount 14dp / chromaticAberration 0.5f`，`indicatorLensBoost / EdgeWarpBoost / ChromaticBoost` 全部强制 `1f`——注释说明原先的 1.35/1.40 系数会把 CLEAR 端折射推到 11.8dp/17.15dp，"比参考长一截，上下色散被摊薄"。
- **`contentDistortionScale` 分段映射**：`0 → 0`、`0.45 → 1.0`、`1.0 → 1.8`。注释说明原先线性映射在 0.81 就饱和，滑杆最后 1/5 完全无效。
- **可读性保护**：`readabilityWeight = midpointLerp(1f, 0.6f, 0.25f, progress)`，`scrimAlpha = boost × 0.34f`。注释说明曾用四次方曲线，结果 62% 默认值被压成几乎不可见的薄纱，改为直接线性。

派生函数：

```kotlin
fun resolveLiquidGlassTuning(progress, advancedSettings, readabilityMode): LiquidGlassTuning
fun resolveLiquidGlassTuning(mode, strength): LiquidGlassTuning      // 兼容旧设置
fun resolveLiquidGlassTuning(style: LiquidGlassStyle): LiquidGlassTuning
fun resolveLiquidGlassIndicatorChromaticAberration(tuning): Float
```

色散分两段（这是 Miuix 上游的设计，被精确复刻）：
```kotlin
// 壳（24dp）保持无彩色，只有移动指示器（10dp/14dp）有色散 0.5
val shellChromaticAmount = if (control > 0.56f)
    (control - 0.56f) / (1f - 0.56f) * 0.5f else 0f
val indicatorChromaticAmount = (control / 0.56f).coerceIn(0f, 1f) * 0.5f
```

### 3.4 外壳渲染：`Modifier.biliPaiFloatingDockShell`（FloatingDockChrome.kt:152）

签名（参数极多，反映三层材质共存）：

```kotlin
@Composable
internal fun Modifier.biliPaiFloatingDockShell(
    backdrop: Backdrop?,
    containerColor: Color,
    pressProgress: Float,
    panelOffsetPx: Float = 0f,
    shape: Shape = CircleShape,
    enabled: Boolean = true,
    blurEnabled: Boolean = false,
    hazeState: HazeState? = null,
    motionTier: MotionTier = MotionTier.Normal,
    isTransitionRunning: Boolean = false,
    forceLowBlurBudget: Boolean = false,
    drawLens: Boolean = true,
    lensIntensity: Float = 1f,
    liquidGlassTuning: LiquidGlassTuning = resolveLiquidGlassTuning(progress = 0.5f),
    interactionLayerBlock: (GraphicsLayerScope.() -> Unit)? = null,
    interactionState: AppElasticPressState? = null,
    tint: Color = Color.Unspecified,
    darkTheme: Boolean? = null,
    readabilitySurfaceColor: Color = Color.Unspecified,
): Modifier
```

渲染分支（严格优先级）：

```
① 玻璃：enabled && samplingBackdrop != null
   → baseModifier(translationX + dropShadow) + drawBackdrop(effects + highlight + layerBlock + onDrawSurface)

② 模糊：blurEnabled
   a) hazeState != null → unifiedBlur(surfaceType = BOTTOM_BAR) + background(containerColor α=0.65)
   b) samplingBackdrop != null → drawBackdrop { blur(25dp, 25dp) } + 实色 α=0.65

③ 实色：background(containerColor.copy(alpha = 1f), shape)
```

注意 ③ 的写法：`if (containerColor.alpha == 0f) containerColor else containerColor.copy(alpha = 1f)` —— 真透明保持透明，其余强制不透明。

基底（三种材质共用）：
```kotlin
baseModifier = this
    .graphicsLayer { translationX = panelOffsetPx }
    .dropShadow(shape = shape, shadow = Shadow(
        radius = 10.dp, color = Color.Black,
        alpha = if (isDark) 0.2f else 0.1f))
```

**嵌套继承**（`FloatingDockChrome.kt:178`）：

```kotlin
val parentGlass = LocalAppGlassContainer.current
    .takeIf { enabled && LocalAppThemeConfig.current.liquidGlassEnabled }
val samplingBackdrop = (if (parentGlass != null) parentGlass.backdrop else backdrop)
    ?.let { rememberContinuousBackdrop(it) }
val inheritDeformation = parentGlass?.ownsDeformation == true
val shouldDrawLens = parentGlass == null && drawLens && resolvedLensIntensity > 0.001f
val blurRadiusPx = if (parentGlass == null) tuning.backdropBlurRadius.dp.toPx() else 0f
val saturation   = if (parentGlass == null) tuning.saturation else 1f
```

→ 嵌套玻璃（dock 里套搜索框）**只做一次模糊 + 一次折射**，内层外壳自动跳过 lens/blur，只保留表面染色与高光。`inheritDeformation = true` 时内层也不做按下拉伸（外层已经拉了）。

按下形变：
```kotlin
val layerBlock = { 
    val s = if (inheritDeformation) 1f 
            else lerp(1f, 1f + 16.dp.toPx() / width, pressProgress)   // 横向拉伸 16dp 绝对值
    scaleX = s; scaleY = s
}
```
用 **16dp 绝对量**除以宽度，而非固定百分比——保证长 dock 与短 dock 的拉伸视觉量一致。

`effectPaddingDp = resolveFloatingDockEffectPaddingDp(refractionAmountDp, pressBloomDp = MIUIX_UPSTREAM_DOCK_PRESS_BLOOM_DP)`：给 backdrop 效果预留 padding，否则折射会画到 shape 外被裁掉。

### 3.5 指示器：透镜 + 色散色带 + 重力高光

```kotlin
internal fun homeIndicatorOpticalScale(restHeightDp: Float): Float =
    (restHeightDp / BottomBarReferenceIndicatorHeightDp).coerceIn(0f, 1f)

internal fun BackdropEffectScope.homeIndicatorLens(
    restHeightDp, progress, tuning,
    shellHeightDp = restHeightDp * 64f / 56f,
    indicatorScaleY = 1f, shellScaleY = 1f,
    chromaticBandHorizontal = false,
) {
    val gapBandDp = resolveFloatingDockChromaticGapBand(indicatorHeight, shellHeight, indicatorScaleY, shellScaleY)
    val reach = homeIndicatorOpticalScale(restHeightDp) * progress.coerceIn(0f, 1f)
    lens(
        refractionHeight = 10.dp.toPx() * reach * tuning.indicatorLensBoost * tuning.contentDistortionScale,
        refractionAmount = 14.dp.toPx() * reach * tuning.indicatorEdgeWarpBoost * tuning.contentDistortionScale,
        depthEffect = true,
        chromaticAberration = resolveLiquidGlassIndicatorChromaticAberration(tuning),
        chromaticEdgeBand = gapBandDp.dp.toPx(),
        chromaticBandHorizontal = chromaticBandHorizontal,
    )
}
```

- `reach` 按指示器实际高度比例缩放光学参数：小指示器（竖排复用）自动弱化——"一个光学配方，所有尺寸复用"
- `chromaticEdgeBand` = 指示器与壳之间的**间隙带**：色散只出现在这条缝里，模拟真实玻璃厚边
- `drawHomeIndicatorSurface`：空闲态 `White/Black α=0.1`，按下时叠 `Black α=0.03×pressed` 并淡出空闲色

**重力高光**（这部分是"活"的玻璃）：

```kotlin
private val iosIndicatorSpecular: Highlight = Highlight(
    width = 1.dp, alpha = 1f,
    style = BloomStroke(
        color = Color.White.copy(alpha = 0.12f),
        innerBlurRadius = 2.0.dp,
        primaryLight = LightSource(LightPosition(0.5f, -0.3f, -0.05f), Color.White, 1f),
        secondaryLight = LightSource(LightPosition(0.5f, 0.8f, -0.5f), Color.White, 0.4f),
        dualPeak = true,
    ),
)

@Composable
internal fun rememberBiliPaiGravityHighlight(base = iosIndicatorSpecular, extraDegrees = 0f, width: Dp = base.width): State<Highlight>
```

- 用 `rememberDeviceTilt()` 读设备重力方向 → 光斑跟着手机倾斜移动（真实玻璃的高光行为）
- `quantizeGravityHighlightDirection(gravityX, gravityY, stepDegrees = 3f)`：**把连续方向量化到 3° 台阶**，避免高光每帧微动造成的高频重绘（`GRAVITY_DIR_THRESHOLD_SQ = 0.01f` 以下视为无方向，回到 `(0,-1)` 正上方）
- 参考点 `LIGHT_REF_X = 0.5f, LIGHT_REF_Y = 0.7f`；调用时传 `extraDegrees = -45f` 做整体偏移
- 只有当 `shouldDrawLens` 为真才给高光 alpha（`0.75f × lensIntensity`），否则 0 —— 注释："内联胶囊（搜索/输入）要同时关掉 shell lens 和它的边缘高光；只留高光会留下 1px '虾线'"

### 3.6 隐藏光学捕获层：`biliPaiFloatingDockCaptureSurface`

```kotlin
@Composable
internal fun Modifier.biliPaiFloatingDockCaptureSurface(
    backdrop: Backdrop, containerColor: Color, panelOffsetPx: Float = 0f,
    shape: Shape = CircleShape,
    liquidGlassTuning: LiquidGlassTuning = resolveLiquidGlassTuning(progress = 0.5f),
): Modifier
```

用 `alpha(0)` 的层把"和可见外壳完全相同的材质"录进 backdrop，供上方浮动指示器采样。`effectPaddingPx = 0f`（隐藏层不需要 padding）。

### 3.7 弹性按压：`appElasticPress` + `AppElasticPressState`

`AppLiquidGlassBackToTopButton.kt:154` 用法：

```kotlin
Modifier.appElasticPress(dragEnabled = true, state = elasticState, transformInBackdrop = glassMotion)
...
biliPaiFloatingDockShell(
    backdrop = backdrop,
    interactionLayerBlock = elasticState.layerBlock,   // 形变进 backdrop 层
    interactionState = elasticState,                   // 高光进 surface 层
    ...
)
```

`transformInBackdrop = glassMotion`（`glassActive && backdrop != null`）—— 只有玻璃模式下才让形变进 backdrop。因为形变如果只作用于前景层、而 backdrop 采样的是未形变内容，就会出现"玻璃形状变了但折射内容没变"的穿帮。

一次性把**弹性形变**（layerBlock）、**按压高光**（interactionState）、**玻璃材质**（shell）三者在同一 Modifier 链上对齐，是这个 API 设计的价值。

### 3.8 玻璃容器继承链：`AppGlassContainer`

```kotlin
class AppGlassContainer internal constructor(val backdrop: Backdrop?, val ownsDeformation: Boolean)
val LocalAppGlassContainer = compositionLocalOf<AppGlassContainer?> { null }

@Composable
fun AppGlassContainerContent(enabled, backdrop, ownsDeformation, content: @Composable () -> Unit)
```

```kotlin
if (!enabled || !LocalAppThemeConfig.current.liquidGlassEnabled) { content(); return }
val parent = LocalAppGlassContainer.current
val inheritedDeformation = ownsDeformation || parent?.ownsDeformation == true
val material = backdrop ?: parent?.backdrop
CompositionLocalProvider(LocalAppGlassContainer provides AppGlassContainer(material, inheritedDeformation)) { content() }
```

`compositionLocalOf`（不是 `static`）——因为 backdrop 会随主题帧更新，需要精确失效。注释："指示器与滑块豁免"（它们是"被透镜放大的内容"，不该再被包一层）。

### 3.9 自适应可读性：`LiquidGlassAdaptiveReadability`

`app/.../feature/home/components/LiquidGlassAdaptiveReadability.kt`（289 行）

```kotlin
internal fun resolveLiquidGlassAdaptiveForegroundTone(...)
@Composable internal fun rememberLiquidGlassAdaptiveReadabilityState(...)
internal fun Modifier.trackLiquidGlassAdaptiveReadability(...)
@Composable internal fun rememberLiquidGlassAdaptiveContentColor(...)
internal fun resolveLiquidGlassContrastGuardedForeground(...)
private fun liquidGlassContrastRatio(foreground: Color, background: Color): Float
private fun Bitmap.averageRelativeLuminance(): Float
```

思路：玻璃背后的内容亮度**不可预知**（可能是白底视频也可能是黑底视频），所以：
1. 采样玻璃区域的位图 → `averageRelativeLuminance()` 算平均相对亮度
2. 按 WCAG 公式算 `contrastRatio`，不达标就翻前景色（白↔黑）或加深 scrim
3. 两种模式：`STABLE`（稳定内容色，只在显著变化时切换，避免闪烁）/ `ADAPTIVE`（自动适配）

这解释了 `LiquidGlassTuning.readabilityMode` 与 `contentReadabilityBoost` / `contentReadabilityScrimAlpha` 的来源。

### 3.10 材质相关的设置项

`app/src/main/java/com/android/purebilibili/core/store/home/LiquidGlassSettingsStore.kt`

```kotlin
enum class GlassPopupPalette(value, label) { DOCK(0, "原有底色"), THEME_FROSTED(1, "主题磨砂") }
enum class LiquidGlassReadabilityMode(value, label) { STABLE(0, "稳定内容色"), ADAPTIVE(1, "自动适配") }
// DataStore keys: liquid_glass_readability_mode / liquid_glass_popup_palette / progressive_blur_renderer
```

`LiquidGlassAdvancedSettings` 暴露给用户的旋钮（从 tuning 反推）：
`contentReadability`、`chromaticAberration`、`contentDistortion`、`progressiveBlurRadius`、`progressiveBlurExtent`、`progressiveBlurCurve`。

---

## 4. 扫码功能

### 4.1 四条链路

| 链路 | 入口 | 用途 |
| --- | --- | --- |
| A. 登录二维码（自己扫别人的） | `LoginComponents.kt:461 QrCodeLoginContent` | 展示本机登录二维码（`BiliPaiQrDecoder` 不参与，纯展示） |
| B. 扫别人的二维码（相机） | `BiliPaiTransferScanner` | 扫 B 站网页/TV 登录码 |
| C. 扫码授权给其他设备 | `OfficialQrAuthorizationContent` + `OfficialQrAuthorizationService` | 已登录状态下替网页/TV 端授权 |
| D. PiliPai 设置迁移 | `LoginScreen.kt:674` 的 `TransferScannerOrNothing` | 同 App 间设置/插件/皮肤二维码传输（含分块） |

### 4.2 相机扫码组件：`BiliPaiTransferScanner`（452 行）

`app/src/main/java/com/android/purebilibili/feature/login/BiliPaiTransferScanner.kt:62`

```kotlin
@Composable
fun BiliPaiTransferScanner(
    onCode: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleShot: Boolean = false,
    acceptAnyQr: Boolean = false,
    onError: (String) -> Unit = {},
    previewModifier: Modifier? = null,   // 显式 opt-in：授权场景把按钮放在预览下方
)
```

**CameraX 装配**：
- `ProcessCameraProvider` + `Preview` + `ImageAnalysis`
- `DEFAULT_BACK_CAMERA`
- `STRATEGY_KEEP_ONLY_LATEST`（**必须**，否则相机队列积压导致 OOM）
- 绑定到 `LocalLifecycleOwner`
- 单线程 `Executors.newSingleThreadExecutor()`

**双重限流**（关键细节）：
```kotlin
now - lastDeliveredAt >= 700L && now - lastAnalyzedAt >= 200L
```
- 200ms：分析节流（每秒最多 5 帧解码，ZXing 单帧成本高）
- 700ms：**投递**节流（防同一张码连续触发多次业务动作）
- 两层分开的理由：解码要够快才跟得上手抖，但业务回调要慢以免重复提交

**帧提取**：
```kotlin
fun ImageProxy.toLuminanceBytes(): ByteArray   // 只取 plane 0 的 Y 通道
```
按 `rowStride` / `pixelStride` 处理，不转 Bitmap——**绕过 YUV→RGB 转换，直接喂 ZXing 的 `PlanarYUVLuminanceSource`**。这是扫码性能的核心优化点（省掉每帧一次色彩空间转换）。

**错误恢复**：连续 3 帧解析失败才报 "相机画面解析失败，请关闭后重新打开扫码"；且 `image.close()` 必须在 `finally`（否则下次 `acquireNextImage` 立刻失败）。

**权限流**：
- 初始检查 `ContextCompat.checkSelfPermission(CAMERA)`
- `ActivityResultContracts.RequestPermission()`
- 拒绝文案 "需要摄像头权限才能扫描二维码" + "打开权限设置" 跳 `Settings.ACTION_APPLICATION_DETAILS_SETTINGS`

**相册识别**：
- `ActivityResultContracts.PickVisualMedia()` / `PickVisualMedia.ImageOnly`（**Photo Picker，不需要读媒体权限**）
- `decodeUriBitmap(context, uri, maxDimension = 2048)`：先 `BitmapFactory.Options.inJustDecodeBounds` 拿尺寸 → 算 `inSampleSize`（`resolveQrAlbumSampleSize`，2 的幂）→ 真解码 → `ExifInterface` 纠正旋转
- 在 `Dispatchers.IO`，`galleryJob?.cancel()` 防竞态，`bitmap.recycle()` 在 `finally`
- 文案："未能识别图片中的二维码，请选择清晰、完整的二维码截图"
  "已识别二维码，但它不是受支持的 B 站登录或哔哩派转移二维码"

**Preview 实现模式**：
```kotlin
// PreviewView 必须用 COMPATIBLE（TextureView）
// SurfaceView 不遵守 View 的裁剪，圆角会失效
```

**手电筒**：`cameraControl.enableTorch(bool)`，按钮跟随 torch 状态。

### 4.3 解码器：`BiliPaiQrDecoder`（101 行）

```kotlin
object BiliPaiQrDecoder {
    fun decode(bytes: ByteArray, width: Int, height: Int, rotation: Int = 0): String?
    internal fun decodeRaw(bytes, width, height, rotation = 0): String?
    fun decodeBitmap(bitmap: Bitmap, acceptAny: Boolean = false): String?
    private fun decodeLuminance(source: LuminanceSource): String?
}
```

- ZXing：`PlanarYUVLuminanceSource` + `HybridBinarizer` + `MultiFormatReader`
- hints：`POSSIBLE_FORMATS = [QR_CODE]`、`TRY_HARDER`
- **反色重试**：`ReaderException` 后对字节做取反再解一次（应对深色二维码 / 负片截图）
- 相册路径：`RGBLuminanceSource` 依次试 `rotation = 0/90/180/270`，临时 Bitmap 用完 recycle

**校验白名单**（`acceptAny` 为 false 时）：
```kotlin
text.startsWith("bilipai://transfer/") || parseBilibiliLoginQr(text) != null
```

### 4.4 安全边界：`parseBilibiliLoginQr`

`app/src/main/java/com/android/purebilibili/feature/login/BilibiliLoginQr.kt`（44 行）——这是全项目安全意识最强的一段：

```kotlin
/** A login request ID, not an account credential. Never fetch an arbitrary scanned URL. */
internal class BilibiliLoginQr(val type: BilibiliLoginQrType, val key: String, val usesAccountPage: Boolean = false) {
    val origin: String get() = if (usesAccountPage) "https://account.bilibili.com" else "https://passport.bilibili.com"
    val confirmationPage: String get() = ...
}

internal fun parseBilibiliLoginQr(raw: String): BilibiliLoginQr? {
    if (raw.length > 4096) return null                                    // ① 长度上限
    val url = raw.trim().toHttpUrlOrNull() ?: return null
    if (url.scheme != "https" || url.port != 443 ||                      // ② 强制 https:443
        url.username.isNotEmpty() || url.password.isNotEmpty() ||        // ③ 禁 userinfo
        url.fragment != null) return null                               // ④ 禁 fragment
    val type = when (url.host to url.encodedPath) {                     // ⑤ host+path 白名单
        "account.bilibili.com"  to "/h5/account-h5/auth/scan-web"        -> WEB
        "passport.bilibili.com" to "/h5-app/passport/login/scan"         -> WEB
        "passport.bilibili.com" to "/x/passport-tv-login/h5/qrcode/auth" -> TV
        else -> return null
    }
    val parameter = if (type == WEB) "qrcode_key" else "auth_code"
    val key = url.queryParameterValues(parameter).singleOrNull() ?: return null   // ⑥ 参数必须唯一
    if (!key.matches(Regex("[0-9a-fA-F]{32}"))) return null                        // ⑦ 32 位 hex
    return BilibiliLoginQr(type, key, usesAccountPage = url.host == "account.bilibili.com")
}
```

为什么这样写：**扫到的二维码内容是不可信输入**。攻击者可以把恶意 URL 编成二维码；如果不校验就"打开"，就是典型的 QR-jacking / phishing 载体。这里的原则是"**只接受完整固定的 host+path，只提取一个 32 位 hex key，然后用代码里写死的域名拼装真实请求 URL**"——扫到的 URL 从头到尾不会被直接请求。注释那句 `Never fetch an arbitrary scanned URL.` 就是这条原则。

对比：绝大多数开源扫码实现是 `onResult -> startActivity(Intent(ACTION_VIEW, Uri.parse(text)))`，直接中招。

### 4.5 取景框动画：`ScannerViewfinderOverlay`

```kotlin
@Composable
private fun ScannerViewfinderOverlay(appear: Float, pulse: Float)
```

`Canvas` 手绘：
- 取景方框：`min(w, h) / 1.5`
- 框外遮罩：`alpha = 0.5f + 0.25f * pulse`
- 白色圆角角标：`stroke = 4.dp * (1 + 0.8 * pulse)`，`cornerLen = 20.dp * (1 + 0.5 * pulse)`
- 拐角用 `quadraticBezierTo` 弯曲，弯曲半径 `min(stroke * 1.5, cornerLen / 2)`——**角标线宽变了弯曲半径跟着变**，所以描边不会在粗细变化时"裂开"

动画：
- 入场 `spring(dampingRatio = 0.8f, stiffness = 250f)`
- 识别脉冲 `spring(dampingRatio = 0.55f, stiffness = 700f)` → `delay(140)` → 投递 `onCode`（让脉冲播完再回调，视觉上有"叮"的落点）

### 4.6 扫码授权状态机

`OfficialQrAuthorizationContent.kt` + `OfficialQrAuthorizationService/ViewModel`

```kotlin
sealed state QrAuthorizationState { Idle, LoginRequired, Checking, Authorizing, Ready, Authorized, Failed }
```

流程：
```
Idle → (扫到码) → Checking（"正在校验登录请求…"）→ Ready（展示账号/地点/类型）
     → (用户确认) → Authorizing（"正在授权…"）→ Authorized（"请查看对方设备的登录结果"）
任意失败 → Failed（展示 message + "重新扫描"）
```

风控设计：
- `Ready` 态展示 `state.type.label`（"网页" / "TV"）、`accountName`、`UID`、`location`
- `locationDiffers` 为真时**红字警告**："该登录请求来自不同地点，请核对是否为你正在使用的设备。"
- 确认按钮 `testTag("qr_authorization_confirm")`（供 UI 测试）
- 确认前有用户协议入口（`UserAgreementReviewDialog`）
- `DisposableEffect { reset(); onDispose { reset() } }` —— 关闭对话框强制重置，防"复用了上次的扫码状态"

`singleShot = true`：授权场景一次只处理一个码，成功后不再接受，避免连续授权。

### 4.7 登录二维码展示（`QrCodeLoginContent`）

`LoginComponents.kt:461`

```kotlin
Box(Modifier.size(262.dp)
    .clip(AppShapes.container(ContainerLevel.Floating))
    .background(palette.qrShell)
    .border(1.4.dp,
        Brush.linearGradient(listOf(
            palette.accentStart.copy(alpha = 0.42f),
            palette.accentEnd.copy(alpha = 0.42f))),
        AppShapes.container(ContainerLevel.Floating))
    .padding(16.dp))
```
- 262dp 外壳 + 16dp 内边距 + 内层 `Color.White` 卡片（**二维码必须纯白底**，任何透明度都会降识别率）
- 1.4dp 渐变描边（两端各 42% alpha 的主题色）
- 状态：`Loading` → LoadingAnimation / `QrCode` → Image / `Scanned` → 二维码 `alpha(0.18f)` + Phone 图标 + "已扫码，请在手机确认" / `Error` → Error 图标 + "重试" / `Success` → SuccessAnimation
- 下方胶囊刷新按钮（`ContainerLevel.Pill`）

---

## 5. 值得直接借鉴的 12 个点

1. **三档材质枚举 `SOLID / BLUR / LIQUID_GLASS`** 作为顶层抽象，而非布尔堆叠 —— 组件 API 不用改，渲染路径全换。
2. **`BlurBudget` 性能预算表**（按 surfaceType × motionTier 查表），而不是在组件里 `if (isLowEnd)`。
3. **采样网格锁死**：进入滚动/转场前决定降级，过程中绝不改分辨率（防 clarity pop）。
4. **`ChromeBackdropSource` 显示列表录制**：子内容只录一次，屏幕和 backdrop 重放同一份 —— 省一半栅格化。
5. **`ContinuousBackdrop`**：多消费方在 factor=1 的连续空间定位再统一缩放，消除亚像素错位抖动。
6. **`ProgressiveFade` 的非线性 alpha 阶梯**（Telegram 0xFF/0xE8/0xB0/0x60/0x00）+ `protectedFraction` 纯色平台。
7. **玻璃配方 = vibrancy + blur + lens 三层 + `onDrawSurface` 表面染色**，且主题染色用 `BlendMode.Hue` 保留明度层次。
8. **嵌套玻璃通过 CompositionLocal 继承已处理材质**，内层不再 blur/lens，形变也继承。
9. **AGSL 边缘折射 shader**：`edge * edge` 二次衰减把折射限制在贴边 `thickness` 一圈内，`max(refract_index - 1, 0)` 物理化。
10. **重力高光量化到 3°**：跟设备倾斜但避免高频重绘。
11. **扫码双重节流（200ms 分析 / 700ms 投递）+ 直喂 Y 平面绕过 YUV→RGB**。
12. **`parseBilibiliLoginQr` 的白名单校验**：只取 32 位 hex key，用写死域名拼 URL，绝不请求扫到的 URL。

---

## 6. 关键文件索引

| 主题 | 路径 |
| --- | --- |
| 模糊策略 | `design-system/src/main/java/com/android/purebilibili/core/ui/blur/BlurBudgetPolicy.kt` |
| 模糊视觉 | `design-system/.../blur/BlurIntensityVisualPolicy.kt`、`BlurStyles.kt`、`BlurIntensity.kt` |
| 背景录制 | `design-system/.../blur/ChromeBackdropSource.kt`、`ContinuousBackdrop.kt`、`SurfaceOverlayBackdrop.kt` |
| 渐进淡出 | `design-system/.../blur/ProgressiveFade.kt` |
| 窗口模糊 | `design-system/.../blur/AppWindowBlurBehind.kt` |
| Scaffold Haze | `design-system/.../blur/AdaptiveScaffoldHaze.kt` |
| 玻璃容器 | `design-system/.../blur/AppGlassContainer.kt` |
| 材质开关 | `design-system/.../ui/AppThemeConfig.kt`（`AppChromeMaterial` / `AppThemeConfig`） |
| 语义色 | `design-system/.../ui/AppSurfaceTokens.kt` |
| 形状 | `design-system/.../ui/AppShapes.kt`、`theme/Shape.kt` |
| 风格枚举 | `design-system/.../theme/UiPreset.kt`（`AppUiStyle`） |
| 字阶 | `design-system/.../theme/Type.kt` |
| 色板 | `design-tokens/src/main/java/com/android/purebilibili/core/theme/Color.kt` |
| 主题装配 | `app/src/main/java/com/android/purebilibili/core/theme/Theme.kt` |
| 统一模糊入口 | `app/src/main/java/com/android/purebilibili/core/ui/blur/UnifiedBlur.kt` |
| 视觉守卫 | `app/src/main/java/com/android/purebilibili/core/ui/blur/RecoverableVisualEffects.kt`、`core/ui/performance/RuntimeVisualGuardLocals.kt` |
| 玻璃参数 | `app/src/main/java/com/android/purebilibili/feature/home/components/LiquidGlassTuning.kt` |
| AGSL shader | `app/.../feature/home/components/LiquidGlassShader.kt` |
| 玻璃外壳 + 指示器 | `app/.../feature/home/components/FloatingDockChrome.kt`（621 行） |
| 玻璃光学基元 | `app/.../feature/home/components/liquid/{Lens,Vibrancy,InnerShadow,StableBackdrop,CombinedBackdrop}.kt` |
| 可读性 | `app/.../feature/home/components/LiquidGlassAdaptiveReadability.kt` |
| 玻璃按钮示例 | `app/src/main/java/com/android/purebilibili/core/ui/components/AppLiquidGlassBackToTopButton.kt` |
| dock 视口 | `app/src/main/java/com/android/purebilibili/core/ui/components/LiquidDockViewport.kt` |
| 扫码组件 | `app/src/main/java/com/android/purebilibili/feature/login/BiliPaiTransferScanner.kt` |
| 解码器 | `app/src/main/java/com/android/purebilibili/feature/login/BiliPaiQrDecoder.kt` |
| 二维码安全解析 | `app/src/main/java/com/android/purebilibili/feature/login/BilibiliLoginQr.kt` |
| 扫码授权 | `app/src/main/java/com/android/purebilibili/feature/login/OfficialQrAuthorizationContent.kt` + `OfficialQrAuthorizationService.kt` + `OfficialQrAuthorizationViewModel.kt` |
| 登录二维码 UI | `app/src/main/java/com/android/purebilibili/feature/login/LoginComponents.kt:461`（`QrCodeLoginContent`） |
| 设置项 | `app/src/main/java/com/android/purebilibili/core/store/home/LiquidGlassSettingsStore.kt` |
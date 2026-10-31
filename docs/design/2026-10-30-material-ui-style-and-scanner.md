# 界面材质、界面风格与应用内扫码（第 15 轮）

> 面向：想知道「设置里的界面材质 / 界面风格到底动了什么」和「相机扫码为什么不再跳出去」的人。
> 三件事都**在这套液态玻璃自己的底座上加档位**，没有把参考项目（BiliPai）的渲染层搬过来。

## 1. 背景与取舍

参考项目 `D:\AAAAAAAAAA\参考项目\BiliPai` 的玻璃是自绘的一套（`LiquidGlassTuning` 三档 + `LocalAppGlassContainer` 嵌套去重 + AGSL 折射）。
本项目的玻璃底座与它同源（`com.kyant.backdrop` 的 `Backdrop` / `effects.lens()` + `RoundedRectangularShape`），
也就是说**渲染管线本来就有**，缺的只是"让用户自己选一档"。

所以本轮的原则是：

- **不移植**：不引入 BiliPai 的渲染代码，只在已有分支上加档位；
- **只动已有回退路径**：三档材质全部落在 `currentGlassCapability()` 已有的枚举里（`Material` / `StaticGlass` / `Backdrop` / `DynamicLens`），
  没有新增任何一颗像素的着色代码 —— 这样即使某一档观感不合意，也只是"少开一个效果"，不会出现新的崩法；
- **不动字号**：风格档只改圆角与行距（见 §3），字号一改，图标与文字的光学对齐全都要重排。

## 2. 界面材质（我的 → 设置 → 外观 → 界面材质）

| 档位 | 实际渲染 | 成本 |
| --- | --- | --- |
| 纯色 | `Material`（不叠玻璃层） | 最省电，文字最清晰 |
| 磨砂 | `Backdrop`（半透明底 + 高斯模糊） | 中；无折射、无色散 |
| 液态玻璃 | `DynamicLens`（折射 + 色散 + 跟手形变） | 最贵；**Android 12 以下自动降级为磨砂** |

- 落地：`manager/AppearanceOptions.kt` 的 `AppMaterialTier` + `resolveEffectiveMaterialTier()`
  （`Liquid` 在 `SDK_INT < S` 降为 `Frosted`，因为折射依赖 `RenderEffect`/AGSL，31/32 上要靠本项目自实现的那条 OpenGL 路径，
  低端机会直接卡）。
- 判定入口只有一个：`ui/system/GlassCompat.kt` 的 `currentMaterialTier()` / `currentGlassCapability()`。
  任何地方要问"现在该画什么玻璃"，都问这里，不要各自读设置。
- 设置页副标题显示的是**实际生效**的档位（`currentMaterialTier().summary`）：
  在不支持液态玻璃的机器上选"液态玻璃"，副标题会如实写"磨砂"，避免"选了没效果"。

### 玻璃质量（同页下方，纯色档隐藏）

| 档位 | 效果 |
| --- | --- |
| 自动 | 按材质档默认开启折射与色散 |
| 极致 | 始终全开 |
| 均衡 | 保留折射，但折射高度 ×0.85、色散强度 ×0.45 |
| 省电 | 关折射与色散，只留模糊 |

关键在于**只缩折射与色散，不缩 `blurDp` 与表面遮罩**：缩放模糊会让画面在切换瞬间"忽然变清晰"，
比少一点折射显眼得多。落地在 `ui/system/GlassMaterial.kt` 的 `qualityScale`。

## 3. 界面风格（同页下一行）

| 档位 | 圆角 token | 行距 / 字距 |
| --- | --- | --- |
| Material 3 | 8 / 12 / 16 / 24 / 28 dp | 原样 |
| 连续圆角 | 10 / 14 / 20 / 28 / 32 dp | 行高 ×1.06，字距 −0.2sp |

- 落地：`ui/theme/Theme.kt` 的 `ContinuousShapes` + `resolveAppShapes()`；`ui/theme/Type.kt` 的 `Typography.withUiStyle()`。
- `CourseSelectorTheme(...)` 新增 `uiStyle` 参数**带默认值并放在 `content` 之前**，现有调用点（末尾 lambda）一行都不用改。
- `withUiStyle()` 逐 15 个槽 copy 而不是只改几个：漏掉的槽会掉回 M3 默认值，顺带把用户选的字体一起冲掉。

## 4. 应用内扫码

原来点"相机扫码"是外跳 zxing 的 `CaptureActivity`：一个库自带的白色系统页面，观感与本应用无关，进入时状态栏还会闪一下。
现在换成**应用内全屏扫码**（`ui/system/scan/InAppQrScanner.kt`，零新依赖 —— 用的就是同一个库已经带进来的 `BarcodeView`）：

- 深色底 + `TextureView` 预览（`SurfaceView` 在 Compose 裁剪/动画下会"挖洞"）；
- 取景框边长取 `min(w,h)/1.5`，四角白色 4dp 角标 + 20dp 圆角长度，中间一条缓慢上下的扫描线（参考项目的取景框比例与角标长度直接沿用）；
- 只解 `QR_CODE`；`isScanInverted = true` 兼容白码黑底；
- 识别后 `delay(140)` 再回调，让用户看清"打中的是哪一格"，而不是瞬间消失；
- 相机权限自己申请（清单里 `CAMERA` 早就声明了，原来靠 `CaptureActivity` 自己申请）；
- 生命周期：`resume()` 只能在 `LaunchedEffect` 里调一次 —— `CameraPreview.resume()` 内部会 `initCamera()`，
  放进 `AndroidView.update` 就成了"每次重组重开一次摄像头"；退出用 `pauseAndWait()`，
  否则相机句柄残留，下次进来报 `Camera already in use`。

相册识别同步做了两处小改：

- 选图从 `ActivityResultContracts.GetContent()` 换成 `PickVisualMedia()`：系统相册选择器，**不需要存储权限**；
- 解码逻辑（`secondclass/QrImageDecoder.kt`）原样保留 —— 它本来就已经有采样到 ≤1024、Exif 旋转、
  4 次旋转 × 3 种 binarizer 全失败后降到 512px 重试。

## 5. 怎么验证

> 本轮改动**没有在本机跑过构建**（沙箱里 Gradle daemon 无法 `CreatePipe`，且此前两次构建被取消），
> 所有 Kotlin 改动都按"只用仓库里已证存在的 API"来写，但仍请以你的构建结果为准。

```bash
./gradlew :app:assembleDebug          # 或 assembleRelease
./gradlew :app:testDebugUnitTest      # 已有 1497 项单测，先确认没打到旧路径
```

装到机器上以后，按这条顺序确认（每步都能一眼看出对错）：

1. 设置 → 外观 → 界面材质：切"纯色"，回桌面再进来，所有玻璃表面都变成不透明；
2. 切"磨砂"：有模糊、没有边缘折射（斜着看图标边缘不会"变形"）；
3. 切"液态玻璃"（Android 12+ 机器）：图标边缘有折射、快速滑动时形变跟手；
4. 玻璃质量切"省电"：折射与色散消失、模糊还在；
5. 界面风格切"连续圆角"：卡片圆角变大、正文行距变松，字号**没变**；
6. 第二课堂 → 活动中心 → 扫码签到 → 相机扫码：不再跳出白色页面，取景框与深色底是应用自己的样式；
7. 同一处选"从相册识别"：拉起的是系统相册选择器，不弹存储权限。

## 6. 已知取舍

- 液态玻璃在 Android 12 以下强制降级为磨砂：那条路要靠本项目自实现的 OpenGL 折射，成本与收益不划算（详见 [API 31/32 折射](../glass-lens-api32.md)）。
- 风格档不改字号、不改间距基数：想改字号得连带重排所有图标对齐，不在本轮范围。
- 扫码器没有做"连续扫多张"：签到场景一次只要一张，`singleShot` 默认开。
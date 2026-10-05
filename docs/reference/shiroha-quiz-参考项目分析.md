# 参考项目分析：Shiroha Quiz（shiroha-quiz-main_2）

> 分析对象：`D:\AAAAAAAAAA\参考项目\shiroha-quiz-main_2\shiroha-quiz-main`
> 版本：原生版 `v0.9.13.1-native`（versionCode 213，2026-10-04）；Web 版 `0.8.8-alpha`（versionCode 54）
> 分析日期：2026-10-04
> 许可：GPL-3.0 —— **注意：GPL 具有传染性，不可直接复制其代码进本项目，只能借鉴思路与交互设计。**

> ⚠️ 目录说明：`参考项目\shiroha-quiz-main\`（无 `_2`）是旧副本（`v0.9.11-native`），本文只描述 `_2` 这一份。
> 两版差异：4 个 Kotlin 文件（`ShirohaAppShell.kt` / `ImportScreen.kt` / `MeScreen.kt` / `StandardImportFormatScreen.kt`）
> \+ 新增 `app/src/native/assets/`（4 份 md + 1 份 xlsx）。

---

## 一、项目定位与仓库结构

一个**多端刷题工具**：自导入题库 → 练习 → 考试 → 复盘。仓库是 monorepo，两条公开线：

| 线 | 技术 | 包名 | 定位 |
| --- | --- | --- | --- |
| Android 原生版（主推） | Kotlin + Jetpack Compose + Material3 | `com.reqir.shirohaquiz` | 手机/平板日常刷题 |
| Web 版 | HTML/CSS/JS（无构建） | —— | 电脑端题库整理 |
| WebView 壳（历史遗留） | WebView | `com.yiqiu.shirohaquiz` | 已不主推 |

Android 工程用 **productFlavor** 区分这两条线（`web` / `native`），**共享同一份 `app/`**，源码分别放在 `src/native/`、`src/web/`。

```
shiroha-quiz/
├── apps/
│   ├── android/app/src/native/java/com/yiqiu/shirohaquiz/   ← 本文重点（40,740 行 Kotlin / 80 文件）
│   │   ├── MainActivity.kt          启动、开屏、主题注入
│   │   ├── ui/app/ShirohaAppShell.kt 应用外壳（导航中枢）
│   │   ├── ui/screens/              18 个页面
│   │   ├── ui/components/           通用组件
│   │   ├── ui/theme/                设计 Token
│   │   ├── ui/text/LatexDisplayFormatter.kt  LaTeX 渲染
│   │   ├── state/                   QuizRepository（6113 行）+ QuestionSearch
│   │   ├── importer/                题库导入解析（7158 行）
│   │   ├── ai/                      AI 客户端 + Prompt
│   │   ├── security/                Key 加密存储
│   │   ├── sync/webdav/             WebDAV 云备份
│   │   ├── document/                MinerU OCR + 文档识别
│   │   └── assets/                  ★ v0.9.13 新增：离线文档 + Excel 模板
│   └── web/
├── docs/                            双端文档
└── test/                            外部回归 + Web 测试
```

**代码规模 Top（Kotlin）**：`QuizRepository` 6113 → `ImportScreen` 5011 → `PracticeScreen` 4019 → `MeScreen` 1744 → `ExamScreen` 1463 → `WrongBookScreen` 1026 → `BankReviewScreen` 1002 → `ShirohaAiClient` 924。

---

## 二、技术栈与构建

| 项 | 值 |
| --- | --- |
| compileSdk / targetSdk / minSdk | 34 / 34 / **26**（Android 8.0） |
| Kotlin JVM target | 17 |
| Compose BOM | `2024.06.00`（compose-compiler 1.5.14） |
| 关键依赖 | okhttp 4.12.0、material-icons-extended、core-splashscreen 1.0.1、**work-runtime-ktx 2.9.1（仅 native）** |
| 测试 | junit4、Robolectric 4.12.2、kxml2、coroutines-test；`testOptions.unitTests.isIncludeAndroidResources = true` |
| Release | `isMinifyEnabled = true` + `isShrinkResources = true`，签名读 `keystore.properties` |
| 权限 | **只有 `INTERNET` 一个** |
| 打包 | `assembleNativeRelease` 后自动 `Copy` 到 `outputs/shiroha-quiz/Shiroha-Quiz-v{ver}-native-release.apk` |
| 图标 | `activity-alias` 两套（默认 / Shiroha 模式），运行时切换 |

> 与我们项目对比：我们用 compileSdk 37 / minSdk 24 / Compose BOM 2026.01.00，比它新；它 minSdk 26 更保守。

---

## 三、应用外壳与导航（`ShirohaAppShell.kt`）

**没有用 Navigation Compose**，而是**自写的单 `enum` 状态机**。

```kotlin
private enum class MainTab(val title: String, val icon: ImageVector, val showInBottomBar: Boolean = true)
```

共 **23 个 Tab**，其中 **4 个进底栏**：

| 底栏顺序 | Tab | 图标 | 说明 |
| --- | --- | --- | --- |
| 1 | `Home`「首页」 | Dashboard | |
| 2 | `Practice`「练习」 | School | |
| 3 | `Import`「导入」 | ImportExport | |
| 4 | `Me`「我的」 | Settings | |

其余 19 个 `showInBottomBar = false`：`Exam` / `BankList` / `QuestionSearch` / `BankDetail` / `BankReview` / `WrongBook` / `Favorites` / `Records` / `RecordDetail` / `AppearancePreference` / `PracticePreference` / `PracticeQuickEdit` / `WrongBookPreference` / `AiSettings` / `DocumentRecognition` / `DataManagement` / `CloudBackup` / `StandardFormat` / `About`。

**导航机制（值得借鉴的三个设计）**：

1. **返回栈是「编码字符串列表」**：`AppRouteSnapshot(tab, bankId, recordId)` 用 `\u001F` 拼接成字符串存进 `rememberSaveable` 的 `List<String>`。因为要跨进程重建（`rememberSaveable` 只能存 Parcelable/基本类型），**编码成 String 是让返回栈能在进程重启后存活的关键**。我们项目用的是 `ReviewPage` sealed interface + `remember`，进程重启会丢栈 —— 这点值得借鉴。
2. **`fallbackBackTarget()` 兜底**：返回栈空时，二级页面按「语义归属」回到某个一级页（`BankDetail → BankList`、`RecordDetail → Records`、所有设置页 → `Me`），四个根 Tab 返回 null。所以**永远不会有「按返回键直接退出 App」的突兀感**。
3. **`BackHandler(enabled = hasBackTarget)`**：只有真有返回目标时才拦截系统返回键。

**手机 / 平板自适应**（不用 `WindowSizeClass`，用 `BoxWithConstraints`）：

```kotlin
val useSideNavigation = QuizRepository.tabletSideNavigationEnabled && maxWidth >= 600.dp && maxHeight >= 480.dp
```

宽屏时 `bottomBar` 整个不渲染，改用 `NavigationRail`（88.dp 宽）+ `VerticalDivider`；否则用 `BottomAppBar`。

**全局任务胶囊**：`DocumentTaskCapsule`（OCR 任务进度）与 `WebDavTaskCapsule`（备份进度）挂在底栏上方 / 侧栏底部，**在任何页面都能看到并点击跳转**，且在自己那个页面时不显示。这是一个很好的「长任务可见性」设计。

**页面转场**：`AnimatedContent` + `fadeIn + slideInVertically(6px)` 180ms `CubicBezier(0.22, 1, 0.36, 1)`，退出仅 `fadeOut` 90ms（**进出时长不对称**，进场慢、出场快，观感更利落）。

**背景**：整个内容区是三层竖直渐变 `BgGradientTop → Middle → Bottom`。

---

## 四、设计系统（`ui/theme/`）

### 4.1 颜色：**可变全局 object**（不是 CompositionLocal）

```kotlin
object ShirohaColors {
    var isDarkMode: Boolean = false          // ← 全局可变开关
    val BgApp: Color get() = if (isDarkMode) ... else ...   // ← 每个颜色都是 getter
    ...
}
```

`ShirohaQuizTheme(darkTheme) { }` 里先 `ShirohaColors.isDarkMode = darkTheme`，再套 `MaterialTheme`。

- **优点**：任何 Composable 直接 `ShirohaColors.Xxx`，不用 CompositionLocal 层层透传。
- **代价**：`isDarkMode` 是普通 `var` 而非 `mutableStateOf`，**切主题时靠外层 `MaterialTheme` 换 `colorScheme` 触发重组来带动**；如果某处只读 `ShirohaColors.Xxx` 且不受 MaterialTheme 影响，理论上不会刷新。
- 我们项目用的是 `LocalWallpaperAppearanceColors` CompositionLocal（能跟随壁纸取色），**两者取向不同**：它要固定品牌色，我们要壁纸适配。

**色板要点**：品牌主色 `#4F7CFF`（暗色下 `#89A7FF`）；语义色四组（Success/Warning/Danger 各带 Soft 变体）；文本三级（Primary/Secondary/Tertiary）；**卡片有一整套不同透明度的白**（`CardWhite84/86/78/72/68/62`）用于叠层，暗色下换成不透明深蓝 —— 这是它做「玻璃感」的方式：**半透明白 + 1dp 描边，而不是真正的模糊**（不用 RenderEffect，minSdk 26 友好）。

### 4.2 尺寸 / 动效 Token（`ShirohaRadius` / `ShirohaSpacing` / `ShirohaDimens` / `ShirohaMotion`）

| Token 组 | 值 |
| --- | --- |
| `ShirohaRadius` | Sm 14 / Md 18 / Lg 24 / Xl 30 / Pill 999 |
| `ShirohaSpacing` | Xs 4 → Xxxl 32（8 档） |
| `ShirohaDimens` | 页边距 `PageHorizontalPadding = 20dp`；卡片高 132；选项标签圆 36；底栏项 56 高、图标 21；空态图 140；加载图 112 |
| `ShirohaMotion` | 页面转场 180/90ms；底栏 140ms；Hero 浮动 1700ms；加载缩放 900ms；开屏 420/1450ms |

### 4.3 字体（`Type.kt`）

全部 `FontFamily.SansSerif`（**不内置字体**），9 档：displaySmall 34/40、headlineMedium 28/34、headlineSmall 26/32、titleLarge 22、titleMedium 18、bodyLarge 16/24、bodyMedium 14/22、bodySmall 12/18、labelLarge 13。
> 我们项目内置了苹方/自定义字体 —— 它没有这个能力。

### 4.4 通用组件（`ui/components/`，1456 行）

| 组件 | 作用 / 关键参数 |
| --- | --- |
| `GlassCard` | **万能卡片**：`animated`（是否淡入上移，受 `cardAnimationEnabled` 全局开关控制）、`contentPadding`；圆角 Lg + 1dp `LineSoft` 描边 + `elevation = 0` |
| `ShirohaHeader` | 三段式页头 `kicker`（品牌小字，主色）+ `title`（displaySmall）+ `subtitle` |
| `StatusChip` | 胶囊状态标签，`selected` 切换主色/灰 |
| `ActionPillButton` | 胶囊按钮，`primary` 决定实心/描边；`fillWidthContent` 控制图标+文字是否居中 |
| `QuizOptionCard` | ★ **选项卡**：`resultStyle ∈ {Neutral, Correct, Wrong}` 三态配色；`compact` 走紧凑行（无圆形标签，直接 `A.` 前缀）；字号取自 `QuizRepository.optionFontSizeSp()`；文本过 `LatexDisplayFormatter.format()`；**带 `semantics { stateDescription = ... }` 无障碍状态** |
| `IllustrationHeroCard` | 欢迎卡 + 看板娘插画，插画做**无限上下浮动**（±2.6dp / 1700ms）；`shirohaModeEnabled` 关掉时不显示插画且内容居中 |
| `EmptyStateIllustration` / `LoadingIllustration` | 空态/加载态（插画 + 文案，加载图有缩放呼吸） |
| `MetricGlassCard` / `ShortcutGlassCard` / `UploadPanel` / `NoticeCard` | 指标卡 / 快捷卡 / 上传区 / 提示条 |
| `ShirohaDangerConfirmDialog` | 危险操作二次确认（`AlertDialog`） |
| `QuizSessionExitIconButton` | 练习/考试页右上角圆形退出按钮 |
| `shirohaNoRippleClickable` | 无涟漪点击（**全项目统一去掉 Material 涟漪**，靠卡片缩放/变色反馈） |

**专项组件**：`MultiBlankComponents`（多空填空编辑）、`QuestionImageComponents`（图片题）、`AiAnalysisFillPanel`（AI 补解析面板）、`DocumentImagesCard` / `DocumentTaskCapsule` / `WebDavTaskCapsule`。

---

## 五、页面清单与布局（`ui/screens/`，18 个文件 / 19,612 行）

| 页面 | 行数 | 布局骨架与要点 |
| --- | --- | --- |
| `HomeScreen` | 446 | `BoxWithConstraints` + 条件 `verticalScroll`。① `ShirohaHeader` ② `IllustrationHeroCard` ③ `TodayStatusCard`（今日学习状态：当前范围 + 今日练题 + 待复习 + 「继续练习」「模拟考试」两颗按钮）④ `HomeShortcutGrid` 2×2 宫格（错题本/收藏夹/题库管理/学习记录，**每格带数量大字**）。★ **自适应技巧**：宫格用 `weight(1f)` 吃满剩余高度，并用 `onSizeChanged` 实测高度，若小于 `224.dp * fontScale(≤1.2)` 就切 `compactHomeLayout = true`（改为可滚动 + 宫格定高）—— 这是**「优先撑满、放不下才滚动」**的写法，比无条件 `verticalScroll` 更稳 |
| `PracticeScreen` | **4019** | 见下方专项 |
| `PracticeQuickEditScreen` | （含在 PracticeScreen.kt 1686 起） | 练习中快速改题并写回题库 |
| `ImportScreen` | **5011** | 见下方专项 |
| `ExamScreen` | 1463 | 三态：`ExamSetupPanel`（配置）→ `ActiveExamPanel`（答题）→ `FinishedExamPanel`（成绩）。配置项：题量、时长、分组模式、**按题型分别设题量与分值**（`examTypeOrder` + `defaultTypeScore`）；答题有 `ExamAnswerCardDialog` 答题卡、`ExamCollapsedStatusPill` 折叠状态条、`questionSwipeNavigation` 滑动切题 |
| `MeScreen` | 1744 | **一个大文件塞了 5 个页面**：`MeScreen`（我的首页）、`DataManagementScreen`（数据管理）、`AppearancePreferenceScreen`（外观偏好）、`WrongBookPreferenceScreen`（错题本设置）、`PracticePreferenceScreen`（刷题偏好）、`AiSettingsScreen`（AI 设置） |
| `BankListScreen` | 501 | 题库列表，支持**分组**（`DEFAULT_BANK_GROUP_NAME = "未分组"`）、`BankCard`、`CompactBankStateChip` |
| `BankDetailScreen` | 580 | 题库详情：摘要 + 题目预览 + **斩题管理**（`SlashedBankChip` / `SlashedQuestionListCard`）+ `QuestionEditDialog` |
| `BankReviewScreen` | 1002 | 逐题核对/修正，含 `ReviewFilteredJumpList`（按筛选结果跳题）+ 题型筛选芯片 |
| `WrongBookScreen` | 1026 | 错题本：`WrongBookScopeDialog`（当前题库/全部题库）、`WrongBookReviewCountDialog`（复习题量）、`WrongBookSmartReviewSection`（智能复习）、`WrongQuestionPreview` |
| `FavoriteScreen` | 472 | 收藏夹，同样有作用域对话框 |
| `RecordsScreen` | 519 | 学习记录列表（`RecordCard`）+ 作用域筛选 |
| `RecordDetailScreen` | 322 | 单轮记录逐题复盘（`RecordSummaryCard` + `QuestionResultCard`） |
| `QuestionSearchScreen` | 531 | 题目搜索：`SearchScopeDialog`、`QuestionSearchResultCard`、`FullQuestionInfo` |
| `DocumentRecognitionScreen` | 815 | MinerU OCR 任务页 |
| `WebDavBackupScreen` | 214 | 云端备份；`WebDavAutoBackupCard` 单独成文件 |
| `StandardImportFormatScreen` | 785 | ★ **本版重点**：离线文档阅读器 |
| `AboutScreen` | 100 | 关于 |

### 5.1 PracticeScreen 专项（最值得借鉴的页面）

顶层结构（40 个 Composable / 纯函数）：

```
PracticeScreen(126)
 ├─ PracticeSetupPanel(1950)      ← 开练前配置
 │   ├─ CompactPracticeSetupHero(2334)
 │   ├─ PracticeSetupStepCard(2387)  ← 分步卡片
 │   ├─ CompactPracticeChip / CompactPracticeActionChip
 │   └─ CustomQuestionCountDialog(3332)
 ├─ [答题区]
 │   ├─ PracticeProgressCard(2773) / PracticeProgressCapsule(2895)
 │   ├─ QuizOptionCard（来自 components）
 │   ├─ SubjectiveAnswerEditor(2660)     ← 简答题输入
 │   ├─ AnswerResultCapsule(2728)        ← 判题结果胶囊
 │   ├─ FavoriteQuestionIconButton(2423) ← 收藏
 │   ├─ QuickEditQuestionIconButton(2448)← 快速编辑
 │   ├─ SlashQuestionRoundButton(2472)   ← 斩题
 │   └─ MissingAnswerAiReferenceCard(3452) ← 无答案题 AI 临时参考答案
 ├─ PracticeAnswerSheetDialog(3025)  ← ★ 答题卡（Dialog 形态）
 │   ├─ PracticeAnswerSheetShortcut(2932)   ← 顶部入口
 │   ├─ PracticeAnswerSheetLegendChip(3127) ← 图例
 │   └─ PracticeAnswerSheetNumberChip(3173) ← 题号格子
 ├─ SingleQuestionAiAnalysisPanel(3553) ← ★ AI 单题解析 + 追问
 │   ├─ SingleQuestionAiConversationCard(3655)
 │   └─ SingleQuestionAiResultCard(3699)
 ├─ PracticeCompletionCard(2567)
 ├─ UnsubmittedPracticeConfirmDialog(3238) / PracticeExitConfirmDialog(3262)
 └─ BatchSubmitConfirmDialog(3306)
```

**关键机制**：

- **两种练习模式**（`PRACTICE_MODE_INSTANT` / `PRACTICE_MODE_BATCH`）：即时反馈（选完立刻判）vs 批量提交（整组提交）。
- **批量模式有分组**：`practiceBatchGroupSize`（默认 10）+ `practiceBatchGroupStartIndex`，配合 `practiceDraftAnswers: Map<questionKey, List<String>>` 暂存。
- **滑动切题**：`Modifier.questionSwipeNavigation`（2957 行处定义），练习与考试各自一份实现。
- **答题卡是 Dialog 而不是内嵌面板** —— 这避开了我们踩过的「同轴嵌套滚动」坑，值得借鉴（我们项目最后是用 `FlowRow` 内嵌解决的）。
- **选项打乱与显示解耦**：`PracticeDisplayOption` + `practiceDisplayOptions()` + `practiceDisplayKey(index)` + `practiceAnswersForDisplay()` —— **只打乱显示顺序，字母、答案、记录都不变**。这是「选项乱序」的正确做法（打乱显示与打乱数据分离）。
- **AI 临时参考答案**：`practiceQuestionHasUsableReferenceAnswer()` 判断题目是否缺答案 → `practiceTemporaryAiReferenceAnswer()` 现算 → `practiceTemporaryAiReferenceDisplayAnswer()` 展示。默认关闭（`ai_missing_answer_reference_enabled`），**不写回题库、不进错题本**。

### 5.2 ImportScreen 专项（5011 行，全项目第二大）

导入页同时承载了「选文件 / 粘贴 / 双文件 / 预览 / 逐题核对 / 编辑 / AI 批处理」六件事：

- `NativeImportSummary` / `NativeImportPreview` / `NativeQuestionReviewScreen` —— 导入结果三段式（摘要 → 预览 → 逐题核对）。
- `ReviewQuestionEditScreen` / `ReviewQuestionEditorContent` —— 逐题编辑器（题干/选项/答案/解析/题型/章节）。
- `ReviewQuestionAssistBlocks` / `AiReviewSuggestionCard` —— AI 建议卡（按 `riskLevel` 分色）。
- `AiImportRangeSheet` / `AiImportScopeOptionRow` / `AiImportTotalChoice` —— **AI 批处理的范围选择**（全部 / 异常题 / 缺解析题 / 自定义题号区间）。
- `AiImportBatchProgressCard` —— 批处理进度（含 `formatElapsedSeconds` 已耗时）。
- `FullImportTextEditorScreen` / `LargeImportTextPreview` —— 大文本编辑与预览。
- 状态：`AiImportBatchState` + `sealed interface AiImportBatchResponse`。

---

## 六、状态层（`state/QuizRepository.kt`，6113 行）

### 6.1 架构：**单例 object + Compose 可观察状态**

```kotlin
object QuizRepository {
    val banks = mutableStateListOf<QuizBank>()
    val wrongBook = mutableStateListOf<WrongQuestionEntry>()
    val slashedQuestions = mutableStateListOf<SlashedQuestionEntry>()
    val favoriteQuestions = mutableStateListOf<FavoriteQuestionEntry>()
    val studyRecords = mutableStateListOf<StudyRecord>()

    var activeBankId by mutableStateOf<String?>(null)
    var practiceIndex by mutableStateOf(0)
    var selectedAnswer by mutableStateOf<List<String>>(emptyList())
    ...
}
```

**没有任何 ViewModel**。全部状态挂在 `object QuizRepository` 上，靠 `mutableStateOf` / `mutableStateListOf` / `mutableStateMapOf` 驱动重组。
- **优点**：跨页面共享极简，无需 DI、无需 hilt、无需把状态沿参数链传。
- **代价**：全局单例、无生命周期、无法并行测试多实例、`init(context)` 是隐式的。

### 6.2 数据模型（字段级）

```kotlin
data class QuizBank(id, name, questions: List<Question>, groupName = "未分组")

data class Question(
    id = UUID, number = "", type: QuestionType, question: String,
    options: List<Option> = [], answer: List<String> = [],
    blankAnswers: List<List<String>> = [],     // 多空填空：每空一个「主答案+备选」列表
    analysis = "", category = "",              // category = 章节/分区
    images: List<QuestionImage> = [],
    score: Double? = null, subject = "", grade = "", difficulty = "",
    knowledgePoints: List<String> = [], tags: List<String> = [],
    source = "", sourceFileId = "", version = 1,
    reviewStatus = "approved", aiConfidence: Double? = null,
    warnings: List<String> = []
)
data class Option(key: String, text: String)
data class QuestionImage(id, localPath, sourceName, order, width, height, sizeBytes)

enum class QuestionType { SINGLE, MULTIPLE, JUDGE, BLANK, SHORT }   // 5 种
```

> 对比我们项目：我们的 `ReviewQuestion` 只有 单/多/判断 三种题型 + `chapter` 字段。**它的 `blankAnswers: List<List<String>>`（多空 + 每空备选答案）和 `images` 是我们没有的**。

```kotlin
data class WrongQuestionEntry(
    bankId, bankName, question, lastAnswer, source, timestamp,
    wrongCount = 1, rightCount = 0, reviewRightCount = 0,
    streakCorrectCount = 0,            // 连续答对计数
    lastWrongAt, lastCorrectAt: Long?,
    status: String = WrongStatus.REVIEWING.label,
    lastReviewedAt: Long?, nextReviewAt: Long?,   // 间隔复习
    reviewLevel: Int = 0
)
enum class WrongStatus(val label: String) { REVIEWING("复习中"), NOT_MASTERED("未掌握"), MASTERED("已掌握") }

data class StudyRecord(id, bankId, bankName, source, title, total, correct, timestamp,
    durationSeconds: Int?, autoSubmitted, startedAt: Long?,
    earnedScore: Double?, totalScore: Double?,
    questionResults: List<StudyQuestionResult>, scopeType: String?, scopeName: String?)

data class StudyQuestionResult(question, userAnswer, userBlankAnswers, correct,
    answerText, earnedScore: Double?, maxScore: Double?, autoScored, sourceBankId, sourceBankName)

data class ExamSummary(total, answered, correct, durationSeconds, remainingSeconds,
    autoSubmitted, totalScore = 0.0, earnedScore = 0.0)
```

### 6.3 持久化

- **`SharedPreferences`（`shiroha_quiz_native`）+ `org.json`**，没有 Room/SQLite。
- 键名常量 **60+ 个**，分四类：
  - **数据**：`banks` / `active_bank_id` / `wrong_book` / `slashed_questions` / `favorite_questions` / `study_records`
  - **练习偏好**：`practice_preferred_*`（题量模式、自定义题量、顺序、题型、模式、批大小）、`practice_sequential_progress`、`practice_recite_sequential_progress`
  - **开关**：`practice_auto_submit_enabled` / `practice_auto_next_enabled` / `practice_recite_mode_enabled` / `practice_slash_enabled` / `practice_quick_edit_enabled` / `practice_option_shuffle_enabled` / `practice_prefer_unseen_enabled` / `screen_reader_assist_enabled` / `exam_auto_next_enabled` / `exam_option_shuffle_enabled` / `wrong_book_smart_review_enabled` / `swipe_navigation_enabled` …
  - **外观 / AI**：`dark_theme_enabled` / `card_animation_enabled` / `shiroha_mode_enabled` / `tablet_side_navigation_enabled` / `question_font_size_mode` / `option_font_size_mode` / `compact_options_enabled`；`ai_provider` / `ai_api_base_url` / `ai_model_name` / `ai_*_enabled` / `ai_only_anomaly` / `ai_require_confirm` / `ai_max_questions` / `ai_timeout_seconds` / `ai_refactor_max_chars`
- **有版本迁移**：`KEY_QUESTION_ID_INTEGRITY_MIGRATION_VERSION = 1` —— 修复题目 id 完整性的一次性迁移。
- **备份走 ZIP**：`ZipOutputStream` / `ZipInputStream` + `SafeZipReader`（防 zip slip）。

### 6.4 业务口径

- **练习范围**：`PRACTICE_SCOPE_BANK`（单库）/ `PRACTICE_SCOPE_GROUP`（分组跨库）；`currentPracticeScopeLabel()` / `currentPracticeScopeSummary()`。
- **顺序练习进度**：`practice_sequential_progress` + `SEQUENTIAL_START_{LAST,FIRST,CUSTOM}`，**背题模式单独一份进度**（`practice_recite_sequential_progress`）。
- **错题三态**：`REVIEWING`（复习中）/ `NOT_MASTERED`（未掌握，错 ≥2 次）/ `MASTERED`（已掌握）。`WrongStatus.normalize()` 对非法值兜底到 `NOT_MASTERED`。
- **智能复习**：`WrongBookSmartReviewSummary(total, notMastered, masteredReview)` + `nextReviewAt` / `reviewLevel`；`todayWrongBookSmartReviewCount()` 给首页用。**可整体关闭**（关掉后首页显示的是 `wrongBookActiveCount()` 而不是「今日待复习」）。
- **错题作用域**：`WRONG_BOOK_SCOPE_CURRENT_BANK` / `WRONG_BOOK_SCOPE_ALL_BANKS`。
- **记住错题本状态**：`remember_wrong_book_state` 下存 scope / filter / sort / 题型 / 复习题量模式 / 自定义题量 **六个维度**，下次进来原样恢复。
- **分组**：`DEFAULT_BANK_GROUP_NAME = "未分组"`。

---

## 七、导入解析层（`importer/`，7158 行）

### 7.1 解析主流程（`QuizImportParser`，480 行）

```
原始文本
 → QuestionTextNormalizer.normalize()          归一化（换行、全角、空白）
 → 判断 hasAnswerSection（是否有集中答案区）
 → SharedStemQuestionFallbackParser.rewrite()  共用题干局部兜底（可选）
 → QuestionParser.parseStandardFirst()          标准优先解析  ← 主路径
 ─┬─ 有答案区 → AnswerSectionParser.parse() → DualFileMerger.mergeAuto() 合并
  ├─ ExcelQuestionTableParser.parse()           表格候选
  └─ FullPaperFallbackStrategy.shouldTry/parse() 整卷兜底候选（按需）
 → chooseBestCandidate()                        在候选间择优
 → buildResult()                                生成 ImportResult + 诊断
```

★ **核心设计：多候选 + 择优**，而不是单条流水线。每个候选带 `name` / `questions` / `warnings` / `score`（`ImportStrategyScorer`），最后 `chooseBestCandidate()` 决定用哪个。

**择优规则（很讲究，值得学）**：

- 表格候选接管条件 `shouldUseSpecializedTable`：题量 ≥ 主候选的 80% **且**（错误更少 **或** 分数高 30+）。
- 整卷候选接管条件 `shouldUseFullPaper`：必须先 `looksLikeFullPaper`，然后比较**六组指标** —— 错误数、疑似误判为主观题的客观题数、题干过短（≤3 字且无选项）数、前言污染数、结构合法客观题数，**并显式禁止「把明确的主观题降级成客观题」**（`downgradesExplicitSubjectiveQuestions`）。
- 允许「整卷凭补回多题接管」的严格前提 `strongMissingQuestionRecovery`：多出的题量 ≥ `primaryCount/10`（至少 2）**且**合法客观题增量相同**且**错误/短题干/前言污染都不退化**且**合法客观题占比 ≥ 70%。
- ★ 注释里写明了一条踩坑史：「避免重新回到按总分自由覆盖标准结果」—— 即**曾经用总分择优，导致整卷兜底把标准解析的正确结果覆盖掉**。

**其它值得注意的实现**：

- **答案区不预先切分**：注释明确「Keep the full text here: QuestionBlockSplitter can skip the answer section and resume later real questions. Pre-splitting would silently drop questions that appear after a mid-paper answer section.」—— 即**整卷中途出现答案区后，后续真实题目不能丢**。
- **前言污染检测**：`pureFrontMatterFragmentRegex` 一个超长正则匹配「绝密/密卷/注意事项/请用2B/答题卡/监考老师/祝各位考生…」，把这类伪题块整块丢弃（`isPureFrontMatterPseudoQuestion`：只对无选项无答案无解析的主观题块生效）。
- **提前终止检测** `detectSuspiciousEarlyTermination`：用 `QuestionBlockSplitter.detectLikelyQuestionNumbers()` 扫原文可能题号，若「已解析最大题号之后仍有 ≥3 道连续题号」就报 ERROR 警告「疑似解析提前终止」。**这是防「静默漏题」的关键护栏**。
- **判断题补选项** `repairQuestionForDisplay`：`JUDGE` 且无选项时自动补 `A. 正确 / B. 错误`（**只在展示层补，不改判分数据**）。
- **诊断输出** `ImportDiagnostics(normalizedLength, blockCount, answeredCount, candidateCount, notes)`，`notes` 含题型分布 + Top5 候选的「题数/答案数/分数」——**把「为什么选了这个策略」暴露给用户**。

### 7.2 解析器文件分工

| 文件 | 行数 | 职责 |
| --- | --- | --- |
| `QuestionBlockSplitter` | 852 | 题块切分 + `detectLikelyQuestionNumbers` |
| `TextImportDecoder` | 747 | 文件字节 → 文本（编码探测） |
| `AnswerParser` | 669 | 答案文本解析（`1.A 2.BC` 这类） |
| `StandardQuestionParser` | 534 | 标准格式解析 |
| `QuizImportParser` | 480 | ★ 总编排 |
| `FullPaperFallbackStrategy` | 428 | 整卷真题兜底 |
| `ExcelQuestionTableParser` | 373 | 表格题库（xlsx/csv） |
| `MultiBlankQuestionParser` | 370 | 多空填空 |
| `QuestionParser` | 330 | `parseStandardFirst` |
| `CompactQuestionRepair` | 321 | 紧凑/粘连题修复（`hasCompactOptionSequence`） |
| `DualFileMerger` | 276 | 题目+答案双文件合并 |
| `CodeLikeTextGuard` | 253 | 代码类文本保护（选项里含代码不误判） |
| `SharedStemQuestionFallbackParser` | 227 | 共用题干/材料题 |
| `AnswerSectionParser` | 177 | 集中答案区 |
| `SectionTitleParser` | 120 | 章节标题 |
| `QuestionTypeLabelParser` | 92 | 题型标记 |
| `QuestionTextNormalizer` | 66 | 文本归一化 |
| `ChapterScopeParser` / `ParserUtils` | 14/48 | 小工具 |
| `validate/ImportValidator` | 92 | 校验 → `ImportWarning` |
| `score/ImportStrategyScorer` | 42 | 候选打分 |
| `model/MultiBlankSupport` | 139 | 多空填空数据支持 |
| `assets/QuestionImportAssetExtractor` | 282 | docx 内嵌图片提取 |
| `assets/QuestionImageBinder` / `QuestionImageMarker` | 102/46 | 图片绑定与占位标记 |

### 7.3 详细识别规则

`apps/android/app/src/native/assets/docs/import_format_support.md`（1611 行）是**逐条规则手册**，章节即规则清单：

```
0 核心导入原则 / 1 最推荐的标准格式 / 2 支持的题号格式（2.1 数字题号）/ 3 支持的题型标记
4 支持的章节标题 / 5 支持的选项格式 / 6 支持的答案标签 / 7 支持的答案分隔符
8 支持的括号答案格式 / 9 支持的客观题答案值 / 10 支持的解析格式
11 支持的主观题/面试题格式 / 12 支持的集中答案区 / 13 支持的填空题格式
14 支持的材料题格式 / 15 支持的图片标记 / 16 不建议或暂不稳定的格式
```

> 这份 md **就是**「解析能力契约」。我们项目的 `docs/adaptation/*.md` 是同一思路，但它是**随 APK 分发、App 内可读**的。

---

## 八、AI 层（`ai/`，1050 行）

### 8.1 服务商与配置

只预置 **两家**（`aiProviderPreset`）：

| provider | apiBaseUrl | modelName |
| --- | --- | --- |
| `DeepSeek` | `https://api.deepseek.com` | `deepseek-chat` |
| `OpenAI 兼容` | `https://api.openai.com/v1` | `gpt-4o-mini` |

外加**手填任意地址与模型**。`shouldReplaceAiEndpoint()` / `shouldReplaceAiModel()` 决定「切服务商时要不要覆盖用户已填的值」——**只在当前值是空白或正好等于某个预置值时才覆盖**，避免把用户手填的地址冲掉。这是个很实用的细节。

> 我们项目刚刚收缩为**只支持 DeepSeek**（用户要求）。它这里是「2 预置 + 自定义」。
> 注意它默认模型仍写 `deepseek-chat` —— 该模型名在 2026-10 已下线（详见我们 `CHANGELOG` 的核实记录）。

### 8.2 五个内置 Prompt（`AiPrompts.kt`，全部中文、全部要求纯 JSON）

| 常量 | 任务 | 输出 JSON 顶层 |
| --- | --- | --- |
| `AI_REVIEW_SYSTEM_PROMPT` | **题库核对**（判断识别是否异常，不重新出题） | `{"items":[...]}` |
| `AI_ANALYSIS_SYSTEM_PROMPT` | **补解析 / 主观题参考作答** | `{"items":[...]}` |
| `AI_SINGLE_QUESTION_ANALYSIS_SYSTEM_PROMPT` | **单题解析**（练习页） | `{"questionId","suggestedAnswer","matchesLocalAnswer","analysis","confidence","needsReview","warning"}` |
| `AI_SINGLE_QUESTION_FOLLOW_UP_SYSTEM_PROMPT` | **单题追问** | `{"reply","revisedAnalysis","confidence","needsReview","warning"}` |
| `AI_REFACTOR_SYSTEM_PROMPT` | **整库结构化清洗** | `{"mode","cleanedText","cleanedAnswerText","questions":[...],"notes":[...]}` |

**Prompt 里最有价值的几条设计**（可直接借鉴到我们项目的 AI 答疑）：

1. **核对任务列出 12 条具体异常类型**（题型识别错、选项缺失/合并/拆分错、答案与选项不匹配、多选答案数异常、判断题误识别为单选、解析与答案冲突、题干不完整、格式污染、**题干残留答案标记**、**分卷章节重复题号导致答案串题**、**选项混入页眉页脚/图片占位符**、答案区未合并）。
2. **三级风险 + 可应用性**：`riskLevel ∈ {auto_safe, needs_confirm, hard_error}` + `canApply`（**只在程序能直接采纳时才 true**；硬错误/信息不足/需要脑补题干时必须 false）。**这解决了「AI 建议能不能自动落地」的判定问题**。
3. **明确禁止 AI 越界**：「不要重新解题，不要根据常识推断答案，不要擅自改变正确答案」；「不确定时标记为需要人工确认」。
4. **结构化兜底策略**：`requestedMode = direct_questions | clean_text` —— **优先让 AI 直接吐结构化题目数组，只有结构化不可靠时才退回「清洗后的文本」**。注释写明「不要先把题目重新拼成标准文本再让客户端猜题块」。这与我们项目「让模型输出 JSON 再校验」是同一思路，但它**多了一层 clean_text 降级**。
5. **图片占位符必须原样保留**：`[[SHIROHA_IMAGE:img_0001]]` 这类标记要求 AI 不得删除/改写/移动 —— **结构化处理与二进制资源绑定的解耦手段**。
6. **主观题不给唯一答案**：简答只给「参考要点」，面试题要求「表明态度—分析原因—提出措施—总结提升」结构，且**禁止虚构机构/姓名/真实事件**。
7. **`confidence ∈ {HIGH, MEDIUM, LOW}` + `needsReview`** 双字段，把「不确定」显式暴露给界面。

### 8.3 客户端（`ShirohaAiClient.kt`，924 行）

`object ShirohaAiClient`，返回类型：`AiReviewSuggestion` / `AiAnalysisSuggestion` / `AiSingleQuestionAnalysis` / `AiSingleQuestionFollowUpResult` / `AiRefactorResult` / `AiSingleQuestionConversationMessage`。基于 okhttp。

---

## 九、安全：Key 加密存储（`security/`，212 行）

```kotlin
interface LocalSecretStore { put / get / contains / remove }

class SecureSecretStore(context) : LocalSecretStore
```

- 密文文件：**`context.noBackupFilesDir / shiroha_secure_secrets.json`**
  → ★ 关键点：`noBackupFilesDir` **不进 Android Auto Backup、也不会被本应用的 ZIP 备份带走**（注释明确写了这个目的）。
- 算法：`AES/GCM/NoPadding`，Key 在 **AndroidKeyStore**（`KeyGenParameterSpec` + `setRandomizedEncryptionRequired(true)`）。
- **每个 key 一个独立 Keystore 别名**：`"shiroha_secure_secrets_v1_" + sha256(key).take(8).hex` —— 单条泄露不牵连其他条。
- 存储结构：`{ "<key>": { "alias": ..., "iv": base64, "value": base64 } }`。
- **原子写**：`android.util.AtomicFile`（`startWrite` / `finishWrite` / `failWrite`）—— 防写一半掉电损坏。
- 读取**全包 `runCatching`**，失败返回 null（换机/密钥库重置时不崩）。

> 与我们项目高度一致（我们也用 AndroidKeyStore + AES/GCM + Base64(iv+out)）。差异：我们单 key，它**逐 key 派生别名**；我们用 SharedPreferences，它用 `noBackupFilesDir` + AtomicFile —— **后者更严谨**（我们那份密文会被 ZIP 备份带走，虽然解不开）。

---

## 十、WebDAV 云备份（`sync/webdav/`，1625 行）

| 文件 | 行数 | 职责 |
| --- | --- | --- |
| `WebDavBackupManager` | 602 | 备份/恢复编排 |
| `WebDavClient` | 554 | WebDAV 协议（PROPFIND/PUT/GET/DELETE/MKCOL） |
| `WebDavModels` | 293 | 数据模型 |
| `WebDavAutoBackup` | 107 | 自动备份调度（`WebDavAutoBackup.schedule(context)`，在 `MainActivity.onCreate` 调用） |
| `WebDavSettingsStore` | 69 | 配置持久化 |

能力：配置 → 测试连接 → 主动建目录 → 完整 ZIP 上传 → 远程列表 → 校验下载 → 确认删除；自动备份默认关闭，可配**网络条件 / 充电条件 / 旧备份保留数**。
恢复支持**副本导入**与**内容覆盖**两条路，覆盖前自动安全备份、失败回滚、中断恢复日志（详见 `docs/native/MinerU与WebDAV功能实施记录.md`）。
依赖 `androidx.work`（**仅 native flavor**）。

---

## 十一、文档识别（`document/`，1887 行）

| 文件 | 行数 | 职责 |
| --- | --- | --- |
| `DocumentRecognitionManager` | 817 | ★ 全局单例任务管理器（`init` / `task` / `shouldShowGlobalTaskCapsule` / `pendingImportDraft` / `consumePendingImportDraft`） |
| `MinerUClient` | 344 | MinerU 在线 OCR API |
| `DocumentRecognitionStore` | 191 | 任务持久化（App 重启后任务还在） |
| `MinerUResultDecoder` | 174 | 结果解码 |
| `MinerUModels` | 107 | 模型 |
| `DocumentImageEditor` / `DocumentImageBinding` | 48/33 | 图片排除/排序/合并、绑定到题 |

流程：免费/精准 PDF 识别 → 上传进度 → 暂停/继续查询 → 任务持久化 → **全局任务胶囊**（任何页面可见）→ 精准 ZIP 图片提取/排除/排序/指定题目及相邻图片手动合并 → 复用导入流程与图片绑定。

---

## 十二、LaTeX 渲染（`ui/text/LatexDisplayFormatter.kt`，447 行）

`LatexDisplayFormatter.format(text)` —— 纯函数，在 `QuizOptionCard` 等处对题干/选项文本做数学公式的**显示态转换**（把 LaTeX 片段转成可读 Unicode / 排版文本）。我们项目没有这个能力。

---

## 十三、v0.9.13 / v0.9.13.1 本版新增（相对 `v0.9.11`）

### 13.1 内置离线文档（★ 最有借鉴价值的一块）

**资产**（`app/src/native/assets/`，128 KB）：

| 文件 | 行数 | 用途 |
| --- | --- | --- |
| `docs/standard_format.md` | 610 | 标准题库格式示例 |
| `docs/import_format_support.md` | 1611 | 导入格式支持说明（规则手册） |
| `docs/import_strategy_guide.md` | 479 | 导入策略与使用指南 |
| `docs/import_parser_notes.md` | 898 | 题目导入解析方法说明 |
| `templates/Shiroha_Quiz_Excel题库导入通用模板.xlsx` | —— | Excel 通用模板 |

**实现**（`StandardImportFormatScreen.kt`，785 行，纯 Compose 自写 Markdown 渲染器）：

```kotlin
private data class LocalDocSpec(val title: String, val desc: String, val assetPath: String)
private val LOCAL_IMPORT_DOCS = listOf(4 份文档)
```

- **加载**：`context.assets.open(spec.assetPath).bufferedReader(UTF_8).readText()` —— **纯离线，不联网**。
- **自写 Markdown 解析器** `parseMarkdown(source): ParsedMarkdown`：
  ```kotlin
  private sealed class MarkdownBlock {
      data class Heading(level, text); Paragraph(text); ListItem(text)
      Quote(text); Code(text); Table(headers, rows); object Divider
  }
  private data class ParsedMarkdown(val blocks: List<MarkdownBlock>, val anchors: Map<String, Int>)
  ```
  支持：标题（h1/h2/h3）、段落、无序/有序列表、引用、代码块（``` 围栏）、**表格**（`|…|` + 分隔行 `---` 校验）、分隔线（`---`）、行内 `**粗体**` / `__粗体__` / `` `代码` `` / `[链接](url)` / `![图](url)`。
- ★ **隐藏 HTML 锚点**（本版新增）：
  ```kotlin
  private val HTML_ANCHOR = Regex("""^<(?:span|a)\s+(?:id|name)=["']([^"']+)["'][^>]*>(?:\s*</(?:span|a)>)?\s*$""", IGNORE_CASE)
  ```
  单独成行的 `<span id="xxx"></span>` **不渲染成正文**，而是记进 `anchors[id] = blocks.size` —— 于是**文档里的目录链接可以跳转到对应章节**，而锚点本身对用户不可见。
- **表格渲染自适应**：`MarkdownTableView` 按列数/宽度在 `CompactMarkdownTable` 与 `WideMarkdownTable` 之间切换。
- **行内富文本**：`MarkdownInlineText` + `buildMarkdownAnnotatedText` + `appendWithoutHtml`（剥掉残留 HTML 标签）。
- **模板导出**：`copyAssetToUri(context, TEMPLATE_ASSET, uri)` + `ActivityResultContracts.CreateDocument(XLSX_MIME)` —— **从 assets 复制到用户选的位置**，同样不联网。
- **内部文档互跳**：`resolveLocalDocSpec(target)` 把「标准题库格式示例」这类中文标题或文件名解析回 `LocalDocSpec`，于是文档之间可以互相跳转。

### 13.2 其它本版改动

- **导入页与标准导入格式页紧凑化改版**（`ImportScreen` / `StandardImportFormatScreen` 各减行）。
- **设置项精简**（`MeScreen`）。
- ★ **统一 Excel 格式口径**：旧版 `.xls` / `.xlsm` 不再支持，需先另存为 `.xlsx` 或 `.csv`。
  → **这条与我们项目直接相关**：我们 `用户手册.md` 里也写了 Excel 导入，需确认口径一致。

---

## 十四、与本项目（zhengfang-apk / 淮师教务助手）的对照

| 维度 | Shiroha Quiz | 本项目 | 判断 |
| --- | --- | --- | --- |
| 定位 | 通用刷题工具（多端） | 教务助手 + 复习模块 | 复习模块可借鉴 |
| 状态管理 | 单例 `object QuizRepository` + `mutableStateOf` | `ReviewActivity` 内 `remember` + `ReviewDatabase`(SQLite) | **不照搬**：单例无生命周期，且我们已用 SQLite 存题 |
| 数据存储 | SharedPreferences + org.json | SQLite（`ReviewDatabase`） | 我们的更适合大量题目 |
| 导航 | 自写 enum 状态机 + **编码字符串返回栈**（可跨进程） | `ReviewPage` sealed interface + `remember`（进程重启丢栈） | ★ **返回栈持久化值得借鉴** |
| 题型 | 5 种（+多空 + 图片） | 3 种（单/多/判断） | 多空/图片是可选扩展 |
| 答题卡 | **Dialog** | 内嵌 `FlowRow` | 各有取舍；Dialog 天然避开嵌套滚动 |
| 选项乱序 | 显示序与数据序分离（`PracticeDisplayOption`） | 无 | ★ **若要加乱序，按这个做法** |
| AI | 2 预置 + 自定义；5 个 Prompt；三级风险 + `canApply` | 仅 DeepSeek；单题解析 + 追问 | ★ **Prompt 的「禁止越界」条款可借鉴** |
| AI Key 存储 | `noBackupFilesDir` + AtomicFile + 逐 key 别名 | SharedPreferences + 单 key | ★ **`noBackupFilesDir` 更严谨** |
| 离线文档 | ★ 自写 Markdown 渲染 + assets 内置 + 锚点跳转 | `用户手册.md` → `users_manual.json` + 原生 Compose 渲染 | **同一思路**；它的**表格 + 锚点跳转**可补 |
| 布局自适应 | `BoxWithConstraints` + `onSizeChanged` 实测后降级 | `BoxWithConstraints` | ★ **「先撑满、放不下才滚动」的写法值得借鉴** |
| 平板 | `NavigationRail`（600×480 阈值） | 无 | 可选 |
| 题库导入 | ★ 多候选择优 + 详细规则手册 | JSON 导入 | ★ **若要加强导入，这是范本** |
| 测试 | 外部回归 63 用例 + JVM 54 项 | 1162 项 JVM 单测 | 我们的单测覆盖更广 |

### 可优先借鉴的四点（按性价比排序）

1. **返回栈编码持久化** —— 改动小，直接解决「进程重启丢返回栈」。
2. **`noBackupFilesDir` + AtomicFile 存密文** —— 改动小，安全性与健壮性提升明确。
3. **离线文档的表格渲染 + 锚点跳转** —— 我们的手册渲染器若缺这两项，可补。
4. **AI Prompt 的「禁止越界 + 三级风险 + canApply」** —— 我们刚收缩到 DeepSeek，正好可以借它的条款强化「AI 不得改答案、不确定要标记」。

### ⚠️ 不可直接复制的部分

- **GPL-3.0 传染性**：任何代码级复制都会让本项目需以 GPL 发布。**只借鉴思路、交互与文档结构，不抄代码**。
- `deepseek-chat` 模型名（已下线）。
- `.xls` / `.xlsm` 不支持的口径（我们的导入若支持，需自行确认，不要被它带偏）。

---

## 十五、关键文件速查

| 想了解 | 看这个 |
| --- | --- |
| 导航 / 外壳 / 自适应 | `ui/app/ShirohaAppShell.kt` |
| 设计 Token | `ui/theme/Tokens.kt`、`Type.kt`、`Theme.kt` |
| 通用组件 | `ui/components/ShirohaComponents.kt` |
| 练习页（答题卡/斩题/AI 单题） | `ui/screens/PracticeScreen.kt` |
| 导入页（预览/核对/AI 批处理） | `ui/screens/ImportScreen.kt` |
| 离线文档阅读器 | `ui/screens/StandardImportFormatScreen.kt` |
| 数据模型与全部业务口径 | `state/QuizRepository.kt` |
| 解析总编排 | `importer/parser/QuizImportParser.kt` |
| 解析规则手册 | `assets/docs/import_format_support.md` |
| AI Prompt | `ai/AiPrompts.kt` |
| Key 加密 | `security/SecureSecretStore.kt` |
| WebDAV | `sync/webdav/WebDavBackupManager.kt` |
| 版本变更 | `CHANGELOG.md`、`docs/native/原生开发进度.md` |

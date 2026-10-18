# 更新日志 (Changelog)

> 1.0.36 – 1.0.66 期间本文件未随版本更新（曾被 `.gitignore` 屏蔽），这一段的变更请查阅 Git 提交历史。
> 自 1.0.68 起，每个版本的更新日志以 `release-notes/vX.Y.Z.md` 为唯一数据源，由 CI 扇出到本文件、GitHub Release 与应用内更新提示。
> 1.0.67 未发布：该 tag 的流水线在版本号校验步骤失败，未产出任何 Release，内容顺延至 1.0.68。

## [1.3.0] - 2026-10-18

### 新增

- **考前提醒改为后台巡检时顺带刷新考试列表**（`academic/ExamWatcher`）：此前 `ExamReminderScheduler.reconcile` 只在打开「成绩 → 考试」页或开机读缓存时被调用，从不点开那个 Tab 的用户本地永远没有考试数据，考前提醒等于不存在。现在挂在已有的 12 小时消息巡检上（不新增闹钟），写入同一份考试缓存并重排提醒；6 小时节流 + 2.5 秒单次上限，排在消息巡检**之前**以免被其预算饿死；失败时按本地缓存重排一次。
- **「继续上次练习」**：退到首页后回来能接着刷（`review/PracticeResume`）。存档只保留"重建会话所需的最小信息 + 作答快照"，题目仍由同一组参数确定性重抽；新开一轮或交卷即作废。`PracticeLaunch` 的编解码从 Activity 私有函数收进数据类，供 saved state 与本地存档共用。
- **题库题目搜索**：逐题编辑页支持按题干 / 章节 / 答案字母过滤，并显示命中数。
- **错题本导出**：把当前筛选与排序后的那一批写成纯文本，走系统分享（`ACTION_SEND`）。
- **限时考试中途不揭晓**：判分后只提示「已记录作答」，对错与解析留到交卷后 —— 考试中看到"答错了 + 正确答案"等于提前泄题。
- **答题卡按章分组**：多章节题库里按「首次出现」顺序插入章节分隔行（纯函数 `answerSheetSections`，格子下标与题目下标严格一致），300+ 题时不用再靠翻几十屏找题。
- **AI 追问可跨题保留上下文**：AI 设置页新增开关（默认关）。开启后换题不清空问答历史，可以就着上一题的讲解继续追问；代价是每题多带一段历史 token。
- **跨题库总错题本**：首页「全部错题」入口，不必先想这道错题在哪个题库里。
- **题库支持 Excel / CSV 导入**：题库仓库新增「从 Excel / CSV 导入」。表头认中英文别名（题干 / 题目 / stem…），选项可以写成一列用 | 分隔，也可以分成 A/B/C/D 四列；题型不写时按答案长度推断单选还是多选。逐行校验，跳过的行会带行号报出来。

### 修复

- **课程提醒开了却从不响**：提醒用的「时间基准」缺了节次时间。`ScheduleRoute` 首次回写日历只给 `firstWeekDate`，而补救迁移 `ScheduleCalendarStore.migrateLegacy` 带着「已有第一周日期就整条跳过」的守卫，于是 `periodStarts/periodEnds` 永远补不进来；`CourseReminderPlanner` 取不到 `periodStarts[startPeriod]` 就返回 `NeedsTime`，**一条闹钟都不排**。修法：`ScheduleReminderScheduler.timeBase` 按 `ScheduleRepository` 的口径合并「设置里的节次时间打底、日历里存过的值覆盖」，老用户下一次 reconcile 即自动恢复；`migrateLegacy` 从「有日期就跳过」改成合并。
- **「通知发不出去，事件却已被消费」——四条推送链路的同类顺序缺陷**：共同形态是「先记账、后确认送达」，通知权限被关时把事件静默吞掉，用户重新打开通知也补不回来。逐条改为先确认送达再推进状态：成绩推送（`GradeWatcher`）不推进基线、教务消息（`MessageCenterNotifier`）不写已播报集合、电费提醒（`YktAlertNotifier`）的 `lastNotifiedAtMillis` 只在真正发出后落盘、考试提醒（`ExamReminderScheduler`）把账号归属判定与 notify 成功都挪到消费计划之前。
- **题库练习：同一道题在一次会话里被重复记入数据库**。作答是"判分即落库"，而「再想一下」只撤回内存判定、不回滚已写入的 `records` / `wrong_book` —— 「确认答案 → 再想一下 → 再确认」会把同一题记两遍，`wrong_book.streak` 被单次会话刷满（点三次就能刷成「已掌握」），详情页正确率因 `SUM(correct_count)` 超过 `COUNT(*)` 而恒等于 100%。修法：`PracticeSession.markRecorded()` 本轮去重，判据随快照存取。
- **题库练习：刚答错的题被排到第二天**。`recordAnswers` 用 `dueAt(masteryLevelOf(0, wrongCount), now)` 计算到期时间，而该函数对"刚入册"返回 Learning（间隔 1 天）—— 于是刚做完错题的人打开错题本看到「今日待复习 0 题」。改为答错即 `due_at = 0`（立即到期），与 `ReviewScheduling` 里 "New → 立即进入复习队列" 的设计意图一致。
- **题库练习：作答快照每次点选项就全量重新编码**（320 题连答 320 次 ≈ 5 万次字符串拼接）。改为 250ms 去抖，并把"恢复"与"保存"两个效果的执行顺序解耦。
- **题库练习：正确率每次重组全量重算**。`PracticeSession` 改为增量维护 `correctCount`。
- **题库练习：`daily_stats` 空壳表残留**。`DB_VERSION` 5 → 6，升级时 `DROP TABLE IF EXISTS daily_stats`。

### 新增（续）

- **错题复习提醒**（`review/ReviewReminderScheduler`）：每天 20:00 检查一次错题本，有到期题就发通知。默认关闭 —— 这是新增的一条打扰，由用户主动打开。按**固定钟点**而非固定间隔排期（固定间隔会随作息偏移一天天漂），复用项目已有的链式单次闹钟模式。
- **数据备份与恢复**（`backup/BackupManager`）：「我的 → 数据与安全」可导出/还原一个 zip，含题库、答题记录、错题本、收藏、自定义课程与各项设置。**不含**账号密码、会话 Cookie、AI API Key —— 备份是明文文件，会离开设备。恢复走 SAF，不需要存储权限。
- **课表直接写入系统日历**（`utils/ScheduleCalendarWriter`）：与「导出 .ics」并存。优先写本地日历（写进账户日历会触发同步上传）；事件带「校园助理」标记，重复导入只清理带标记的那些；不设日历提醒（课表提醒由应用自己的闹钟负责）。
- **电费提醒支持度数型费项**：金额与电量各有独立阈值（默认 20 元 / 20 度），按费项实际返回的单位自动选用。单位认不出来时不提醒 —— 把 312 度当成 312 元会得出「余额充足」的结论，然后宿舍就断电了。
- **隐私政策**（`assets/privacy_policy.json`）：11 章，逐条对应 Manifest 的 14 项权限与全部网络出站域名。写明「数据只存在本机」「无第三方 SDK」「AI 请求只发往 DeepSeek 且不含成绩学号」「备份不含凭据」。
- **AI 悬浮球显示「AI」字样**：原先用对话气泡图标，在刷题场景里更像「反馈」或「聊天室」，看不出是「问 AI 这道题」。

### 修复（续）

- **成绩订阅：列表位移导致整批误报**。`gradeIdentity` 把该行在列表里的**下标**拼进了身份，而教务列表是插入式的 —— 重修/补考成绩插到中间，其后每一行下标 +1，全部被当成「新成绩」播报一遍（30 门课里插一门补考 = 通知「你有 27 门新成绩」）。改为「课程稳定字段 + 组内出现序号」，列表位移不再改变身份。
- **会话 Cookie 明文落盘**。它是等同于登录态的凭据（拿着 JSESSIONID 就能读学号、成绩、课表、余额，不需要密码），原先直接写进 `course_selector_prefs`，账号列表 JSON 里还有每个账号一份。改为 AES/GCM 加密存储（复用系统密钥库），并主动删除老版本的明文行；退出登录与删除账号时一并清除。
- **后台刷新拿到的续期 Cookie 从未落盘**。教务每访问一次可能下发新 Cookie，后台巡检（消息中心/成绩/考试）走的是内存 CookieJar，新值只留在内存 —— 进程下次冷启动会从持久化恢复出一张**可能已作废的旧票**，表现为「刚收到消息通知，重进却提示登录失效」。新增 `AcademicCookieSync`，用带 token 校验的写入（慢请求返回时若会话已换则整笔丢弃）。
- **WebView 登录页允许混合内容**。三处 WebView（教务登录、一卡通登录、一卡通账单）一律用 `MIXED_CONTENT_COMPATIBILITY_MODE`，对承担凭据输入的页面是实打实的风险。改为**跟着主文档 scheme 走**：https 最严（禁止 http 子资源），http 站点才放宽（否则整页白屏）。
- **一卡通记忆的选择可能与新配置错位**。原先只比「级数」，挡不住「级数没变但级的含义变了」（楼栋→单元→房间 改成 园区→楼栋→房间）。旧的 `(code, value)` 被原样传给接口，等于拿别人的房间号查电费。改为逐级比对 `code`，遇首个不一致即截断。
- **CAS 手机登录并发无锁**。`execution` / `sessionValue` 无同步，两个协程并发进入会互相覆盖，先拿到的验证码绑在作废会话上。加锁并把「建会话 → 校验验证码 → 提交」整段纳入临界区。
- **7 处空 catch 静默吞异常**。`GradesRoute` 两处在解析成绩详情，异常被吞后返回**半截列表** —— 用户看到的是「少了几门课」而不是报错。全部补上带条数的日志。

### 变更

- **题库练习逻辑层性能与可测性**：`recordAnswers` 改为批量预读既有状态（每题 2 次查询 → 每库各 1 次）；乱序 / 错题优先抽题改为「先只取标识符排序、再回读选中的题」，不再把整库题干读进内存；首页概览的 6 条独立查询合并为 1 条；`AiConfigStore` 增加进程内解密结果缓存；抽题的到期判定支持注入 `now`；复合键统一收敛到 `ReviewQuestion.compositeKey`。
- **题库练习测试基建**：引入 Robolectric（仅测试依赖），为 `ReviewDatabase` 补 25 个真实 SQLite 用例 —— 覆盖抽题三模式与 LIMIT、连对掌握、批量落库、概览口径、题库取出与排序、逐题编辑、脏章节名等此前零覆盖的路径。`ReviewDatabase` 也顺势拆开：表结构与迁移进了 `ReviewSchema.kt`，行读取与批量回读进了 `ReviewDbRows.kt`，主文件从 1,225 行降到 982 行；练习页的临时开关状态收进 `PracticeUiState`，"哪些会随进程死亡恢复、哪些只是页面开关"从代码上一眼可辨。
### 变更（续）

- **字体子集化**：三个苹方字重各 10.9 MB → 2.2 MB（GB2312 全集 + 项目用字 + 标点，共 7054 字符，零缺字）。APK 从 26.5 MB 降到 **9.8 MB**。
- **targetSdk 34 → 35**：升级前逐项核查 Android 15 的强制变更（edge-to-edge 已由 `CourseSelectorTheme` 统一处理、无前台服务、native so 已 16 KB 对齐、混合内容已按站点分别处理）。
- **启动期预热 SharedPreferences**（`utils/PrefsWarmer`）：后台线程（低优先级，不与首帧抢 CPU）把 11 个启动/首屏要用的 prefs 先解析一遍，把首次读盘从主线程挪走。凭据类不预热（要碰 Keystore，反而更慢）。
- **大列表改为分块渲染**（`ui/system/ChunkedList`）：一卡通账单（可达上千笔）与空闲教室（「加载更多」会累积）改为一次最多组合 40 项。**不换 LazyColumn** —— 外层已是 `verticalScroll`，同轴嵌套会崩。
- **PDF 打开移到 IO 线程**：`ParcelFileDescriptor.open` + `PdfRenderer(...)` 原先在 `DisposableEffect`（主线程）里，大 PDF 会吞掉首帧。
- **拦截器 peekBody 改两段式**：原先每条成功 HTML 响应都 peek 256 KB。改为先看 8 KB 判登录表单特征，命中疑似失效才读大块。
- **资源收缩开启**（`shrinkResources true`）：`resources.arsc` 1,450,272 → 939,312。
- **诊断基建**：Debug 构建装 StrictMode（违反策略直接打完整调用栈）；release 装崩溃兜底（本地存最近 5 份，接在「导出日志」里，不上报任何数据）。
- **JaCoCo 覆盖率报告**：`./gradlew :app:jacocoTestReport`。首次报告揭示总体行覆盖率 14%（纯逻辑层 43%~95%，UI 层接近 0）。
- **测试从 1,227 增至 1,478**：新增 Robolectric 基座；`ReviewDatabase` 25 个真实 SQLite 用例；`xuegong` 从零覆盖到 32 个用例（此前 1,109 行零测试）；`SiteConnectivityProbe` 12 个；`AccountKeys` 19 个；以及成绩身份、复习提醒时间、悬浮球、备份边界、隐私政策、课表事件展开等。
## [1.2.7] - 2026-10-05

### 新增

- **题库仓库模型**：`banks` 表加 `selected` / `sort_order`（`DB_VERSION = 4`，幂等 `ALTER TABLE`，存量行迁成 `selected = 1`）；`BundledBanks` 删 `importAll`，改为按需 `importOne`。`ui/BankLibraryScreen` 独立成页并接管题库管理，原 `BankManageScreen` 删除。
- **开练中枢**：章节按自然序排（纯函数 `review/ChapterOrder`，中文数字 + 终止符两关）、可多选（`PracticeLaunch.chapters`）、可折叠；练习模式改滚轮（纯函数 `review/PracticeMode`），四档 顺序 / 乱序 / 考试 / 背题 + 考试时长（10/20/30/45/60/90/120 分钟或自定义 5–300）。
- **答题卡**：纯函数 `review/AnswerSheet`（四态），独立整页（`GlassSubpage` + `LazyVerticalGrid`），格子 52dp / 17sp、带颜色图例与底部「提交」。
- **限时考试**：纯函数 `review/ExamTimer`，墙钟差值判超时、5/1 分钟各警告一次、到点自动交卷。
- **提交与总结页**：纯函数 `review/PracticeSummary`（正确率以已判分为分母、七档鼓励语），最后一题由「完成」改「提交」；到点自动交卷走同一路径。
- **自动下一题**：`ReviewPrefs` 新增开关，判据 `PracticeSession.canAutoAdvance`（多选题与背题模式不自动跳），落跳前等 220ms 并重校验。
- **错题本 / 收藏独立页**：`ui/BankWrongBookScreen`（三态 + 三种排序 + 间隔复习）、`ui/BankFavoriteScreen`；纯函数 `review/WrongBookLogic` / `review/ReviewScheduling`。
- **抗进程死亡**：`PracticeSession.exportState` / `restoreState`（JSON），题号与 `ReviewPage` 走 `rememberSaveable` + 自定义 `Saver`。
- **选项乱序**：纯函数 `review/OptionShuffle`，显示序与数据序分离，判分与落库照旧走原始字母。
- **演示题库** `assets/review/demo.json`（两章 8 题），首次进入时幂等播种，用户可删且不再长出。
- **底部导航栏可重排与显隐**（`manager/NavBarLayout` 纯函数 + `NavBarSettingsDialog`，「我的」固定、至少 2 项）。
- **课前提醒按学期一键全开 / 全关**（纯函数 `schedule/SemesterReminderPlan`）。

### 变更

- **AI 答疑收缩为只支持 DeepSeek**：`review/AiEndpoint` 重写为一份常量，删除 `AiProfile` / `AiFailover` 与手填端点；`AiConfigStore` 改单配置，Key 走 AndroidKeyStore AES/GCM 加密。思考过程可折叠 + 用量显示。
- **练习页重构**：1076 行的 `PracticeScreen.kt` 拆成 `PracticeAnswerSheet` / `PracticeSummaryPane` / `PracticeAiController` / `PracticeExamCountdown` 四份；顺带修五处体验——进度条改显作答进度、题间切换加滑动过渡并滚回顶部、倒计时下沉为独立每秒重组的小字、自动下一题开关并入答题卡入口条。
- **「问 AI」改造为液态玻璃悬浮球 + 讲解弹窗**：新增 `PracticeAiFloatingBall`——常驻练习页的可拖拽玻璃圆球（`adaptiveGlassChip` 真折射芯片材质），拖拽跟手、松手贴边吸附（「减弱动效」时直接落位），生成中球上带进度环。点球弹出 `AiExplainDialog`（`SystemDialog` 玻璃弹窗），答案区独立滚动、输入框固定底部，未配 Key 给「去配置 AI」直达设置页。删除三处被取代的旧交互：题卡「问 AI」按钮行、内嵌 `AiExplainPanel`、模块根层「AI 正在生成」全局胶囊。
- **判分按钮统一「确认答案」**（此前多选叫「提交答案」）。
- **按钮排版统一**：`ReviewButtons` 抽出 `ConfirmButtonHeight`(48dp) / `SecondaryButtonHeight`(42dp)，`SecondaryAction` 加 `height` 形参 —— 修掉主/次按钮并排时底部参差（`Row` 默认 `Alignment.Top`）。
- **斩题彻底下线**：`onUpgrade` v4→v5 `DROP TABLE slashed`，删 `SlashPolicy.kt` 与相关 DB 方法，`PracticeLaunch` 编码收成 8 字段并严格校验字段数。
- **题库仓库删除「创建题库」「合并题库」「格式说明」**，只留「内置库取出」与「JSON 导入」两条来源。
- **删除「查看 → 统计」**入口与整页，连只服务于它的 `daily_stats` 表一并删除（顺带发现那条写入本来就是坏的：`OR REPLACE` 撞主键使 `answered` 恒为 1）。
- **交卷补判**：`PracticeSession.judgePending()` 把"选了没确认"的题一并判分并落库（三条边界：只补判有作答的、已判过的不动、背题恒空）；删除已无独立行为的 `resultForTimeout()`。
- **作答落库批量单事务**（`recordAnswers(List<AnswerRecord>)`）+ 失败 `GlassToaster` 提示，此前交卷补判 N 题起 N 个事务、IO 抛错会静默丢记录。
- **考前提醒改为后台巡检时顺带刷新考试列表**（新增 `academic/ExamWatcher`）：此前 `ExamReminderScheduler.reconcile` 只在用户打开「成绩 → 考试」页或开机读缓存时被调用，从不点开那个 Tab 的用户本地永远没有考试数据，考前提醒等于不存在。现在挂在已有的 12 小时消息巡检上（不新增闹钟），写入同一份 `GradesCacheManager` 考试缓存并重排提醒；6 小时节流 + 2.5 秒单次上限，排在消息巡检**之前**以免被其预算饿死；失败/超时静默跳过，并按本地缓存重排一次。

### 修复

- **题库练习：同一道题在一次会话里被重复记入数据库**。作答是"判分即落库"（防进程被杀丢记录），而「再想一下」只撤回内存判定、不回滚已写入的 `records` / `wrong_book` —— 「确认答案 → 再想一下 → 再确认」会把同一题记两遍。后果：`wrong_book.streak` 被单次会话刷满（**点三次就能把一道题刷成「已掌握」**，实际只答对一次），详情页正确率因 `SUM(correct_count)` 超过 `COUNT(*)` 而恒等于 100%。修法：`PracticeSession.markRecorded()` 做本轮去重，判据随快照存取，进程重建后同样生效。
- **题库练习：作答快照每次点选项就全量重新编码**。`exportState()` 把整轮作答拼成 JSON 再写回 `rememberSaveable`，320 题的题库连答 320 次就是 O(n²) 的字符串拼接。改为 250ms 去抖（连点只编码最后一次），并把"恢复"与"保存"两个效果的执行顺序解耦（此前靠声明顺序保证）。
- **题库练习：正确率每次重组全量重算**。`PracticeSession` 改为增量维护 `correctCount`（`judge` / `unjudge` / `judgePending` / `restoreState` 四处分別更新），`result()` 与 `accuracy` 都读缓存，不再每次遍历全库跑 `judgeAnswer`。
- **题库练习：`daily_stats` 空壳表残留**。统计页下线后读写代码已删，但老安装的库里表还在。`DB_VERSION` 5 → 6，升级时 `DROP TABLE IF EXISTS daily_stats`。
- **横向审查遗留问题第二批全部修复**（范围与明细见 `docs/后续更新计划.md`，该文档同时收录剩余技术债与功能演进方向）：
  - **更新包签名一致性校验**：`AppDownloader.isTrustedUpdate` 比对下载包与当前应用签名，下载完成 / 「已下载直接安装」/ 拉起安装器三处生效 —— 发布通道账号被盗时假包会被提前拦截删除，而不是等用户走到系统安装器才报错。
  - **二课扫码静默失败**：从列表页顶栏扫码时 `failDetail` 对 null 详情是 no-op，所有失败毫无反应 —— 补 Toast 兜底；「我的签到码」改为每 10 秒重算（文案承诺的动态码此前是静态的，弹窗开久即过期）；本人 userId 拿不到时不再误判「代他人签到」，改提示重试；申报表单必填项本地拦截（此前空字段原样上报），且表单值改 `mutableStateListOf`（普通 Array 不触发重组，校验提示会停在旧值）；「加载更多」按钮防连点；切 Tab 清空搜索关键词；DeepLink 的 host 判定从 `contains` 收紧为精确匹配。
  - **考级报名**：类别快速切换加请求序号守卫（旧请求返回整包丢弃，不再出现「A 类别的 chip 挂 B 类别的项目」）；确认弹窗提交中锁定（按钮「提交中…」、禁取消/禁点外部关闭）。
  - **空闲教室**：查询/加载更多加代际守卫（旧翻页不再把旧条件的第 N 页追加进新结果），查询中禁用翻页按钮；切换学期整组重置周次/节次/楼号（旧学期勾选条件在新学期可能不可查）。
  - **一卡通**：首屏 5 个串行请求改并行（详情→读数一路，汇总、流水各一路，首屏约省一半）；切月不再重拉明细（账单接口没有日期参数，此前白打一个请求还把展开的明细弹回第一页）；令牌采集的手工 JSON 反转义改 `JSONTokener`。
  - **其它**：教务网页清 Cookie 后不再等 removeAllCookies 回调加载页面（个别 WebView provider 不回调会永远白屏）；附件缓存文件名从 `hashCode` 改 SHA-256 指纹（防不同附件互撞缓存）；「导出日志」的 logcat 拷贝与落盘挪出主线程；二课活动列表的时钟读取与分区、`formatRange` 的三个 SimpleDateFormat 都加 remember/缓存（列表滚动热点）；申报材料上传先查声明大小再读内存（几百 MB 的"图片"不再整读进内存才拒绝）。
- **全应用横向代码审查修复一批跨模块 bug**（2026-10 用户要求「检查所有模块的问题和优化方案」，审查覆盖题库练习 / 教务与账号 / 一卡通 / 二课 / 考级 / 空闲教室 / 桌面卡片，课表域做了定向扫描未见高风险项）：
  - **WebView 同进程二次设数据目录后缀崩溃**：教务网页（"academic"）与一卡通两个网页（"ykt"）各持一个静态 `suffixConfigured`，同一次进程会话先后打开两类页面时第二次 `setDataDirectorySuffix` 必抛 `IllegalStateException` 崩掉 Activity。新增 `utils/WebViewDataDir` 进程级守护（先到先得 + runCatching），三处调用点统一接入。
  - **从练习页去 AI 设置再返回、作答位置回卷到第 1 题**：`when` 切页时 PracticeScreen 离开组合，`rememberSaveable` 随组合注销——悬浮球把「没配 Key → 去配置」变成了必经路径，等于一配 Key 就被踢回第一题。`ReviewFlow` 挂 `SaveableStateHolder` 按页面 key 隔离保存容器（练习页 key 带开练时刻，新开一轮不带上轮状态）。
  - **消息后台巡检无超时、可被系统腰斩**：`MessageCheckReceiver` 的巡检（消息 2 桶×10 页 + 成绩翻页）超出 goAsync 约 10 秒预算后进程被回收、本轮静默作废。包进 `withTimeoutOrNull(9_000)`，与更新/电费接收器同款。
  - **会话过期不落盘**：`UserManager.setLoggedIn(false)` 只改内存，杀进程重开后拿死 Cookie「欢迎回来」再撞一轮失效探测。登出方向同样 `saveLoginState()`。
  - **课表内存缓存跨账号串显**：`CourseCacheManager` 三个内存字段无同步，JMM 重排下可能读到「新账号 key + 旧账号课程列表」，全部加 `@Volatile`。
  - **消息/成绩缓存的读盘写盘跑主线程**：`MainActivity` / `SettingsRoute` / `MessageCenterScreen`（读缓存、写已读、一键已读）与 `WidgetBoardRoute`（成绩、二课缓存在 `remember` 组合期同步读）全部改 `LaunchedEffect` + `Dispatchers.IO`。
  - **悬浮球旋转/分屏后悬在屏幕外**：容器尺寸变化时把球夹回新的合法范围（`onSizeChanged`）。
  - **电费提醒节流状态没落盘**：`YktAlertStateStore` 补 `lastNotifiedAtMillis` 读写——此前读回恒为 0，同余额 24h 节流形同虚设，会退化成每 6 小时一条轰炸。
  - **`pickQuestions` 的 `limit` 在乱序/错题优先分支被忽略**：重排后补 `take(limit)`（此前谁传 limit 都会静默抽全库）。
- **课程提醒开了却从不响**：提醒用的「时间基准」缺了节次时间。`ScheduleRoute` 首次回写日历只给 `firstWeekDate`，而补救迁移 `ScheduleCalendarStore.migrateLegacy` 带着「已有第一周日期就整条跳过」的守卫，于是 `periodStarts/periodEnds` 永远补不进来；`CourseReminderPlanner` 取不到 `periodStarts[startPeriod]` 就返回 `NeedsTime`，**一条闹钟都不排**。修法：`ScheduleReminderScheduler.timeBase` 按 `ScheduleRepository` 的口径合并「设置里的节次时间打底、日历里存过的值覆盖」，老用户下一次 reconcile 即自动恢复；`migrateLegacy` 从「有日期就跳过」改成合并（补齐缺失节次、不覆盖用户调过的值）。
- **一卡通缴费提醒只在打开页面时才触发**：根因是电费巡检用 `setInexactRepeating` 而 `CourseApplication` 每次冷启动都 `schedule`，`FLAG_UPDATE_CURRENT` 把触发时刻一并重置 —— 后台巡检从未触发过。电费 / 消息 / 更新检查三条链路改为「链式单次闹钟 + 持久化下一轮触发时刻」，触发后自我续期；电费巡检加密到六小时一次并按余额下降节流（`YktAlertPolicy`）；更新检查补齐 BOOT_COMPLETED。
- **「通知发不出去，事件却已被消费」——四条推送链路的同类顺序缺陷**：这组缺陷的共同形态是「先记账、后确认送达」，通知权限被关时直接把事件静默吞掉，用户重新打开通知也补不回来。逐条改为「先确认送达、再推进状态」：
  - **成绩推送**（`GradeWatcher`）：`postNotification` 返回布尔，未送达时**不推进基线**，下一轮巡检会重新播报这批成绩；
  - **教务消息推送**（`MessageCenterNotifier`）：同上，未送达时不写已播报集合；
  - **电费提醒**（`YktAlertNotifier`）：`YktAlertStateStore` 的 `lastNotifiedAtMillis` 只在真正发出后才落盘 —— 此前发不出去也照样写，24h 同额节流会把这条提醒直接压掉一天；
  - **考试提醒**（`ExamReminderScheduler`）：把账号归属判定与「真的 notify 成功」都挪到 `savePlans` **之前**，一次性计划不再在送不出去的情况下被摘掉。
- **系统返回键 / 侧滑手势未接进模块内导航**：`ReviewActivity` 挂 `BackHandler`；横向排查 14 个 Activity 后另修公告中心与消息中心的详情页。
- **乱序抽题在非数字题号下退化成顺序扫描**：SQL 的 `ORDER BY (qid || bank_id) * …` 在非数字 qid 上整个变 0，排序移到 Kotlin 层（`review/QuestionOrder.kt`，`String.hashCode` + 字典序决胜键）。
- **`PracticeSession.key()` 字符串模板写错**：`"${q.bankId}::$q.id"` 把整道题的 `toString()` 当 map key，作答快照膨胀几十倍；改为 `"${q.bankId}::${q.id}"`。
- **Markdown 不成对星号原样露出**：新增 `sanitizeMarkdown`，在"是否走富文本"判定**之前**清洗（空加粗整对删、奇数时只删片段首尾、代码块整段保护）。
- **题库仓库页顶栏不跟手折叠**：`GlassPageScaffold` 的 `collapseFraction` 默认 `0f`，补 `rememberLazyListState` + `derivedStateOf`。
- **复习模块 23 处提示静默失效**：`ReviewActivity` 根层补 `GlassWindowHost` + `GlassToastHost`。
- **逐题编辑页改懒加载**（1059 题的库此前进页面一次性组合全部题卡）；`ReviewDatabase` 新增 `bankName(bankId)` 单行查询，不再为取标题跑全表。

## [Unreleased]

### 新增

- 底部导航栏支持重排与显隐：`manager/NavBarLayout`（纯函数 —— `default` / `MIN_ITEMS=2` / `pinned=Settings` / `resolve` 容错解析已存路由、丢弃未知项并去重、缺「我的」或不足 2 项则回退默认 / `move`·`moveUp`·`moveDown` 越界返回 null / `canRemove` 返回 `RemoveCheck` 判定）、`manager/NavBarLayoutSignal`（全局修订号，编辑后通知底栏重渲染）、`manager/StartupPagePreferences` 新增 `readNavBar` / `readNavBarRoutes` / `writeNavBar` / `clearNavBar`（有序路由列表持久化）。编辑器 `ui/screen/NavBarSettingsDialog`（上移 / 下移 / 移除 / 恢复默认，全玻璃件），入口「我的 → 外观 → 底部导航」。**「我的」固定不可移除**（唯一设置入口，防止藏没后无法改回），**至少保留 2 项**。`MainActivity` 页签派发由 `Int` 下标改为**路由字符串**，重排后按路由重新对齐当前页，杜绝串页（由 `NavBarLayoutTest` 22 项覆盖）。
- 复习模块新增题库管理：创建 / 改名 / 逐题编辑 / 多选批量删除 / 合并题库。判据抽成纯函数 `review/BankManageLogic`（`validateBankName` → `NameCheck`、`resolveMerge` → `MergeCheck`、`planQidRemap` 题号冲突自动重分配），`ReviewDatabase` 补 `createBank` / `renameBank` / `updateQuestion` / `questionById` / `listQuestions` / `deleteQuestions` / `refreshQuestionCount` / `mergeBanks`。**合并口径**：只搬题目，**不迁移练习记录、错题本、收藏**；题号冲突自动重分配、绝不覆盖目标题库已有题目；**不自动删除源题库**，由用户二次确认后自行删除（由 `BankManageLogicTest` 25 项覆盖）。
- 课前提醒新增「按学期一键全开 / 全关」：纯函数 `schedule/SemesterReminderPlan`（`planSemesterToggle` 幂等 —— 状态未变则 revision 不变；`summarize` → 已开启 N/M 与状态分组；`toggleAvailability` 区分 Ready / NoSnapshot / MasterOff），`ScheduleReminderScheduler` 补 `setAllEnabled`（整批**只调一次 `reconcile()`**）/ `semesterSummary` / `semesterCourses`，提醒设置页新增「本学期课程」区块（进度 + 全部开启 / 关闭，无快照时禁用并提示）。**提前量沿用设备级全局设置**（不逐条存），复用既有 `updateSnapshot` 课表快照（不另造存储）（由 `SemesterReminderPlanTest` 26 项覆盖）。
- 复习模块新增「限时考试 / 到期自动交卷」：纯函数 `review/ExamTimer`（`ExamTiming`（<=0 不限时）/ `remainingSeconds` 夹取 >=0 / `formatRemaining` 走 `mm:ss` 与 `h:mm:ss` 两档 / `examTimeouts` 返回动作列表而非 Boolean，Submit 优先、5 分钟与 1 分钟各「未警告过才给」/ `ExamDurationOptions` 不限时 + 20/30/60 分钟），`PracticeSession` 加带默认值的 `timing` / `startedAt`（`isExpired` 按**墙钟差值**判断、后台回来不漂；`resultForTimeout` 未判分的题计为未作答、不算错），`PracticeScreen` 限时下起对齐秒边界的每秒 tick、归零自动交卷并 `GlassToaster` 提示，`BankDetailScreen` 加时长档入口（由 `ExamTimerTest` 26 项覆盖）。
- 复习模块新增「间隔复习（今日待复习）」与错题三态 / 斩题 / 背题：纯函数 `review/ReviewScheduling`（`MasteryLevel` 四级、`masteryLevelOf`、`reviewIntervalDays` = 0/1/3/7、`dueAt` / `isDue` / `dueCount`，不读墙钟）、`review/WrongStatus`（三态 `Reviewing` / `NotMastered` / `Mastered`，**已掌握优先**、`mastered` 仍由 `MASTER_STREAK` 决定不改既有语义，含 `WrongStatusFilter` / `WrongSort` / `wrongStatusOrder`）、`review/SlashPolicy`（**斩题 ≠ 已掌握**，`checkSlash` 三态、`wrongStatusAfterSlash` 恒等钉住不变量）、`review/PracticeLaunch`（开练参数收成具名数据类，含 `isTimedExam` / `title`）。`ReviewDatabase` 加 `slashed` 表 + `wrong_book.due_at`（`DB_VERSION = 3`，走 **`PRAGMA table_info` 判列的幂等 `ALTER TABLE`，绝不删表重建**）、`dueWrongQuestions` / `dueWrongCount`，`pickQuestions` 加 `onlySlashed` / `excludeSlashed` / `dueOnly`。题库详情页加「今日待复习（N）」入口，错题本支持按状态筛选与三种排序（由 `ReviewSchedulingTest` 26 / `WrongStatusTest` / `SlashPolicyTest` / `PracticeLaunchTest` 覆盖，review 全量 210 项）。

- 复习模块的 AI 配置收缩为**只支持 DeepSeek**（2026-10 用户要求「只支持 deepseek，档案删除」）。`review/AiEndpoint` 从「9 家预置服务商 + 端点校验 + 环回明文放行」整个重写为一份常量：`BASE_URL = "https://api.deepseek.com"`、`DEFAULT_MODEL = "deepseek-flash"`、`MODELS = ["deepseek-flash", "deepseek-v4-pro"]`、`DOC_URL` / `KEY_URL`（设置页两个跳转入口）。模型名依 **2026-10 官方文档**核实：旧名 `deepseek-chat` / `deepseek-reasoner` / `deepseek-v4-flash` 对应的模型**已下线**，请求转由 DeepSeek-V4.1-Flash 承接并按 Flash 计费，故不再作为选项。**删除** `review/AiProfile`（`AiProfile` / `AiProfileCodec` / `AiProfileRules` / `AiFailover` 主备降级）与用户手填端点能力 —— 用户既然只能连 DeepSeek，端点就不是「用户的选择」而是「程序的事实」，留着只会让配置页多出五个必然填错的输入框。`review/AiConfigStore` 重写为单配置：`AiConfig(baseUrl, apiKey, model, timeoutSeconds, retries)` + `AiConfigRules`（`clampTimeoutSeconds` 夹取 10–600 秒 / `clampRetries` 夹取 0–5 次 / `normalizeModel` 把空值与四个**已下线旧名**改写为 `deepseek-flash`，但**清单外的名字原样保留** —— 否则用户在设置页用「获取可用模型」选中的新模型，下次进页面会被静默改回去 / `migrateProfiles` 只在读到中间多档案版遗留的 `profiles` JSON 时触发，且**只迁含 `api.deepseek.com` 的档案** —— 把 OpenAI 的 Key 迁来指向 DeepSeek 只会得到语义混乱的 401）。落盘键名沿用最早单配置版的 `api_key` / `model` / `timeout_seconds` / `retries`，**老用户升级无需迁移**。`AiChatClient` 去掉端点校验分支（`apiKey.isBlank()` 是唯一的本地拒绝理由）与 `allowFailover` / `parseFailure`；**刻意不启用** `thinking: {"type":"enabled"}` 与 `reasoning_effort`（显著拉长首字延迟且多烧 token，而本模块的用法是「针对一道选择题问一句」，收益不成正比）。设置页 `ui/AiSettingsScreen` 重写为六区（服务与端点只读说明 + 查看官方文档 / 申请 API Key、API Key、模型芯片 + 获取可用模型 + 手填、网络参数、保存·测试连接·清除配置、关于隐私），Key 仍走 AndroidKeyStore AES/GCM **加密落盘且绝不打日志**。单测 `AiEndpointTest`（6 项，钉住 `chatUrl()` 就是 `https://api.deepseek.com/chat/completions`、**不补 `/v1`**）/ `AiConfigRulesTest`（13 项）/ `AiStreamTest`（20 项，含 MockWebServer 端到端）。
- 复习模块的**章节名按自然序排列**：纯函数 `review/ChapterOrder`（`chapterNumber` 解析编号 —— 中文数字与阿拉伯数字都认，`十`→10、`二十三`→23、`1.1`→1.1；`ChapterSortKey` 以「解析出的数值 + 名称」为键；`sortChapters` / `sortChaptersWithCount` 是给 DB 与界面的入口）。原先 `chaptersWithCount` 直接 `ORDER BY chapter`，而 SQLite 是逐字节比较、章节名里的编号又是中文数字，排出来是「第一章、第七章、第三章、第九章、第二章…」这种完全错乱的顺序（2026-10 用户报「章节顺序问题」）。**判定编号要过两道关**：① 位置（串首，或紧跟 `第` 之后）；② 终止符（后面是串尾 / 标点空白 / 编号量词「章节讲课篇部分…」）。第 ② 关是单测逼出来的 —— 只靠位置时「三个代表重要思想」因为以「三」开头被判成第 3 章、「一元函数微分学」「一般现在时」被判成第 1 章。无编号的章节（绪论 / 导论 / 前言）当负无穷处理、排在最前（由 `ChapterOrderTest` 15 项覆盖）。
- 题库详情页的章节练习支持**多选**：`PracticeLaunch.chapter: String?` 改为 `chapters: List<String> = emptyList()`（**空集合与原来的 `null` 语义逐字一致**，所以「只选一章」的行为与从前完全相同），`ReviewDatabase.pickQuestions` 展开成 `chapter IN (?, ?, …)`（**必须用占位符**：内置题库就有「第七章 “三个代表”重要思想」这种含引号的章节名，拼字面量会 SQL 语法错误）。选择状态的载体由「下标 → 章节名」的映射换成 `Set<String>`，`ChapterOption` / `resolveSelectedChapter` / `modeIndex` / `modeAt` 一并删除（下标映射当初存在的唯一理由是「`if (index == 0) null else list[index-1]` 容易差一位」，改用集合后这个理由消失）；`toggleChapterSelection` **返回新集合**而不是原地改 `MutableSet` —— Compose 靠引用变化触发重组，原地改会让界面停在旧状态。`chapterSelectionLabel` 按「空集 → 全部章节 / 单章 → 章节名 / 多章 → 已选 N 章」给标题（由 `BankDetailLogicTest` 13 项 / `PracticeLaunchTest` 覆盖）。
- 题库详情页的章节列表**垂直可折叠**：标题行常显（显示当前选择与合计题数），点一下收起 / 展开，**默认展开**以保留原有观感 —— 章节多时（`mao_ze_lishi` 有 11 章）展开后会顶掉下面的练习模式，而「我在哪一章」又恰恰是开练前最该看清的信息，故不做成默认收起。勾选框是自绘的 `CheckMark`（圆角方块 + 对勾），不用 Material `Checkbox`（尺寸与配色与本页玻璃件不搭）。
- 练习页新增**答题卡**：纯函数 `review/AnswerSheet`（`AnswerSheetStatus` 四态 `Unanswered` / `Answered` / `Correct` / `Wrong`、`answerSheetStatus`、`AnswerSheetCell`、`summarizeAnswerSheet` → `AnswerSheetSummary`）。四种状态刻意分开：本应用是「先选再确认」的两步作答，「选了但没确认」（未判分）既不算对也不算错，合成一种用户就分不清「我提交了吗」。**未作答优先于一切** —— 背题模式下用户从不作答，若先判 `judged` / `correct` 会把整张卡涂成「答错」。界面上是可折叠的一排方格（`FlowRow`，**不能用 `LazyVerticalGrid` / `LazyRow`** —— 练习页根布局是 `verticalScroll`，同轴嵌套滚动会让子节点收到无限高约束并在测量阶段抛异常崩进程，即「点开始练习退回课表页」的同一根因），点格子跳题、当前题加描边（由 `AnswerSheetTest` 9 项覆盖）。
- 题库详情页新增**收藏入口与收藏练习**：`ReviewDatabase.listFavorites`（JOIN 取回、最近收藏在前、`bankId` 为空时跨库）、新页面 `ui/BankFavoriteScreen`（汇总卡 + 「练习收藏题（N）」+ 只读列表），`ReviewPage` 加 `Favorites(bankId)`。`pickQuestions` 的 `onlyFavorite` 早就支持抽题，缺的只是一个能看清「我收藏了哪些」的入口 —— 原先收藏的题除了在练习中被随手收藏外**没有任何地方能看到**，也就等于不能专门练。收藏页刻意与错题本对称（汇总 → 开练 → 列表），但**不做筛选排序**（收藏没有掌握度 / 错次 / 到期时间，摆一排只有一两种取值的芯片等于给用户出选择题），列表也**不提供「取消收藏」**（在列表里取消后条目会当场消失，用户想收回就得回去找那道题）。
- 题库详情页按「先定练哪一部分、再定怎么练」重排：**章节练习**改为**垂直展开、可折叠、可多选**的列表（一行一章 + 题数，自绘勾选框；标题行常显当前选择与合计题数 —— 原先横向 chip 要靠左右滑，章节多时既看不全也容易点错，而"我在哪一章"恰恰是开练前最该看清的信息）；**练习模式**收成固定四档（顺序练习 / 随机练习 / 背题模式 / 考试模拟）+ 考试时长档（「不限时」时**考试模拟禁用并说明原因**，因为那时它与顺序练习完全等价，不该让用户点了才发现）；「**统计**」「**错题本**」「**收藏**」改为三个**独立胶囊按钮跳页**查看 —— 原先点错题本等于直接开练，把"我错了哪些、哪些没掌握"这层信息完全藏起来了。**移除「管理题目（逐题编辑）」入口**：编辑属题库管理，统一走「复习 → 题库 → 管理」。新增错题本独立页面 `ui/BankWrongBookScreen`（汇总卡「在册错题 / 待复习 / 今日到期 / 已掌握」+ 按状态筛选 + 三种排序 + 错题列表，根布局用 `LazyColumn`），判据抽成纯函数 `review/WrongBookLogic`（`filterWrongEntries` / `sortWrongEntries`（三个口径均带**稳定兜底键**，否则同一批数据每次进页面顺序都可能变）/ `summarizeWrongBook` → `WrongBookSummary`（**已掌握的题不计入「今日到期」**，与 `dueWrongQuestions` 的 `mastered = 0` 对齐）/ `dueLabel`（"从未排期"与"已到期"统一说成"现在可复习"，判据上 `due_at = 0` 本就等价）），`ReviewDatabase` 加 `WrongBookEntry` 与 `listWrongBook`（JOIN 取回、**刻意不在 SQL 里过滤掌握状态** —— 在 SQL 里过滤就得为 5 个筛选项各写一条 SQL、三态判据也被拆到两处，而错题本量级只有几百行），`ReviewPage` 加 `WrongBook(bankId)`。由 `WrongBookLogicTest` 27 项覆盖。
- 复习模块改为**题库仓库**模型（2026-10 用户要求「做一个题库仓库，存储所有的题库，用户按需选取题库出来，进行对应的练习并支持调整顺序」）：`ReviewDatabase` 的 `banks` 表加 `selected` / `sort_order` 两列（`DB_VERSION = 4`，走 `PRAGMA table_info` 判列的幂等 `ALTER TABLE`；**存量行一律迁成 `selected = 1`**，否则老用户升级后题库会全部"消失"），`listBanks` 改按 `selected DESC, sort_order ASC, created_at DESC` 取全量，新增 `setBankSelected` / `moveBank`（相邻两条互换 `sort_order`，两者权重相同时退化成按当前顺序重排一遍）/ `reorderSelectedBanks`（拖拽落位）/ `nextSortOrder`。`BundledBanks` **删除 `importAll` / `importAllBlocking`**（不再一进复习页就把 4 个内置库 1059 题全塞给用户），改为 `importOne`（点「取出」才解析落库，查重两道）。复习首页 `ui/ReviewHomeScreen` 整页重写为**两段式**：「我的题库」（长按拖动 + 上/下移按钮两种排序都支持，另有「移出」「删除」）+「题库仓库」（未导入的内置库 + 已导入被移出的库，点「取出」即回来）。**「移出」只改显示状态、不删任何数据** —— 题目、答题记录、错题本、收藏原样留着，随时能取回。
- 复习模块新增「**选项乱序**」：纯函数 `review/OptionShuffle`（`DisplayOption` 三键 —— 显示字母 / 原始下标 / 原始字母；`displayOptions` 用「会话种子 ⊕ 题目键哈希」作随机源，保证同一题在同一轮里顺序稳定、换一轮整体重排；`seedFor`；`toDisplayAnswer` 把原始字母答案翻译成显示字母）。**显示序与数据序彻底分离**：界面只认重新编号的 A/B/C…，而作答、判分、落库、错题本、答题卡一律继续用原始字母，因此 `PracticeSession` 与数据库一行未改。`QuestionCard` 按显示序渲染选项与「正确答案 / 你选」字母条。开关落在 `review/ReviewPrefs`（明文 SharedPreferences，默认**关** —— 乱序会打乱"答案是 A"的肌肉记忆，且与题库纸质材料对不上），入口在题库详情页「练习模式」卡片。**已知限制**：解析正文里写死的「A 项」字母引用不会跟着重排（正则重映射会把「A 股」这类词一起改掉，比不改更糟），提示文案里已如实写出。
- 复习模块新增「**抗进程死亡**」：练习进度在系统回收进程后可恢复。`PracticeSession` 加 `exportState` / `restoreState`（作答与判分导出成 JSON；用 JSON 而非自定义分隔符，是因为 `bankId::qid` 复合键来自题库数据，"选一个不会出现的分隔符"是赌）；`PracticeScreen` 的题号与作答快照走 `rememberSaveable`；`ReviewActivity` 的页面选择（`ReviewPage`）与两个"来处"改为 `rememberSaveable` + 自定义 `Saver`（编码成字符串 —— 参考项目 Shiroha Quiz 的返回栈也是这么做的），并把开练参数 `PracticeLaunch` 编码保存：进程重启后**按同一组参数重新抽题**（`pickQuestions` 的顺序模式走 `ORDER BY rowid`、随机/错题优先走 qid 哈希，都不依赖时间，所以重抽即同一批题、同一顺序），会话因此能原样重建，重建期间显示「正在恢复练习…」而不是弹回首页。**乱序种子必须沿用原值**，否则重建后选项会重新洗一遍、用户选中的项会跳到别的字母上。以前全模块**没有一个 `rememberSaveable`**，切后台久了进度全丢。
- 题库仓库**独立成页**，并把题库管理整个搬进去（2026-10 用户要求「将题库仓库做成页面，将题库管理功能移到题库仓库里」）：新页面 `ui/BankLibraryScreen`（原 `BankManageScreen` 删除），三段 —— ①**取出到我的题库**（未导入的随包内置库 + 用户导入过又被移出的库，点「取出」即回来）②**导入题库**（系统文件选择器选 JSON + 格式说明入口）③**题库管理**（创建 / 合并 / 逐题编辑 / 改名 / 删除，列出**全部**题库并标出「在我的题库」或「在仓库」）。复习首页 `ui/ReviewHomeScreen` 因此瘦身成单段「我的题库」，只在标题行右侧留一个「题库仓库」入口；`ReviewPage.BankManage` 改名 `ReviewPage.Library`，逐题编辑与格式说明的返回目标一并改为仓库页。为什么第 ① 段与第 ③ 段会有重叠：两段的**动词**不同 —— 前者是"拿出来 / 放回去"（只改显示状态），后者是"改它 / 删它"（不可逆）。
- 内置**演示题库**（`assets/review/demo.json`，「演示题库」两章共 8 题：第一章 计算机基础 4 题、第二章 网络基础 4 题，单选与多选都有），用来让人一进模块就有东西可练。它**刻意不进题库仓库** —— 它是"新手指引"，放进仓库反而要求用户先学会「取出」才看得到题；由 `BundledBanks.ensureDemoBank` 在首次进入模块时直接导入并**取出**（幂等，播种标记落 `ReviewPrefs` 的 `demo_bank_seeded`，**纳入备份**：换机恢复后不该又冒出一个）。**用户可删除**，删掉之后不会再长出来（否则删了又出现，像个删不掉的流氓软件）。
- 题库详情页的**练习模式改为滚轮选择**（2026-10 用户要求「和一卡通中选择宿舍的差不多」）：四档由并排的 `ModeCell` 格子改成一行表单字段（`练习模式   顺序练习 ›`），点开走 `GlassOptionWheelDialog`（玻璃滚筒 + 吸附，与一卡通选宿舍电费同一形态）。新增纯函数 `review/PracticeMode`（`PracticeModeOption` 四档 `顺序练习 / 乱序练习 / 考试 / 背题模式` + `needsDuration` 只有考试为真 + `hint()` 逐模式说明；`resolvePracticeParams(mode, durationMinutes)` → `PracticeParams(mode, memorize, durationSeconds)`），界面只做渲染、映射可单测。**「考试时长」只在选中「考试」时出现**，档位改为 `ExamDurationPresetsMinutes`（10/20/30/45/60/90/120 分钟）+ **「自定义…」**（再开一个滚轮，`CustomDurationMinutes` 5…300 步进 5）；`ExamDurationOptions` / `ExamDurationOption` 整个删除 —— 它的「不限时」档正是上一版必须把「考试模拟」禁用并解释原因的根源（不限时的考试就是顺序练习），现在这一档直接不存在了。四档旁边补一个「开始练习（N 题）」主按钮（原先点格子即开练，改成先选后确认）。
- 练习结束改为「**提交** → **总结页**」（2026-10 用户要求「顺序练习和乱序、考试模式下答完题后的完成按钮改为提交并有个总结页面，总结页面要添加鼓励语」，并追加「总结页面还是要有本次答题的数据的」）。新增纯函数 `review/PracticeSummary`：`PracticeSummary`（`judged` = 对 + 错、`accuracyPercent` **以已判分为分母**、`title` 按 练习完成 / 考试结束 / 考试时间到 三态、`encouragement`、`elapsedLabel`、`tallyLabel`、`durationMinutes` 向上取整）、`practiceSummaryOf(result, elapsedSeconds, durationSeconds, timedOut)`、`elapsedSeconds(startedAt, nowMillis)`（墙钟差值，开始时刻未设定或时钟倒退一律返回 0 —— 界面上出现 `-00:03` 只会让人以为程序坏了）、`encouragementFor(accuracyPercent, judged, total)`（七档 + 「没有题目」「还没作答」两句**独立**文案：「一题都没答」与「全错」必须给不同的话，否则会对着 0% 说"非常棒"）。`PracticeScreen` 底部最后一题由「完成」改「**提交**」并走主按钮样式；**限时到点自动交卷也走同一条路径**（被强制收卷的人比谁都更需要立刻看到结果）；`SummaryPane` 展示 鼓励语 + 正确率 + 汇总行 + 「本次数据」胶囊（总题数 / 答对 / 答错 / 未作答 / 用时，限时再加考试时长）+ 「答题明细」只读格子。**背题模式不判分，仍叫「完成」并直接返回** —— 对着一个没有分数的会话说"提交"是假话。（由 `PracticeSummaryTest` 13 项覆盖）
- 答题卡新增「**提交**」按钮，并把**字号放大**（2026-10 用户要求「在答题卡页面添加一个提交按钮」「答题卡的字样太小无法看清」）：`AnswerSheetPage` 加 `bottomBar` —— 顺序 / 乱序 / 考试下是「提交」（答题卡是"交卷前最后一眼"，用户在这里最容易想交卷，让他先关掉再滑回练习页底部按提交是多余的一步），背题模式位置改成一句「背题模式只看答案、不计分，看完直接返回即可。」而不是点了没反应的灰按钮。格子从 44dp / 12sp 提到 **52dp / 17sp**、描边 1.5dp→2dp、网格最小列宽 46dp→56dp，并加了「未答 / 对 / 错」颜色图例（没有它，用户只能靠猜颜色含义）。答题卡入口条的摘要行 12sp→13sp。
- 练习页新增「**自动下一题**」开关（2026-10 用户要求「我要的是开启自动下一题的按钮，就是用户选完选项后自动跳到下一题」）：打开后**选完选项就自动跳到下一题**。开关常显在练习页（答题卡入口那一行下面），状态存 `ReviewPrefs`——"我现在想快点刷"是刷到一半才会冒出来的念头，藏进设置页就废了，但也不该每轮重选。判据抽成 `PracticeSession.canAutoAdvance(q, enabled)`（可单测）：**多选题不自动跳**（要选好几项，程序无从判断"选完了没有"，按点击次数猜会把只想选一项的用户弹走）、**背题模式不自动跳**（选项是直接摊开的，根本不可点）。⚠️ 它**只管跳不跳、不管判分**：跳过的题作答照旧留着，交卷时由 `judgePending()` 统一判 —— 所以开着它一路刷到底，成绩一样是准的，只是中途看不到解析。落跳前**等 220ms** 让"选中"那一下的高亮能被看见（立刻跳会让人以为自己点空了），并且**在跳之前再确认一次**开关还开着、没提交、不是最后一题 —— 等待期间用户可能已经改选、关开关、手动翻页或提交。用自增计数器 `autoAdvanceTick` 而不是布尔：连点两个选项时要**重新计时**，而不是叠加成"连跳两题"。
  > 第一版做成了选项下方一颗手动的「下一题」按钮，用户明确纠正「我要的不是下一题的按钮」—— 已回退，那颗按钮不存在。

### 变更

- **修复「每当我进入一卡通时，才给我发送缴费提醒」**（2026-10 用户报，并要求排查其余模块一并修复）：根因是电费巡检闹钟用 `setInexactRepeating(RTC_WAKEUP, now + 24h, 24h)`，而 `CourseApplication` **每次进程冷启动都调用 schedule**——`FLAG_UPDATE_CURRENT` 覆盖会把触发时刻一并重置，用户每天打开 App 的场景下巡检被每天往后推 24 小时，**后台巡检从未触发过**，提醒只剩「打开一卡通页面拉到余额时判一次」这一条路径。修复：电费 / 消息 / 更新检查三条巡检链路全部改为「**链式单次闹钟 + 持久化下一轮触发时刻**」——冷启动按存储值**恢复**原时刻而不是顺延，错过（进程死亡超期）则 30 秒后立即补跑一轮，触发后由 Receiver 自我续期。同步调整：① 电费巡检从**一天一次加密到六小时一次**（2026-10 用户要求「当电费达到阈值时就发送提醒」），`YktAlertPolicy` 加**同一余额 24 小时内不重复**节流——余额不动时一天最多一条、**余额继续下降立即再提醒**（保留"知道低了不充就反复催"的原诉求），`YktAlertPolicyTest` 全量改写钉住新口径；② 消息巡检（连带成绩订阅 GradeWatcher 的后台路径）保持 12 小时但不再被冷启动顺延；③ **更新检查补齐开机重排**——`UpdateCheckReceiver` 此前是全项目唯一没有 BOOT_COMPLETED intent-filter 的调度链路，重启后彻底失联，现已与消息巡检同款补上（BOOT / 应用更新 / 改时间 / 改时区）。其余提醒链路（课表课前提醒、自动勿扰、考前 24h 提醒、桌面卡片）经逐一排查为链式 exact 闹钟 + 开机重排，设计正确无需改动。
- **系统返回键 / 侧滑手势接进模块内页面导航**（2026-10 用户报「在题库练习中使用手机自带的返回功能就返回到我的界面了，只有点击左上角的返回才能返回到上一面」，并要求排查其余模块一并修复）：`ReviewActivity` 挂 `BackHandler`，Home 之外的每个页面系统返回 = 左上角返回（练习页与左上角同口径：作废会话与"可重建"信息后回来处）；全项目横向排查 14 个自研 Activity 后确认同型问题还有两处 —— 公告中心与消息中心的**详情页**（详情打开时系统返回先回列表而不是退出 Activity），其余模块（一卡通 / 考级报名 / 空闲教室 / 手册 / 二课中心 / 文档查看器等）要么是单页、要么内部导航已被 `BackHandler` / `DialogHost` 的预测性返回覆盖，无需改动。
- **斩题功能彻底下线**（2026-10 用户决定彻底清理；此前 UI 入口已删、后端刻意保留）：`ReviewDatabase` 在 `onUpgrade` v4→v5 直接 `DROP TABLE IF EXISTS slashed`（幂等；**历史被斩的题全部放回练习**——它们此前被 `excludeSlashed` 过滤、且应用内已无任何找回入口），`onCreate` 不再建表；删除 `slashQuestion` / `unslashQuestion` / `isSlashed` / `slashedCount` / `listSlashedQuestions` 与 `pickQuestions` 的 `onlySlashed` / `excludeSlashed` 过滤；`PracticeLaunch` 删 `onlySlashed` / `excludeSlashed` 字段，`encode` / `decodePracticeLaunch` 同步收成 8 字段并**严格校验字段数**——格式换代后旧串直接拒收，宁可让用户重新开练，也不能错位读出一个错误的练习模式；删除 `review/SlashPolicy.kt` 与 `SlashPolicyTest.kt`；`importBank` / `deleteBank` 的级联删除清单与 `deleteQuestions` 的单题清理同步去掉指向 `slashed` 的行（表没了再 DELETE 会直接报错）。用户手册同步：斩题小节整删、FAQ Q35 改写为「功能已下线、历史被斩的题已全部放回」。
- 题库仓库**删除「创建题库」「合并题库」「格式说明」**（2026-10 用户要求）：`ui/BankLibraryScreen` 精简为「取出 / 导入 / 管理（逐题编辑、改名、删除）」，`ReviewPage.FormatHelp` 与 `BankFormatHelpScreen`（含 JSON 样例常量）整个删除，`ReviewActivity` 的 `help` 路由编码 / 解码与 `onOpenTemplate` 参数一并去掉。只服务于它们的 `ReviewDatabase.createBank` / `mergeBanks` 与 `BankManageLogic.resolveMerge` / `MergeCheck` / `planQidRemap` / `MergeReport` 随之删除（`BankManageLogicTest` 删合并相关用例）。题库的来源从此只剩「内置库取出」与「JSON 导入」两种。
- **重构答题界面**（2026-10 用户要求「重构一下答题界面」）：1076 行的 `PracticeScreen.kt` 拆成四份 —— `ui/PracticeAnswerSheet.kt`（答题卡入口条 / 整页网格 / 格子 / 图例）、`ui/PracticeSummaryPane.kt`（总结页）、`ui/PracticeAiController.kt`（AI 配置加载、对话历史、用量累计与流式状态机，从 `onAsk` 的 60 行闭包里抽成独立控制器；**不持有 session**——作答文本由调用方传入，避免重组间拿到过期引用）、`ui/PracticeExamCountdown.kt`（倒计时显示），主文件瘦到约 570 行、只负责拼装。拆分之外五处体验修正：① **进度条改显作答进度**（已答/总题数）——此前显示的是正确率，与副标题重复，且"答一半全对"时进度条是满的，会让人误以为做完了；② **题间切换加滑动过渡**（行进方向 1/4 屏宽 + 淡入淡出 240ms，系统开「减弱动效」时退化成瞬时淡入），换题同时**把内容区滚回顶部**——此前长题滚到中段翻页，新题会从屏幕中间开始看；③ **倒计时下沉**：剩余时间此前是顶层状态、被顶栏 subtitle 读取，限时考试期间整个练习页每秒重组一次；现在显示由独立的 `ExamCountdownText` 自持（每秒只重组那一行小字，移到进度条行尾），超时循环只保留 5/1 分钟警告与自动交卷两个动作；④ **「自动下一题」开关并入答题卡入口条**行尾，省出一整行给题干，说明文字改为开关打开时才出现的一行小字；⑤ AI 状态机的行为不变（含"每次回到练习页重读配置"），只是搬进了 `rememberPracticeAiController`。
- 修复乱序抽题在非数字题号下**悄悄退化成顺序扫描**：`pickQuestions` 的 `ORDER BY (qid || bank_id) * 1103515245 % …` 依赖 SQLite 把 TEXT 参与乘法时 CAST 成数字，qid 非数字开头时整个表达式变 0、所有题并列，「乱序练习」名存实亡（qid 原样入库，数字与否完全取决于导入数据）。排序整个挪到 Kotlin 层：SQL 只做过滤（顺序模式顺带把 `LIMIT` 下推进 SQL），新增纯函数 `review/QuestionOrder.kt` —— `randomOrderKey` 用 `String.hashCode`（算法由 JLS 规格固定，跨进程跨设备稳定，进程重建「重抽即同序」依旧成立），追加 `bankId::qid` 字典序决胜键保证全序确定；`wrongFirstOrderComparator` 的错题优先分组一并移出 SQL，靠一次 `unmasteredWrongKeys()` 小查询取集合（由 `QuestionOrderTest` 5 项覆盖）。
- 修复 `PracticeSession.key()` 的字符串模板写错：`"${q.bankId}::$q.id"` 里 `$q` 把**整道题的 toString()（题干+选项+解析全文）**插进了作答键、后面的 `.id` 只是字面文本 —— 作答、判分、答题卡、快照全部在拿全文长串做 map key，作答快照 `exportState()` 因此膨胀几十倍，大题库的 `rememberSaveable` 有超 Bundle 上限被系统杀掉的风险。改为 `"${q.bankId}::${q.id}"`（同一会话内 key 前后一致，判分口径不受影响；旧格式快照不跨版本存活，无需兼容）。
- 作答落库**批量单事务 + 失败兜底**：`ReviewDatabase` 新增 `recordAnswers(List<AnswerRecord>)`，整批一个事务 —— 此前交卷补判 N 题就起 N 个协程、开 N 个独立事务（每题 3~4 条 SQL）；单题 `recordAnswer` 保留为便捷入口。`PracticeScreen` 的落库协程加 `runCatching`，失败时 `GlassToaster` 提示一次（防刷屏）—— 此前 IO 抛错会**静默丢作答记录**，用户答了题、统计与错题本却没记住且无任何提示。
- `ReviewDatabase` 新增 `bankName(bankId)` 单行查询：题库详情页与逐题编辑页此前为取一个标题要跑 `listBanks()` 全表（每行还带一个错题本关联子查询）。
- 逐题编辑页**改懒加载**：`BankQuestionEditScreen` 的题目列表从 `Column + verticalScroll + forEach`（进页面一次性组合全部题卡，1059 题的库肉眼可见地卡）改为「工具行常驻 + `LazyColumn`（`key = qid`）」。仍不给外层加滚动 —— 同轴嵌套滚动的崩溃教训照旧有效。
- 删除「**查看 → 统计**」入口与整页统计（2026-10 用户要求「将查看的统计删除」，经确认选择「入口 + 统计页一起删」）：`ui/BankStatsScreen` 整页删除、`ReviewPage.BankStats` 与其 `stats:` 编码 / 解码一并去掉、`BankDetailScreen` 去掉 `onOpenStats` 参数（「查看」卡片只剩 错题本 / 收藏）。只服务于它的 `ReviewDatabase.dailyStats()` 与 `daily_stats` 表的建表 / 写入也**一并删除** —— 留一张没有读方的表就是"只写不读"的黑洞。顺带发现那条写入**本来就是坏的**：`INSERT OR REPLACE INTO daily_stats (day, answered, correct) VALUES (?, 1, ?)` 撞主键是整行替换，所以每天的 `answered` 永远被写成 1 ——「近 7 天每日答题量」显示的从来不是答题量，而是一排 1；一个没人能正确解读的指标比没有指标更糟。题库详情页概况卡里的「已练正确率」不受影响，一轮练习结束后的总结页也会给出本次正确率。老安装里 `daily_stats` 会作为空壳残留（里面还有历史数据），不影响任何功能，想彻底清掉可以卸载重装。
- 练习页的判分按钮**统一叫「确认答案」**（2026-10 用户要求「有的按钮是确认答案，有的是提交答案，统一成确认答案」）：此前 `QuestionCard` 按题型分两种字样 —— 单选「确认答案」、多选「提交答案」。同一个动作在一页里有两个名字，用户会以为多选那颗是"交卷"，而它其实只是"现在揭晓这道题的解析"。
- **统一题库练习模块的按钮排版**（2026-10 用户要求「将整个题库练习的按钮排版一下」）：`review/ui/ReviewButtons.kt` 把高度抽成具名常量 `ConfirmButtonHeight`(48dp) / `SecondaryButtonHeight`(42dp)，并给 `SecondaryAction` 加形参 `height`。★ 修掉的真实缺陷是**主按钮与次级按钮并排时底部参差**：`Row` 默认按 `Alignment.Top` 排列，而两者原本固定差 6dp，于是右半边永远矮一截 —— 练习页底栏（上一题 / 下一题·完成·提交）与题库仓库页（创建题库 / 合并题库）都中招。修法是**与主按钮并排时把次级按钮抬到 48dp**（主次改由样式区分，不再靠高度），并补 `verticalAlignment = CenterVertically`。胶囊类按钮（错题本 / 收藏 / 取出 / 上移…）保持 42dp 不变。
- **交卷时把"选了答案但没点确认答案"的题一并判分**（2026-10 用户要求「若用户只选择选项之后提交要判断对错，不要只是在该题做完后点确认答案后给出对错」）：`PracticeSession` 新增 `judgePending(): List<ReviewQuestion>`，`PracticeScreen.submit()` 在取汇总数字前先调它，并把**新判分的题逐条落库**（`records` / 错题本）—— 判分即落库这条不变量对补判的这批同样成立，否则交卷后数字变了、错题本却没记住。三条边界：① **只补判"有作答"的题**，从头到尾没碰过的题保持未作答（没答就是没答，编一个"错"出来比留白更糟，限时考试到点会留下大片这种题）；② **已经判过的不动**，否则用户点过「再想一下」的撤回会失效；③ **背题模式恒返回空**，把"背题不判分"这条不变量再守一道。**限时考试到点自动交卷走的是同一条路径**，因此超时交卷也不再需要单独一套口径 —— 旧的 `PracticeSession.resultForTimeout()` 只是 `result()` 的别名，已删除（`ExamTimerTest` 里那两条依赖它的测试相应改写成"没碰过的题仍算未作答"与"选了没确认的题会被补判"）。
- 修复 Markdown 把**不成对的星号**原样露给用户（2026-10 用户报「软件里 md 的残留 `****`」）：`ui/system/MarkdownText` 新增纯函数 `sanitizeMarkdown`，在"判定走不走富文本"与"真正渲染"**之前**统一清洗。规则三条：① 空加粗（`****`、`** **`）整对删掉（commonmark 的可配对规则本来就不认它，只会渲染成字面星号）；② 标记数为奇数时**只删落在片段首尾**的那一个 —— 流式输出里露出来的正是它，而落在中间的不动（否则 `2**3` 会被改成 `23`，比留着星号更糟）；③ 行内代码与围栏代码块**整段保护**（`**kwargs`、`a ** b` 是内容不是标记）。`AnnouncementDialog` / `AnnouncementScreen` 里直接调 `Material3RichText` 的两处也接上同一道清洗。
- 修复「在题库仓库页面向上滑动后，题库仓库的字样没有改变」（2026-10 用户报）：`BankLibraryScreen` 此前走 `GlassPageScaffold` 的默认值 `collapseFraction = 0f`，于是内容滚动时大标题**始终不变** —— 而课表 / 成绩 / 设置 / 二课 / 一卡通都会收成细玻璃条，同一个手势在不同页面表现不一致。补上 `rememberLazyListState` + `derivedStateOf`（首项已滚出屏幕 ⇒ 直接收满；否则按首项偏移 / 96dp 连续跟手），与其他页面同一判据。
- 复习模块补上 **overlay 宿主**：`ReviewActivity` 根层新增 `GlassWindowHost` + `GlassToastHost`。此前全项目只有 `MainActivity` 挂了 toast 宿主，而 `GlassToaster.show()` 只是往一个全局状态写值、真正渲染它的是 `GlassToastHost` —— 复习模块里 **23 处提示因此全部静默失效**，包括限时考试的「时间到，已自动交卷」「还剩 5 分钟」、题库的「已保存」「已删除」「导入失败：…」。挂 `GlassWindowHost` 而不是只加一个 `Box`：`GlassPageScaffold` 见到已有 `DialogHost` 就复用，整个复习模块因此共用同一套弹窗 / 玻璃折射 / overlay 栈。
- 复习模块的卡片补上**「淡入上移」入场动效**，且**复用全项目同一套 `ModuleMotion`** 而不是另造一份：新增 `ui/ReviewMotion.kt`（`LocalReviewEntrance` 进度源 + `rememberReviewEntrance(key)` 页面级时间轴 + `Modifier.reviewCardEntrance(group)` 转发给 `moduleEntrance`）。复习模块是独立 composition，`LocalModuleEntrance`（MainActivity 的底栏切页提供）在这里取不到、`moduleEntrance` 会退化成空操作，所以自带进度源。**刻意用页面级时间轴而不是每张卡片自持动画**：后者在 `LazyColumn` 里滚回来会重播一遍，滑一下列表就满屏闪；页面级时间轴只在进这一页时走一次（605ms），之后滚入滚出的卡片读到的进度恒为 1。`ReviewFlow` 以当前页为复位键下发，换页即重播；系统开「减弱动效」时直接落终态。
- 复习模块的 AI 答疑新增**思考过程可折叠 + 用量显示**（2026-10 用户要求「ai 解答的思考构成可折叠，然后显示回答的内容和用量」）：`AiChatClient.Result.Success` 与内部 `Attempt.Ok` 补上 `reasoning`（此前 `ChatReply` 本来就有，但 `readStream` / `readWholeBody` 把它丢了 —— 于是流式一结束思考过程就**永久消失**），`AiUiState.Done` 扩成三字段，`AiExplainPanel` 新增 `ThinkingBlock`（流式中默认展开、出正文后自动收起，也可手动展开/收起，折叠条显示「思考过程（N 字）」）与 `UsageFooter`（本轮 + 本题累计；两者相同时不重复渲染第二行）。`AiUsage` 加 `Zero` 与 `cumulative()`，`PracticeScreen` 持有 `aiUsageTotal`（换题归零）。顺带修掉一个真实缺陷：`Done` 态下同一段 AI 回答在面板里**显示两遍**（既渲染 `state.text`、又渲染 `history` 末尾那条）。
- 练习页答题卡从「页内折叠面板」改为**独立整页**（2026-10 用户要求改成弹窗形态）：`GlassSubpage` + `LazyVerticalGrid`，页内只留一行常驻摘要（已答 N/M · 对 X · 错 Y）。这不只是形态变化 —— 页内版本处在 `Column(Modifier.verticalScroll(...))` 里，任何同轴懒加载容器都会收到 `maxHeight = Infinity` 并在测量阶段抛异常崩进程（就是「点开始练习退回课表页」那个事故的同一根因），所以页内版本只能退而求其次用非懒的 `FlowRow`、320 题的库要一次性测量全部格子；独立页没有外层滚动，可以真正懒加载。
- 复习模块新增**「AI 正在生成」全局胶囊**：AI 面板内嵌在可滚动的题干列里，面板一收起（或用户往下滚到答题卡 / 底部导航），界面上就没有任何"还在生成"的信号了，用户只能干等十几秒、会以为卡死。胶囊常驻在底部（Toast 之上），点一下把 AI 面板重新打开。位置刻意放在复习模块的**根层**而不是练习页里 —— 放页内的话它会跟着题干一起滚走，等于没做。
- 复习模块的顶栏标题**按长度自动降档**：`ui/system/SystemUi.kt` 新增 `expandedTitleSizeSp`（≤8 字 28sp / ≤14 字 24sp / 更长 20sp），展开态不再写死 28sp。内置题库里有 20 字的库名（「毛泽东思想和中国特色社会主义理论体系概论」），28sp × 20 字 ≈ 560dp 在手机上必然放不下。
- 清理抢课遗留死代码：删除 `ui/system/LiquidTaskSurface.kt` / `LiquidTaskButton.kt` / `LiquidTaskControls.kt` / `StateSymbols.kt` / `AnimatedStateIcon.kt`（`main` 里零引用，只有 androidTest 还在用），`animatedIconSpec` / `ActionLineIcon` 并入 `AnimatedIconSpec.kt` 保留（仍被 `GlassKit` / `LiquidComponents` / `SystemActionMenu` / `LiquidActionGroup` 使用）；`SettingsRoute` 里 `cookieUpdateFeedback` / `cookieUpdateResult` 两个**写进去没人读**的死状态一并删除。androidTest 相应改写 4 个测试夹具、删除 3 个已无对应实现的测试。
- 修复 DeepSeek Key 密文被上传云备份：`res/xml/backup_rules.xml` 与 `data_extraction_rules.xml` 补 `<exclude domain="sharedpref" path="review_ai_prefs.xml" />`。它与项目已排除的 `secure_credentials.xml` 完全同类（都是本机 Keystore 密文，换机后本就解不开），此前漏排除。
- 练习页改为「**先撑满、放不下才滚动**」+ **底栏常驻**（2026-10 用户要求，借鉴参考项目首页的自适应技巧）：整页从"一个 `verticalScroll` 的 `Column`"改成"外层撑满整屏（只吃 scaffold 的底部 inset）+ 内层内容区 `weight(1f)` 吃满剩余高度并滚动 + 底部「上一题 / 下一题」常驻"。此前底部那行也在滚动列里，**题干一长就被顶到屏幕外**，用户必须先滑到底才能翻页。⚠️ 同轴滚动容器仍然只有一个（`weight(1f)` 给的是确定高度、不是 `Constraints.Infinity`），不会触发「点开始练习退回课表页」那个崩溃。
- 模块改名：**「复习」→「题库练习」**（2026-10 用户要求）。改动点：`SettingsScreen` 的入口标题与副标题、`ReviewHomeScreen` 的顶栏标题、`ReviewActivity` 的类注释与「复习模块的页面」注释。模块内部标识（`ReviewActivity` / `ReviewPage` / `ReviewDatabase` / `ReviewPrefs` 等）**一律不改** —— 它们同时是类名与落库键的一部分，为了一个显示名去动它们只会制造无谓的迁移风险。
- **「随机练习」→「乱序练习」**（2026-10 用户要求）：`ReviewMode.RANDOM` 的显示名改掉，枚举名 `RANDOM` 与落库值**保持不动**。同时在 `ReviewMode` 的注释里写明它与 `ReviewPrefs` 的「选项乱序」的区别 —— 前者管**题目顺序**，后者管**同一道题里 A/B/C/D 的排列**，两个"乱序"在界面上同时出现时必须有明确分工（题库详情页的模式说明里也写了这一句）。
- 「我的题库」每一行**去掉「删除」按钮**（2026-10 用户要求，删除归题库管理、随题库管理一起搬进题库仓库页）：行内只剩上移 / 下移 / 移出。「移出」只改显示状态、数据全留着，「删除」不可撤销 —— 两者并排放在一行极易点错，现在物理上分开了。
- 修复「打开答题卡后背景是题目的页面，看不清」（2026-10 用户报）：`GlassWindowHost` 在 `LocalGlassWindowOwner.current === LocalView.current` 时会**提前 return**（它认为"这个窗口已经有底图了"），而 `GlassSubpage` 的 Page 弹层正跑在同一个窗口里 —— 于是弹层底下根本没有壁纸，只剩 `DialogHost` 那层 22% 的黑色遮罩，**上一页的内容整片透出来**（答题卡格子里的数字和题干文字糊在一起）。修法：`DialogHost` 对 `DialogPresentation.Page` 补画一层 `drawWallpaperPattern`（纯 Canvas 绘制、不依赖 backdrop，所以不支持折射的设备上同样有效），弹层因此成为真正的不透明整页。**刻意不挂 `layerBackdrop`** —— 那份 layer 的宿主是窗口根部的 Canvas，同一个 backdrop 挂两处会互相覆盖。这条修复同时惠及课表设置 / 课程编辑 / 桌面组件看板等其余 `GlassSubpage` 使用者。
- 用户手册（`用户手册.md`）补：10.4.1「底部导航（可重排 / 可隐藏）」、10.3.3 题库练习模块「题库仓库与题库管理」小节、11.3「本学期课程（批量开关）」；题库练习模块再补「背题模式 / 斩题」「今日待复习（间隔复习）」「错题本三态」「限时考试」「题库仓库与我的题库（排序）」「选项乱序」「AI 思考过程与用量」「演示题库」八段（第 14 章 FAQ 加 Q35–Q37）。`QuestionCard` 删掉该按钮及其 `slashed` / `onToggleSlash` 参数。⚠️ **已知后果**：`slashed` 表与 `excludeSlashed`（默认 true）都还在，此前被斩过的题仍然不会出现在练习里，而**应用内已没有任何改斩题状态的入口** —— 要放回得另加「斩题本」入口或关掉过滤；`slashQuestion` / `unslashQuestion` / `isSlashed` / `SlashPolicy` / `onlySlashed` 等随之成为无调用点的代码，但**保留**（`SlashPolicy` 有单测覆盖，且它是「斩题 ≠ 已掌握」这条不变量的书面约束）。
- 修复「题库页底部内容被截断、要上滑才看得全」：`BankLibraryScreen` / `BankQuestionEditScreen` / `BankDetailScreen` / `BankWrongBookScreen` 的根容器都**漏吃 scaffold 的 bottom inset**（只 pad 了 top），末位内容被系统导航条压住。其中前两个页面还是「外层定高不可滚 + 内层固定 `heightIn` 限高滚动」的结构 —— 题库/题目一多（或系统字体放大）底部就顶出屏幕且**永久不可达**；现内层改为 `weight(1f)` 吃掉剩余高度（工具行常驻顶部、列表在下方独立滚动）。**刻意不给外层加 `verticalScroll`**：那会与内层形成同轴嵌套滚动，子节点收到 Infinity 高度约束后 `ScrollNode` 直接抛异常崩进程 —— 正是下面那条崩溃的同一根因。
- 修复「点开始练习后退回课表页」：`QuestionCard` 自带的 `Modifier.verticalScroll` 被 `PracticeScreen` 的 `verticalScroll` 以**无限高约束**测量，内层 `ScrollNode` 抛 `IllegalArgumentException`（"Vertically scrollable component was measured with an infinity maximum height constraints"）→ 测量阶段崩进程 → 系统重启应用落回 `MainActivity` 的课表页（底栏下标是伴生 object，进程重启即清空）。修法：删掉 `QuestionCard` 的内层滚动，滚动只由 `PracticeScreen` 承担。上一版（422cffc）只改了 `ReviewActivity` 的一行参数，未触及根因。
- 复习首页**移除**跨题库的错题本与收藏入口（2026-10 用户要求）：首页只负责选题库，开练与错题本 / 统计全部收进题库详情页 —— 跨库混在一起既看不出归属，也与"按库练习"的心智不符。
- 题库详情页的入口按新流程重排：原先单列的「今日待复习（N）」入口不再出现，改由**错题本页**承担（那里有「今日到期」汇总与「练习待复习错题（N）」按钮）；详情页改为「统计」「错题本」两个**常显**胶囊按钮（数量为 0 时点进去是空列表，而不是入口消失 —— 项目红线：入口不按状态条件隐藏）。
- 用户手册（`用户手册.md`）补：10.4.1「底部导航（可重排 / 可隐藏）」、10.3.3 复习模块「题库管理」小节、11.3「本学期课程（批量开关）」；复习模块再补「背题模式 / 斩题」「今日待复习（间隔复习）」「错题本三态」「限时考试」四段（第 14 章 FAQ 加 Q35–Q37）。

## [1.2.6] - 2026-09-26

### 新增

- 一卡通（校园卡）只读查询：`ykt/YktClient`（`queryCard` 卡余额 / `ecardConfig` 展示口径 / `feeItems` 费项 / `feeItemDetail` 费项详情与级定义 / `thirdData` 级联取数与最终读数 / `electricityBalanceAt` 读数文本解析 / `turnoverSummary` 消费汇总 / `billUrl` 官方明细入口）、`ykt/YktModels`（`YktCard` / `YktEcardConfig` / `ElectricityBalance` / `YktFeeItem` / `YktFeeItemDetail` / `YktSceneLevel` / `YktThirdData` / `YktSceneContext` / `SceneOption` / `ScenePick` / `YktSceneSelection` / `YktTurnover`）、`ykt/YktBalance`（纯函数：金额分转元、`parseElectricityBalance` 从"剩余电量…"文本抽值并**推断单位**、`computeTotal` 按 `ecardConfig.type` 算总额、`extractFeeItemId` 从应用项 URL 抽 `feeitemid`、`monthRange` / `monthLabel` 用 `java.util.Calendar` 算月度区间与标签）、`ykt/YktSelectionStore`（按账号记忆上次选定的费项与各级房间选项）、`ykt/YktStore`（令牌按账号落盘，**无任何密码存取 API**）。入口「我的 → 校园服务 → 一卡通」（`YktActivity`）。
- 一卡通登录页 `YktLoginActivity`：WebView 走学校统一身份认证（站点 `loginType` 只有 `sso`，**不做账号密码表单**），`onPageFinished` 主动从 **`sessionStorage`**（非 Cookie，真机 + 反混淆双重确证）采集 `access_token` + `token_type` 拼成 `synjones-auth`，采集前过域名白名单；附白屏修复（本站 `vh` / 百分比高度基准为 0，改用 `window.innerHeight` 像素高度）。
- 宿舍电费低余额提醒：`ykt/YktAlertPolicy`（纯函数判定：金额型且低于阈值才提醒，**不限次数**）、`ykt/YktAlertSettings`（开关 + 阈值，默认 20 元，全局）、`ykt/YktAlertScheduler` / `YktAlertReceiver`（AlarmManager 每日巡检，所以"每次都提醒"的实际频率是每天一条）、`ykt/YktAlertStateStore`（只用于排查与未来可能的节流，**不参与去重**）、`ykt/YktAlertNotifier`（`IMPORTANCE_HIGH` 渠道，同一 id 覆盖旧条）。**度数型费项不参与金额阈值**（`supportsAmountAlert`）；余额解析失败时**不提醒**（宁可漏报不可误报）。
- 一卡通页面 `ui/screen/YktScreen`：卡余额卡、账户详情卡、`ElectricityCard`（费项选择 + 校区/楼栋/楼层/房间四级级联 + 余额）、`ConsumptionCard`（按月消费汇总，左右切换本月/上月/更早，含「查看逐笔明细」入口）、提醒设置卡、能力边界说明卡；未登录时给**可操作**的 `LoginRequiredCard` 而非一句"请先登录"。
- 只读官方页面查看器 `YktWebActivity`：打开一卡通官方账单/明细页（`billUrl`）。与登录页**共用 `ykt` 存储目录**以便复用登录态，但**不读、不采、不落盘任何令牌**（由 `YktNoPasswordGuardTest` 断言守住）。Manifest 已注册。
- 单测：`YktBalanceTest`（71 项，含费项抽取的内嵌 JSON 串形态、月度区间跨年/闰月边界）、`YktClientAppSchemeTest`（8 项，钉住"首页要扫**所有**组件、不能只取第一个"）、`YktAuthCookieTest`（30 项，含 Web Storage 主路径）、`YktAlertPolicyTest`（20 项，含"不限次数"与"解析失败不提醒"）、`YktLostConfirmTest`（17 项）、`YktLostResultTest`（12 项，含 `60007` 只在挂失方向算成功）、`YktBillPageTest`（11 项）、`YktNoPasswordGuardTest`（5 项，含只读浏览页不导出凭据）。
- 空闲教室（空闲场地查询）：`emptyroom/EmptyRoomModels`（`EmptyRoom` / `EmptyRoomOption` / `EmptyRoomFilters` / `EmptyRoomQuery` / `EmptyRoomCampusOptions`，以及纯函数 `weekMask` / `periodMask` / `weekdayList` / `termParts` / `validateQuery` / `seatRangeInvalid` 与校区归属判据 `buildingOptionsFor` / `periodOptionsFor`）、`emptyroom/EmptyRoomParser`（查询页筛选项解析、结果信封解析、校区可选项 `campusOptions`）、`emptyroom/EmptyRoomClient`（`loadPage` / `query` / `campusOptions` 三个入口，各自返回 `PageResult` / `QueryResult` / `CampusResult`，登录失效判据统一为"响应是登录页"）、`ui/screen/EmptyRoomScreen`、`EmptyRoomActivity`。入口「我的 → 校园服务 → 空闲教室」。单测 `EmptyRoomContractTest`（47 项）。
- 课程提醒设置页：`schedule/ScheduleOccurrences`（`weekOneMonday` / `parseClock` / `occurrenceAt` / `courseWindows`，提醒与自动模式**共用同一份**时间基准 —— 各算各的会出现"提醒响了但勿扰没切"）、`schedule/AutoModePlan`（`AutoModeKind` / `AutoModeRuntime` / `AutoModeAction` / `decideAutoMode`，纯决策无 Android 依赖）、`schedule/ReminderSettings`（设备级偏好 `settings.master` / `settings.lead` / `settings.auto-mode` + 运行态 `auto.applied-kind` / `auto.prev-filter`）、`schedule/AutoModeReceiver`、`ui/screen/ReminderSettingsScreen`（与课表设置**共用同一个 `GlassSubpage`**，由 `showReminderSettings` 在两层间切换）。单测 `ReminderSettingsTest`（9 项）/ `AutoModePlanTest`（11 项）。
- 课表日期条的滑动指示器：`ui/system/LiquidSelectionComponents` 的 `LiquidSegmentedControl` 新增 `edgePadding` / `verticalInset` / `restingRefraction` / `showTrack` / `showIndicator` 五个可选参数（默认值与原硬编码值一致，既有调用方零改动），日期条用 `0.dp` / `0.dp` / `0f` / `false` 与下方网格逐列对齐、且静止态不折射以免把密排文字拉糊；滑块落点抽成纯函数 `ui/screen/ScheduleDateStripSelection.scheduleDateStripSelection`，由 `ScheduleDateStripSelectionTest`（9 项）覆盖。顺带把此前声明了却从未调用的 `WeekHeaderCompact.onDayClick` 接上（日视图直接翻页、周视图切到日视图并定位）。
- 应用内组件工作台：`widgetboard/WidgetBoardModels`（四列网格布局模型 `WidgetInstance` / `WidgetBoardLayout` / `WidgetBoardStore`，JSON 持久化 + 损坏回退默认布局 + 下架卡片丢弃）、`widgetboard/InAppWidgetRegistry`（七种卡片规格与可用尺寸）、`widgetboard/WidgetBoardData`（复用 `ScheduleWidgetState.from()`、`ExamCountdown.parseStart`、`SecondClassExtraScore.compute()` 组装卡片数据）、`secondclass/SecondClassOverviewCache`（二课积分按账号隔离的本地缓存，12 小时新鲜窗口）。
- 工作台界面：`ui/screen/WidgetBoardScreen`（`LazyVerticalGrid` 四列 + `detectDragGesturesAfterLongPress` 拖动换位 + 尺寸调节面板 + 组件抽屉带实时预览 + 每张卡片「添加到桌面」）、`ui/screen/WidgetBoardCards`（玻璃卡片外壳与七种卡片渲染，文字显式 lineHeight 防字体放大破版）、`ui/route/WidgetBoardRoute`（数据装配、每 30 秒对齐一次当前时间、点卡片跳转）。
- 入口：课表「···」菜单与「我的」页各加一项「组件工作台」（`MainActivity.AppTabNavigation.request` 提供跨 Tab 跳转）。
- 手机桌面卡片组件：`widgetboard/CardWidgetProviders`（七种卡片各一个无参 `AppWidgetProvider` 子类 + 卡片 id 与组件的一一对应表 `CardWidget`）、`widgetboard/CardWidgetRenderer`（`cardDraft()` 纯函数组装一屏文字，`views()` 按组件实际尺寸决定留几行、截断时标注「还有 N 条」，小尺寸下先砍汇总再砍副标题而不是把字裁成半截）、`widgetboard/CardWidgetDataLoader`（同步只读本地缓存，可用性判据与 App 内一致）、`widgetboard/CardWidgetUpdater`（缓存 / 会话 / 设置变化监听 + 节次边界闹钟 + 「添加到桌面」的 `requestPinAppWidget`）、`widgetboard/CardWidgetRefreshReceiver`（开机 / 更新 / 改时间重画入口）、`widgetboard/CardWidgetNavigation`（点卡片落到底栏对应 Tab，课表类卡片额外复位到本周今天）。
- 桌面组件资源：`res/layout/card_widget.xml` + `card_widget_row.xml`（七种卡片共用骨架，行容器动态 `addView`）、`res/drawable/card_widget_light.xml` / `card_widget_dark.xml`、`res/xml/card_widget_*_info.xml`（七份，最小尺寸与选择器文案各自不同）、`res/values/card_widget_strings.xml`。
- 课表 5 天布局（只看周一至周五）：`schedule/ScheduleDisplayStore` 新增 `showWeekend` / `setShowWeekend`（默认**显示**，全局持久化）；可见天数与被隐藏的周末课程数抽成纯函数 `schedule/ScheduleVisibleDays.scheduleVisibleDays` / `hiddenWeekendCourseCount`，由 `ScheduleVisibleDaysTest`（10 项）覆盖。`ScheduleScreen.WeekHeaderCompact` 的星期条改为 `WeekdayShortLabels.take(visibleDays)`（保留「第 N 格 = 星期 N」的不变量，日期与今天圆点的下标换算零改动）；`ScheduleGrid` 新增 `showWeekend`，列宽按可见天数均分、`rememberCoursePeriodHeight` 也只实测可见课程；`TimetableBackground` / `TimetableLayout` 新增 `dayCount` 参数（默认 7，既有调用方零改动）；设置页「课表显示」新增「显示周末」一行。
- 日视图恒 7 天是刻意设计：日视图也收成 5 天就等于让周末的课彻底消失（那不是隐藏是丢数据），所以周末课程改由日视图承担展示，网格下方补一条「另有 N 门周末课程，可在日视图查看」（计数为 0 时整条不出现）。附带好处是分页的页单元换算 `(周-1)*7 + 天` 不必跟着开关变，翻页逻辑与开关彻底解耦，`pagerState` 也不会因为改这个开关而重建。
- 星期标签换算统一：`schedule/ScheduleWeekdayLabel`（`scheduleWeekdayChar` / `scheduleWeekdayShort` / `scheduleWeekdayLong`，越界返回 `'?'` —— 界面上一出现问号就说明上游给错了值，不该被悄悄掩盖成"周六"），由 `ScheduleWeekdayLabelTest`（5 项）覆盖。原先「一二三四五六日」+ 下标换算在补课弹窗、补课结果提示、课程详情页各写了一遍，星期条另有一份单字表，四处都靠"下标 = 星期 - 1"这个不变量活着；现在共用同一张映射，「第 N 格 = 星期 N」只剩一处定义。
- 「设置 → 外观 → 底部导航栏自动收起」：`AppearanceSettingsManager` 新增 `navBarAutoCollapseEnabled` / `updateNavBarAutoCollapse`（默认 **开**，与历史行为一致；键 `nav_bar_auto_collapse_enabled`），设置页「外观」组新增一行开关。判据落在 `MainActivity`：`navBarScrollConnection` 多读一个开关，`minimized = navBarAutoCollapseEnabled && navBarMinimized` —— 底栏本身不需要知道"为什么收起"，因此 `CapsuleNavigationBar` 零改动。`LaunchedEffect(selectedTab, enabled)` 的 key 加上开关，切开关时同时清掉收起状态与滚动方向累计值（否则重新打开时会按上一次的方向立刻再收一次）。
- 桌面组件的版式决策抽成纯函数 `widgetboard/CardWidgetLayout.cardWidgetLayout`（`widthDp` / `heightDp` / `fontScale` + 草稿 → 内边距、大标题字号、行高、角标与副标题/汇总的可见性、行数上限），由 `CardWidgetLayoutTest`（13 项）覆盖。原先这段算术埋在 RemoteViews 的渲染路径里，只能上真机摆一个组件肉眼验证。
- 「点某一行打开那门课」的落地判据抽成纯函数 `schedule/CourseOpenDecision.decideCourseOpen`（课程 id + 当前课表 id 集合 + 是否加载中 → 打开 / 等待 / 丢弃 / 无请求），由 `CourseOpenDecisionTest`（8 项）覆盖。它只有三个输入却有四种结果，而每种时机错了都表现为「偶尔点了没反应 / 偶尔莫名弹出详情」这类最难复现的问题：**命中必须优先于「加载中」**（课表已加载完、后续同步又把 `isLoading` 置回 true 时，课程其实就在手上，让它再等一轮会变成要点两次），加载中找不到要**留着请求**，加载完仍找不到才丢弃。
- `app/build.gradle` 的 `benchmark` buildType 补上用途与用法注释（原先只有一行 `initWith release` 加几个参数，看不出该在什么时候用，事实上从建立起就没有任何执行者）：它是唯一能量「R8 混淆优化**之后**」真实帧时间的变体（release 包不可 profile，量不了），配套 `app/benchmark-rules.pro` 保住 `androidx.tracing` 与 Compose `Recomposer`（否则仪器读帧要用的类会被 R8 裁掉）、`app/src/benchmark/AndroidManifest.xml` 声明 `<profileable android:shell="true"/>`。用法写进注释：`./gradlew :app:assembleBenchmark` 出包、`./gradlew :app:assembleBenchmarkAndroidTest -PuiTestBuildType=benchmark` 用优化产物跑仪器测试 —— 必须带 `-PuiTestBuildType`，因为 `testBuildType` 默认是 `debug`，而性能类仪器测试都带 `assumeTrue(BuildConfig.UI_PREVIEW)`，默认变体下会被整批跳过。这次一并实测该变体可用（`BUILD SUCCESSFUL`，产出 `app-benchmark.apk`）。
- `scripts/publish.py` 新增发布闸门 `EXPECTED_CERT_SHA256`：上传前的验包步骤要求包内证书 SHA-256 与常量一致，debug 签名的包一律拒绝（详见下方「变更」里的签名条目）。
- 应用内下载器新增公共「下载」目录备份 `AppDownloader.publishToPublicDownloads`：下载与完整性校验通过后，再往系统「下载」目录写一份同名副本，用 `MediaStore.Downloads` + `IS_PENDING`（先占位、写完置 0）做原子落盘 —— Android 10 / API 29 起写公共目录免存储权限；API 29 以下需要 `WRITE_EXTERNAL_STORAGE`，而本项目刻意不申请任何存储权限，因此那些设备直接跳过、继续走"浏览器到发布页下载"的老路。写入前按 `hnnu-jiaowu-v` 前缀清掉自己以前留下的备份，「下载」目录里始终只留最新一份，不会随版本堆积。全程 best-effort：失败只记日志并删掉半截文件，绝不影响下载结果（安装用的始终是私有目录里那份）。它要解决的是换签名版本的死路 —— 必须先卸载旧版才能装，而私有目录里的安装包会被卸载一并删掉。

### 变更

- 桌面组件改为**按真实尺寸出图**：`CardWidgetRenderer.responsiveViews` 在 API 31+ 读 `OPTION_APPWIDGET_SIZES` 逐个尺寸各出一份 `RemoteViews`，交给系统的响应式布局（`RemoteViews(Map<SizeF, RemoteViews>)`）；旧版本退回"横向 / 纵向两个变体"。`CardWidgetUpdater` 由"只传 `OPTION_APPWIDGET_MIN_*`"改为把整份 options 交给渲染层 —— 原先等于"无论用户拖多大都只画紧凑版然后拉伸"，大尺寸上该出现的教室、更多行、汇总全都没有。
- 桌面组件外观对齐参考实现：底色由半透明玻璃（`#EAF4F6FE` / `#E01B1B1F`）改为近乎不透明的 `#FAF8FAFD` / `#FA20262F`，圆角 24dp → 18dp，去掉 1dp 描边。壁纸颜色不可控，半透明的底会让同一份文字在深色壁纸上读不清。应用内卡片外壳同步由 20dp 改为 18dp，同一张卡片在工作台里和钉到桌面上轮廓一致。
- 今日时间轴新增轨道与圆点：桌面组件新增 `res/layout/card_widget_timeline_row.xml`（左侧 12dp 轨道槽：1dp 竖线 + 5dp 圆点），进行中的那一节点亮成强调色、其余静默；`CardRowDraft` 新增 `active` 字段承载这个状态。应用内时间轴卡片同步改成"左轨道 + 时间/状态一行 + 课名一行"，高卡多显示一行教室（与桌面组件"地方够才显示教室"同一取舍）。
- 组件抽屉新增「桌面上最小尺寸的样子」：`CardWidgetRenderer.previewBitmap` 用真实渲染器出图再截成位图（`RemoteViews.apply` → measure/layout → `Canvas`），尺寸取 `AppWidgetProviderInfo` 的 `minWidth`/`minHeight`。抽屉里原本只有一张 App 内的 Compose 卡片，而用户是在**添加之前**看预览的 —— 预览好看、桌面缺行的代价是加完再删一次。渲染失败或没有对应桌面组件时整段不出现。
- 「我的」页移除「组件工作台」入口：与课表「···」菜单里的那一项重复，设置页不该再挂一个内容入口；`SettingsScreen.onWidgetBoard`、`SettingsRoute.showWidgetBoard` 与那里的 `WidgetBoardRoute` 宿主一并删除。
- 组件工作台顶部新增常驻说明（`WidgetBoardScreen.WidgetBoardHomeHint`）：写明在系统里找本应用微件的**确切路径** —— 「长按桌面空白处 →「卡片」→ 卡片中心 → 一直往下滑到最底部 →「插件」→ 选「校园助理」」（桌面菜单里若直接有「小组件 / 桌面工具」，从那里进也一样）。这条路径是在 ColorOS 13 真机上逐层实测出来的，不是照抄文档 —— 空态进来的用户本来看不到卡片右上角的「添加到桌面」，这条说明是他们唯一能知道桌面组件存在的地方。刻意不做可关闭：它的价值恰恰在于每次进来都能看见，关掉就没有第二次机会了。
- 补课弹窗的**目标周和目标天都由调用方写死改为弹窗内可选**：`MakeUpCourseDialog` 的 `targetWeek` 改名 `initialTargetWeek`（语义从"目标"变成"初值"），新增 `FoldPanel.TargetWeek` 与第四个折叠选择器（全学期周次都给），`onConfirm` 由 `(源星期, 源周次, 目标星期)` 扩为 `(源星期, 源周次, 目标星期, 目标周次)`；`ScheduleRoute` 只把 `makeUpDay` / `makeUpWeek` 当**初值**，不再拿它们当答案。四行选择器**先目标、后源**排序（「补到第几周 / 补到哪一天 / 要补哪一周 / 要补哪一天」），对应"我要**在第几周周几**补课，补的是**那周那天**的课"这句人话；预览行仍是"源 → 目标"（`A 的课 → 落到 B`）—— 选择与预览语序刻意不同，前者按提问顺序、后者按因果顺序。补完若落到了别的周，`ScheduleRoute` 会把 `currentWeek` 跳到目标周（`weekJumpSeq` 换一个 `weekRequestKey` 触发 `ScheduleWeekPagerSync`），否则用户改了却看不到结果。
- 课表页「···」菜单移除「桌面组件」一项：桌面卡片现在统一从「组件工作台」加，不再单独维护一套只有课表的选择器。
- 桌面课表卡片点击的「回到今天」改走 `AppTabNavigation`（`MainActivity.EXTRA_OPEN_TODAY`）：与系统通知共用同一条落地通道，冷启动与热启动都复位到本周今天，不会再落到上次浏览的那一周。
- 桌面卡片支持**逐行点击**：`CourseRowUi` 新增 `courseId`（由 `ScheduleWidgetCourse.course.id` / `ScheduleOccurrence.course.id` 一路带下来，与 `ScheduleCourseUi.id` 同一套口径），`CardRowDraft` / `CardDraft` 各新增一个 `courseId`；`CardWidgetRenderer` 给带 id 的行单独挂 `PendingIntent`，「下一节课」卡则整张指向那一门课。`CardWidgetNavigation.intent` 新增 `courseId` 参数，并把 id **编码**进 data URI —— `PendingIntent` 判重不看 extras，逐行意图只能靠不同的 data 区分，否则所有行会共用一个（点哪一行都跳同一门课）；不编码时课程 id 里的 `:` 多数情况能侥幸解析，但一旦出现空格或 `#` 就会被截断，退化成同一个问题。请求经 `MainActivity.EXTRA_OPEN_COURSE` → `AppTabNavigation.requestedCourseId` 传给 `ScheduleRoute` 消费；拿不到 id 的行、以及课程已不在课表里（换账号 / 换学期 / 卡片过期）时，行为退回「落到课表页」而不是跳到一门不存在的课。
- `AppUpdateChecker` 支持强制更新：Release 正文里独立成行的 `[force-update]` 标记会被识别并从用户可见文案中摘掉，命中后弹窗不可关闭且无视此前的「以后再说」；标记的判定口径、`## notes` 的写法与校验规则写进了 `scripts/release_notes.sh` 的文件头。
- 底栏第 2 个 Tab 由「选课」换成「学工系统」：`StartupPage.Grab` 更名 `Xuegong`（route `grab` → `xuegong`，旧存偏好在 `decode` 回退课表），`BottomNavItem` / 分页 `when(page)` 同步换位；学工从独立全屏页改为常驻路由 `XuegongRoute`（复用 `XuegongScreen`，其 `onBack` 改为可空——子页打开时才显示返回箭头，Tab 根页面不显示，与其它 Tab 一致）。底栏「选课」专属的 `TaskControlsReservedHeight` 预留随页面一起移出（`GlassToastHost` 不再为第 1 个 Tab 让位）。引导页的「选课」卡片换成「学工系统」。
- `GlassFilterChip` 从 `ui/screen/CourseFilterPanel` 抽到独立文件 `ui/screen/GlassFilterChip.kt`：它是通用筛选芯片（成绩页的学期筛选、课表设置的开关都用它），不该跟着选课面板一起消失。签名与实现逐字保留，仅补上原本由同文件提供的 import。
- **发布签名改用正式密钥**（本轮最要紧的一条，它改变了用户这一版的安装方式）：新增 `app/release-key.jks`（RSA 4096 / SHA256withRSA / 10000 天有效期），口令与别名写入 `local.properties`（`RELEASE_STORE_PASSWORD` / `RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD`），两者都已被 `.gitignore` 覆盖、不入库；CI 侧由 `KEYSTORE_BASE64` 与同名三个 secret 提供（`.github/workflows/release.yml` 的 `Decode Keystore` 步骤无需改动）。起因是审计时用 `keytool -printcert` 实测发现：Gitee 上 v1.2.4 与 v1.2.5 的 `app-release.apk`，与本机 `~/.android/debug.keystore` 的证书 SHA-256 **完全相同** —— 也就是说此前发到用户手机上的包全部是 debug 密钥签的。机制上并不意外：`app/release-key.jks` 一直不存在、`local.properties` 也没有那三个口令，`hasReleaseSigning` 恒为 `false`，于是 `build.gradle` 第 66 行那道 `GradleException` 闸门因 `releaseKeystoreFile == null` 根本没机会触发，静默走了「缺正式签名就退回 debug keystore」的兜底（那段注释写的用途是"本地出包别产出装不上的 unsigned 包"，结果它同时接管了线上）。另一层后果是 debug keystore 每台机器各生成一把，换台机器构建就会产出换签名的包、用户无法覆盖安装。现在 `build.gradle` 的兜底仍在（本地确实没凭据时还能自测），但命中时会打印醒目告警；发布侧由 `publish.py` 的证书指纹闸门兜住。**换签名后存量用户必须卸载重装一次**，已单独成节写进本版公告与更新说明。这里还有个容易漏掉的副作用：`network/AppDownloader` 把安装包落在 `context.getExternalFilesDir("Download")`（应用专属外部目录），而**卸载会连同这份文件一起删除** —— 换签名的版本恰恰必须先卸载才能装，于是「应用内下载 → 卸载 → 安装」正好断在最后一步。为此下载成功后会再往公共「下载」目录写一份（见上方「新增」），公告也按新流程重写。
- `scripts/publish.py` 的签名校验从「有没有签名」升级为「证书对不对」：原先只断言 `apksigner verify` 返回 0 并打印证书 SHA-256 前 16 位（`已签名（证书 f59889b3f667f365…）`）—— 而 debug keystore 签出来的包同样能通过 `apksigner verify`，那串 sha256 看起来也完全正常，没人会去比对，兜底签的包就这样可以一路推到 Gitee。现在锚定 `certificate SHA-256 digest`（刻意避开 apksigner 同时打印的 `public key SHA-256 digest`，那是公钥、值不同）并要求等于 `EXPECTED_CERT_SHA256`，不等就带着"你是不是走了 debug 兜底 / 换密钥要同步改哪些地方"的说明直接 `die`。
- 移除 `androidx.navigation:navigation-compose:2.7.7` 依赖：`app/src` 全仓搜 `NavHost` / `rememberNavController` / `androidx.navigation` **零命中**，`dependencyInsight` 显示没有任何其它库要求 navigation（这一行就是整棵 navigation 依赖树的根），R8 早已把它全部裁掉（`usage.txt` 里 222 条、`mapping.txt` 0 条、release dex 0 命中 / debug dex 659 命中），因此对发布包体积与运行时零影响，占的只是 debug 包（未混淆）与编译 classpath。删它的真实理由是版本落差：2.7.7 与 Compose BOM 2026.01（Compose 1.9 系）差两个大版本，而 BOM 与 `kotlin.plugin.compose 2.3.10` 都不能降 —— 今天无痛只因为没人调用，哪天有人真写 `NavHost` 就会拿 2.7.7 的 API 去接 1.9 的 Compose。将来若要引入导航库，直接上当时的最新版，别把 2.7.7 捡回来（原位置留了注释说明）。
- 更新弹窗的 Release 说明改按 Markdown 渲染：`AppUpdateChecker.stripMarkdown` 改名 `notesForDisplay`（只摘 `[force-update]` 标记行，不再把 `**加粗**`、标题号、列表符剥成纯文本），弹窗正文改走新增的 `ui/system/MarkdownText`（复用现成的 `com.halilibo.compose-richtext`，没有加新依赖）；**不含 Markdown 标记时它退回普通 `Text`**，排版与以前逐字一致，所以全应用其它说明不受影响。发版链条上那条「notes 里不许有 Markdown」的约束同步放开：`scripts/release_notes.sh` 的 `notes_violations` 现在只拦行首 `#`（`## notes` 区块靠 `^## <段名>` 切分，notes 里出现 `## xxx` 会让后面的内容在抽取时被整段丢掉，**而且不报错**）与反引号（等宽底纹在窄弹窗里和中文糊成一片）；`extract_notes` 由「删掉所有空行」改为「只掐首尾空行」—— Markdown 里单个换行只是软换行，段与段之间没空行就会被并成一大坨，比纯文本时期更难扫读。

### 删除

- `XuegongActivity`（学工独立全屏页）：页面已上移为底栏「学工」Tab，调用点为零后连同 Manifest 注册一并删除。
- 旧的三套课表桌面组件：`schedule/ScheduleWidgetProvider`（双课程卡片 / 简洁单课 / 今日时间轴）、`schedule/ScheduleWidgetPicker`（App 内的样式选择器）、`schedule/ScheduleWidgetPresentation`（旧的 RemoteViews 渲染层）与 `schedule/ScheduleWidgetNavigation`（旧的自定义 scheme 深链），连同它们的 `res/layout/schedule_widget*.xml`、`res/drawable/schedule_widget*`、`res/xml/schedule_widget*_info.xml` 与两份 strings / styles。它们的"算什么"那半（课表状态换算）保留在 `schedule/ScheduleWidgetState`，由 App 内卡片与桌面卡片共用。
- 选课功能整套下线，共 29 个源码文件：`GrabActivity`、`service/GrabService`、`receiver/GrabAlarmReceiver`、`ui/route/GrabProRoute` / `CourseListRoute` / `AcademicRoutes` / `SelectedCoursesRoute`、`ui/screen/GrabProScreen` / `GrabQueueScreen` / `CourseListScreen` / `SelectedCoursesScreen` / `CourseFilterPanel`、`ui/system/TaskModeBar`、`academic/AcademicGrabQueue` / `AcademicGrabScheduler` / `AcademicQueueRun` / `ProtocolGrabRunner` / `AcademicCourseBridge` / `AcademicCourseActions` / `AcademicSelectionResolver`、`utils/GrabTaskUtils` / `TeachingClassMatcher`、`manager/SmartSelector`、`CourseDetailActivity`、`model/CourseFilter`，连同清单注册、前台服务与 `WAKE_LOCK` 权限、`proguard-rules.pro` 的两条 keep 规则、`DemoData` 的选课演示数据，以及选课/抢课相关的单元测试与仪器测试。
- `CourseApiClient` 的选课协议方法（`fetchCourseParams` / `fetchCourseDisplayParams*` / `fetchAvailableCourses*` / `fetchFilteredCourses` / `fetchSelectedCourses*` / `selectCourse*` / `fetchCourseSelectionDetails*` / `dropCourseSync` / `fetchCourses` / 展示参数缓存与 `buildAbsoluteCourseUrl`，共 421 行）与 `SchoolConfig` 的选课 URL 模板（`getAvailableCoursesUrl` / `getSelectedCoursesUrl` / `getSelectCourseUrl` / `getCourseSelectionDetailsUrl` 与 `courseDisplayPath` / `courseListPath` / `selectedCoursesPath` / `selectCoursePath` / `courseDetailsPath` 五个路径字段）。`CourseParser` 的 `parseCourseList` / `parseCourseParams` / `parseFilterOptions*` 与配套的 `FilterCategory` / `FilterOption` 内部类、`Course.getQueueStatusKey()` 同步删除。
- `用户手册.md` 第 5 章「选课」整章删除（后续章节顺次前移，正文交叉引用一并改号），`README.md` 与随包公告同步更新。
- `ui/system/GlassPerformancePolicy`（含 `GlassPerformanceTier` / `GlassDeviceProfile` / `GlassPerformancePolicy.resolve`）与配套单测 `GlassPerformancePolicyTest`：它从引入起就没有任何消费端 —— 主源码零引用，`git log -S` 全历史只命中 v1.1.7 那次整仓导入提交（不是"消费端后来被删了"），release 包里本来也被 R8 裁掉（release dex 0 命中）。留着只会让人误以为玻璃效果已经按设备能力分过级，而文件自己的 KDoc 写的就是「仅供诊断与后续选择等价实现；不会关闭任何玻璃特性」—— 想真正接入得先定"降档关什么"，那与这条既定取舍冲突，属于产品决策。真要再做，按当时的设备情况从零写。

### 修复

- **考级报名显示「读取失败」，其实是没有开放批次**（2026-09-24 线上反馈）：批次全部结束、或该功能被教务关闭时，教务返回的是一个**没有项目卡片**的合法页面。而 `KaojiParser.parseIndexPage` 原本把「一个 `xm_block` 卡片都找不到」直接当成「这不是考级页」返回 `null`，`KaojiClient.loadPage` 再把它变成 `Failure`，界面于是把"当前没有开放批次"渲染成「读取失败 —— 教务系统没有返回可识别的考级报名页面，可能该功能未开放」。现在解析器先按页面特征认页（入口表单 action 含 `kjbm_cxXskjbm`、页头用了 `sl_tit_kbmxm`、或页面带 `xmlbfl` 隐藏域），认得出来就返回 `projects` 为空的合法页面，界面显示「当前没有开放报名的考级项目」。真的认不出来时（例如正方返回「无功能权限」这类通用错误页，三个特征一个都不命中）也不再自造文案，改为把站点自己写的 `.nodata` / `.error_title` 原文透给用户。`KaojiPage` 新增 `pageNotice` 字段（默认空串，既有调用点零改动）承载站点提示语，列表为空时优先展示它；`KaojiParserTest` 新增 5 项把「零卡片是合法页面、不是失败」这条回归钉住。
- **考级报名只看得到「等级考试报名」，看不到「大类分流报名」「推免报名」**：客户端把类别参数 `xmlbfl` 写死成 `1001`，而教务侧的类别远不止一个 —— 2026-09-25 用测试账号实测，`xmlbfl=1003` 是一张合法页面（页头「2026-2027学年1学期参加大类分流报名」）、`1004` 也是（「2026-2027学年1学期推免报名」）。这两件事对本科生都是重量级通知，却被硬编码挡在门外。现在类别改为**从教务功能菜单发现**：菜单里每条 `clickMenu('N2510','/kjgl/kjbm_cxXskjbm.html?xmlbfl=1001','考级项目报名','null')` 就是一处权威登记，带着类别代码、入口路径和站点自己的标题 —— 菜单登记了几个就显示几个。**多类别时**页面顶部出现切换芯片，点一下即换；**单类别时界面与本版之前完全一致**，不会多出一排只有一个选项的芯片。菜单读不到（接口异常、条目里没带 `xmlbfl`）时退回 `1001`，保证不会比原来更差。新增 `AcademicMenu` 解析器（引号感知切分实参，兼容双引号、HTML 实体转义与整段内联进 `<script>` 的写法）与 `AcademicMenuParserTest` 9 项。
- **已报名记录只显示 15 条，而且以前一个字都不说**：这不是漏读，是服务端行为 —— `kjbm_cxXskjbm.html?doType=query` **完全忽略分页参数**。实测 `rows`、`page`、`limit`、`offset`、`pageSize`、`pageSizeInt`、`page.count` 以及 jqGrid 全套参数（`_search`、`nd`、`sidx`、`sord`），GET 与 POST 各试一遍，返回条数一律是 15；改 `xmlbfl`、去掉参数也一样。既然翻不动页，至少要把"被截断了"说出来：现在解析信封里的 `totalCount`，一旦它大于实际返回条数，列表下方就写明「教务系统该接口一次最多返回 15 条，这里显示的是最近 N 条（共 M 条）。更早的记录请到教务系统网页端查看」——否则用户会以为自己的报名记录丢了。
- **新增「本学期过期项目」**：教务网页端的页头一直挂着一个信封按钮（`cxGqxm()` → `kjgl/kjbm_cxGqxm.html?doType=query`），列出本学期已经截止报名的项目，App 此前完全没有接。现在考级页底部多出一块「本学期过期项目」，**点一下才查**——与网页端同样的交互，不会因为进页面就多打一个请求；切换类别时会丢弃上一个类别的结果，避免把 `1001` 的项目挂在 `1003` 下面。该接口的信封与已报名同族但**字段集不同**（没有 `xsbmqk_id`/`zkzh`/`zsbh`/`xmcj`/`sjhm`），硬塞进已报名的模型会得到一堆永远为空、语义还不一样的字段，所以单独建了 `KaojiExpiredProject` 并用一条测试把这个事实钉住。`KaojiParserTest` 由 16 项增至 26 项。
- **已报名记录不显示「缴费状态」，而这件事直接决定能不能退报**：教务自己的表格里就有这一列 —— `xskjbm.js` 的 `colModel` 里 `name:'sfqr'`（label 用的是模块 i18n 的 `jfzt = 缴费状态`，取值口径 `jfzt_all = "0:未缴;1:已缴"`），而我们从头到尾没把它显示出来。更麻烦的是它与退报绑定：站点退报前会查 `kjbm_cxXskjbmjfzt.html`，`sfqr == '1' || sfzfzzt == '1'` 就拒绝（「该项目已经缴费或正在缴费，无法退报！」），于是用户能看到「退报」按钮、点进去、确认了，才被告知已缴费不能退。现在记录上直接标出「缴费状态：已缴 / 未缴」（用教务自己的字，不另造说法），**并且已缴费的条目不再摆出退报按钮**。一个关键细节：`sfqr` 缺失 ≠ 未缴，而是"这一条不涉及缴费"（站点同样留空），所以那种情况整行不显示 —— 把它当成「未缴」会平白吓人。**顺带纠正一个前提**：这个字段一直在已报名列表的信封里（实测该账号三条记录里两条带 `sfqr=0`），所以这次不需要为展示多打一次 `kjbm_cxXskjbmjfzt.html`。同一批数据里还一直带着 `shjg`（审核状态，口径 `shzt_all = "1:待审核;2:审核中;3:已通过;4:退回;5:不通过"`），它是缴费的前置条件（`shjg != '3'` 时站点直接拦缴费），所以只在**异常时**显示（「已通过」是常态，逐条印出来是噪音）。
- **退报按钮把三种"不能退"混成了一句**：原先 `canWithdraw` 返回布尔值，界面于是给「已缴费」「往期批次」「过截止时间」一律印「已过报名截止时间，不支持退报」—— 用户会以为是自己错过了时间，而实际上该去查缴费。现在判定抽成纯函数 `KaojiWithdrawPolicy.blockReason()`（从 Composable 里挪出来，首次可单测），返回**具体原因**：已缴费 / 该批次已不在本学期开放列表中 / 已过报名截止时间；三种情形各说各的。`KaojiWithdrawPolicyTest` 8 项覆盖优先级（已缴费优先于其它）、不涉及缴费与未缴的区别、截止时间解析不出来时放行。
- **电费看不到、也没有校区/楼栋/楼层/房间可选**（内部回归，1.2.6 开发期发现）：`YktClient.collectAppViewItems` 只取「首页里**第一个**带 `combinedAppList` 的组件」。真机 `appScheme/info` 的首页有 **6 个组件**，`combinedAppList` 分别为 2 / 3 / 9 / 0 / 0 / 0，而电费两个入口（`elcpay`→181、`elec`→201）在**第 3 组**；又因为首页组件的 `componentKey` / `code` 全为 `null`，「按 key 含 `appView` 匹配」的分支永不命中，于是稳定取到第 1 组（天气 / 搜索）→ `feeItems` 判空 → 界面印「学校未开通电费查询」并**提前 return，把整块四级级联一起藏掉**（观感就是"没有那栋那层那间"）。现在改为**遍历首页（或全部菜单）的所有组件**收集应用项，不写死索引、不依赖 `componentKey`；该函数由 `private` 提为 `internal` 并由新增的 `YktClientAppSchemeTest`（4 项）钉住"必须扫所有组件"这条语义 —— 它此前无任何测试保护，正是它悄悄退化成"只取第一个"才导致这次问题。
- **应用项 `url` 是内嵌 JSON 串，抽 `feeitemid` 会带出尾引号**：同一批应用项里 `website` 是**干净 URL**（`/charge/feeitem/toAppitem?feeitemid=181`），而 `url` 是**一段被转义的 JSON**（`{"name":"App",…,"url":"/charge/feeitem/toAppitem?feeitemid=181"}`）。旧实现对 `url` 直接 `substringAfter('?')`，切出的是 `feeitemid=181"}`，尾部的 `"}` 会被当成 ID 的一部分。现在先剥引号 / 花括号，并识别内嵌 JSON 时解出内层 `url` 再取 query（新增纯函数 `embeddedUrlValue`）；`collectFeeItems` 改为 `website` 优先、`url` 兜底，名称取 `appName` 优先、`name` 兜底。`YktBalanceTest` 新增 6 项覆盖（含"`url` 之前另有问号时必须先解内层 url"这一条 —— 它是 `embeddedUrlValue` 的**唯一有效护栏**，其余形态单靠剥引号也能侥幸通过）。
- **校园卡消费记录无处可看**（用户反馈）：SPA 内**根本没有逐笔明细接口**（全站 95 chunk 核对过），只有区间汇总 `GET /berserker-search/statistics/turnover/count`（返回 `{income, expenses}`，单位分，字段名是**复数 `expenses`**）；逐笔明细在 `/campus-card/?name=billList`、`/merchant/?name=billList1`、`/charge/…` —— 那些是校方另一台服务器上的 jQuery 子应用，不带令牌直接访问 **401**，无法在客户端复刻。现在「一卡通」页新增 `ConsumptionCard`：按月（本月 / 上月 / 更早）展示支出与收入，并提供「查看逐笔明细（官方页面）」跳 `YktWebActivity`。**应用只给汇总、明细交官方页面**，不把汇总伪装成明细，避免用户误以为记录缺失。
- **一卡通电费级联：这里前后错过两次，最终按官方 `/charge-pc/` 口径做实**（用户反馈「校区列表加载失败，是否没有位置权限」）：要纠正的第一层是 —— **与位置权限无关**（应用没有任何定位权限，真机 `dumpsys package` 的权限清单里也没有）。真正的教训在第二层：先按 EasyUI 老页面的 `/charge/sceneroom/comboxCampus` 等端点实现级联，真机上恒返回 `200 + 正文 null`；于是判断"级联端点不存在"，据此**把四级选择器整个撤掉**、改成"只带 `feeitemid` 查一段文本"（`/charge/feeitem/getThirdDataByFeeItemId`）—— **这一步是错的**，错在只检索了 `/plat/` SPA 的 98 个 chunk，而级联其实属于官方 `/charge-pc/`（Vue SPA「缴费.新中新」）那一套。**最终确证的口径**（由 `/charge-pc/js/app.*.js` 定义，已用真机令牌逐级实测走通）：`GET /charge/feeitem/singleFeeitem?feeitemid=` 给出 `view`（`"choose"` = 必须先选房间）与 `interfacechoice`（级定义，形如 `校区_campus,楼栋_building,楼层_floor,房间_room`）；`POST /charge/feeitem/getThirdData`（`application/x-www-form-urlencoded`）**既做级联取数、也做最终读数**，靠 `type` 区分 —— `select` 时 `map.data` 是下一级候选，`IEC` 时 `map.showData.信息` 是读数文本；各级提交值形态固定为 `"<id>&<name>"`（`1&本校区`），**必须原样回传**。**级数由服务端决定、界面不写死**（淮师费项 181「1-6单元电费」是 4 级）。选择器由 `YktScreen` 按 `YktSceneLevel` 动态渲染 N 级，选定项落 `YktSceneSelection` / `ScenePick` 并按账号记忆。⚠️ 一条独立判据：**`HTTP 200` + 正文 `null` 是"空数据"而不是"错误"**（`YktClient.isNullBody()`，`null` 与空正文都判为空）—— 把它当格式错误会报出"返回了无法识别的数据"，而正是这条误导性文案把用户引向了"是不是没给定位权限"。
- **`HTTP 200 + 正文 null` 曾被当成"返回了无法识别的数据"**：站点 combobox 系列接口在"无数据"时回的是**正文就是 `null`** 的 200，既不是 `[]` 也不是 `{"code":200,"data":[]}` —— 对 EasyUI combobox 是合法的空列表，对 `JSONObject` 却是解析失败。旧代码因此把它当格式错误，用户看到的是"系统坏了"而不是"这里没有可选数据"，也正是这条误导性文案把用户引向"是不是没给定位权限"的方向。现在新增判据 `YktClient.isNullBody()`（`internal` + 单测，`null` 与空正文都判为空数据），`sceneBox` 据此返回空列表而不是报错。
- **失败文案丢掉了真实原因，并且把原因写死成"网络"**：`openPicker` 把失败一律写成「校区列表加载失败」等四句，把"登录已失效 / 无法连接 / HTTP 5xx / 返回了无法识别的数据"这四种**处置方式完全不同**的情况压成同一句；选择弹窗里还写死一句「请检查网络后重试」，把"登录失效"也归因成网络 —— 用户会去反复切 WiFi，而正确动作是重新登录。现在失败文案由 `failureText()` 统一生成，保留真实原因与出错层级，令牌失效/缺失时切回登录态并提示「登录已失效，请先登录」；弹窗那句改为「可取消后重试；若提示登录失效，请重新登录一卡通」。由 `YktNoPasswordGuardTest` 新增 1 项钉住（保留真实原因、令牌缺失须提示登录、**不得出现权限归因**、弹窗不得写死网络原因）。
- **应用项跳转补上站点唯一的官方口径**：`/berserker-base/redirect?appId=<应用项 bh>&type=app&synjones-auth=<token>`（取自 SPA 多处 `openNewPage`）—— 参数名是 `appId`、值取 `bh`，不是 `feeitemid`、不是 `appCode`。用于「在官方页面查看/缴纳电费」入口，与"充值/缴费交官方页面"的合规口径一致。
- **一卡通费项名是空的**：应用项的展示名是 `appName` 而非 `name`，旧 `collectFeeItems` 只读 `name`，导致费项只能显示兜底的「电费 181」。现在 `appName` 优先、`name` 兜底，界面上能看到学校登记的名字（「1-6单元电控缴费」等）。
- **空闲教室：校区名被截断、场地类别选项看不见、换校区后楼号不刷新**（用户反馈，附截图）：三个现象两个原因。① 校区用的是等宽分段控件（宽度按选项数均分 + 省略号），4 个校区挤一行只剩「朝阳…」「应用…」；② 场地类别用的是浮层，其定位判据是"下方空间不足就往上开"，正好盖住上面的校区与楼号字段；③ 更实质的一条 —— **楼号与节次必须按校区取**：页面内联的 `#lh` **只属于默认校区**，服务端脚本在 `#xqh_id` 变化时会重取 `cdjy/cdjy_cxXqjc.html`（**`gnmkdm` 必填**，缺了返回"参数不能为空"）重建楼号与节次列表，我们此前没接这条，于是换校区后楼号不刷新，而且是**静默错误**（服务端不报错，只返回空集或别的楼，用户看到的是"这个校区没有空教室"）。现在：校区改为内容宽度的流式芯片（不再等宽截断）；楼号与场地类别改为**内联**选项列表（不再用会盖住上方字段的浮层，超过 12 项给筛选框）；节次改用校区下发的真实节次名；切校区时清空已选楼号与节次。三份来源按"**哪份确定属于当前校区**"取舍（对不上宁可只给「全部」，也不给错的选项）。获取方式上也有个坑：`raw.githubusercontent.com` 被代理挡，改用 `api.github.com` 的 contents API 取回服务端 JS 才读到 `hqjcList()`。
- **共享选择组件：长列表靠后的选项永远停在半透明**（同一批反馈里"场地类别后面无法显示"的另一半原因）：`LiquidPicker` 的行显隐由**时钟**驱动（`phaseTimeSeconds` 从 0.22s 起、每行 +0.018s），而"是否静止"由**位置/速度**驱动 —— 长列表（36 项场地类别约需 1.05s）弹簧先停，时钟冻在半路，靠后的行就永远显不出来。现在 `rowRevealProgress` 补上"静止即完全显形"。这是共享组件的潜伏缺陷，不只影响空闲教室，所以修在组件里而不是页面里。
- 强制更新弹窗在下载失败、缺少直链等卡住的情形下仍允许关闭，避免用户被一个点不动的弹窗困住。
- 「下一节课」卡片页脚把状态当成了周次：`ScheduleCardData.heading` 是「今日课表 / 今天无课 / 今日已结束」这类整句状态，模板里却多了一个「第」字，页脚会印出「第 今日课表 · 9月23日 周三」。
- App 内卡片与桌面卡片对同一份数据用了两套数字精度（工作台保留两位小数、桌面保留一位），同一个绩点在应用里是「3.46」、钉到桌面变成「3.5」。现在两边共用 `widgetboard/number()` 一个实现。
- 桌面「未读消息」卡片收不到刷新：消息中心的列表缓存在 `cacheDir` 的 JSON 文件里，而组件的自动刷新只监听 SharedPreferences，于是那条卡片只能等系统 30 分钟的兜底周期。`MessageCenterManager` 写入 / 清空缓存后会显式通知 `CardWidgetUpdater.refreshSoon()`。
- 组件抽屉里已添加到工作台的卡片，按钮显示「已在工作台」却仍可点、点了没反应，现在按已添加状态置灰。
- 清掉已废弃的 `Icons.Outlined.AddToHomeScreen` 引用（改用 `Icons.AutoMirrored.Outlined`）与两处未使用的图标 import。
- **应用内点课表类卡片不会回到本周今天**：`WidgetBoardRoute.open` 对「下一节课 / 今日课表 / 今日时间轴」只请求切到课表 Tab，漏了 `AppTabNavigation.requestToday()`。课表页记着上次翻到第几周、日视图停在哪天，于是应用内点卡片会落在几天前的那一页上 —— 而**桌面上点同一张卡片是会复位的**（`CardWidgetNavigation.resetsToToday`）。同一张卡片两处表现不一致，且发版说明里承诺的「自动回到本周今天」此前只对桌面成立。现在两处共用同一条语义。
- **桌面卡片的行数预算漏算了行容器的上边距**：`card_widget.xml` 里行容器自带 `layout_marginTop="6dp"`，这段开销不属于标题、大标题、副标题、汇总中的任何一个，原先完全没有计入预算。行高是 21dp，凭空多出 6dp 会让约 29% 的高度档位上多算一行，而多出来的那一行会被行容器裁掉一截 —— 半截的「08:00–09:4…」比不显示更容易被当成数据错了。现在 `CardWidgetLayout` 单独列出 `RowsLeadingCost` 并在"要画行"时计入，由 `CardWidgetLayoutTest` 新增 2 项钉住（同一份草稿在 60dp 高时放不下一行、70dp 高时放得下一行；去掉这 6dp 前一条立刻失败）。
- **「显示密度」设置从未生效**（1.2.5 已发布的功能）：`ScheduleDisplayStore.setCompact` 的调用点为零，且 `ScheduleRoute` 调用 `ScheduleSettingsScreen` 时没有传 `displayCompact` / `onDisplayCompactChange`，于是「课表设置 → 课表显示 → 显示密度」恒显示「标准」、点「紧凑」没有任何效果，`compact` 永远读回默认的 `false`。现在补上接线：设置页只发意图，内存状态与落盘都在 Route 更新（与 `dayView` 同一条写法），否则会出现"设置页选了、退回来课表没变"。
- 隐藏周末后周视图的空态与提示：空态改按**可见课程**判断（只藏了周末课时网格确实是空的），并新增「另有 N 门周末课程，可在日视图查看」；周末课程在隐藏时也不再参与列布局与节次行高实测 —— 否则一节周六的课会把整周的节次行高顶高，而用户看到的是一片空白。
- 补课入口随「显示周末」挪位：日期条上点六/日开补课弹窗是**唯一**入口，周末一隐藏那两格就没了，补课会彻底点不到。现在「课表设置 → 自定义课程」下多出一行「补课」（默认落周六，弹窗内仍可改目标星期与源星期；点它会先关掉设置页 —— 补课弹窗渲染在 Route 层，设置页是压在它上面的独立窗口，不关就看不见）。这一行是否出现由设置页**自己的本地开关状态**决定，拨到「隐藏」时立刻出现，不必退出重进。
- 周视图日期条的滑块语义修正：`scheduleDateStripSelection` 的第三条规则从「非本周锚定第 1 格」改为**返回 null（整条不画滑块）**。周视图一次浏览一整周，滑块只用于表达"今天是这一天"；翻到别的周时今天并不在这一周里，锚在第 1 格会让用户以为"正在看周一"。配套给 `LiquidSegmentedControl` 加 `showIndicator`：为 false 时两条渲染路径（有折射 / 无折射兜底）都不画滑块，且**所有格子的选中文字样式一并收掉** —— 否则滑块藏了、第 1 格的字仍是主色，看起来还是像被选中。日视图不受影响，恒返回下标（有单测 `dayViewNeverReportsNoSelection` 穷举钉住）。
- 设置页的展示偏好改为本地状态：`ScheduleSettingsScreen` 的「显示密度」「显示周末」原先直接读外部参数，而该页跑在独立 Dialog 窗口里，控件状态必须先绕一圈"页内点击 → 外层状态 → 新参数传回窗口"才会更新；现在与 `periodCount` / `fontScale` / `semesterStartDate` 统一为本地 `remember`（点击立即生效）+ `LaunchedEffect` 同步外部值。`onMakeUpCourse` 也随之改为恒定传入（不再按开关传 `null`），避免"拨到隐藏的那一瞬间回调还是 null、行渲染不出来"。
- 补课可以补到"自己身上"：目标天与源天相同（且同一周）时，原实现会按原时段生成一条与原课程完全重叠的自定义课程 —— 课表上多出一张看得见却点不中下面那张的卡片。以前目标天写死在周六 / 周日、而周末通常没课，撞不上；现在目标天任选，必须在弹窗里堵掉（`isSameSlot`：确认按钮置灰并说明"就是要补到的那一格"）。
- **桌面组件的引导文案指向了一个不存在的菜单项**：工作台顶部与组件抽屉里原先都让用户"长按桌面空白处 → 小组件"，但 ColorOS（OPPO / 一加 / realme）的长按菜单里只有「卡片 / 壁纸 / 图标 / 布局 / 翻页 / 更多」，**没有「小组件」**。用户照着找必然一无所获，再回到应用里点「添加到桌面」也等不到确认框，于是判定"组件坏了"。在 ColorOS 13 真机（PHJ110）上逐层走通并实测确认后，文案改为确切路径：**长按桌面空白处 →「卡片」→ 卡片中心 → 一直往下滑到最底部 →「插件」→ 选「校园助理」→ 点要加的卡片**（组件能正常加上，且渲染出真实数据）。
- **顺带纠正一处错误结论**：此前注释与文案里写"ColorOS 的「卡片中心」只收系统自带卡片、第三方应用的组件根本不在那里"。实测**不成立** —— 卡片中心是审核制的目录（目录里确实有哔哩哔哩、百度地图、小红书、腾讯地图这些第三方卡片），但它列表最底部那条「插件」才是标准微件列表，本应用的七种组件全部在里面，还带着各自的尺寸与说明。所以问题从来不是"加不上"，而是"入口找不到、指路指错了"。
- **「添加到桌面」在 ColorOS 上是一条"发得出去、落不下来"的通道**：实测日志显示 `requestPinAppWidget` 返回 `true`、系统确实转发了 `CONFIRM_PIN_APPWIDGET` 并拉起 `com.android.launcher/...AddItemActivity`，桌面进程也为这张卡片建好了预览用的 `ImageReader`，但确认页 `resumed` 仅 55ms 就被自行关掉（约 335ms 后整个 Activity 被移除，全程无异常日志），卡片始终进不了桌面。该路径在其它系统上仍有效，因此保留按钮，只是在提示语里补上桌面手工路径的兜底，避免用户对着一个一闪而过的框空等。
- **一卡通页说明里的 `**` 会原样显示给用户**：「该费项返回的是**电量（度）**而非金额」「挂失会立即冻结这张卡、**无法在本应用内自行撤销**」「本应用**不接收、不保存**你的一卡通密码」—— 这些星号当初是照 Markdown 写的，但页面用的是普通 `Text`，用户看到的就是一串星号。现在说明性文案改走 `MarkdownText`（`YktScreen.HintMarkdown`，与 `HintText` 同款排版），加粗真正生效；同时把「提醒阈值」「后台运行权限」「消费汇总」几处挤在一行里的多点说明改写成了列表。服务端回传的文本（报错、「接口原文：…」）仍走普通 `HintText`：不受我们控制的内容不该被当成排版指令。新增 `MarkdownTextTest` 14 项，把「纯中文段落与 `· ` 列表必须判为纯文本」这条钉住 —— 误判成 Markdown 等于悄悄改掉全应用的版式，是比漏判更难被发现的那一侧。

### 文档

- 新增 `scripts/format_release.py`：一份草稿（`release-notes/drafts/vX.Y.Z.md`）排版出 `release-notes/vX.Y.Z.md` 的 `## notes` / `## changelog` 与 `assets/announcement.json` 的本版公告，并在落盘前用发版链条上真正的校验器复核；附带 `release-notes/preview/<tag>.html` 排版预览页。
- `用户手册.md`：10.3.2 空闲教室补上「楼号与节次随校区变化」的说明，并**删掉已失效的**「切到非默认校区时楼号可能仍是默认校区」；第 15 章清掉选课残留的过时表述（选课声明、选课窗口与冲突规则、清空队列），改为指向「选课已移除」。改完重跑 `scripts/build_manual.py` 重新生成 `app/src/main/assets/users_manual.json`。
- `README.md`：功能清单补齐「一卡通」「空闲教室」与「课程提醒」三块（原「考级项目报名」一节扩为「校园服务」），首屏简介补上「一卡通」；「教务系统与限制」表新增两行 —— 选课**不提供**（1.2.6 起整体下线）、一卡通只读且**不提供充值缴费**。

### 公告

- 随包新增 v1.2.6 公告（`20260926_v1_2_6_release`），版本范围锁 10206–10206。

## [1.2.5] - 2026-09-23

### 新增

- 课表日视图：`ScheduleAgenda`（周次 + 节次换算真实时间轴，正在上课 / 下一节 / 剩余堂数）与 `ScheduleAgendaContent`（按天分页的课程卡列表）；顶栏「日 / 周」`LiquidSegmentedControl` 切换，两种视图各自记住滚动位置；日视图偏离今天时时间列显示「今天」快捷按钮，星期条点按跳日且高亮跟随浏览日。
- 分页同步泛化：`ScheduleWeekPagerSync` 由「周次」泛化为「页单元」制（周视图 = 周次，日视图 = (周-1)*7+星期），新增 firstUnit / lastUnit 参数，`settledWeek` 更名 `settledUnit`，单元测试同步覆盖两种模式。
- 桌面小组件回归（三种样式）：`schedule/ScheduleWidgetProvider` + `ScheduleSingleWidgetProvider` + `ScheduleTimelineWidgetProvider`（RemoteViews 渲染，浅深色两套背景），`ScheduleRepository.snapshot` 只读本地课表缓存（schedule_cache + 自定义课程 + 四级时间基准兜底），`ScheduleWidgetUpdater`（prefs 监听 + 会话流 + 跨天边界闹钟，挂 CourseApplication 主进程），`ScheduleWidgetNavigation` 深链（点组件回课表 / 课程详情 / 同步 / 设置），`ScheduleWidgetPicker` 预览并请求钉到桌面。
- 课表设置新增「课表显示」分区：显示密度「标准 / 紧凑」（紧凑节次行高 ×0.78，`ScheduleDisplayStore` 全局持久化）；课表字号由滚轮弹窗改为 `SystemPicker` 内联点选档位。

### 变更

- 课表顶栏动作区重构：移除上一周 / 下一周箭头（翻周由 pager 横滑承担），动作行改为「日 / 周切换 + 更多菜单」；`SystemActionMenu` 收纳同步课表 / 导出课表 / 添加课程 / 桌面组件 / 课表设置五个动作；标题列宽度放宽，「第 X 周」、日期与「(非本周)」标注不再受挤压。
- 第二课堂入口大标题「活动中心」改为「第二课堂」；活动页顶栏筛选 / 刷新 / 扫码三个图标收进「···」更多菜单（与课表顶栏同款交互），其余 Tab 动作不变。

### 文档

- 用户手册：4.1 改为「日视图与周视图」（含新顶栏说明），4.2 改为「切换周次与回到今天」（横滑 + 星期条点按 + 今天按钮），4.6 / 4.8 入口改为「···」菜单写法并补充显示密度行，4.9 整节重写为「桌面小组件（1.2.5 起）」三种样式与用法；第 7 章补充大标题与「···」菜单说明，7.4 / 7.5 入口同步；重跑 `scripts/build_manual.py` 重新生成 `assets/users_manual.json`。

### 公告

- 随包新增 v1.2.5 公告（`20260923_v1_2_5_release`），版本范围锁 10205–10205。

## [1.2.4] - 2026-09-22

### 新增

- 接入学工系统（学生工作处 `xg.hnnu.edu.cn`），只读查看日常请假与节假日去向登记。新增 `xuegong/XuegongClient.kt`（登录、请假列表、去向登记列表，同源重定向跟随，HTML 登录页翻成 `sessionExpired`）、`xuegong/XuegongStore.kt`（复用 `CredentialStore`，token 存 `xuegong_prefs`）、`xuegong/XuegongModels.kt`、`XuegongActivity.kt` 与 `ui/screen/XuegongScreen.kt`（液态玻璃页头信息卡 + 统计胶囊 + 图标化列表 + 分区详情 + `moduleEntrance()` 入场动效）；入口在「我的」页新增的「校园服务」分组。
- 学工系统登录：`POST /PhoneApi/api/Account/Login`，密码用站点写死的 JSEncrypt 公钥做 RSA PKCS#1 v1.5 加密后提交，成功判据是响应 `Msg == "OK"`；成功后请求头为 `Authorization: Bearer <token>`（与二课的无前缀写法不同）。`utils/RSAUtils.kt` 新增 `encryptWithPublicKey(spkiBase64, data)`，直接用 `X509EncodedKeySpec` 吃 DER，不用手工剥 TLV。登录状态失效时用保存的密码自动静默续期一次（只试一次，失败即回到登录框）；顶栏提供「刷新」与「退出学工登录」，后者只清本机学工凭据。
- 第二课堂新增「申报」标签（位于「已报」右侧）：`SecondClassroomClient.myApplications()` 拉取 `/project/request/list1`（一次 50 条），`applicationCategories()` 拉取 `/dict/project/choice-sort`；新增模型 `SecondClassApplication`，列表展示项目名、认定档位、所属模块、认定学时与时间，点开进入详情看完整字段。只读，不含任何提交通道。
- 后台自动检查更新：新增 `network/UpdateCheckScheduler.kt`（`AlarmManager.setAndAllowWhileIdle` 自续期、24 小时一次、穿透 Doze）、`network/UpdateCheckReceiver.kt`（`goAsync()` + 协程 + `withTimeoutOrNull(9s)`，`finally` 重排下一次）、`network/UpdateNotifier.kt`（建「版本更新」通知渠道，同一版本只通知一次，点通知去下载页）；`MainActivity` 启动时挂载调度，与登录态无关。不引入 WorkManager（项目未依赖）。

### 变更

- 学工系统页面按应用既有的液态玻璃语言重做：页头信息卡（系统图标 + 系统名 + 登录徽章 + 学号 + 统计胶囊）、`InsetGroupedSection` 分区的登录表单、图标 chip 行式列表、时间线卡片式的详情页、页面底部固定的只读提示，全部走 `glassSurfaceColor()` / `glassBorderColor()` 与 `moduleEntrance()` 入场动效，不再是纯文字堆叠。
- 学工接口的错误判定只认 HTTP 状态：实测 `/DailyLeave/StuDisLeave` 会在 `data` 完全正常的情况下返回 `errcode: 1, errmsg: "销假失败"`，errcode 语义未定，因此不拿它判成败。同时该站点两处列表的信封不一致（请假有 `data` 包裹、`HolidayWhereabouts/GetStuList` 是顶层裸对象），客户端分别取值。
- 明文 HTTP 白名单新增 `xg.hnnu.edu.cn`（该站点无 HTTPS 入口）。`network_security_config.xml` 是白名单机制、注释里已写明「新增明文站点必须显式登记」，未回到全局开关。
- 第二课堂 Tab 由 4 个变 5 个，`TABS = 活动 / 已报 / 申报 / 消息 / 成绩单`；消息与成绩单的下标后移，`when(ui.tab)` 内容分支、副标题与顶栏按钮、空态判定、以及宿主借助下标驱动的「成绩单按需加载」常量与 `loadMore` 分支全部同步调整。
- 学工系统与二课申报的只读边界写进页面文案与手册：提交类接口（`DailyLeave/SaveForm`、`HolidayWhereabouts/SaveForm`、`HolidayStay/SaveForm`、`HolidayStay/Del`、二课 `/project/apply/delete`、`/project/apply/repeal` 及各类 `apply/add`）一律不接入。

### 文档

- 用户手册新增第 8 章「学工系统」（登录、日常请假、节假日去向登记、只读说明），其后章节整体顺延；第 7 章第二课堂补充「申报」标签的用法与只读/原始状态码说明；第 10 章新增「校园服务」分组条目，并更新「检查更新」与「有新版本吗」两处关于后台巡检的描述；手册版本标注同步为 v1.2.4。
- 删除仓库中上一轮逆向分析留下的 9 个临时文件（`cas_*.js` / `cas_login.html` / `scripts/_probe_*.py`），均未被任何代码引用。

### 公告

- 随包新增 v1.2.4 公告（`20260922_v1_2_4_release`），版本范围锁在 10204–10204。

## [1.2.3] - 2026-09-22

### 新增

- 第二课堂活动中心（活动列表 / 详情 / 报名 / 取消 / 我的活动 / 活动通知）：搜索、分类筛选、排序、分页加载；报名支持自定义字段表单，取消需确认；详情页带名额进度与附件区。
- 第二课堂「已报」顶部学时汇总卡：已获得学时（`grantHours` 优先、缺省回退活动标称学时）与已报名数量，直接在已加载的活动清单上求和，不额外请求接口；还有下一页时附一行说明，避免把"已加载部分"当成终值。
- 「我的」页新增「通知提醒」状态行：显示系统通知权限是否开启，关闭时点一下直达系统通知设置页（Android 13+）。
- 扫码签到与「我的签到码」：zxing 扫码 + 签到安全闸门（未报名、代他人、扫自己的等待码均拦截并说明原因），签到码二维码本地生成。
- 内置附件查看器（DocumentViewerActivity）：Word（docx 标题/段落/列表/表格/图片/分页）、Excel（多工作表、冻结表头、点击单元格看全文）、PDF（PdfRenderer + 双击缩放）、图片（捏合缩放 + EXIF 方向）、文本（BOM/UTF-8/GBK 自动识别）；魔数嗅探 + 扩展名兜底，独立下载器限 25MB，支持系统分享与外部打开。
- 课表 Excel 导出：与既有日历导出并列的格式选择弹窗；`SpreadsheetWriter` 纯 JDK 手拼最小 OOXML 包（inlineStr + 加粗表头），导出列：课程名称 / 教师 / 地点 / 星期 / 开始节次 / 结束节次 / 周次。
- 课表 Excel 导入：SAF 选文件 → 按表头名识别列（顺序无关，支持「星期一」「周1」「Sunday」等写法与「1-16周」「1,3,5」「2-8双」等周次格式），无表头时按导出格式按位置取列；导入落自定义课程并登记提醒记录，重复课程跳过，成功后自动关闭设置页展示结果。
- 成绩 Excel 导出：原 CSV 导出升级为 xlsx（SpreadsheetWriter），表头加粗，Excel/WPS 直接打开。
- 第二课堂初始密码自动登录：无凭据时先用默认密码（学号 + &Zhtx）静默登录并落库；被拒（改过密码）时自动拉起登录框，输入框预填初始密码。
- 扫码 / 剪贴板深链：`qutuo://` 码与 `ekta.hnnu.edu.cn` 活动链接（含 hash 路由）在 Activity intent 与剪贴板两个入口统一解析，确认后直达活动详情或签到；扫到活动码直接打开对应活动详情页，不再被签到闸门拦下。
- 消息中心一键已读：顶栏在有未读时显示「一键已读」按钮，全部标为已读并即时清除红点；本地已读状态在刷新后与服务端结果合并保留（单条已读同样受益）。
- 手机号短信登录：登录页新增入口，内嵌浏览器打开学校统一身份认证（xxmh CAS，手机号+短信验证码/账号密码），登录成功后自动合并 CAS 域与教务 SSO 域名的 Cookie 并验证进主界面，无需手动复制。
- 名额报满与截止提示：列表卡片名额满时显示「已满」标记；详情页按状态显示禁用态的「报名人数已满」「报名已截止」「报名未开始」，代替直接报错。
- 活动封面：活动卡片与详情页用 Coil 加载活动 `logo` 封面（相对路径自动拼到二课站点源站）。
- 相册识别二维码：活动中心顶栏新增「从相册识别」入口，选图后本地解码（大图采样防 OOM、EXIF 摆正、四个方向各试一次），与相机扫码走同一条签到/深链处理链路。
- 首次进入主界面统一申请通知权限（Android 13+）：此前只有课表提醒、抢课两处流程会申请，从不开这些流程的用户，成绩发布 / 站内消息 / 考前提醒三类推送会静默失效。拒绝后不再打扰，改由「我的 → 通知提醒」引导。

### 变更

- 第二课堂成绩单（表头 / 综测附加分 / 模块情况）与排行榜成为二课页的第 4 个 Tab，位于「消息」右边；「我的」页原「第二课堂成绩单」入口与 `secondClassSubtitle` 一并删除（二课 Tab 保留活动中心）。成绩单按需加载：不点开该 Tab 就不拉概览与榜单，宿主的会话/快照通过 `transcriptContent` 注入，活动中心不必认识二课的成绩单链路。
- 活动中心列表层的「空」不再吞掉失败：新增 `needBind` 状态与 `ListFailureState`，凭据缺失/失效显示「去绑定」（通达宿主登录弹窗），请求失败显示「重试」；带 `hostRevision` 参数，宿主登录成功后自动重新拉取，不会停在「尚未绑定第二课堂」上。
- 活动中心第 2 个 Tab 由「我的」更名为「已报」（`TABS` 字面量），与页内实际内容一致：那一栏装的就是自己报名的活动；改名只动标签文案，Tab 下标与分段逻辑不变。
- 活动列表剔除「报名已截止」的活动：`SecondClassActivity` 新增 `enrollmentEndedOf(now)`（只认 `enrollEndTime`，缺省 0 视为不限期；**还没开始报名**的活动仍保留，详情页会显示禁用的「报名未开始」），`ActivityTab` 在本地筛选之前先 partition 掉已截止项，并在列表状态行与空态文案里说明隐藏条数。全部被过滤干净但 `hasMore` 为真时，空态额外给出「继续加载」按钮 —— 否则用户会卡在一张既没有内容、又没有触发下一页入口的空页上。
- 活动中心「已报」Tab 在「成绩单」迁出后补上学时汇总卡；列表为空且存在错误时优先展示失败态。
- 「本院系可报」筛选改按活动的**院系列表**判定：`SecondClassActivity` 新增 `collegeLimit`（`"0"`/空 = 不限院系，否则逗号分隔的院系 id）与纯函数 `enrollableForCollege(myCollegeId)`，`ActivityTab` 的判据由 `isAbleEnroll` 换成它。原先用的 `isAbleEnroll` 是服务端的**复合**判定（院系 ∧ 年级 ∧ 诚信分 ∧ 名额 ∧ 时间），2026-09-21 用真实账号只读对照发现：`collegeLimit="0"` 的活动里既有 `isAbleEnroll=1` 也有 `0`，用它当院系筛选会把「对全校开放、只是名额已满或年级不符」的活动整片误杀。本人院系 id 来自二课 `/student/achievement/detail` 的 `user.collegeId`（`SecondClassProfile` 新增 `collegeId`），**只在用户打开这枚开关时才拉一次**（`ensureCollegeInfo`，不常驻请求）；取不到时 `myCollegeId = 0` 一律放行，宁可多显示也不把列表筛空。筛选弹窗副标题会带上院系名，取不到时明确写出来，避免用户以为开关坏了。
- 「我的」页页头第二行由校名改为「院系 · 专业」：`SettingsScreen` / `SettingsHeader` 的参数 `school` 更名 `affiliation`，新增 `collegeName` / `majorName` 两个入参，`SettingsRoute` 在**已绑定二课时**拉一次 `profile` 填充（切账号跟随 `session.token` 重拉并先清空，避免上一个账号的院系留在表头）；两处都为空时回退显示校名。
- 登录页删除「体验只读演示模式」入口：`LoginScreen` 去掉 `onDemoMode` 参数与那个 `TextButton`，`LoginActivity` 同步删掉 `onDemoMode` 接线与 `handleDemoMode()` 方法。`BuildConfig.UI_PREVIEW` 的设计预览分支与 `UserManager` 的演示态防御性判定保留（前者是排版预览用，后者是运行时兜底）。
- 活动中心链路补日志（TAG `SecondClassActivity`）：第 1 页拉取条数 / `hasMore` / 关键词有无 / 分类，拉取失败；本地筛选的「原始 → 剔除已截止 → 显示」三段计数与两个开关状态、`collegeId`；本人院系的取到 / 跳过（未绑定）/ 失败。全部只记条数、布尔与 id，不落密码与 Set-Cookie。
- 用户手册从 WebView + HTML 改为原生 Compose 渲染：`scripts/build_manual.py` 现在把 `用户手册.md` 解析成结构化 JSON（`assets/users_manual.json`：章节 / 段落 / 有序无序列表含子项 / 表格 / 引用块 / 行内粗体斜体代码链接），由 `ui/screen/UserManualScreen.kt` 渲染，自带全文搜索与可跳转目录，排版跟随应用主题。随之删除 `assets/users_manual.html`、`ManualActivity` 的 WebView 与 `settings.javaScriptEnabled`，`scripts/publish.py` 的 `REQUIRED_ASSETS` 改为校验 `users_manual.json`。
- 移除开源版永久不可达的激活页死路径：`ActivationManager.checkActivation` 是硬编码 `return true`，`MainActivity` 里 `activationState == 1 → ActivationScreen` 分支永远不会进入，删除 `ActivationScreen.kt`（233 行）与 `MainActivity` 的 `activationState` 状态机；`checkActivation` 保留一次调用只为落盘设备 ID（「我的」页要显示）。手册同步去掉「设备授权页」一节，并写明永久免费、无需激活。
- 移除活动中心「签到记录」标签页与相关数据加载，只保留扫码签到；活动详情签到区不再展示历史记录列表。
- 成绩页三个 Tab 的失败态补上「重试」按钮（原本文案只说"点击刷新获取最新成绩"，得让用户自己意识到要去点右上角）。
- 消息中心的加载态收敛为 `SystemLoadingState`（带文案的玻璃转圈），替掉两处裸 `CircularProgressIndicator`；「一键已读」补上完成提示，与二课消息页同一句反馈。
- 附件 MIME 映射改为纯映射表（MimeTypeMap 在 JVM 单测不可用）。

### 修复

- 教务签名防盗校验（`network/CourseApiClient.java`）不再破坏请求：原先哨兵判定签名非法时会往 `xsxk` / `xkoper` / `kbcx` 的 POST 里塞假 Cookie（`cracked_by_yellow_cow_blocked`）并 `sleep` 3–8 秒，等于让任何重签名构建零报错地丢掉选课与课表。改为只写一行告警日志，请求按原样发出且不再人为延时（该分支当前恒为放行：`AUTHORIZED_SIGNATURE_HASH` 未填）。
- 清除缓存此前是空实现：`SettingsRoute` 的确认弹窗只关掉自己，课表 / 成绩 / 消息缓存与 `schedule_cache` 分毫未动。现在依次调用 `CourseCacheManager.clearCache`、`GradesCacheManager.clearAccount`、`MessageCenterManager.clearCache`、清 `schedule_cache` prefs，并 `PageDataClearSignal.bump()` 让内存中的页面数据一起作废。
- 删除账号未清干净：补上成绩缓存、消息缓存与二课 token / 密码（`SecondClassroomStore.clearAccount`）。
- 考试提醒的闹钟会跨账号误删：`ExamReminderScheduler` 的计划表按 `examId` 摘要去重、`reconcile` / `clearAll` 无视账号，切换账号会把上一个账号的考前提醒一起 `cancel`。现在 `Plan` 自带 `account` 字段、`examId` 把账号算进摘要，`reconcile` / `clearAll` 只动本账号；无 `account` 的历史计划按当前账号归属。
- 消息播报基线跨账号互相覆盖：`MessageCenterNotifier.reset` 原先 `.clear()` 整个 prefs 文件，改为只删本账号的 `notifiedKey` / `lastCheckKey`。
- 短信登录（CAS）第二步会带上一个账号的会话：`CasPhoneLoginClient` 是 object 单例，CAS 的 `execution` 是一次性的，提交回 `/cas/login` 之后没有再置位，换账号时旧会话被复用。新增 `sessionConsumed` 旗标与 `reset()`，提交后置位、下次 `ensureSession()` 清 Cookie 重开；同一轮"发短信 → 提交验证码"仍复用同一 SESSION。
- 成绩页缓存命中时学期 Tab 恒空：缓存分支（`revision == 0 && reportLoaded`）没有选中默认学期，`semester` 为空串时选栏空白。现在会在缓存学期列表里补选第一个可用项。
- 账号/密码里含 `-` 时短信登录会把消息切错：`message.split("-")` 未限长，改为 `split("-", limit = 3)`。
- 协程取消被当成业务失败：`MessageCenterManager` 的 `load` / `loadDetail`、`CasPhoneLoginClient` 的请求与 `SecondClassroomRoute` 的加载在 `catch (Exception)` 前补 `catch (e: CancellationException) { throw e }`，页面切走时不再把取消当"加载失败"写进状态、也不再弹登录框。
- 抢课服务的队列快照在账号 key 为空时刚写就被抹掉：`saveServiceQueueSnapshot` / `saveServiceTargetCourseSnapshot` 改为仅在 `scopedKey` 非空时删除裸 key 分支。
- 活动详情「已驳回」误显示：未报名的活动此前会被服务端回成驳回态（无驳回原因），现在只有驳回原因非空时才采信驳回状态；报名状态改为 participant / non-member 双端点合并判定。
- 报名按钮条件放宽：报名时间不限（endTime 为 0）时也显示报名按钮；报名未开始时显示禁用的「报名未开始」。
- zxing 相机扫码页锁定竖屏（Manifest 覆盖库声明的方向）。
- 列表页顶栏扫码签到成功无反馈的问题。
- 相册识别二维码此前**任何图都识别不出来**：`decodeQrFromImage` 量尺寸那一步写成 `openInputStream(uri)?.use { decodeStream(...) } ?: return null`，而 `inJustDecodeBounds = true` 时 `decodeStream` 按契约【必然返回 null】，elvis 命中的是它的 null 而不是"流没打开"，于是每张图都在真正解码前直接返回 null，用户只会看到"图片里没有识别到二维码，换一张试试"。改为先判流是否打开、再用 `use` 跑量尺寸，与 `WallpaperCropStore` / `WallpaperImageStore` 的正确写法对齐（同一个坑本仓库第三次踩，前两处都留了注释）。
- 相册识别二维码的解码循环顺带做了防御性收口（**不是修某个已知现象**）：`MultiFormatReader.decodeInternal` 内部已经把 `ReaderException`（含 `ChecksumException` / `FormatException`）吞掉并统一抛 `NotFoundException`，所以原来的 `catch (NotFoundException)` 行为上是对的；改为 `catch (ReaderException)` 只是少依赖一层库内部实现。真正顺手治掉的是 `decodeWithState`（平台类型）返回 null 时 `.text` 会 NPE 并打断整轮"4 方向 × 3 种二值化"穷举 —— 现在按失败处理、继续试下一种。
- WordDocument 解析 docx 时忽略标签命名空间前缀（`w:body` 之前按全名匹配导致正文判空）；分页符段落（仅含 w:br）不再被误判为普通段落。
- 附件查看器改用 File.readBytes() 替代 java.nio.file.Files（API 26+，minSdk 24 会 NoClassDefFoundError）。
- 课表 Excel 无表头导入的列映射键错误（导出用例回归覆盖）。

### 测试

- 新增 `UserManualTest`（12 例）：校验 `assets/users_manual.json` 可解析、章节数与 `用户手册.md` 的 h2 对账、块类型齐全（段落 / 小节 / 有序无序列表 / 表格 / 引用）、列表子项保留、行内记号被消费干净、无空块、搜索命中摘要含关键词且大小写不敏感、坏 JSON 与缺字段不崩。手册改成原生渲染后解析层是新增的失败面，且失败时"页面照常渲染、只是内容缺一块"，必须有测试盯住。
- 新增 `ImageDecodeElvisGuardTest`（2 例，源码级护栏）：`inJustDecodeBounds` 那个变量参与的解码调用不得进 elvis（判据取变量名，不误伤传 `options` 的真解码），外加一条断言 `QrImageDecoder.kt` 仍同时具备量尺寸与带 `inSampleSize` 的真解码两步。之所以守住"写法"而非行为：`decodeStream` 需要 Android `Bitmap`，纯 JVM 单测跑不起来，而这个坑在本仓库已经踩了三次。

## [1.2.2] - 2026-09-20

### 新增

- 公告中心整页（`AnnouncementActivity` + `AnnouncementScreen`）：与消息中心同构的全屏页 —— 顶栏返回 + 「未读 / 已读」分段 + 卡片列表 + 页内详情。「我的 → 公告」从此进整页，不再是一个列表弹窗叠一个详情弹窗（公告正文动辄上千字，弹窗里那点高度读起来很难受）。
- 应用内下载器（AppDownloader）：流式下载 APK 到应用私有目录，带进度回调、体积与 zip 魔数校验（挡住服务端返回网页/错误页），下载完成后经 FileProvider 唤起系统安装器；Android 8.0+ 自动引导「安装未知应用」授权。
- 启动自动检查更新（UpdateCenter）：6 小时节流，发现新版本弹出液态玻璃更新弹窗；被「以后再说」跳过的版本不再自动打扰，手动检查仍可见。
- 更新弹窗（AppUpdateDialog）：显示版本号、更新说明与下载进度 —— 进度条 + 百分比 + 实时速度 + 预计剩余时间，按钮从「前往下载（跳浏览器）」改为「立即更新（应用内下载 + 唤起安装）」；下载中可随时取消，已下载过同一版本时直接显示「安装更新」。
- 第二课堂「综测附加分」（SecondClassExtraScore）：按学校综测口径对七个模块积分加权求和（权重合计 1.00、单项封顶 100 分），在成绩单表头「总积分」右侧展示；一个可识别模块都没有时不渲染该框，避免给出错误的 0 分。

### 变更

- 移除桌面小组件（4×1「下一节课」/ 2×2「今日课程」）：两个 `AppWidgetProvider`、快照生成器 `WidgetSnapshotBuilder`、布局与 `appwidget-provider` 元数据、manifest 中的两个 `<receiver>`、课表页的主动刷新调用一并删除，`strings.xml` 里的小组件文案随之清掉。桌面上的既有小组件会显示成空占位，删除重新添加即可（该功能自 1.2.1 引入）。
- 启动闪屏不再使用自绘的旧 Logo：`Theme.CourseSelector.Starting` 原先用 `windowSplashScreenAnimatedIcon = @drawable/ic_startup_logo` 覆盖系统图标（一枚蓝绿渐变的帽体 + 闪电，与应用图标风格不一致），现改为直接用应用图标 `@mipmap/ic_launcher`。随之一并删除 `ic_startup_logo` / `ic_startup_cap` / `ic_startup_bolt` 三个矢量，以及只服务于它的 `StartupLogoAnimation`（含从未挂载的 `LogoOverlay`）、`StartupLogoRenderer`、`StartupChoreography` 与对应单测/设备测试。
- 公告按安装版本下发（`minVersionCode` / `maxVersionCode`）：客户端只对**适用于当前 versionCode** 的公告弹启动提示、计未读；写给旧版本的、以及没有声明版本范围的历史公告一律按「历史」处理，留在公告中心的已读一栏可查。此前线上那条"请手动下载新版"的迁移公告没有版本字段，于是所有装上新版的用户每次进「我的」都被提示一次。
  约定**每个版本一条公告**：内置文件里写本版公告时把范围锁到"恰好本版"（1.2.2 → 10202–10202），只有装了这一版的用户会收到它，下一版用户在公告中心的历史里回看；要写给别的范围（如"旧包名用户"的迁移公告）就显式声明，显式值永远优先。这条不变式由单测强制，见下方修复段。
- 公告改为**随包内置**（`app/src/main/assets/announcement.json`），不再从 Gitee Raw 拉取：公告跟着安装版本走，运行期不联网、不落缓存。原来的设计把「公告能不能被看到」押在一次外部发布动作上 —— 文件没推上去、或线上还停着旧版本，用户就什么都看不到；而「没拉到」和「线上真没公告」在界面上长得一模一样，出了问题极难自查。顺带删掉 `AnnouncementCenter` 的 10 分钟节流、`lastRefreshFailed` 与 prefs 缓存，以及公告中心顶栏的「刷新」按钮和「拉取不到 → 重试」空态（内置公告没有「刷新」这个动作，留着按钮点了不动反而像坏了）。`release-notes/vX.Y.Z-announcement.json` 归档与 `scripts/publish_announcement.py` 一并退休，发版流程只保留交付校验（`scripts/verify_delivery.py`）。
- versionCode 改为 `major*10000 + minor*100 + patch`（1.2.2 → 10202）。旧方案"取 patch 位"只对 1.0.x 成立（那时 minor 恒为 0）；1.2.x 的 code 是顺手 +1 加出来的（1.2.1 → 91，1.2.2 原本也要给 92），按 patch 位算只会得到 2 < 91，单调性检查当场拦下、用户端也永远升不上去。`scripts/release.sh`、`release.yml` 的 Resolve Version From Tag、`scripts/publish.py` 三处同步；旧 tag 的 code 按新公式重算后再做单调性比较。
- 公告弹窗（启动提示）顶部图标由 72dp 圆底 + 36dp 图标缩到 52dp / 24dp，不再压过弹窗标题。
- 应用包名由 `com.tyust.course` 更换为 `com.hnnujw.course`（applicationId 与 namespace 同步迁移，Kotlin 源码包路径一并移动）。
- 版本号 1.2.1 → 1.2.2（versionCode 91 → 10202）。
- 更新弹窗下载中的次要按钮由「后台下载」改为「取消下载」并真正调用取消：下载协程挂在 `rememberCoroutineScope()` 上，弹窗一旦离开组合树就会被取消，旧文案与真实行为不符。

### 修复

- 公告已读记录会丢 id、导致看过的公告"回潮"：已读 id 原先存在无序 `StringSet` 里，裁剪时写 `takeLast(100)`，在无序集合上等于随机丢 id，被丢掉的公告下次拉到就重新变成未读。改为 `id<TAB>时间戳` 的有序文本（上限 200 条，裁剪丢的一定是最旧的），旧格式启动时自动迁移。
- 公告拉取把"网络失败"和"线上确实没有公告"混为一谈 —— 这是上面那条设计问题的另一面，随"改为内置"一并消失：不再有拉取，也就没有"拉取不到"这种状态。
- **公告一条都看不到**：`AnnouncementCenter.isRead(id)` 写成了 `id !in readIds`（语义正好反了），`isUnread` 里又对它取反，两层颠倒叠在一起，等于「`showOnce = true` 的公告永远算已读」——启动不弹窗、红点不亮、「未读」分段永远为空；再加上那份公告文件本来就没推到线上，1.2.2 上一条公告都看不到。现在判定统一收敛到 `Announcement.isUnreadIn()` 这个纯函数（不依赖 Android/Compose），由 `AnnouncementLogicTest` 的 18 个用例盯住。
- `showOnce = false`（语义是"每次都要显示"）的公告实际只显示一次：启动弹窗一关就把它写进已读，`firstUnread` 里再也不出现、红点也跟着灭。现在这类公告永远算未读，不看已读记录。
- 公告逻辑此前**零测试覆盖**（269 个用例全绿，功能却是坏的）。新增 `AnnouncementLogicTest`：未读判定（含上面那条写反的判断的回归）、版本范围开闭区间、无版本字段按历史归档、解析容错，以及一条不变式 —— **`assets/announcement.json` 必须有一条适用于当前 versionCode 的公告**（读 `app/version.properties` 交叉校验，id 里还要带上本版本号）。改版本号却忘了补公告，构建会当场失败。
- 更新下载得到 `.zip` 压缩包：Gitee 附件响应头为 `Content-Type: application/zip`，浏览器据此决定扩展名而忽略 `Content-Disposition` 里的文件名。改为应用内下载后，无论服务端声明什么 MIME 一律按 `.apk` 落盘。
- 更新弹窗看不到下载速度与预计剩余时间：`AppDownloader.State.Running.bytesPerSecond` 从未被赋值，一直是默认 0，界面上那两段文字因此永远不显示。新增 `speedOf()` 计算整段平均速度（前 300ms 样本不足不显示）。
- 连点「立即更新」可能启动两个下载：`download()` 是进入 `Dispatchers.IO` 之后才把状态切成 `Running`，在此之前有一段「按钮已按下、状态未更新」的窗口，按钮仍可点，两个协程会同时写同一个目标文件，下出来的包必定损坏。改为进入 IO 之前先切状态。
- 「安装更新」可能指向一个残缺的包：下载途中进程被杀、清理失败会留下「大于 512KB 但被截断」的文件，`existingApk` 此前只判断存在与体积，会把它当成下好的包。新增 `isValidApk()`（按 zip 容器读中央目录并要求含 `AndroidManifest.xml`），在下载完成校验 / 复用已有包 / 唤起安装器三处都过这道闸。
- 外部存储不可用时安装失败：`getExternalFilesDir("Download")` 返回 null 会兜底落到内部 `filesDir/Download`，但 `file_paths.xml` 只登记了 `external-files-path`，FileProvider 找不到 configured root 直接抛异常，用户看到的是「没有找到可用的安装器」这种误导性提示。补上对应的 `<files-path name="internal_downloads">`。
- 安装失败后按钮卡死：安装器返回不可用时确认按钮仍停在「安装更新」，再点几次结果一样。现在只有在包确实已无效时才退回「重新下载」；仅仅是设备上没有可用安装器时保留已下好的包，用户还能去文件管理器手动安装。
- 应用内用户手册的目录与 FAQ 交叉引用点击无反应：`scripts/build_manual.py` 没有启用 `toc` 扩展，生成的 HTML 里所有标题都没有 `id`，而正文用的是 `#4-课表` 一类锚点。已启用并改用保留 Unicode 的 slugify（默认 slugify 会把中文标题剥成空 id）。
- 公告弹窗正文高度写死 280dp，在 16:9 短屏上「图标 + 两行标题 + 正文 + 按钮」总高超出可用高度，底部按钮被裁切不可点。改为按 `usableHeightDp` 自适应。
- 公告列表弹窗高度写死 380dp，同样问题，改为自适应（上限 460dp）。
- 更新检查的 HTTP 响应未关闭（`response.use` 缺失），存在连接泄漏。
- AnnouncementCenter 的未读 id 快照是普通 MutableSet，已读状态变化时不一定触发重组，导致红点与「已读/未读」偶发不刷新；改为 Compose State。
- AppUpdateChecker 补充 User-Agent，并按扩展名严格挑选 `.apk` 资产 —— 同一 Release 下还有 `.zip` / `.tar.gz` 两个源码包，不能误当安装包。

## [1.2.1] - 2026-09-20

### 新增

- 桌面小组件：4x1「下一节课」与 2x2「今日课程」，只读本地课表缓存、无网络开销，点击跳转课表页。
- 成绩订阅推送：每 12 小时巡检成绩 diff，新出成绩或分数变化即发系统通知，点击落到成绩页。
- 考试倒计时视图：考试列表按考试时间排序，显示"还有 N 天"徽标，临近考试高亮。
- 考前提醒：开考前 24 小时发送系统通知（时间、地点），数据更新或开机后自动重排。
- 公告中心：「我的」页公告入口（未读红点）+ 全部公告列表 + 液态玻璃公告弹窗，公告来自仓库 announcement.json。

### 变更

- 考试列表从教务原始顺序改为按考试时间排序。
- 本版本为旧包名的最后一版：下一版本起更换包名，需按应用内公告引导手动下载迁移。

## [1.2.0] - 2026-09-20

品牌与体验更新：应用更名为「教务助理」，全新图标，「设置」页更名为「我的」。

### 品牌与界面

- 应用更名为「教务助理」，全新应用图标（方形与圆形、五档密度），包体积约 13MB 降至约 4.7MB。
- 「设置」页与底部导航更名为「我的」，相关文案同步。
- 首次登录引导改为液态玻璃弹框，介绍条目点击直达对应页面。
- 冷启动进入应用时显示「欢迎回来」提示。
- 「关于项目」重新分节排版，新增对原作者 znjhahaha（zhengfang-apk）的致谢。
- 移除开机 Logo 动画。

### 课表与选课

- 课表时间冲突支持「本节上哪门」：课程详情里点选要上的课，另一门让位隐藏；按节记录、按账号保存、可一键恢复。

### 设置与工具

- 新增应用内「用户手册」（离线阅读）、「检查更新」（Gitee）、「推荐网站」、「分享给校友」。
- 账号管理空槽位文案与样式统一。

### 安全与清理

- 移除残留的第三方反馈接口（hidisiwa.xyz）及签名密钥配置。
- 公告源、更新检查与 CI 发布脚本统一指向本项目 Gitee 仓库。

## [1.1.7] - 2026-09-20

本版本包含安全加固与两处体验增强。

### 安全

- **移除全局 HTTPS 信任绕过**。此前网络层装了一个"接受一切证书"的信任管理器，
  并把主机名校验恒置为通过（注释写的是"解决部分学校证书问题"），
  等于把账号密码、Cookie 与选课请求全部暴露给中间人。
  实测主站证书为公共 CA 签发的通配证书（`*.hnnu.edu.cn`），系统信任链可直接校验，
  因此改为使用默认的严格校验。
- 新增网络安全配置：明文 HTTP 只对备用入口 `211.70.176.172` 与
  `www.gdjw.zjut.edu.cn` 放开，其余域名一律禁止明文；同时去掉 Manifest 里
  全局的 `usesCleartextTraffic="true"`。
- **日志脱敏**：登录与验证码流程原先会把完整的 `Set-Cookie`（含 JSESSIONID）打进
  logcat，而应用自带"导出日志"会把 logcat 落盘并分享出去。
  现改为只记录 Cookie 名，值一律不落日志。
- **云备份收紧**：`secure_credentials`（账号密码密文）与 `course_selector_prefs`
  （`saved_cookie` / `saved_accounts`，内含会话 Cookie）两个文件从云备份与
  设备迁移中排除，会话与凭据不再随备份扩散到其它设备。

### 登录页

- 新增「测试站点连通性」：一次点击依次探测主站与备用入口的登录页，
  分别显示连通与耗时（毫秒），失败时给出原因（域名解析失败 / 连接超时 /
  证书校验未通过 / 无法建立连接）。
- 探测使用独立的网络客户端，不带 Cookie 与会话拦截器，不会污染真实登录会话；
  任何 HTTP 状态码都视为"通"，只有网络层异常才算不通。

### 课表

- 课表设置新增「课表字号」，提供 小 / 标准 / 大 / 特大 四档（0.85 – 1.30 倍）。
- 实现方式是缩放课表网格的渲染密度 `fontScale`，因此网格几何（列宽、行高、留白）
  完全不变，只有文字变大变小；课程卡片行高按实测文字高度自适应，放大后不会挤压。
- 该设置只作用于课表网格，成绩、选课、第二课堂与设置页均不受影响。
- 设置按账号隔离保存，改完立即生效，无需重启。

## [1.1.6] - 2026-09-19

本版本按星期条日期那个坑的做法，把课表头部其余几处「写了字号却没写行高」的文字一并补齐。

### 课表

- 顶栏「第 N 周」与今天的日期：显式指定行高（展开 26sp / 19sp，收拢 17sp / 15sp），
  不再继承正文样式的 24sp。原来两行加起来正好等于整行高度，属于零余量的危险状态：
  任何一点字体度量的出入都会让第二行排不下而整行不显示。
- 这两行现在按实际需要的高度排版，在整行里垂直居中，与标题的间距更紧凑。
- 时间列的月份标签、「请设置 →」引导入口同样显式指定行高。
- 顶栏高度的计算方式不变（仍按每行 24sp 倒推），实际文字比预算更矮，差额即为安全余量。

## [1.1.5] - 2026-09-19

本版本修复星期条日期「有位置没字形」的排版问题。

### 课表

- 修复星期条下方的日期数字完全不显示的问题。
  根因是两处文字都没有显式指定行高，于是继承了正文样式的行高（24sp，约 48 像素），
  而星期行的固定高度只有 68 像素：星期那一行先占掉 48 像素加 6 像素间距，
  只剩 14 像素给日期，比一行文字还矮，排版结果是「一行都排不下」，
  于是文字一个像素都不画，界面层级里却还能看到这些节点。
- 星期与日期改为显式指定紧凑行高，两者加起来正好落在星期行的高度内。
- 日期字号与上方的星期文字保持一致，不再明显偏小。
- 星期行高度补上下限，系统字体放大或屏幕较矮时也不会再挤掉日期。
- 切周滑动时日期的淡入淡出保留最低可见度，不会整行消失。

## [1.1.4] - 2026-09-19

本版本包含 v1.1.3 之后的日期、头像、绑定页与消息中心修复。

### 课表

- 修复星期条下方不显示日期数字的问题。
- 第一周日期的取值增加到四级兜底：提醒日历、设置中保存的日期、进程内记忆、按学期推算，任何一层命中都能显示日期。
- 修复提醒日历在学期标识为空时写入静默失败，导致设置的日期只保存在设置里、课表读不到的问题。
- 老版本保存的非周一日期会自动归一到所在周的周一，保证日期与周次准确对应。
- 星期条拿不到日期时改为按本周推算，不再显示占位小圆点。
- 课表设置的「第一周开始日期」现在直接显示当前生效的日期，并标明来源是「已按你的设置」还是「按学期推算」，不用连电脑抓日志也能判断日期对不对。

### 头像

- 修复选图后一律提示「无法读取这张图片」的问题：量尺寸那一步误用了空值判断，任何图片都会解析失败。
- 裁剪页显式带上相册的临时读取授权，避免部分机型上因授权失效导致读取失败。
- 直接解码失败时改为先把图片复制到本地再解码，规避不可随机访问的流与授权过期。
- 区分「图片读取失败」与「启动参数缺失」两种提示，不再混为一谈。
- 修复裁剪页取景框中间出现黑色方块、把照片盖住的问题：原来用混合模式挖空窗口，
  在默认合成下会被清成黑色而非透明；改为用奇偶填充规则直接挖洞，窗口正常透光。
- 取景窗口改为圆角，轮廓线收细，不再抢照片本身的注意力。
- 解析第一周日期时会打印诊断日志（标签 ScheduleDates），便于定位日期对不上的原因。

### 绑定账号

- 确认绑定账号页面移除绑定配额卡片与下方名额说明，页面只保留账号确认。

### 消息中心

- 顶部新增未读与已读切换按钮，未读在左侧，默认选中未读。
- 按钮使用与全站一致的液态玻璃分段控件，带光线折射与边缘色散。
- 选项上直接显示对应分组的消息条数。
- 切换后只列出该分组的消息；分组为空时给出对应提示文案。

## [1.1.3] - 2026-09-19

本版本包含 v1.1.2 之后的课表、选择器交互与头像相关修复与改进。

### 课表

- 课表设置的「自定义课程」每一行右侧新增删除按钮，点击后弹确认框，确认即删除。
- 删除自定义课程时会一并移除它的上课提醒，课表页面与设置列表即时同步刷新。
- 修复星期条下方日期数字不显示的问题：未手动设置第一周日期时，按学期自动推算第一周周一。
- 日期计算增加两级记忆：进程内记住已确定的第一周日期，页面也记住最后一次有效值，避免冷启动与恢复快照时日期闪一下又消失。
- 老版本保存的非周一日期会自动归一到所在周的周一，保证日期与周次严格对应。
- 通过补课生成的课程不再显示铅笔图标，与普通课程外观一致，无障碍播报改为「补课」。

### 交互与材质

- 补课弹窗里的周次、星期等选项由点击选择改为上下滑动的滚轮选择。
- 滚轮中间的选中指示条换成液态玻璃材质，与底部导航栏一致，带折射与边缘色散。
- 系统弹窗内容区接入折射底图，弹窗内的滚轮也能采到正确的背景。

### 头像与背景

- 更换头像改为「选图 → 取景框裁剪 → 保存」，方形取景框保证裁出正好的头像比例。
- 头像与自定义背景复用同一套裁剪与压缩管线，支持双指缩放与拖动，并自动摆正旋转的照片。
- 头像不再依赖相册读取权限，改由系统照片选择器代理读取，选图失败的情况大幅减少。
- 新增头像后立刻刷新显示，不再出现换了图还显示旧头像的问题。

## [1.1.2] - 2026-09-19

本版本包含 v1.1.1 之后的课表、消息中心、第二课堂与头像相关修复。

### 消息中心

- 消息列表按未读、已读分成两组，各组带数量标题，未读在前。
- 组内按发送时间倒序排列，最新的消息排在最上方。

### 第二课堂

- 榜单名次改为按积分由客户端重新计算：同分并列共享名次，名次号不再跳号。
- 修复全班 37 人时最大名次只显示到 35、出现重复名次的显示问题。
- 「我的排名」卡片与榜单内的名次保持一致。
- 登录框按学校统一规则预填初始密码（学号 + &Zhtx），改过密码可直接覆盖输入。
- 账号或密码不正确时给出明确提示，并说明初始密码规则与官方改密方式。

### 课表

- 补课入口改为携带点击时的周次：在第 4 周的周六格点击，课程会落到第 4 周周六。
- 弹窗内选择要补哪一周、哪一天，整天的课程按原时段一起搬过来，无需逐门挑选。
- 同一门课在同一格重复补课时自动合并周次，不再叠加出多张重叠卡片。
- 补课生成的课程会同步登记提醒记录，可在课程详情里一键开启上课提醒。
- 修复星期条下方的日期数字不显示的问题。

### 自定义头像

- 点「更换头像」时先检查并申请相册读取权限，再打开相册，避免部分机型选图静默失败。
- 权限被永久拒绝时给出跳转系统设置的引导，并修正文案与实际流程不一致的问题。

## [1.1.1] - 2026-09-19

本版本包含 v1.1.0 之后的界面修复、消息中心与账号数据相关改动。

### 消息中心

- 消息中心改用教务系统真实消息列表接口，拉取当前登录角色下的已阅与未阅消息并合并展示，正文、时间和详情链接一并解析。
- 登录状态失效时给出明确提示，不再把登录页误判为空列表。
- 未读消息数量作为全局状态维护，底部导航与设置入口在有未读时显示红点。
- 新增后台巡检，每 12 小时检查一次新消息；检测到新未读消息时发出系统通知，通知记录按账号隔离，避免重复提醒。
- 设备重启、应用更新、系统时间变更后自动重排巡检任务。

### 第二课堂

- 班级榜单改为分页累加至接口返回的总人数，修复只显示部分同学的问题。
- 榜单按学号去重，解决名次并列时的重复与丢失。
- 设置页新增「显示班级同学排名」开关，关闭后仅在榜单中隐藏同学排名。

### 课表

- 补课弹窗改为液态玻璃材质，与主站登录页保持一致。
- 周次与星期改为折叠选择器，展开状态互斥；按周次与星期网格点选。
- 补课时不再需要逐门挑选课程，确认后自动补齐所选周次与星期当天的全部课程，并在确认前预览受影响门数。

### 自定义头像

- 修复选择图片后进入裁剪页提示无法读取图片的问题：原实现在主线程解码原始尺寸大图导致内存溢出，现改为先降采样并处理旋转后落盘，裁剪页只读取本地副本。
- 裁剪结果强制正方形并限制尺寸，取消裁剪时清理临时文件。
- 补充读取图片权限的申请与授权引导，权限被永久拒绝时可直接跳转系统设置。

### 其他

- 修复课表中自定义课程颜色全部显示为黑色的问题。
- 补全课表星期下方的日期数字显示。



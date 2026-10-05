# 题库练习模块（v1.2.7 新增）

> 模块名 2026-10 由「复习」改为「**题库练习**」（用户要求）。**只有显示名改了** ——
> `ReviewActivity` / `ReviewPage` / `ReviewDatabase` / `ReviewPrefs` 等标识一律不动：
> 它们同时是类名与落库键的一部分，为一个显示名去动它们只会制造无谓的迁移风险。

## 是什么

从题库仓库取出题库 → 按章节与模式刷题 → 错题本 / 统计 → 可选让 AI 讲解。数据全部在本机。

参考实现：`D:\zhiyu`（知隅，Flutter 题库 app）。**只借它的题库格式与练习流程**，
不引它的任何代码，也不带它的题库文件（版权 + 包体）。

## 入口

「我的 → 校园服务 → 题库练习」。独立 Activity（`ReviewActivity`），内部用同一 composition 流转页面 ——
练习会话是内存对象，换 Activity 会被回收，正在做的题会丢。

## 页面与练习流程

```
题库练习首页（单段：「我的题库」；标题行右侧一个「题库仓库」入口；另有 AI 设置入口）
  └─ 题库仓库页（BankLibraryScreen）
       ├─ ① 取出到我的题库   未导入的内置库 + 已移出的库，点「取出」即回来
       ├─ ② 导入题库         系统文件选择器选 JSON + 格式说明入口
       └─ ③ 题库管理         创建 / 合并 / 逐题编辑 / 改名 / 删除（列出**全部**题库）
             └─ 逐题编辑页 BankQuestionEditScreen（返回回仓库页）
  └─ 题库详情页（开练中枢）
       ├─ ① 概况          总题数 · 章节数 · 已练正确率
       ├─ ② 章节练习      垂直展开的章节列表      ← 先定"练哪一部分"
       ├─ ③ 练习模式      滚轮选四档（顺序 / 乱序 / 考试 / 背题）+ 考试时长 + 开始练习 + 选项乱序 ← 再定"怎么练"
       └─ ④ 错题本（胶囊按钮）→ 跳 BankWrongBookScreen 查看
       └─ ④ 收藏（胶囊按钮）→ 跳 BankFavoriteScreen 查看
                                  └─「练习待复习错题（N）」开练
  └─ 练习页（PracticeScreen）
       ├─ 顺序 / 乱序 / 考试：最后一题是「提交」→ **总结页**（SummaryPane）
       └─ 背题模式：最后一题是「完成」→ 直接返回（不判分，没有"提交"可言）
```

三条口径（改页面时别破坏）：

- **顺序不可打乱**：先章节、后模式，最后才是"看数据"。章节刻意用**垂直展开列表**而不是横向
  chip —— 章节多时横滑既看不全也容易点错，而"我在哪一章"恰恰是开练前最该看清的信息。
- **错题本与收藏只跳页、不直接开练**：用户需要先看清"我错了哪些、哪些没掌握、今天该复习什么"，
  再决定练哪一批。点一下就开始刷会把这份信息完全藏起来。
- **入口常显**（项目红线）：题数为 0 / 没有错题时入口照常渲染，只是点进去是空列表，
  绝不按状态把入口藏掉 —— 用户会以为功能根本不存在。
- **编辑入口不在这一层**：练习题目页与题库详情页都**不提供**编辑，统一走「题库仓库 → 题库管理」。
- 🔴 **「统计」入口与统计页已删除**（2026-10 用户要求「将查看的统计删除」）：「查看」卡片只剩
  错题本 / 收藏。正确率仍然看得到 —— 详情页概况卡有「已练正确率」，一轮练习结束的总结页有本次正确率。


## 题库仓库页与「我的题库」（v1.2.7 新增，第三批改为独立页）

2026-10 用户要求：「做一个题库仓库，存储所有的题库，用户按需选取题库出来，进行对应的练习
并支持调整顺序」；第三批又要求「**将题库仓库做成页面，将题库管理功能移到题库仓库里**」。
**默认一个都不取出** —— 上一版的行为是"首次进复习页且一个库都没有时，把 4 个内置库全导进来"，
用户一进来就被塞 1059 道题，没有选择余地。

- 「**我的题库**」（首页唯一一段）= `banks.selected = 1`，可**长按拖动**或点**上/下移箭头**
  调整顺序（两种都保留：无障碍模式下拖拽手势不可用，且拖动一格的精度不如点一下），
  顺序落库、重启保持。行内按钮收成**上移 / 下移 / 移出**三个 ——
  「删除」搬去了仓库页，因为它不可撤销、与「移出」并排在一行极易点错。
- 「**题库仓库**」页的「取出到我的题库」= 还没导入的内置库（`BundledBanks.pendingSpecs`）
  + 已导入但被移出的库。点「取出」即回来：前者要读 asset 解析落库
  （`BundledBanks.importOne`，查重两道），后者只翻一个标志位（`setBankSelected`）。
- 🔴 **「移出」只改显示状态，不删任何数据**：题目、答题记录、错题本、收藏原样留着，
  所以随时能取回来。真正删除只走仓库页的「删除」（二次确认）。
- 为什么「取出」和「题库管理」会列出同一批库：两段的**动词**不同 —— 前者是"拿出来 / 放回去"
  （只改显示状态），后者是"改它 / 删它"（不可逆）。混成一段用户会分不清。
- 内置库**不再自动全量导入**：`BundledBanks.importAll` / `importAllBlocking` 已删除。

## 演示题库（v1.2.7 新增）

`assets/review/demo.json`，两章共 8 题（第一章 计算机基础、第二章 网络基础，单选与多选都有）。
它的内容**与应用无关，就是普通测试题**。

- **不进题库仓库**：`DEMO_SPEC` 刻意不在 `BundledBanks.SPECS` 里，因此不参与 `pendingSpecs`。
  它是"新手指引"，放进仓库反而要求用户先学会「取出」才看得到题。
- **只播种一次**：`BundledBanks.ensureDemoBank` 在首次进入首页时导入并**取出**（`selected = true`），
  播种标记落 `ReviewPrefs` 的 `demo_bank_seeded`。用户删掉之后**不会再长出来** ——
  否则删了又出现，像个删不掉的流氓软件。该标记**纳入备份**（换机恢复后不该又冒出一个）。
- 导入失败时**不写标记**，下次进模块再试一次（坏资产 / 磁盘满都属可恢复）。
- `TOTAL_QUESTION_COUNT`(1059) 与 `SPECS` 只统计四份正式题库，**不含**演示题库。

## 练习模式与考试时长（v1.2.7 新增）

2026-10 用户要求「**将练习模式改为滑动选择模式，就是和一卡通中选择宿舍的差不多**」。
于是四档模式由并排的 `ModeCell` 格子改成**一行表单字段 + 玻璃滚轮弹窗**
（`GlassOptionWheelDialog`，与一卡通选宿舍电费同一形态）。

- 界面上的「练习模式」**不是** `ReviewMode`：四档里「考试」与「背题模式」都按顺序抽题
  （`ReviewMode.SEQUENTIAL`），区别在"限不限时""判不判分"，那是 `PracticeLaunch` 上的
  另外两个字段。硬套 `ReviewMode` 会让「考试」没有自己的位置。
  于是新增 `review/PracticeMode.kt`：`PracticeModeOption`（`SEQUENTIAL` / `SHUFFLED` /
  `EXAM` / `MEMORIZE`，各带 `label` 与 `hint()`）+ `resolvePracticeParams(mode, minutes)`
  → `PracticeParams(mode, memorize, durationSeconds)`。**映射是纯函数、可单测** ——
  它错了界面看起来完全正常，用户只会发现"我明明选了考试，怎么不限时"。
- **时长只对「考试」生效**（`PracticeModeOption.needsDuration`）。其余模式一律 `durationSeconds = 0`：
  用户在「考试」里把时长调到 120 再切回「顺序练习」时，`durationMinutes` 这个变量还在，
  映射必须把它吞掉，否则顺序练习会突然开始倒计时（有单测钉住）。
- 时长档位：`ExamDurationPresetsMinutes`（10/20/30/45/60/90/120 分钟）+ 末档「**自定义…**」
  再开一个滚轮（`CustomDurationMinutes` 5…300 步进 5）。
  ⚠️ `ExamDurationOptions` / `ExamDurationOption` **已删除** —— 它的「不限时」档正是上一版
  必须把「考试模拟」禁用并解释原因的根源（不限时的考试就是顺序练习），现在这一档不存在了，
  那条禁用逻辑也一并消失。
- 四档改成"先选后确认"之后多了一个「**开始练习（N 题）**」主按钮（原先点格子即开练）。
- 「**乱序练习**」与「**选项乱序**」是两回事：前者管**题目顺序**（`ReviewMode.RANDOM`），
  后者管**同一道题里 A/B/C/D 的排列**（`ReviewPrefs`）。模式说明与文档里都写明了这一点。

## 提交与总结页（v1.2.7 新增）

2026-10 用户要求「顺序练习和乱序、考试模式下答完题后的完成按钮改为**提交**并有个**总结页面**，
总结页面要添加鼓励语」，并追加「总结页面还是要有**本次答题的数据**的」。

- **入口**：练习页底部最后一题由「完成」改「**提交**」（走主按钮样式，与「下一题」拉开层级）；
  **答题卡页底部也加了「提交」**（答题卡是"交卷前最后一眼"，用户在这里最容易想交卷）；
  **限时到点自动交卷也走同一条路径** —— 被强制收卷的人比谁都更需要立刻看到结果。
- **背题模式例外**：不判分，最后一题仍叫「完成」并直接返回；答题卡底部改成一句
  「背题模式只看答案、不计分，看完直接返回即可。」而不是点了没反应的灰按钮。
- **总结页放在 `PracticeScreen` 内部**（`SummaryPane`），**不加 `ReviewPage`**：它要显示本次的
  逐题明细，而明细只有这个会话知道。上抛成新页面就得把整个 `session` 也传出去，等于把会话的
  生命周期摊到宿主里。`question == null`（题号越界）这个防御分支也走同一个总结页，少一套口径。
- **数字口径全部在纯函数 `review/PracticeSummary`**（`PracticeSummaryTest` 13 项）：
  - ★ **正确率的分母是 `judged` = 对 + 错**，**不是** `total`，也**不是** `PracticeResult.answered`
    （后者是"碰过的题"，含"选了但没确认"）。混用会出现"顶部副标题 67%、总结页 40%"这种
    自相矛盾的数字，而它不崩不报错，只有用户自己发现。未作答的题也**不算答错** ——
    到点自动交卷时会有大片未作答，算进分母等于惩罚"没来得及做"。
    ⚠️ 但**"选了没确认"的题在交卷时会先被补判**（见下一节），所以它们不会停在 `answered` 里
    当"未作答"—— 那条口径只对**真正没碰过**的题成立。
  - `elapsedSeconds(startedAt, nowMillis)` 按**墙钟差值**算（与倒计时同一口径），开始时刻未设定
    或时钟倒退一律返回 `0` —— 界面上出现 `-00:03` 只会让人以为程序坏了。
  - `encouragementFor(accuracyPercent, judged, total)` 七档，外加「没有题目」「还没作答」两句
    **独立**文案：「一题都没答」与「全错」必须给不同的话，否则会对着 0% 说"非常棒"。
  - `title` 三态：练习完成 / 考试结束 / 考试时间到。
- **总结页结构**：鼓励语 → 大号正确率 + 汇总行 → 「本次数据」胶囊（总题数 / 答对 / 答错 /
  未作答 / 用时，限时再加考试时长）→ 「答题明细」（把答题卡的格子原样搬来、**只读**）→「返回题库」。
  明细用 `FlowRow` 而**不是**懒加载网格：它在 `verticalScroll` 里，任何同轴滚动容器都会拿到
  `maxHeight = Infinity` 并在测量阶段抛异常（项目红线）。

## 判分按钮与交卷补判（v1.2.7 调整）

2026-10 用户报三件事：「有的按钮是**确认答案**，有的是**提交答案**，统一成确认答案」「若用户
**只选择选项之后提交**要判断对错，不要只是在该题做完后点确认答案后给出对错」「增加**选择答案后
就跳到下一题**的按钮」。

- **按钮名统一**：`QuestionCard` 此前按题型分字样 —— 单选「确认答案」、多选「提交答案」。
  同一个动作在一页里有两个名字，用户会以为多选那颗是"交卷"。现在一律「**确认答案**」。
- ★★ **「确认答案」不是"这题算不算数"的开关**。它只是"**现在就把解析摊开给我看**"。
  用户选了 A 就点「下一题」往下走，交卷时这题必须算数 —— 否则总结页会把它报成「未作答」，
  用户明明选了却看到未作答，正确率也是假的。
- **`PracticeSession.judgePending(): List<ReviewQuestion>`**：交卷时把"选了但没判分"的题一并判分，
  返回**本次新判分**的题（按出题顺序）。`PracticeScreen.submit()` 在取汇总数字前调它，并把这批
  逐条 `recordAnswer` 落库 —— 「判分即落库」这条不变量对补判的这批同样成立，否则交卷后数字变了、
  错题本却没记住。三条边界：
  1. **只补判"有作答"的题**：从头到尾没碰过的题保持未作答。没答就是没答，编一个"错"出来比留白更糟
     （限时考试到点会留下大片这种题）。
  2. **已经判过的不动**：用户点过「确认答案」的题可能有后续的「再想一下」，重复判分会让撤回失效。
  3. **背题模式恒返回空**：把"背题不判分、不计入统计"这条不变量在 `recordsAnswers` 之外再守一道。
- ★ **限时到点自动交卷与手动提交是同一个 `submit()`**。因此 `PracticeSession.resultForTimeout()`
  （`result()` 的纯别名）彻底失去存在意义，**已删除**；`ExamTimerTest` 里依赖它的两条测试改写成
  「没碰过的题仍算未作答」与「选了没确认的题会被补判」。**不要**再给超时路径加第二套口径。
- ★ **「自动下一题」开关**：打开后**选完选项自动跳到下一题**。判据抽成
  `PracticeSession.canAutoAdvance(q, enabled)`（`PracticeSessionTest` 4 项）：
  - **多选题不自动跳** —— 多选要选好几项，程序无从判断"选完了没有"，按点击次数猜会把
    只想选一项的用户弹走。这条漏了，界面上的表现是"开关没生效"，用户只会以为程序坏了。
  - **背题模式不自动跳** —— 选项是直接摊开的、根本不可点，再守一道。
  - 它**只管跳不跳、不管判分**：跳过的题作答照旧留着，交卷时由 `judgePending()` 统一判。
  - 落跳前**等 220ms**（`AUTO_NEXT_DELAY_MS`）让"选中"的高亮能被看见，并**在跳之前再确认一次**
    开关还开着、没提交、不是最后一题 —— 等待期间用户可能已经改选 / 关开关 / 手动翻页 / 提交。
    用自增计数器 `autoAdvanceTick` 而不是布尔：连点两个选项时要**重新计时**，不是叠加成连跳两题。
  - 开关常显在练习页（答题卡入口行下面），状态存 `ReviewPrefs`（开一次一直生效，但不必每轮重选）。
- ⚠️ **第一版做成了选项下方一颗手动的「下一题」按钮，用户明确纠正「我要的不是下一题的按钮」**。
  记住这条偏好：他要的是**行为开关**（打开后不用再操作），不是又一个要点的按钮。

## 按钮排版（v1.2.7 统一）

2026-10 用户要求「将整个题库练习的按钮排版一下」。

- ★ **主按钮与次级按钮并排时底部参差**：`ConfirmButton` 固定 48dp、`SecondaryAction` 固定 42dp，
  而 `Row` 默认按 `Alignment.Top` 排列 —— 于是右半边永远矮一截。练习页底栏（上一题 /
  下一题·完成·提交）与题库仓库页（创建题库 / 合并题库）都中招。
- 修法：`review/ui/ReviewButtons.kt` 把高度抽成具名常量 `ConfirmButtonHeight`(48dp) /
  `SecondaryButtonHeight`(42dp)，给 `SecondaryAction` 加形参 `height`；**与主按钮并排时把次级
  按钮抬到 48dp**（主次改由**样式**区分，不再靠高度），并补 `verticalAlignment = CenterVertically`。
- 胶囊类按钮（错题本 / 收藏 / 取出 / 上移 / 编辑题目…）**保持 42dp** —— 它们不跟主按钮并排，
  矮一档正是它们作为"轻量动作"的观感。

## 答题卡（v1.2.7 调整）

- 格子 **52dp / 17sp**（原 44dp / 12sp）、当前题描边 2dp、网格最小列宽 56dp。
  上一版的 12sp 题号被用户报「太小看不清」—— 答题卡的全部信息就是那个数字，它不该比正文还小。
- 加了「未答 / 对 / 错」**颜色图例**（`AnswerSheetLegend`）：没有它，用户只能靠猜颜色含义。
- 底部 `bottomBar` 是「提交」（见上一节）。弹层里没有底部导航栏，但系统导航条还在，
  所以按钮自带 `navigationBarsPadding()`。

## 顶栏跟手折叠

`GlassPageScaffold` 的 `collapseFraction` **默认 0 = 不折叠**，所以每一页都得自己接上滚动状态，
漏了就表现为「滑动后大标题始终不变」。已接入的页面：课表 / 成绩 / 设置 / 二课 / 一卡通 /
空闲教室 / 提醒设置 / 组件工作台 / **题库仓库**（`BankLibraryScreen`，2026-10 用户报
「在题库仓库页面向上滑动后，题库仓库的字样没有改变」）。

判据统一：**滚动 96dp 收满**；用 `LazyColumn` 的页面（题库仓库）取
`if (firstVisibleItemIndex > 0) 1f else (firstVisibleItemScrollOffset / 96f)`，
首项滚出屏幕后直接收满 —— 否则长列表快滚时首项偏移会来回跳，标题跟着抖。

## Markdown 渲染

全应用只有 `ui/system/MarkdownText` 一处入口（`looksLikeMarkdown` 判定走富文本还是普通 `Text`）。
`AnnouncementDialog` / `AnnouncementScreen` 直接调 `Material3RichText`，是两处**例外**。

★ **不成对的星号会被 commonmark 原样渲染成字面字符**（2026-10 用户报「软件里 md 的残留 `****`」）。
根因是 `looksLikeMarkdown` 只认**成对**的加粗（`**x**`），于是不成对的星号被判成"这段不是
Markdown"而走纯文本分支，原样露出来。三种来源：空加粗 `****`、**流式输出中途**的半截标记、
作者/模型打了一半就换行。

修法是纯函数 `sanitizeMarkdown`（在判定与渲染**之前**统一清洗，`MarkdownTextTest` 21 项）：

1. 空加粗（`****`、`** **`）整对删掉 —— commonmark 的可配对规则本来就不认它；
2. 标记数为奇数时**只删落在片段首尾**的那一个（流式露出来的正是它）；落在中间的**不动**，
   否则 `2**3` 会被改成 `23`，比留着星号更糟；
3. 行内代码与围栏代码块**整段保护**（`**kwargs`、`a ** b` 是内容不是标记）。

## 题库格式

见应用内「题库仓库 → 导入题库 → 格式说明」，样例见 `docs/review/sample-bank.json`。

顶层两种：`{"name":..., "questions":[...]}` 或直接题目数组。中英文键名都认
（`stem`/`题干`、`options`/`选项`、`answer`/`答案`、`type`/`题型`、`chapter`/`章节`、
`explanation`/`解析`、`source`/`来源`）。`options` 兼容 `"甲|乙|丙"` 写法。

导入规则：
- 单选答案必须 1 个字母，多选至少 2 个；不合规的题**单独跳过**并提示跳过条数，不整份失败。
- 选项 2–8 个，答案不得超出选项范围。
- **同名题库重复导入 = 覆盖**（旧题与旧答题记录一并清掉，避免孤儿行）。

## 表结构

`review.db`，原生 SQLite（项目没引 Room，不为一个模块加 KSP）。表名与列名照 zhiyu 的
`database.dart` 对齐，方便对照迁移。当前 `DB_VERSION = 4`，升版走 **`PRAGMA table_info` 判列的
幂等 `ALTER TABLE`，绝不删表重建**（删表 = 用户记录全丢）。

| 表 | 作用 |
| --- | --- |
| `banks` | 题库。**`selected`**（是否已从仓库取出）/ **`sort_order`**（取出后的排序权重）为 v4 新增 —— 迁移时**存量行必须写成 `selected = 1`**，否则老用户升级后题库会全部"消失" |
| `questions` | 题目，`(bank_id, qid)` 联合主键 —— 不同库可有同 id 的题 |
| `records` | 每题对错次数与最近结果 |
| `wrong_book` | 错题本：连对 `MASTER_STREAK`(3) 次算已掌握；掌握后答错重新入册。另含 `streak` 与 `due_at`（下次到期时刻，`0` = 从未排期，判据上等价于立即到期） |
| `slashed` | 斩题：用户主观标记"不用再练"，**与掌握状态无关**。⚠️ 2026-10 起**界面里已没有改它的入口**（斩题按钮已删），表与 `excludeSlashed` 过滤仍在 ⇒ 已斩的题仍被过滤出练习，且无法在应用内放回 |
| `favorites` | 收藏 |
| ~~`daily_stats`~~ | 每日答题量。🔴 **已随统计页一起删除**（2026-10）：表不再创建、不再写入、也没有读方 —— 它当时只有统计页一个消费者，留着就是"只写不读"的黑洞。顺带发现那条写入**本来就是坏的**：`INSERT OR REPLACE ... VALUES (?, 1, ?)` 撞主键是整行替换，每天的 `answered` 永远被写成 1。老安装里该表会作为空壳残留（含历史数据），不影响功能 |
| `meta` | 杂项键值 |

## 判分与状态口径

- **多选必须完全一致才算对**（少选/多选/错选都算错）。抽成纯函数 `judgeAnswer()` 而非
  Composable 里的布尔表达式 —— 返回布尔会把"为什么错"丢掉，界面只能给所有 false 印同一句话。
  单测在 `PracticeSessionTest`。
- **掌握三态**（`WrongStatus`）：复习中 / 未掌握（错 ≥2 次）/ 已掌握。**已掌握优先**，
  判据只有一处。
- **间隔复习**（`ReviewScheduling`）：新题当天 / 学习中 1 天 / 较熟 3 天 / 已掌握 7 天。
  `dueAt` / `isDue` 不读墙钟，时间由调用方传入 —— 否则单测无法稳定。
- **斩 ≠ 已掌握**：斩题是主观跳过，不动错题本状态（`SlashPolicy.wrongStatusAfterSlash` 用恒等
  钉住这条不变量）。
- **背题不计入统计**：不判分、不进错题本、不改掌握度。
- 错题本的筛选 / 排序 / 汇总全在纯函数 `WrongBookLogic` 里，界面不自己判掌握状态。
  汇总的「今日到期」**不含已掌握**，与 `dueWrongQuestions` 的 SQL 条件 `mastered = 0` 对齐。
- **答题卡四态**（`AnswerSheet`）：未作答 / 选了未确认 / 答对 / 答错。**未作答优先于一切** ——
  背题模式从不作答，若先判 `judged`/`correct` 会把整张卡涂成"答错"。判据在 `answerSheetStatus`。
- **章节自然排序**（`ChapterOrder`）：章节名的编号是中文数字，`ORDER BY chapter` 会排成
  「第一章、第七章、第三章…」。解析编号要过两道关 —— ① 位置（串首或紧跟 `第`）；② 终止符
  （串尾 / 标点空白 / 量词「章节讲课篇部分…」）。  只靠 ① 会把「三个代表重要思想」判成第 3 章。

## 选项乱序（v1.2.7 新增）

**显示序与数据序彻底分离**（`OptionShuffle.kt`，思路参考 Shiroha Quiz 的 `PracticeDisplayOption`，
**不抄代码** —— 它是 GPL-3.0）：

- `DisplayOption` 三个键：`letter`（按**显示位置**重新编号的 A/B/C…）、`originalIndex`、
  `originalLetter`（题库原始字母）。
- 界面只认 `letter`；**作答、判分、落库、错题本、答题卡一律继续用 `originalLetter`** ——
  所以 `PracticeSession` 与数据库一行未改，乱序纯粹是渲染层的事。
- `QuestionCard` 里「正确答案 / 你选」的字母条要经 `toDisplayAnswer()` 翻译成**显示字母**：
  用户看到的是打乱后的排列，报原始字母等于让他去数一个屏幕上不存在的顺序。
- **随机源**：`seedFor(question, seed) = seed xor (题目键.hashCode() * 2654435761L)`。
  不能直接用 `Random(会话种子)` —— 那样所有题目共用同一个随机序列，题与题之间高度相关，
  看起来像"没乱"。会话种子每轮开练新取，所以同一题在同一轮里顺序稳定（重组不会跳）、
  换一轮整体重排。
- **开关默认关**（`ReviewPrefs`，明文 SharedPreferences）：乱序会打乱"答案是 A"的肌肉记忆，
  且与题库纸质材料对不上。
- ⚠️ **已知限制**：解析正文里写死的「A 项」字母引用不会跟着重排。解析是题库作者的自由文本，
  正则重映射会把「A 股」「A4 纸」这类词一起改掉，比不改更糟。提示文案里已如实写出。

## 抗进程死亡（v1.2.7 新增）

2026-10 起，练习进度在系统回收进程后可恢复。此前**全模块没有一个 `rememberSaveable`**，
切后台久了回来就从头开始。

- `PracticeSession.exportState()` / `restoreState()`：作答与判分导出成 **JSON**
  （不用自定义分隔符 —— `bankId::qid` 复合键来自题库数据，"选一个不会出现的分隔符"是赌）。
- `PracticeScreen`：题号与作答快照走 `rememberSaveable`。恢复只在会话确实"一次都没作答"时灌，
  避免覆盖用户这一轮已有的作答；「已灌过」标记**必须用 `remember`**（进程重启后要重新变 false）。
- `ReviewActivity`：页面选择 `ReviewPage` 与两个"来处"改 `rememberSaveable` + 自定义 `Saver`
  （编码成字符串，`Bundle` 只收 Parcelable / 基本类型），并把开练参数 `PracticeLaunch`
  编码保存（字段分隔 `\u001E`、列表元素分隔 `\u001F`）。
- **会话可重建**：进程重启后按同一组参数**重新抽题** —— `pickQuestions` 的顺序模式走
  `ORDER BY rowid`、随机/错题优先走 qid 哈希，**都不依赖时间**，所以重抽即同一批题、同一顺序。
  重建期间显示「正在恢复练习…」，**不能**像以前那样弹回首页（那正是"进度丢失"的表现）。
- ⚠️ **乱序种子必须沿用原值**（`practiceSeed`）：否则重建后选项会重新洗一遍，
  用户会看到自己选中的项"跳"到别的字母上。
- 限时考试的 `startedAt` 也沿用原值：倒计时按真实墙钟走，进程被杀期间的时间本来就该算进去。

## AI 答疑

- **只支持 DeepSeek 一家**（2026-10 用户要求「只支持 deepseek，档案删除」）：端点固定
  `https://api.deepseek.com`，模型 `deepseek-flash`（默认）/ `deepseek-v4-pro`。
  `AiEndpoint` 因此只剩一份常量；`chatUrl()` **不补 `/v1`**（DeepSeek 不需要，补了反而 404）。
  旧名 `deepseek-chat` / `deepseek-reasoner` / `deepseek-v4-flash` 对应模型**已下线**，
  `AiConfigRules.normalizeModel` 在**读取时**把它们改写为默认模型，但**清单外的名字原样保留**
  —— 否则用户用「获取可用模型」选中的新模型下次进页面会被静默改回去。
- **没有档案、没有主备降级**（`AiProfile` / `AiFailover` 已删除）：只有 DeepSeek 一家时，
  "换一条档案重试"没有意义。401/403 这类 Key 问题**不原地重试**（重试 100 次还是同一个结果）。
  中间版本遗留的 `profiles` JSON 由 `AiConfigRules.migrateProfiles` 迁移，**只迁含
  `api.deepseek.com` 的档案**（把 OpenAI 的 Key 迁来指向 DeepSeek 只会得到语义混乱的 401）。
- **刻意不启用** `thinking: {"type":"enabled"}` 与 `reasoning_effort`：会显著拉长首字延迟并多烧
  token，而本模块的用法是「针对一道选择题问一句」，收益不成正比。
- **流式输出**：SSE 逐字上屏，思考态（`reasoning_content`）弱化色小字单列。
  **已吐出过内容就不再重试** —— 否则用户会看到两段半截回答拼在一起。
- **思考过程可折叠 + 用量显示**（v1.2.7 新增）：流式中默认展开、出正文后自动收起，
  也可手动展开/收起；折叠条显示「思考过程（N 字）」。`AiUiState.Done` 带 `reasoning` ——
  此前 `readStream` / `readWholeBody` 把 `ChatReply.reasoning` 丢了，流式一结束思考过程就
  **永久消失**。用量显示「本轮 + 本题累计」（`AiUsage.Zero` / `cumulative()`，累计值由
  `PracticeScreen` 持有、换题归零）；两者相同时不重复渲染第二行。
- Key 存 `AiConfigStore`：AndroidKeyStore + AES/GCM，密文落 SharedPreferences。
  落盘键名沿用最早的单配置版（`api_key` / `model` / `timeout_seconds` / `retries`）⇒
  **老用户升级无需迁移**。独立于 `CredentialStore`（那是教务账号密码专用，键名 `pw_` 前缀、
  绑 accountKey，混用会让两套生命周期纠缠）。解密失败**不抛**、只清空 Key 而保留模型
  （换机 / 密钥库重置时不该顺手删掉用户配置）。
- 题目**只在你点「问 AI」时**才发出去；Key 绝不打日志（连片段都不打）。
- 回答用项目现成的 `MarkdownText` 渲染（内部判到 Markdown 语法才切富文本）。

## 文件

| 路径 | 作用 |
| --- | --- |
| `review/ReviewQuestion.kt` | 题目模型 + JSON 解析 + 校验 |
| `review/ReviewDatabase.kt` | SQLite 表与全部查询（含 `WrongBookEntry` / `listWrongBook`） |
| `review/ReviewSql.kt` | SQL 片段与建表语句 |
| `review/PracticeSession.kt` | 练习会话纯逻辑（出题/作答/判分/进度） |
| `review/PracticeLaunch.kt` | 开练参数（题库/章节/模式/筛选/时长）具名数据类 |
| `review/ReviewScheduling.kt` | 间隔复习：掌握等级、间隔天数、到期判据 |
| `review/WrongStatus.kt` | 错题三态 + 筛选项 + 排序口径 |
| `review/WrongBookLogic.kt` | 错题本的筛选 / 排序 / 汇总 / 到期文案（纯函数） |
| `review/SlashPolicy.kt` | 斩题判据（斩 ≠ 已掌握） |
| `review/ExamTimer.kt` | 限时考试：倒计时、提醒阈值、**时长档位**（`ExamDurationPresetsMinutes` 10–120 分钟 / `CustomDurationMinutes` 5–300 步进 5 / `durationLabel`；**刻意没有「不限时」档** —— 不限时的考试就是顺序练习） |
| `review/PracticeMode.kt` | 界面上的「练习模式」四档（`PracticeModeOption` + `needsDuration` + `hint()`）与 `resolvePracticeParams` 映射（纯函数） |
| `review/BankManageLogic.kt` | 题库管理纯函数（名校验 / 合并 / 题号重分配） |
| `review/BankDetailLogic.kt` | 章节多选（`Set<String>`）与题数汇总（纯函数） |
| `review/ChapterOrder.kt` | 章节名自然序排序（中文数字解析 + 排序键） |
| `review/AnswerSheet.kt` | 答题卡四态与汇总（纯函数） |
| `review/PracticeSummary.kt` | 提交后的总结页口径：正确率分母（已判分）/ 用时 / 标题三态 / 鼓励语分档（纯函数） |
| `review/OptionShuffle.kt` | 选项乱序：显示序 ↔ 数据序映射（纯函数） |
| `review/ReviewPrefs.kt` | 复习模块本地偏好（选项乱序开关，默认关；演示题库播种标记 `demo_bank_seeded`） |
| `review/BundledBanks.kt` | 内置题库规格：`SPECS`（4 份正式库，**只作为仓库可选项**，不再自动全量导入）+ `DEMO_SPEC`（演示题库，**不进仓库**、只播种一次）+ `ensureDemoBank` |
| `review/AiEndpoint.kt` | DeepSeek 端点 / 模型常量（纯常量，无逻辑） |
| `review/AiConfigStore.kt` | 单配置 + 网络参数的加密存储（含 `AiConfigRules`） |
| `review/AiChatClient.kt` | DeepSeek 客户端（SSE 流式 / 重试 / 用量 / 模型列表） |
| `review/ui/ReviewHomeScreen.kt` | 题库练习首页（单段「我的题库」+ 排序 + 「题库仓库」入口 + AI 设置入口） |
| `review/ui/ReviewMotion.kt` | 卡片入场动效的进度源（复用全项目 `ModuleMotion`） |
| `review/ui/BankDetailScreen.kt` | 题库详情页（章节多选折叠 → 模式滚轮 + 考试时长 → 开始练习 → 错题本 / 收藏） |
| `review/ui/BankFavoriteScreen.kt` | 收藏独立页面（汇总 / 开练 / 只读列表） |
| `review/ui/BankWrongBookScreen.kt` | 错题本独立页面（汇总 / 筛选 / 排序 / 列表） |
| `review/ui/BankLibraryScreen.kt` | **题库仓库页**（取出 / 导入 / 创建 / 合并 / 逐题编辑 / 改名 / 删除；原 `BankManageScreen` 已并入本页并删除） |
| `review/ui/BankQuestionEditScreen.kt` | 逐题编辑与批量删除 |
| `review/ui/PracticeScreen.kt` | 逐题练习（唯一承担滚动的一层）+ 答题卡 + **总结页**（`SummaryPane`） |
| `review/ui/QuestionCard.kt` | 题目卡片（**纯展示，内部不可再套滚动容器**） |
| `review/ui/ReviewButtons.kt` | 复习模块专用按钮（`ConfirmButton` / `SecondaryAction`） |
| `review/ui/AiExplainPanel.kt` | AI 讲解面板（流式 / 追问） |
| `review/ui/AiSettingsScreen.kt` | AI 设置（仅 DeepSeek）+ 题库格式说明 |
| `ReviewActivity.kt` | 宿主与页面流转 |

## 宿主与提示（v1.2.7 修复）

🔴 **复习模块此前没有 overlay 宿主**：`GlassToaster.show()` 只是往一个全局状态写值，
真正渲染它的是 `GlassToastHost`，而全项目只有 `MainActivity` 挂了宿主 —— 于是复习模块里
**23 处提示全部静默失效**（限时考试的「时间到，已自动交卷」「还剩 5 分钟」、题库的
「已保存」「已删除」「导入失败：…」全都在内，且不报任何错）。

`ReviewActivity` 根层现在挂了 `GlassWindowHost` + `GlassToastHost`。挂 `GlassWindowHost`
而不是只加一个 `Box`：`GlassPageScaffold` 见到已有 `DialogHost` 就复用，整个复习模块因此
共用同一套弹窗 / 玻璃折射 / overlay 栈。「AI 正在生成」的全局胶囊也挂在这一层。

**新页面 / 新 Activity 若要用 `GlassToaster`，先确认自己这一层有没有 `GlassToastHost`。**

## 整页弹层必须自己铺不透明底（v1.2.7 修复）

🔴 用户报「**打开答题卡后背景是题目的页面，导致看不清**」。根因在 `GlassWindowHost`：
它在 `LocalGlassWindowOwner.current === LocalView.current` 时**提前 return**
（它认为"这个窗口已经有底图了"），而 `GlassSubpage` 的 `DialogPresentation.Page`
弹层正跑在**同一个窗口**里 —— 于是弹层底下根本没有壁纸，只剩 `DialogHost` 那层
`Color.Black.copy(alpha = 0.22f)` 的遮罩，**上一页的内容整片透出来**（答题卡格子里
的数字和题干文字糊在一起）。

修法：`DialogHost` 对 `Page` 形态补画一层 `drawWallpaperPattern`（纯 Canvas 绘制、
不依赖 backdrop，所以在不支持折射的设备上同样有效）。**刻意不挂 `layerBackdrop`** ——
那份 layer 的宿主是窗口根部的 Canvas，同一个 backdrop 挂两处会互相覆盖。

这条修复同时惠及课表设置 / 课程编辑 / 桌面组件看板等其余 `GlassSubpage` 使用者。

## 滚动约束（踩过两次，务必遵守）

页面里**同一轴向上只能有一层滚动容器**。`Modifier.verticalScroll` 对应的 `ScrollNode` 会给子节点
传 `maxHeight = Constraints.Infinity`，子节点若自身也是 `verticalScroll` / `LazyColumn`，
`checkScrollableContainerConstraints` 直接抛 `IllegalArgumentException` → 测量阶段崩进程
（表现是"点了就退回课表页"，因为进程重启后落回 `MainActivity`）。

正确做法：外层负责滚动，内层用 `weight(1f)` 吃掉剩余高度；或者反过来让内层用
`LazyColumn` 而外层不滚。**不要**在 `QuestionCard` 这类"会被塞进滚动页"的组件里自带滚动。

## 隐私

- 题干内容、答案、API Key **一律不进日志**（只记 id 与条数）。
- 不做任何统计上报。
- AI 请求只有用户主动触发才发出，文案里已写明。

# 课程提醒设置（2026-09-26）

本次更新的第 2 项：**优化课表提醒**。参考了用户给的参考截图与开源参考项目
[`ShiGuangSchedule/shiguangschedule`](https://github.com/ShiGuangSchedule/shiguangschedule)（时光课表）。

> ⚠️ **来源说明**：参考截图在本会话里始终没能读到（不在磁盘上、也不在上下文里），
> 因此这一页是**按参考项目的源码**重建的 ——
> `shared/src/{commonMain,androidMain}/.../ui/settings/notification/`
> 下的 `NotificationSettingsScreen.kt` / `GeneralSettingsCard.kt` /
> `AdvancedSettingsCard.kt` / `NotificationSettingUtils.kt`，以及
> `androidApp/.../service/DndSchedulerWorker.kt`。文案也从它的
> `composeResources/values-zh-rCN/strings.xml` 里取过一遍。
> 如果截图里还有别的项，需要用户再确认一次。

## 做出来的东西

新增一页「课程提醒设置」（`ui/screen/ReminderSettingsScreen.kt`），
入口在**课表设置 → 课程提醒**，与课表设置共用同一个子页窗口，返回箭头回到课表设置。

| 项 | 类型 | 说明 |
| --- | --- | --- |
| 课程提醒 | 总开关 | 关闭 → 不再排任何提醒（`reconcile` 传空列表，把已排的全撤掉） |
| 提前提醒时间 | 滚轮 | 上课时 / 5 / 10 / 15 / 20 / 30 / 45 / 60 分钟，默认 15 |
| 上课自动模式 | 滚轮 | 关闭 / 勿扰 / 仅闹钟 / 仅优先，上课时自动切系统勿扰档位，下课还原 |
| 通知权限 | 自检 + 跳转 | 缺了运行时权限就地申请（API 33+），否则跳应用通知设置 |
| 精确闹钟权限 | 自检 + 跳转 | API 31+ 才出现 |
| 勿扰模式权限 | 自检 + 跳转 | `ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS` |
| 后台运行和自启 | 跳转 | 应用详情页 |
| 忽略电池优化 | 自检 + 跳转 | 读 `PowerManager.isIgnoringBatteryOptimizations` |

## 与参考项目有意不同的三处

1. **没有「兼容穿戴设备同步通知」开关。**
   参考项目加它是因为 **Android 16 的实时通知（live update）属性与部分手表同步软件冲突**。
   本应用的提醒本来就是一条普通文本通知（标题 + 正文 + 图标），没有实时更新，
   这个开关在这里会是**一个拨了没有任何效果的开关**。宁可不做，也不做假开关。

2. **没有「跳过日期 / 更新节假日信息」。** 它需要一个第三方节假日数据源，
   本应用目前没有任何节假日数据；参考项目自己也把这一块标注为
   「实验性的功能，不保证一定有用」。截图里也没有这一块。要做需要先定数据源。

3. **自动模式只走 `setInterruptionFilter` 一条路，不做「改响铃模式」。**
   参考项目里「静音」是用 `AudioManager.setRingerMode(SILENT)` 实现的。
   但从 Android N 起，**改响铃模式本身就等价于开勿扰，并且同样要求勿扰访问权限**
   （AOSP 在 `setRingerMode` 里检查调用方是不是 notification policy access holder）。
   也就是说它不多出一个能力，却多一条「想静音、结果把勿扰打开了」的路径。
   所以档位直接映射系统的四档之一，界面文案与实际效果严格一致：

   | 档位 | 系统值 | 实际效果 |
   | --- | --- | --- |
   | 关闭 | — | 不接管 |
   | 勿扰 | `INTERRUPTION_FILTER_NONE` | 完全静默 |
   | 仅闹钟 | `INTERRUPTION_FILTER_ALARMS` | 只放行闹钟 |
   | 仅优先 | `INTERRUPTION_FILTER_PRIORITY` | 只放行优先打扰 |

   四个常量与三个方法都是 **API 23**（查 `api-versions.xml` 确认），
   `minSdk 24`，所以不需要版本判断。清单里**不需要** `MODIFY_AUDIO_SETTINGS`。

## 上课自动模式的安全设计

这是本次唯一可能造成实际困扰的功能：切了勿扰却没还原，手机会一直安静下去。
因此：

- **没权限就绝不施加。** `hasDndAccess()` 为假时既不施加、也不排自动模式闹钟。
  施加了还原不了，比不施加糟得多。
- **还原与施加是两个独立判断**（`decideAutoMode` 的第一个分支）：
  档位改成「关闭」、总开关关掉、或不在课时内，只要运行态说我们改过，就必须还原。
- **运行态落盘**（`auto.applied-kind` / `auto.prev-filter`）：进程被杀、重启后仍知道
  「系统现在这个样子是我改的、改之前是哪一档」。
- **中途换档不覆盖还原目标。** 上课上到一半把「勿扰」改成「仅闹钟」时
  `capturePrevious = false` —— 否则还原目标变成我们自己刚设的档位，手机再也回不到原样。
- **还原失败不清运行态。** 权限被撤销时 `setInterruptionFilter` 抛 SecurityException，
  这时如果照样清空运行态，还原目标就永久丢了。留着它，下一次对账再试。
- **每次 `reconcile()` 都重新对齐**，而 `reconcile()` 挂在：
  每个 Activity 的 `onResume`、账号会话变化、开机 / 换时区 / 改时间 /
  精确闹钟权限变化（这几个 action 都挂在 `CourseReminderReceiver` 上）、以及上/下课闹钟本身。
  所以「没还原」最多持续到下一次进 App 或开机。
- **下课闹钟用 `setExactAndAllowWhileIdle`**，Doze 里也能唤醒去还原。
- **自动模式挂在总开关下**：总开关关掉 = 应用不做任何自动行为。
  否则「关掉课程提醒」之后手机还会在课上自己切勿扰，那很意外。

### 课表快照为什么要单独存一份

`records()` 只装用户点过提醒开关的那几门课，而自动模式要的是**全部课程**。
所以 `updateSnapshot()` 额外把课表快照写进 `auto-courses:$account`（带 term）。
三个调用点（缓存命中 / 网络加载 / 旧接口回退）传进来的都是**当前学期**，
课表本来就只展示本学期，所以不需要调用方多传一个「是不是当前学期」的标记。
`clearAccount()` 会删掉它 —— 否则换账号后自动模式还会按上一个账号的课表切勿扰；
设备级偏好（总开关 / 提前量 / 自动模式开关）不删，它们管的是系统权限，与账号无关。

## 提前量从「写死 15」变成「可设置」

原来 `CourseReminder.leadMinutes` 是**逐条课程**存的，但界面上没有任何地方能改它，
`CourseDetailContent` 里甚至硬编码了「上课前 15 分钟」。现在：

- 提前量是**设备级**设置（`settings.lead`），改动会**把所有已存在的记录一起改过去**
  （`setLeadMinutes` + `reconcile` 里的 `alignLeadMinutes` 自愈归一）。
- 课程详情页显示真实值（`CourseDetailUiState.reminderLeadText`）。
- 0 分钟的文案是「上课时 / 上课时提醒」，不是「提前 0 分钟」。
- 取值收敛到 `LEAD_MINUTE_OPTIONS`（`sanitizeLeadMinutes`）：它直接参与
  `startsAt - lead * 60_000`，放进 -1 会立刻误响、放进 100000 会永远不响。

## 共享时间算术

`CourseReminderPlanner.plan` 里原本内联着「解析第一周周一 / 某周某天某时刻」的算术，
自动模式要用同一套判断，于是抽到 `schedule/ScheduleOccurrences.kt`
（`weekOneMonday` / `parseClock` / `occurrenceAt` / `courseWindows`），两边共用一份实现 ——
否则迟早出现「提醒响了但勿扰没切」。这次重构是**行为保持**的：
原有的 `CourseReminderPlannerTest` 5 项全部照旧通过。

`courseWindows` 在下列情况**跳过该课程**而不是猜一个时间：
周次解析不出来、起始节开始时间缺失、结束节结束时间缺失、星期几越界、节次区间反了、
结束时刻不晚于开始时刻。宁可不切勿扰，也不要按一个编出来的下课时间恢复手机。

## 验证情况

| 项 | 结果 |
| --- | --- |
| `compileReleaseKotlin` | 通过 |
| `testReleaseUnitTest` | **803 tests / 0 failed / 2 skipped**（此前 783） |
| 新增单测 | `ReminderSettingsTest` 9 项 + `AutoModePlanTest` 11 项 |
| 变异验证 A | 去掉「该还原」分支 + `capturePrevious` 恒 true + 去掉周一校验 → 5 项 FAILED，签名与预期完全一致；还原后逐字节相同 |
| 变异验证 B | `sanitizeLeadMinutes` 变恒等 + 下课时间错用起始节 → 7 项 FAILED；还原后逐字节相同 |
| `assembleRelease` | BUILD SUCCESSFUL |
| dex 字符串 | 19 项全中（`course-auto-mode`、`COURSE_AUTO_MODE`、7 个偏好键、`auto-courses:`、9 条界面文案）；`silent` 档位残留 0 |
| 二进制清单 | `com.hnnujw.course.schedule.AutoModeReceiver` 已注册 `exported=false`；`ACCESS_NOTIFICATION_POLICY` 在；`MODIFY_AUDIO_SETTINGS` 不在 |

### ⚠️ 未验证的部分（照实说）

- **没有真机验证**（用户明确要求不测真机）。因此以下几件事只做到了「逻辑正确 + 可编译 +
  产物里有」，没有在设备上跑过：
  - 勿扰档位切换与还原的真实行为（尤其各 ROM 对 `setInterruptionFilter` 的裁剪）；
  - 上下课闹钟在 Doze 下的唤醒；
  - 权限自检里四个系统设置页的跳转在各 ROM 上是否都可达
    （代码里已经对 `ActivityNotFoundException` 退到应用详情页，但没实测）。
- **参考截图没读到**，页面结构来自参考项目源码（见文首说明）。

## 改动文件

**新增**
- `app/src/main/java/com/hnnujw/course/schedule/ScheduleOccurrences.kt`
- `app/src/main/java/com/hnnujw/course/schedule/AutoModePlan.kt`
- `app/src/main/java/com/hnnujw/course/schedule/ReminderSettings.kt`
- `app/src/main/java/com/hnnujw/course/schedule/AutoModeReceiver.kt`
- `app/src/main/java/com/hnnujw/course/ui/screen/ReminderSettingsScreen.kt`
- `app/src/test/java/com/hnnujw/course/schedule/ReminderSettingsTest.kt`
- `app/src/test/java/com/hnnujw/course/schedule/AutoModePlanTest.kt`

**修改**
- `schedule/CourseReminder.kt` — `plan()` 改用共享助手（行为保持）
- `schedule/ScheduleReminderScheduler.kt` — 总开关 / 提前量 / 自动模式与它的闹钟、运行态
- `ui/screen/ScheduleSettingsScreen.kt` — 新增「提醒」分区与入口行
- `ui/route/ScheduleRoute.kt` — 子页内两层切换 + 状态文案
- `ui/screen/CourseDetailContent.kt` / `ui/screen/ScheduleCourseSheet.kt` — 真实提前量、总开关提示
- `AndroidManifest.xml` — `ACCESS_NOTIFICATION_POLICY` + `AutoModeReceiver`
- `用户手册.md` / `app/src/main/assets/users_manual.json` — 第 11 章重写 + 4.3/4.8 + Q27–Q29

# 公告按版本下发（versionCode 锁定）、随包内置与已读记录

日期：2026-09-20
涉及文件：`app/src/main/assets/announcement.json`、`app/src/main/java/com/hnnujw/course/announcement/*`、
`scripts/verify_delivery.py`、`scripts/release.sh`、`.github/workflows/release.yml`

> 本文记录同日先后落地的两次改动：**① 公告按版本下发**（解决"老公告骚扰新版本用户"），
> **② 公告改为随包内置**（解决"公告一条都看不到"）。第 ② 次推翻了第 ① 次里的
> 线上拉取通道（`publish_announcement.py` / Gitee Raw / 缓存节流），
> 但**版本范围协议与已读记录完全保留**，见下。

## 一、为什么要做

### ① 老公告骚扰新版本用户

线上那条 `migration-package-2026-09`「重要：应用将更换包名，需手动下载新版」没有版本字段。
客户端此前只按 `showOnce` + 已读 id 过滤，于是**所有版本**的用户都会收到它 —— 包括已经装好
新包名、根本不需要"手动下载新版"的用户。用户的原话是"都发送第一个公告了"。

公告当时是"改仓库即生效"的通道（不跟版本走），这本身是优点；但正因为不跟版本走，
它必须自己声明"我写给哪个版本看"，否则老公告会一直挂在新版本用户的未读里。

### ② 公告一条都看不到

v1.2.2 装上去之后，启动没有更新公告弹窗、红点不亮、未读列表空。两个原因叠加：

1. **线上那份 `announcement.json` 其实从没推上去过**（仓库根的 `announcement.json`
   还躺在 `.gitignore` 里，连跟踪都没有），线上仍是只有迁移公告的旧版；
2. 更致命的是**未读判定写反了**：`AnnouncementCenter.isRead(id)` 实现成
   `id !in readIds`，与调用方的 `isUnread = ... || !isRead(id)` 组合后语义反转 ——
   `showOnce = true`（默认，v1.2.2 那条就是）的公告**永远被判成"已读"**，
   于是弹窗、红点、未读列表三者同时为空。当时这条链路**零测试覆盖**，
   269 个用例全绿也照样漏。

结论：公告必须**跟代码同一次构建、同一个版本号**，不能靠一条独立的线上通道；
未读判定必须抽成可测的纯函数。于是公告文件搬进 `assets/`，`isUnread` 移到
`Announcement.isUnreadIn(readIds, versionCode)`。

## 二、协议

`assets/announcement.json` 里每条公告三个字段：

| 字段 | 含义 |
| --- | --- |
| `created_at` | 发布日期 `YYYY-MM-DD`。列表排序与详情展示用。应用侧兼容 `createdAt` 写法。 |
| `minVersionCode` | 适用版本下限（含）。`0` 或省略 = 不限。 |
| `maxVersionCode` | 适用版本上限（含）。`0` 或省略 = 不限。 |

判定规则（`Announcement.appliesTo`）：

- **两个字段都没写过** → 视为历史归档，`appliesTo` 恒为 `false`。
  这一条让任何漏写版本范围的公告**默认闭嘴**，而不是默认广播。
- 写过任一个 → 按区间判定；`0` 表示该端不限。

`versionCode` 的公式是 `major*10000 + minor*100 + patch`（1.2.2 → 10202）。
旧包名的 1.2.1 是 91，所以"写给旧包名用户"的公告写 `maxVersionCode: 91`
（例如 `migration-package-2026-09`）。

### 写公告的默认姿势：每个版本一条，范围恰好本版

在自己版本对应的公告里**显式写死两端**：

```
minVersionCode: 10202, maxVersionCode: 10202   // v1.2.2
```

"只发给正好装了这个版本的人"，效果：

- 装了这一版的人 → 首次启动看到它（弹窗 + 未读红点），读完入「已读」；
- 装了下一版的人 → 它在「已读 → 历史」里，不再打扰；
- 跳版本升级的人（1.2.1 → 1.2.3）→ 只看到 1.2.3 那条，1.2.2 的在历史里回看。

要写给更宽的范围就显式声明：

| 场景 | 写法 |
| --- | --- |
| 发版公告（默认，面向本版用户） | `minVersionCode: <本版>, maxVersionCode: <本版>` |
| 面向旧包名 / 更早版本的用户 | `minVersionCode: 0, maxVersionCode: 91` |
| 面向所有人（含将来版本） | 两端都写 `0` —— 慎用，会一直打扰后续版本 |

> **不变量由测试守住**：`AnnouncementLogicTest` 会读 `app/version.properties` 与
> `assets/announcement.json`，断言"必须存在一条覆盖当前 versionCode 的公告"。
> 忘了给新版写公告，发版前的单测就会红。

## 三、客户端行为

| 位置 | 口径 |
| --- | --- |
| 启动弹窗（`AnnouncementDialog`） | `firstUnread` = **未读 且 适用于当前版本** 的第一条 |
| 「我的」公告红点 | `unreadCount` = 未读 且 适用于当前版本的条数 |
| 公告中心「未读」分段 | 未读 且 适用于当前版本 |
| 公告中心「已读」分段 | 已读的 **∪** 不适用于当前版本的历史公告（行上打「历史」标） |

历史公告不是"已读"，但它同属"不需要你处理"的一类，和已读放一起才不会既占着未读又无处可查。

**没有「刷新」这个动作**：公告随包内置，新公告只能随新版下发。公告中心顶栏因此没有刷新按钮，
空状态也只有「暂无公告」一种（不再有"拉取不到、点击重试"）。

## 四、未读判定

抽成纯函数，两处口径共用一个实现：

```kotlin
// Announcement.kt
fun isUnreadIn(readIds: Set<String>, versionCode: Int): Boolean =
    appliesTo(versionCode) && (!showOnce || id !in readIds)
```

- `showOnce = true`（默认）：读过就不算未读；
- `showOnce = false`：字面意思是"每次都要显示"，这类公告**永远算未读**，不看已读记录。

`AnnouncementCenter.isUnread(...)` 只做委托，不再自己拼条件。

## 五、已读记录

旧实现：`announcement_prefs` 里存一个无序 `StringSet`，超过 100 条时
`readIds.toList().takeLast(100)`。无序集合上的 `takeLast` 等于**随机丢 id** ——
被丢掉的公告下次拉到就重新变成未读，用户看到的是"看过的公告又冒出来了"。

新实现：`read_announcement_records`，一行一条 `id<TAB>时间戳`。

- 裁剪按时间戳排序后丢最旧的（上限 200 条），丢的永远是最早读的那些；
- 重复标记会刷新时间戳；
- 启动时把旧的 `read_announcement_ids` 集合迁移进来（时间戳补 0，天然排在最旧的一端），
  随后删除旧 key。

## 六、退场的线上通道

第 ② 次改动把这套东西整体删掉，因为它们的存在本身就是上一个 bug 的温床：

| 退场项 | 说明 |
| --- | --- |
| `publish_announcement.py` | 原来负责把本地公告推上 Gitee Raw，以及按 tag 自动填版本范围；公告内置后没有可推的东西，整条链路退休（连同它的 20 例测试） |
| `scripts/verify_delivery.py` | 保留但**改写**为校验应用真正读的那个接口（Gitee `releases/latest` 的 tag 与 `.apk` 资产），不再看仓库根的 `version.json` —— 那个文件从没提交过、也没有任何一版应用读过它 |
| `release-notes/vX.Y.Z-announcement.json` | 发布期公告归档。现在公告只有 `assets/announcement.json` 一份，随包走 |
| release.yml 的 "Validate Release Announcement" 步骤 | 校验归档公告的 CI 闸门，随归档一起删 |
| `AnnouncementManager` 里的 OkHttp / `ANNOUNCEMENT_URL` / 10 分钟缓存节流 | 网络通道与它的缓存语义（`null` = 没拉到、空列表 = 真的没有）全部不再需要 |
| `fetchAllAnnouncements` 的弱网语义 | 内置文件读不到只可能是包坏了，`loadAnnouncements` 直接返回 `null` 并记日志 |

`versionCode` 方案：见 CHANGELOG 1.2.2 的「变更」，三处脚本同步为按位公式。

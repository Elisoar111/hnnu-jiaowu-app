# 更新包完整性校验（第 16 轮：借鉴参考项目的强制更新）

> 面向：想知道「强制更新为什么还要多算一次 SHA-256」「Release 正文里那行 `sha256:` 是谁写的」的人。

## 1. 先说结论：本项目早就有强制更新

参考项目 `D:\AAAAAAAAAA\参考项目\BiliPai`（com.android.purebilibili）其实**没有**强制版本闸门
（`mandatory` / `forceUpdate` / `minSupported` 全库无命中），它的更新是「GitHub Release 检查 + 可关掉的弹窗 + 后台下载 + 安装」。

本项目反而更完整：

| 能力 | 位置 |
| --- | --- |
| `[force-update]` 独立成行标记 → 弹窗不可关闭 | `network/AppUpdateChecker.kt`、`network/UpdateCenter.kt` |
| 半天节流；**强制更新不写**「跳过此版本」 | `network/UpdateCenter.kt` |
| 应用内下载（不把用户丢给浏览器把 APK 存成 zip） | `network/AppDownloader.kt` |
| 装不上时兜底放行（没有直链 / 下载失败 / 没有可用安装器） | `ui/update/AppUpdateDialog.kt` 的 `stuck` |

所以这一轮**不重写**任何一条，只补参考项目有、这边没有的一件事：**装之前先证明这个包是我发布的那个包**。

## 2. 为什么值得做

强制更新的意思是：用户**没有"不装"这个选项**。
那么"装上去的东西确实是发布方构建的"就必须能被证明，而不能只靠"下载链接是 HTTPS、服务端没被动过"这种信任。
参考项目用的是 GitHub CI 产出的 `build-metadata.json` / `checksums.txt` + immutable release + provenance，
Gitee 的 Release 拿不到这些 —— 所以只借它的**思路**，落成一个不依赖服务端格式的版本。

## 3. 做了什么

### 3.1 声明式校验和

Release 正文里可以写一行：

```
sha256: 3f2c…（64 位十六进制）
```

- 解析：`network/AppUpdateIntegrity.kt` 的 `parseDeclaredSha256()`。
  允许 `SHA-256` / `sha256`、半角 `:` 与全角 `：`、行首有列表符 `-`、加粗 `**`、行内代码 `` ` ``；
  值必须是**完整 64 位**，正文里碰巧出现的短哈希不会被误当校验和。
- 展示：`AppUpdateChecker.notesForDisplay()` 会把这行摘掉再给弹窗 —— 那是给机器看的，
  露出一串十六进制只会把用户想看的更新说明挤走几行。
- 自动写入：`scripts/publish.py` 的 `with_checksum_line()` 在发布时**用发布出来的那个 APK 现算**
  SHA-256 并追加到正文末尾（`release-notes/*.md` 里已经手写了就尊重手写值，不覆盖）。
  让人手抄 64 位十六进制，抄错的概率远高于写对的概率，而抄错的后果是**所有人都装不上**。

### 3.2 装之前的闸门

`ui/update/AppUpdateDialog.kt` 的 `launchInstaller()`：

1. 主按钮先变成「校验安装包…」并禁用（避免并发拉起两次安装）；
2. 在 `Dispatchers.IO` 上流式计算文件 SHA-256（64KB 缓冲，**不整读**，二三十兆的包整读会在低端机上 OOM）；
3. 三种结果：
   - **一致** → 正常安装，正文里多一行小字「安装包校验通过（SHA-256 与发布说明一致）」；
   - **不一致** → **拒绝安装**，把缓存包删掉、`readyApk` 清空，按钮回到「重新下载」，
     红字提示"请点「重新下载」重试"。删包是必须的：留着它，用户下次点「安装更新」只会重复失败；
   - **发布方没写校验和** → 不拦（否则大多数发布都装不上），如实提示"已改用安装包签名校验"，
     交给 `AppDownloader.isTrustedUpdate()` 原有的签名比对。

### 3.3 不制造新的死锁

强制更新的弹窗本来是锁死的。校验失败如果也锁死，用户就一条路都没有了。所以：

- 第一次失败：只提示「重新下载」（更可能是传输截断）；
- 同一个版本**连着两次**对不上：`stuck` 成立，弹窗允许关闭（`integrityFailures >= 2`）。
  这时基本可以判定是发布说明写错了或包被替换 —— 与其把人关在一个点不动的框里，不如放他出去。

## 4. 怎么验证

```bash
./gradlew :app:assembleDebug
python scripts/publish.py --dry-run     # 看发布正文里会不会追加 sha256 行
```

手工验证校验和这条链（不用真发布）：

1. 本机算一下 APK：`sha256sum app/build/outputs/apk/release/app-release.apk`；
2. 在 `release-notes/<tag>.md` 的 `## notes` 里加一行 `sha256: <上面那个值>`；
3. 正常装旧版 → 检查更新 → 下载 → 应当看到"校验通过"并顺利进安装器；
4. 把那个值故意改一位 → 重复第 3 步 → 应当看到红字"安装包校验失败"、按钮变回「重新下载」，
   并且 `Android/data/<包名>/files/updates/` 下的包被删掉；
5. 强制更新场景下重复第 4 步两次 → 弹窗应当变成可关闭（不然就是死锁）。

### 4.1 弹窗样式（同一轮的第二件事）

用户要求"把强制更新的样式做成和参考项目一样的"，所以 `AppUpdateDialog` 的排版按参考项目的
`feature/settings/update/Material3AppUpdateDialog.kt` 重排了（**只重排观感，不改上面任何一条行为**）：

| 区域 | 现在长什么样 |
| --- | --- |
| 标题 | 左对齐「发现新版本」+ 小字「版本 vX.Y.Z」，右侧一个关闭 `X`（仅 `dismissible` 时出现） |
| 版本卡 | `surfaceContainerHigh` 卡片，并排「当前版本 / 更新至」，下面一行 `安装包 xxx.apk` |
| 状态卡 | `primaryContainer` 卡片：状态文字 + 大号百分比 + 进度条 + 已下载/总量·速度·剩余 + 校验和一行 + 红字错误 |
| 说明 | 「本次更新」标题 + `MarkdownText`（能吃 `**加粗**`、`- 列表`、`## 小标题`） |
| 渠道 | 「其他下载渠道」+ 两个 `OutlinedButton`：浏览器打开直链 / 前往发布页 |
| 按钮 | 主按钮按状态变化：下载更新 → 下载中 x% → 安装更新 / 重新下载；次按钮「稍后」或「取消下载」 |

对外壳**没有**照搬：弹窗本体仍是本项目的液态玻璃 `SystemDialog`（参考项目是 Material3 `AlertDialog`），
卡片用实色（`surfaceContainerHigh` / `primaryContainer`）而不是玻璃，符合本项目「玻璃弹窗内的控件一律实色，
避免玻璃叠玻璃发糊」的既有约定。

三处刻意没抄：

- 不移植它的 `parseUpdateReleaseNotes()` 解析器 —— 本项目已有 `MarkdownText` 直接吃 Markdown；
- 不换 `LinearProgressIndicator` —— 本项目有带玻璃质感的 `GlassProgressBar`；
- 不硬凑「正式版/测试版」两个渠道 —— Gitee 只有一个发布页，凑出来是点了没反应的按钮，
  改成「浏览器打开 / 前往发布页」两个真能点通的出路。

## 5. 已知取舍

- **没有做后台下载/通知**：参考项目有 WorkManager，本项目下载协程挂在 `rememberCoroutineScope()` 上，
  弹窗一关就真的断了（所以按钮写的是「取消下载」而不是「后台下载」—— 写后者是骗人）。
  真要做得把任务挪到进程级作用域并在完成时发通知，属于另一轮的事。
- **没有引入 immutable release / provenance**：Gitee 没有对应机制，硬造一套只会得到"永远未验证"的灰标。
- **签名校验是兜底而不是主判据**：`AppDownloader.isTrustedUpdate()` 在取不到当前包签名时会放行（`?: return true`），
  这是历史行为，本轮没动它 —— 校验和是更强的判据，签名只是退路。
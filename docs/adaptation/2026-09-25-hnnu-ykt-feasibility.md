# 淮南师范学院一卡通（yktapp.hnnu.edu.cn）实现方案

> 评估日期：2026-09-25
> 方法：**仅分析公开可达的静态前端资源**（HTML/JS），与浏览器访问完全等价。
> 未登录、未提交任何凭证、未调用任何业务接口、未做扫描/爆破/压力测试。
>
> **本次范围（用户确认）**：① 校园卡余额查看；② 电费余额查看。
> **附加问题**：交电费能否实现。

---

> ## ⚠️ 后续修正（2026-09-25，晚于本文主体；以代码为准）
>
> 本文 §3.2 / §「真根因」把电费口径定为 `GET /charge/feeitem/getThirdDataByFeeItemId`，
> 并据"`/charge/sceneroom/combox*` 不存在"得出"只带 `feeitemid` 查一段文本"的结论。
> **这两点都不对**，原因是**搜错了前端家族**：只检索了 `/plat/` SPA 的 chunk。
>
> 真实口径在官方 `/charge-pc/`（Vue SPA「缴费.新中新」），已用真机令牌逐级实测走通，
> 现行实现见 `ykt/YktClient.kt` 的类注释（`feeItemDetail` + `thirdData`）：
>
> - `GET /charge/feeitem/singleFeeitem?feeitemid=` → `view`（`"choose"`=必须先选房间）、
>   `interfacechoice`（级定义，如 `校区_campus,楼栋_building,楼层_floor,房间_room`）。
> - `POST /charge/feeitem/getThirdData`（form-urlencoded）→ **既取级联也取读数**，靠 `type` 区分：
>   `select` 给下一级候选，`IEC` 时 `map.showData.信息` 是读数文本。
> - 各级提交值固定 `"<id>&<name>"`（`1&本校区`），**必须原样回传**；级数由服务端决定，UI 不写死。
> - ⚠️ **HTTP 200 + 正文 `null` 是"空数据"，不是"错误"**。
>
> 保留此段是为了记住教训：**"名字听起来像"不等于存在，但"搜不到"也不等于不存在 —— 要先确认搜对了 app 家族。**

## 一、平台识别

| 项 | 值 |
|---|---|
| 站点 | `https://yktapp.hnnu.edu.cn/plat/` |
| 后端前缀 | `/berserker-app`、`/berserker-base`、`/berserker-auth`、`/berserker-search`、`/charge` |
| 厂商 | 新中新（Synjones / ZTrust），前端工程名 `mobile-service-app-new` |
| 形态 | Vue 2 SPA（`window.webpackJsonp`），95 个异步 chunk，主包 285 KB |
| 认证 | `synjones-auth` token，**与正方 Cookie / 二课 Bearer 完全独立** |

## 二、结论

| 需求 | 可行性 | 关键接口 |
|---|---|---|
| ① **校园卡余额查看** | ✅ **可实现** | `GET /berserker-app/ykt/tsm/queryCard` |
| ② **电费余额查看** | ✅ **可实现**（返回为非结构化文本） | `GET /charge/feeitem/getThirdDataByFeeItemId?feeitemid=` |
| ③ **交电费（充值）** | ❌ **不可自实现**（充值/支付全在远端 H5 子应用） | 无，需跳转 `appId` 重定向 |

## 三、接口详情（可直接照此实现）

### 3.1 校园卡余额 —— `GET /berserker-app/ykt/tsm/queryCard`

无入参，返回 `data.card[0]`。前端消费的字段：

| 字段 | 含义 | 单位 |
|---|---|---|
| `account` | 卡账号 | — |
| `cardNo` | 卡号 | — |
| `db_balance` | **卡内余额（已入账）** | 分 |
| `unsettle_amount` | **未结算金额** | 分 |
| `elec_accamt` | **电费子账户余额** | 分 |
| `status` / `expireDate` | 卡状态 / 有效期 | — |

前端展示逻辑（`chunk-493ca944.js`、`chunk-395c2b3f.js` 逐字一致）：

```js
const t = e.data.card[0] || {};
let n = 0;
"1" !== $ecardConfig.type && (n += (t.db_balance + t.unsettle_amount) / 100); // 普通余额
"2" !== $ecardConfig.type && (n += t.elec_accamt / 100);                      // 电费账户
```

**要点**：金额单位统一为**分**，需 `/100`。`$ecardConfig.type` 为 `"1"` 时不计普通余额、为 `"2"` 时不计电费账户——**该配置由服务端下发，不可硬编码**，实现时须读配置决定展示口径，否则会与官方页面金额对不上。

### 3.2 电费查询 —— `GET /charge/feeitem/getThirdDataByFeeItemId`

```js
async getBalance(e) {
  const t = { feeitemid: e };   // 入参仅费项 ID
  return this.$api.get("/charge/feeitem/getThirdDataByFeeItemId", { params: t });
}
// 返回 map.showData["信息"] 是一段带中文标签的文本，用正则抠数字：
getBalanceFromString(e) {
  const n = ["剩余电量", "剩余金额", "剩余水费"];
  const a = new RegExp(`(${n.join("|")})\\s*(?::)?\\s*([-+]?\\d*\\.?\\d+)`);
  const s = e.match(a);
  return s && parseFloat(s[2]);
}
```

返回结构是 `{ map: { showData: { "信息": "剩余电量：56.78" } } }`。

**要点（三条硬约束）**：
1. **必须解析文本**，无结构化字段；需处理解析失败（前端回退 `"0.00"`）。
2. **`feeitemid` 不由用户选择**，取自应用清单 `getAllApps` 返回项里的 `website` 地址 query 参数 `showBal`：
   ```js
   const n = getRequest(app.website)?.showBal;   // 即 feeitemid
   ```
   → 实现时必须先调 `GET /berserker-app/app/getAllApps?platType=&userType=user`，
   在返回的应用列表里找到带 `showBal` 配置的项，取其值作为 `feeitemid`。
3. **接口无宿舍/房间维度入参**。能否查到"宿舍电费"取决于学校把该费项配置成了什么计量口径。

### 3.3 附录：余额变动汇总（可选）

`GET /berserker-search/statistics/turnover/count?timeFrom=&timeTo=`
返回 `{ expenses, income }`（单位分）。前端用于首页"昨日消费/上月消费"卡片。
注意：**消费明细列表不在本包内**，点击后跳转远端 `${campusCard}?name=chartList`。

## 四、为什么"交电费"不能自实现

对全部 95 个 chunk 做写操作接口盘点，**全站仅 8 个写接口**：

```
POST /berserker-app/cardConflict/saveHintConfig    # 用卡冲突提示设置
POST /berserker-app/vouchers/updateVoucherStatus   # 券状态
POST /berserker-app/ykt/tsm/barcodeDel             # 删除付款码
POST /berserker-app/ykt/tsm/modifyAcc              # 自助转账标识/额度（圈存开关）
POST /berserker-app/ykt/tsm/payLimiteModify        # 消费限额
POST /berserker-base/login/*                       # 登录/改密/验证码
```

**没有任何** `recharge` / `topup` / `order` / `pay` / `deposit` 的**下单或支付接口**。

充值入口的真实机制（`chunk-493ca944.js`）：

```js
// 1) 拉应用清单，挑出充值子应用
l.get("/berserker-app/app/getAllApps", { params: { platType, userType: "user" } })
 .then(e => e.data.find(x => "card-recharge" === x.appCode));
// 2) 点击后整页跳转远端 H5（接口/签名/请求体全在另一台服务器）
window.location.href = `${baseURL}/berserker-base/redirect?appId=${e}&type=app&...`;
```

同类远端子应用：`card-recharge`(充值)、`bindCampusCard`(绑卡)、`cardOperation`(卡操作/解挂)、`cardDetails`(卡详情)、`cardcode`(付款码)、`chartList`(消费明细)、`payResult`(支付结果)。

**补充澄清**：卡片详情页里名为 `set-electric` 的组件**不是缴费**，它是"自助转账标识 / 自动转账额度 / 消费限额"设置，仅调用 `modifyAcc` 与 `payLimiteModify` 两个写接口（`chunk-6634992d.js`）。唯一带"转账"语义的 `modifyAcc` 是**银行圈存自动转账开关 + 阈值**，不是发起一笔支付。

## 五、实施建议

### 建议实施
1. **卡余额**：`queryCard` → 按 `$ecardConfig.type` 决定是否计入 `db_balance`/`unsettle_amount`/`elec_accamt`，前端 `/100` 展示。
2. **电费余额**：先 `getAllApps` 取 `showBal` 作为 `feeitemid` → 调 `getThirdDataByFeeItemId` → 正则解析 `剩余电量|剩余金额|剩余水费`。**必须防御解析失败**。
3. **交电费**：`WebView` 打开校方官方充值页（`card-recharge` 的 `redirect?appId=` URL），而非自建支付。

### 不建议
4. **逆向远端子应用自建支付**：涉及真实资金链路，签名与订单流程逆向风险高、合规性存疑。

### 前置依赖（必须先解决）
- **独立认证**：需新增 `synjones-auth` token 的获取与存储，与现有正方/二课凭据体系隔离。登录入口为统一身份认证跳转，实际可用性与账号体系需在用户本人触发下实测。
- **配置依赖**：`feeitemid`、`$ecardConfig.type` 均由服务端按校下发，不可硬编码。

## 六、实施状态（2026-09-25 已落地）

用户确认**电费按宿舍计量**，范围收敛为「余额查看 + 电费查看」，已完成实现：

| 文件 | 作用 |
|---|---|
| `ykt/YktModels.kt` | 数据模型（`YktCard` / `YktEcardConfig` / `ElectricityBalance` / `YktOverview`）与 `YktException` |
| `ykt/YktBalance.kt` | **纯函数**：电费文本解析、总额口径、feeitemid 提取、分转元/格式化 |
| `ykt/YktClient.kt` | 只读客户端（`queryCard` / `electricityBalance` / `feeItemIdFor` / `ecardConfig` / `overview`） |
| `ykt/YktStore.kt` | 令牌存储（账号键归一化）。**不存任何密码**（见 §8.3） |
| `ui/screen/YktScreen.kt` | 页面（总额卡 / 卡片明细 / 电费卡 / 能力边界说明） |
| `YktActivity.kt` | 独立全屏宿主页（Manifest 已注册） |
| 入口 | 「我的」→「校园服务」→「一卡通」（`SettingsScreen` + `SettingsRoute`） |
| 删号清理 | 已接入 `SettingsRoute.deleteAccountEntirely`（`YktStore.clearAccount`） |
| 单测 | `YktBalanceTest`（26 例，含负向验证） |

**实现中修正的两处官方缺陷**：
1. 官方电费解析抽不到数字时回退 `0.00` 并直接显示，用户无法区分"真没电"与"解析失败"。
   本实现返回 `null`，界面显示"—"并标注「未能识别余额数值」。
2. 官方未开通电费时也显示 0；本实现区分「学校未开通」与「接口通了但解析失败」两种情形。

### 6.1 电费低余额提醒（用户追加需求，默认 20 元）

| 文件 | 作用 |
|---|---|
| `ykt/YktAlertPolicy.kt` | **纯函数**决策：是否提醒、去重、回弹重置 |
| `ykt/YktAlertSettings.kt` | 开关 + 阈值（默认 20，范围 1~500），**全局不按账号** |
| `ykt/YktAlertStateStore.kt` | 去重状态持久化（**按账号**，账号键归一化） |
| `ykt/YktAlertNotifier.kt` | 系统通知（渠道 `ykt_balance_alert`，IMPORTANCE_HIGH） |
| `ykt/YktAlertScheduler.kt` | AlarmManager 每日一次自我续期 |
| `ykt/YktAlertReceiver.kt` | 广播入口（含开机/更新重排） |
| 单测 | `YktAlertPolicyTest`（19 例） |

**决策规则**（每条都有单测）：
- 余额 `null`（解析失败）→ **不提醒**，且**保留**原状态（清空会导致下轮重复提醒）
- 余额 ≥ 阈值 → 不提醒，并**重置**状态（这是"充值回升后能再次提醒"的关键）
- 余额 < 阈值且此前未提醒 → 提醒一次
- 余额 < 阈值但已提醒 → 不重复（**同一欠费周期只响一次**）
- 负余额（已透支）同样提醒——比低余额更紧急

**能力边界（重要）**：后台巡检需要本地已有 `synjones-auth` 令牌。
一卡通认证与教务独立，未在 App 内登录过一卡通时**无法**静默建会话——
那需要用户的一卡通密码，静默登录新系统属于越界，**本实现不做**。
因此提醒在用户通过 WebView 登录一次后生效；令牌过期后需重新登录
（详见 §8.5 的提醒与登录关系）。

## 七、待确认

1. **电费口径**：已确认**按宿舍计量**。
2. 是否接受"跳转官方充值页"方案（本次未实现充值入口）。

## 八、登录入口落地（2026-09-25 追加）

此前一卡通页面在未登录时只显示「尚未登录一卡通，请先在设置中登录」，
但**设置里并没有这个入口** —— 提示指向了不存在的地方，用户无路可走
（`YktStore.saveToken` 全库零调用点，令牌永远拿不到）。

### 8.0 正确的登录入口（用户提供 URL 后修正，**此前搞错过**）

**曾用错入口**：最初拿首页地址 `/plat/shouyeUser?appId=1` 当登录入口，
假设"未登录会自动跳 CAS"。**curl 实测证伪**：

| 地址 | 实测结果 |
|---|---|
| `/plat/shouyeUser?appId=1` | **HTTP 200** + SPA 空壳，**不触发认证** → WebView 里只有白屏，永远登不上 |
| `/berserker-auth/cas/login/wisedu?targetUrl=…` | **HTTP 302 → `https://xxmh.hnnu.edu.cn/cas/?service=…`** ✅ |
| `/plat?name=loginTransit` | 301 → `/plat/?name=loginTransit`（尾斜杠归一，无害） |

**正确的三层参数链**（逐层解码，勿简化）：

```
CAS 页  : https://xxmh.hnnu.edu.cn/cas/login?service=<网关>
网关    : https://yktapp.hnnu.edu.cn/berserker-auth/cas/login/wisedu?targetUrl=<中转页>
中转页  : https://yktapp.hnnu.edu.cn/plat?name=loginTransit
```

三个易错点：

1. **认证由服务端网关发起，不是首页地址。** 首页恒 200，把 CAS 层交给
   `berserker-auth` 网关的 302 自动补上即可 —— 客户端只需给出「网关 + targetUrl」。
2. **`targetUrl` 必须是 `/plat?name=loginTransit`（登录中转页）**，
   不能换成首页：换票据、把 `access_token` 写入 `sessionStorage` 的动作在它那里完成
   （**不是写 Cookie**，见 8.2），换成首页会让票据无处兑换 → 登录后仍拿不到令牌。
3. **CAS 在另一个域**：CAS 是 `xxmh.hnnu.edu.cn`，业务是 `yktapp.hnnu.edu.cn`，
   登录全程**必然跨域**。因此令牌抽取必须过域名白名单（见 8.2），
   CAS 域下的同名 Cookie 语义完全不同，绝不能当令牌落盘。

实现：`YktClient.loginEntryUrl()`（构建网关地址 + URL 编码的 targetUrl），
`YktStore.loginUrl()` 直接转发它。

### 8.1 为什么只能走 WebView

实测 `frontInfo.loginType` **只有 `sso`**（wisedu CAS），站点不暴露账密登录接口。
因此不能复刻二课那种「账号密码 → `POST /token` 换 Bearer」的对话框模式，
唯一正确做法是 WebView 走完 CAS，再从**页面内**接管 `synjones-auth`。

### 8.2 令牌在哪：**`sessionStorage`，不是 Cookie**（本轮最重要的更正）

> ⚠️ **早期判断（"从 Cookie 里接管 `synjones-auth`"）是错的，已实测推翻。**
> 真机现象：业务首页**已登录**（截图可见扫一扫/付款/认证码），
> 但 `CookieManager.getCookie()` 在 `berserker-auth` 网关地址与 `/charge`
> 两个候选上都**读不到** `synjones-auth`，只有 CAS 的 `SESSION` / `TGC`。
> 表现为点「我已登录完成」毫无反应（只弹"还没检测到登录状态"）。

反混淆 `login.70990e50.js` + `ykt_app.js`（webpack bundle）得到 SPA 的真实逻辑：

```js
// ① 拿到 oauth/token 的响应后（CAS 分支 this.getToken(ticket, targetUrl)）
getFlagAfter(t){ this.userToken={token_type:t.token_type||"bearer", token:t.access_token};
                 this.token=t.access_token; this.$store.dispatch("LoginAction", this.userToken); … }

// ② store 的 Login mutation —— 令牌落点在这里
Login(e,t){ t.noCatch || (sessionStorage.setItem("access_token", t.token),
                          sessionStorage.setItem("token_type", t.token_type));
            e.token=t.token; e.token_type=t.token_type }
```

⇒ **令牌落在 `sessionStorage` 的 `access_token` / `token_type` 两个键**，
既不是 Cookie，也不是 `localStorage`。**按 Cookie 读必然读空**。

补充证据（同一 bundle 内的一致性）：
- 全部业务组件通过 `$store.state.token` 取值
  （`computed:{ token:e=>e.token }`），从无 `document.cookie` 读取；
- `loginTransit` 组件在 `loginTransit?ticket=…` 形态下走
  `this.getToken(ticket, targetUrl)`：`POST /berserker-auth/oauth/token`，
  body 里 `logintype:"sso"` 且 **`username = password = ticket`**，
  `Authorization: Basic bW9iaWxlX3NlcnZpY2VfcGxhdGZvcm06…`；
- 另一条链（App/小程序）靠 **URL 查询参数** `?synjones-auth=<token>` 传递，
  App 入口 `created()` 里 `getRequest()` 取出后再 dispatch `LoginAction`。

**令牌形态**（业务请求头 `synjones-auth: <值>`）：

```
synjones-auth = token_type + " " + access_token     # 例：bearer eyJhbGci…
```

`token_type` 缺失时按小写 `bearer` 兜底；若 `access_token` 已是完整形态
（以 `bearer `/`Basic ` 开头）则**原样返回**，避免拼成 `bearer bearer xxx`。

抽取逻辑提为纯函数：
- 主路径 `YktClient.extractAuthFromStorageJson(storageJson)` —— 解析
  `sessionStorage` 键值 JSON，取 `access_token` + `token_type` 并拼接；
  键名同时接受 snake_case 与 camelCase（`accessToken`）且大小写不敏感，
  抽不到 `access_token` 返回**空串**（绝不返回半截值）。
- 兜底 `YktClient.extractAuthFromCookie(cookie)` —— 保留用于"个别版本同时写
  Cookie"的情况，要点：只能按**第一个** `=` 切分（base64 padding 自带 `=`，
  用 `split("=")[1]` 会被截断）；不模糊匹配 Cookie 名；抽不到返回空串。

采集由 `YktLoginActivity` 注入的 `CAPTURE_TOKEN_JS` 完成：优先
`sessionStorage`，再兜底 `localStorage` 与 URL 的 `synjones-auth` 查询参数，
返回 JSON 交给 Kotlin 侧解析（`evaluateJavascript` 是**异步回调**，
所以 `sniffToken(onDone)` 带回调、点按钮时不能同步判断）。

**采集前必须先过域名白名单**（`YktLoginActivity.isAllowedHost` → 纯函数
`WebLoginNavigation.hostMatches`）：登录全程跨域（业务域 ↔ CAS 域），
只在 `yktapp.hnnu.edu.cn`（含子域）上取令牌。判定用「整串相等 or `.` + 后缀」，
**不能用裸 `endsWith`** —— 否则 `evilyktapp.hnnu.edu.cn` 会被误判为子域。

### 8.3 为什么不做静默登录（安全边界，勿改）

`synjones-auth` 里含 `access_token`，理论上可拿用户保存的密码
去 `berserker-auth/oauth/token` 静默续期。**本实现刻意不做**：

1. 那需要用户的一卡通密码（教务密码 ≠ 一卡通密码，不能想当然复用）；
2. 会在用户不知情下访问另一个业务系统，属于越界；
3. 收益极小 —— 令牌有效期足够长，用户偶尔打开一次即自动续上。

**用户明确要求"注意安全不要保存密码"。** 因此：
- `YktStore` 中历史上的 `hasPassword` / `savePassword` / `loadPassword`
  三个方法（**调用点始终为零**）已**删除** —— 它们是"待被顺手用起来"的钩子，
  留着等于邀请后人存用户口令。删除理由写在 `clearAccount` 上方的注释里。
- 新增护栏单测 `YktNoPasswordGuardTest`（3 例），把边界变成可执行断言：
  一卡通包内不得出现密码存取 API / 密码字段 / `CredentialStore.save`；
  登录页不得出现密码输入控件。
  **已做变异验证**：把 `savePassword` 加回 `YktStore` → 2/3 用例 FAILED，
  证明护栏真的生效（不是空跑）。

### 8.4 本轮新增/改动文件

| 文件 | 变更 |
|---|---|
| `YktLoginActivity.kt` | **新增**：WebView 登录页（独立 `WebView.setDataDirectorySuffix("ykt")`，与教务 Cookie 隔离），每页 `onPageFinished` 主动采集令牌（CAS 成功是服务端 302，客户端收不到地址变化回调），点按钮时再采一次兜底；**采集走 `CAPTURE_TOKEN_JS` 读 `sessionStorage`**（不是 Cookie，见 8.2），异步回调式；**采集前过域名白名单**（`isAllowedHost`，委托纯函数）；附白屏修复（像素高度补丁，见 8.6） |
| `YktClient.kt` | 新增纯函数 `extractAuthFromStorageJson`（**主路径**）/ `composeAuthFromStorageValues` / `extractAuthFromCookie`（兜底）/ `authCookieUrls` + `readAuthCookie`；新增 `CAS_HOST`、`STORAGE_ACCESS_KEY`/`STORAGE_TYPE_KEY` 与 `loginEntryUrl()`（正确网关入口，见 8.0） |
| `YktStore.kt` | 新增 `loginUrl()`（转发 `YktClient.loginEntryUrl()`）；**删除**三个密码 API；`clearAccount` 保留清理旧 `ykt::` 残留键 |
| `WebLoginNavigation.kt` | 新增纯函数 `hostMatches(url, allowed)` —— 域名白名单判定，修掉「`endsWith` 会把 `evilyktapp.hnnu.edu.cn` 当子域」的漏洞 |
| `YktScreen.kt` | 未登录改为**可操作**的 `LoginRequiredCard`（含登录按钮 + 登录后能做什么），ActivityResult 返回后自动重载；`needLogin` 与普通加载失败分离 |
| `AndroidManifest.xml` | 注册 `.YktLoginActivity` |
| 单测 | `YktAuthCookieTest`（27 例，含 Web Storage 主路径 9 例）、`YktNoPasswordGuardTest`（3 例）、`WebLoginNavigationTest` 追加 `hostMatches` 5 例 |

### 8.5 阈值提醒与登录的关系（回答"webview 登录能否实现阈值通知"）

**能，且比密码方案更干净。** 提醒链路与登录方式解耦：
后台巡检只依赖"本地有一个可用令牌"（`YktAlertReceiver`），
每次巡检**现场重新查服务端**，因此只要令牌有效，提醒就一直有效。

| 场景 | 提醒是否生效 |
|---|---|
| 从未登录 | ❌ 无令牌，静默跳过 |
| WebView 登录过、令牌有效 | ✅ 每日巡检正常 |
| 令牌过期 | ❌ 巡检报 `sessionExpired` 清令牌 → 界面转"需要登录"；**需用户再登一次** |

**诚实的局限**：不做静默续期 ⇒ 令牌过期后提醒会停，直到用户再次打开
一卡通页面。这是安全换来的代价，且界面会明确提示需要重新登录，
不会让用户以为"提醒还开着"。

## 8.6 电费看不到 / 没有楼栋楼层房号选择（2026-09-25 追加，真机实证）

用户反馈：「没有办法查看电费，没有选择那栋那层那间」。

### 根因：费项列表取错了组件（不是"学校没开通"）

`appScheme/info` 的首页 `combinedComponentList` 实测有 **6 个组件**，
`combinedAppList` 分别为 `2 / 3 / 9 / 0 / 0 / 0` 项：

| 组件 | 应用 |
|---|---|
| #1 | 天气、搜索 |
| #2 | 扫一扫、付款、认证码 |
| **#3** | 账单、卡片充值、修改查询密码、挂失·解挂、校园助手、**`elcpay`(181)**、**`elec`(201)**、银行卡、校园卡绑定 |
| #4–#6 | 空 |

电费两个入口在 **第 3 组**。但旧 `collectAppViewItems`：

1. 先按 `componentKey`/`code` 是否含 `appView` 匹配 —— 首页组件的
   `componentKey`/`code` **全是 `null`**（`type` 恒为 `user`），**永不命中**；
2. 于是退到"第一个带 `combinedAppList` 的组件" —— 取到的是 **#1（天气/搜索）**；
3 ⇒ `feeItems` 判空 ⇒ 界面显示「学校未开通电费查询…」并**提前 return，
   把校区/楼栋/楼层/房间四行一起藏掉** —— 与用户描述完全吻合。

**修法**：`collectAppViewItems` 改为**遍历首页（或全部菜单）的所有组件**，
收集每组 `combinedAppList`；去重与费项判定交给 `collectFeeItems`（按 `feeitemid`）。
不写死索引、不依赖 `componentKey`。

### 连带 bug：`url` 字段是**内嵌 JSON 串**，不是干净 URL

同一应用项里：

```
website : /charge/feeitem/toAppitem?feeitemid=181                       ← 干净 URL
url     : {"name":"App","code":"app",…,"url":"/charge/feeitem/toAppitem?feeitemid=181"}
```

旧 `extractFeeItemId` 直接对 `url` 做 `substringAfter('?')` → 切出
`feeitemid=181"}`，value 带上尾部的 `"}`，查询必然失败。
**修法**：先剥引号/花括号，并识别内嵌 JSON 时解出内层 `url` 再取 query
（新纯函数 `embeddedUrlValue`）；`collectFeeItems` 也改为 `website` 优先、
`url` 兜底，名称取 `appName` 优先、`name` 兜底。

> 这两处都有单测钉死（`YktBalanceTest`：内嵌 JSON 取 id / website 优先 / appName 优先等）。

## 8.7 消费记录（2026-09-25 追加）

用户反馈：「没有校园卡的消费记录」。**结论：SPA 内只有"区间汇总"，
没有逐笔明细；明细交给官方页面。**

### 全站核对结果

| 能力 | 端点 | 说明 |
|---|---|---|
| **区间汇总**（唯一可直接实现的） | `GET /berserker-search/statistics/turnover/count?timeFrom=&timeTo=` | 返回 `{"data":{"income":0.0,"expenses":2324.0}}`，**分**。真机（账号 19704，2026-09）实测 `expenses=2324` → ¥23.24 |
| 逐笔明细 | `/campus-card/?name=billList`、`/merchant/?name=billList1`、`/charge/…` | 在**另一台服务器的 jQuery + EasyUI 子应用**，不带令牌直接访问 **HTTP 401**，渲染逻辑不在 SPA 的 95 个 chunk 内，**无法在客户端复刻** |
| 应用清单 | `GET /berserker-app/app/getAllApps?platType=h5&userType=user&websiteRequired=true` | 31 项，含 `elcpay`/`elec`（费项 URL）、`bill`/`billMercacc`（账单）——诊断用，非必需 |

### 落地

- `YktClient.turnoverSummary(token, from, to)` + `YktTurnover` 模型（分转元；
  字段名是**复数** `expenses`，勿写单数）。
- 新增纯函数 `YktBalance.monthRange(offset, today)` / `monthLabel`（用
  `java.util.Calendar` 处理跨年与闰月，单测覆盖 9 月/8 月/跨年/闰年 2 月）。
- `YktScreen` 新增 `ConsumptionCard`：月份左右切换（本月/上月/更早），
  展示支出与收入两行，并给「查看逐笔明细（官方页面）」入口。
- 新增 `YktWebActivity`（**只读**官方页面查看器）打开 `billUrl`；
  与登录页共用 `ykt` 存储目录以复用登录态，**不导出任何凭据**；
  Manifest 已注册。

> **诚实的边界**：本客户端只呈现汇总数字，逐笔明细一律走官方页面。
> 不把"汇总"伪装成"账单明细"，避免用户误以为记录缺失。

## 8.8 电费「校区/楼栋/房间」级联是**虚构的**（2026-09-25 真机联调推翻）

用户反馈：「**校区列表加载失败，是否没有位置权限**」。
两件事被同时证实：**与位置权限无关**，而**级联本身建立在虚构端点上**。

### 先排除的（用户的推断方向）

| 检查 | 结果 |
|---|---|
| 是否缺定位权限 | `AndroidManifest.xml` **无任何** `ACCESS_FINE/COARSE_LOCATION`；真机 `dumpsys package` 的 requested permissions 里也没有 —— 权限列表只有 `INTERNET`/`NETWORK_STATE`/通知/相机/媒体等 |
| 是否网络问题 | 同一次会话里 `queryCard`、`ecardConfig`、`turnoverSummary` **全部成功**，卡余额 ¥4.54 正常显示 |
| 是否登录失效 | 不是：失效会得到 `HTTP 401` + `{"code":401,"message":"缺失令牌,鉴权失败"}`，而这里是 **HTTP 200** |
| 校区是否本来就该有数据 | 官方页面同样没有"选校区"这一步（见下） |

### 真根因：`/charge/sceneroom/combox*` **不存在**

真机抓包（临时诊断，已删除）：

```
GET /charge/sceneroom/comboxCampus               → 200, application/json, body=null
GET /charge/sceneroom/comboxCampus?feeitemid=181 → 200, application/json, body=null
GET /charge/sceneroom/comboxCampus?feeitemid=201 → 200, application/json, body=null
GET /charge/feeitem/comboxCampus?feeitemid=181   → 200, text/html, body=<!DOCTYPE html>  （路径不存在，SPA 兜底）
```

全量 SPA 反混淆核对（98 个 chunk）：

```
grep -oh "combox[A-Za-z]*"  *.js   → 零命中
grep -oh "sceneroom[a-zA-Z]*" *.js → 零命中
grep -oh "/charge/[a-zA-Z/]*" *.js → 只有 /charge/feeitem/getThirdDataByFeeItemId
```

**即：这四个端点名是"看起来合理"而被造出来的，从未在站点里出现过。**
（本文档此前也没有记录过它们 —— 它们是实现阶段的臆测，没有走文档验收。）

### 站点真实口径：**电费查询没有任何房间选择**

`chunk-3d2fd493` 里官方的取数逻辑（权威依据）：

```js
async getBalance(e){ const t={feeitemid:e};
  return this.$api.get("/charge/feeitem/getThirdDataByFeeItemId",{params:t}) }
getBalanceFromString(e){ /* 正则 (剩余电量|剩余金额|剩余水费)\s*:?\s*([\d.]+) */ }
async appName(e){ /* 取到数值后内联成应用显示名："<span…>xx.xx 元</span>" */ }
```

**只带 `feeitemid`**，返回一段文本，前端正则抽数字后把余额**当作应用名字**显示。
没有校区、没有楼栋、没有楼层、没有房间。

### 两个连带结论

1. **`HTTP 200 + 正文 null` 是"空数据"不是"错误"**。旧 `parse()` 对 `null`
   走 `JSONObject("null")` → 抛「返回了无法识别的数据」→ 用户看到"系统坏了"。
   现抽出判据 `YktClient.isNullBody()`（`internal` + 单测），`sceneBox` 据此返回空列表。
   **这条也解释了用户为何会往"权限"上想**：文案在给错误的行动建议。
2. **应用跳转有唯一口径**（SPA 多处 `openNewPage`）：
   ```
   {base}/berserker-base/redirect?appId=<应用项 bh>&type=app&synjones-auth=<token>
   ```
   参数名是 `appId`、值取 `bh`；不是 `feeitemid`、不是 `appCode`。

### 修法

- `YktClient.sceneCascadeSupported()`：**淮师 = false**，把"端点可能不存在"显式化；
  级联方法保留为"若某校真提供则可用"的扩展点。
- `YktScreen`：`showCascade=false` 时不渲染四级选择器（不再把用户卡在永远为空的选区），
  改走 `electricityBalance(token, feeItemId)` 直查；并给「在官方页面查看/缴纳电费」入口。
- 费项自动选中扩展到"直查路径 + 多个费项"也默认选第一个 —— 否则用户进页面只看到
  "请先选择电费项目"却不知该点哪，与之前"看不到电费"是同一类死界面。
- 失败文案保留真实原因（见 8.9）。

> **方法教训（最重要的一条）**：**没验证过的端点不能当作能力**。
> "名字听起来像"（`comboxCampus`）不等于存在；必须真机抓一次响应。
> 臆造端点 + 把 `null` 当错误 = 用户被卡在一个功能上，还收到了误导性的原因。

## 8.9 失败文案不得丢失原因、不得错误归因（2026-09-25）

同一条反馈暴露出第二类缺陷：**错误文案把多种失败压成一句，并给出错误的行动建议。**

| 位置 | 旧行为 | 现在 |
|---|---|---|
| `YktScreen.openPicker` | 四处写死「校区列表加载失败」等，**丢掉** `YktException.message` | `failureText(error, label)`：保留真实原因 + 出错层级，令牌失效/缺失时切回登录态 |
| `SceneWheelPickerDialog` | 写死「请检查网络后重试」 | 「可取消后重试；若提示登录失效，请重新登录一卡通」（不预设原因是网络） |
| 令牌缺失 | 不置 `needLogin`，界面停在"已登录"假象，只印"加载失败" | 明确「登录已失效，请先登录」并切登录态 |

失败至少有四种、处置完全不同：令牌失效（→重登）/ `IOException`（→换网络）/
`HTTP 5xx`（→只能等）/ 解析失败（→系统改版）。压成一句 = 用户只能猜，
且"检查网络"这个**错误建议**比不写更糟。

> 注：`YktClient` 里 `catch (IOException)` 抛的「无法连接一卡通系统，请检查网络或稍后重试」
> 是**网关层**文案，只在真的连不上时出现，与上面那条写死的 UI 文案不是一回事，保留。

护栏：`YktNoPasswordGuardTest.pickerFailureKeepsRealReasonAndNeverBlamesNetworkOrPermission`
（要求保留真实原因、令牌缺失须提示登录、**不得出现权限归因**、弹窗不得写死网络原因）。

## 九、二课「按学期」显示缺陷修复（2026-09-25 追加）

用户反馈两处，均已定位到**服务端字段语义误用**：

| 现象 | 根因 | 修法 |
|---|---|---|
| 学期标题只显示学年（`2025-2026`），同一学年两个学期标题一模一样，分不清 | 站点 `termName` **只给学年**，学期号在 `termNumber`（`"1"`/`"2"`）里 | 新增纯函数 `SecondClassPointAnalysis.termDisplayName` → `2025-2026 第一学期` |
| 每个数值后面多个 `3`（如 `25.8 3`） | 站点 `termHoursUnit` 下发的是**数字枚举码**（实测恒为 `"3"`），被直接当单位拼上 | 新增 `displayUnit` → 数字码丢弃，回退表头真实单位（学时） |
| 每条记录后面也有个 `3` | `record.sourceLabel` 把 `sourceType=3` 渲染成 `"来源 3"` —— 站点**无类型文案也无取值表** | 记录行**移除**该字段展示（`sourceLabel` 属性保留供诊断，KDoc 写明勿加回界面） |

要点（都有单测）：
- `termDisplayName` 对已带学期号的 `termName`（`2025-2026-1`）**原样返回**，
  不重复拼接；判据只看末段**一位数字** —— 放宽会把四位年份也当成学期号。
- `displayUnit` 只在站点给的是**中文单位**时才采信（权威值），否则回退。
- `sourceType` 无权威取值表 ⇒ 任何数字都不该展示给用户。

单测：`SecondClassPointAnalysisTest` 31 → 43 例。
**已做变异验证**：改 `displayUnit` 回退分支 → 3 例 FAILED，证明断言非空。

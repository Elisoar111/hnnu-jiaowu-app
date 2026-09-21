# 淮南师范学院「第二课堂」接入说明

> 目标站点：`https://ekta.hnnu.edu.cn/`
> API 根地址：`https://ekta.hnnu.edu.cn/api/app/client/v1/`
> 记录日期：2026-09-18

---

## 1. 这是什么系统

第二课堂（共青团"成绩单"系统）与教务系统**完全独立**：

| 维度 | 教务系统 | 第二课堂 |
| --- | --- | --- |
| 域名 | `jwgl.hnnu.edu.cn` | `ekta.hnnu.edu.cn` |
| 会话 | Cookie（JSESSIONID） | `Authorization: <access_token>` |
| 登录 | 直登表单 + RSA 加密 | `POST /token` 换 token |
| 账号 | 学号 | 学号（同一个） |
| 密码 | 教务密码 | **第二课堂自己的密码**（通常与教务不同） |

两套凭据必须分开存。App 里第二课堂的密码走 `CredentialStore`，键名加 `ekta::` 前缀，
与教务密码互不覆盖。

### 前端形态

SPA（Vue + webpack），`index.html` 只挂 `manifest/vendor/app` 三个 bundle，
页面组件全是懒加载 chunk。接口地址写在 `app.<hash>.js` 的 axios 实例里。

> **抓包提示**：chunk 的 URL 形如 `static/js/<id>.<hash>.<buildTimestamp>.js`，
> 中间那串 build 时间戳**必须带上**（从 `manifest.<hash>.js` 尾部的
> `b.p+"static/js/"+c+"."+{...}[c]+".1787823794093.js"` 读出来）。
> 少一段会拿到站点的"无法访问"错误页，看起来像是被 WAF 拦了，其实是路径不对。

---

## 2. 请求协议（关键）

### 2.1 统一信封

所有接口的响应都是：

```json
{ "code": 0, "data": {...}, "msg": "", "timestamp": 1789743417940, "token": "" }
```

`code == 0` 为成功。常见错误码：

| code | 含义 | 处理 |
| --- | --- | --- |
| `10001` | 登录失效 | 清本地 token，提示重新登录 |
| `20002` | 账号或密码不正确 | 提示核对密码 |
| `10007` | 学校未开通服务 | 该校不该出现此功能 |
| `10002` | 参数格式不正确，参数必须是 JSON 格式 | 编码少了一层（见下） |

### 2.2 参数是**双重 URL 编码**

这是本协议最容易踩的坑。站点前端：

```js
// app.<hash>.js 的请求拦截器
t.headers = { "Content-Type": "application/x-www-form-urlencoded" };
if (t.method === "post") {
  let n = encodeURI(Encrypt(JSON.stringify(t.data)));   // Encrypt 在该构建里是恒等函数
  n = n.replace("+", "%2B");
  t.data = qs.stringify({ params: n });                 // qs 再编一次
} else if (t.method === "get") {
  let o = encodeURI(Encrypt(JSON.stringify(t.params)));
  t.params = { params: o.replace("+", "%2B") };
}
```

即 `encodeURI` 之后又走了一次 `qs.stringify`，服务端相应解两次。

实测对照：

| 编码层数 | 结果 |
| --- | --- |
| 1 层（`encodeURI` 量级） | `10002 参数格式不正确，参数必须是JSON格式` |
| 2 层 | 进入业务校验（凭据正确时 `code=0`） |

**真正要保证的是"线上恰好一层"**。服务端解两层，而对一层输入来说第二次解码是空操作，
所以一层 / 两层都能过，**三层一定失败**（实测，`POST /token`）：

| 线上层数 | 结果 |
| --- | --- |
| 1 层 | `code=0` |
| 2 层 | `code=0` |
| **3 层** | **`code=10002 参数格式不正确，参数必须是JSON格式`** |

站点前端的净效果就是 1 层：`encodeURI(json)` 并不编码 `{}"` 这些字符，随后被
`qs.stringify` 一次编掉。

> **OkHttp 陷阱（本项目真踩过，导致第二课堂登录必失败）**
> `FormBody.Builder().add(name, value)` 会对 value **再编一层**，只有
> `addEncoded()` 是原样发送。所以 POST 必须写成
> `addEncoded("params", <编好一层的串>)`。若先用 `URLEncoder` 编两层再交给 `add()`，
> 线上就是三层 → 固定回 `10002`，用户看到的现象是"一输第二课堂密码就提示参数格式不正确"。
> GET 的参数是手工拼进 URL 的，OkHttp 不会重编，没有这个坑。
>
> 据此 `encodeParams` 只编一层：`URLEncoder.encode(json, "UTF-8").replace("+", "%20")`
> （`+`→`%20` 只为与 `encodeURIComponent` 逐字对齐；org.json 的 `toString()` 不含空格，实际等价）。

> **验证方法的教训**：拿"无参数的 GET"做编码对照是**无效**的——编码函数根本没被调用，
> 两边都会回 `code=0`，会得出"两种编码都行"的假结论。做这类对照必须带上参数，
> 且要分别覆盖 GET（手工拼 URL）与 POST（经过 `FormBody`）两条路径。

> `Encrypt`/`Decrypt` 在 `aNLv` 模块里是 `function(t){return t}`，**没有签名也没有加密**。
> 换学校/换版本时先确认这一点，否则会漏掉真正的签名步骤。

### 2.3 认证头

```
Authorization: <access_token>
```

没有 `Bearer` 前缀，也没有 Cookie 依赖。`token` 也出现在响应体里，但请求不需要它。

---

## 3. 登录

```
POST {base}/token
body: params=<双重编码的 {"schoolCode":"10381","code":"<学号>","password":"<密码>"}>
→ {"code":0,"data":{"access_token":"..."}}
```

- `schoolCode` 是**该平台内部的学校编号**，不是教务的学校代码。淮南师范学院两处恰好都是 `10381`，
  但换学校时不能想当然——拿一个不存在的编号会回 `10007 您的学校暂未开通服务`，
  可以据此区分"编号错"和"密码错"。
- 连续输错 5 次锁定 30 分钟：`密码输错5次，请30分钟后再尝试`。**调试时别反复试密码。**

> ⚠️ **令牌在 `data` 里，不在信封顶层。**
> 信封顶层**确实有一个 `token` 字段，但它是空串**（见 §2.1 的信封样例），很容易误读成"令牌就在顶层"。
> 照顶层去取 `access_token` 会在**登录已经成功**的情况下报"缺少 access_token"
> ——这正是本项目踩过的第二个登录 bug（第一个是 §2.2 的编码层数）。
> 取法：`data.access_token`，兜底 `data.token` / `data.accessToken`。
> 本文件其余接口也都从 `data` 取数，可互相印证。

### 3.1 其它登录通道（本 App 未使用）

站点还支持 `GET /user/hnnuSSOAuth` → `/third/token` 的教务 SSO 跳转（`schoolCode=10381`），
以及微信/易班等入口。App 只做密码直登，不碰这些通道。

---

## 4. 取数接口

| 用途 | 请求 | 关键字段 |
| --- | --- | --- |
| 身份 / 单位 | `GET /student/user/my-info` | `name` `code` `schoolName` `collegeName` `majorName` `grade` `hourUnit` `waysConvert` |
| 成绩单表头 | `GET /student/achievement/detail` | `user.name` `user.code` `user.collegeName` `user.majorName` `user.grade` `user.hours` `user.score` `user.gender` `user.className` `user.campusName` `user.origLogo` |
| 各模块积分 | `GET /student/achievement/detail-app` | `tags[].{name,userValue,minHours,showUserValue}` |
| 学分完成情况 | `GET /student/user/transcript?params=...` | `list[].{classifyName,name,type,rtHours,minHours,rtScore,minScore,isQualified}` `total` |
| 我的名次 | `GET /student/achievement/self/rank?level=<n>` | `name` `majorName` `score` `hours` `rownum` `className` `campusName` |
| 榜单 | `GET /student/achievement/rank?pageNum&pageSize&level` | `list[].{rownum,name,majorName,score,avatar,gender}` `total` `lastPage` |
| 换算方式 | `GET /ways-convert` | `waysConvert` |
| 明细·按分类 | `GET /student/achievement/by-classify-list` | `[].{classifyId,classifyName,classifyHours,minHours,hoursRecordList[].{name,hours,amount,time,sourceType,relationId}}` |
| 明细·按学期 | `GET /student/achievement/by-term-list` | `[].{termName,termNumber,termHours,termHoursUnit,hoursRecordList[].{name,hours,classifyName,time}}` |
| 分类字典 | `GET /qu-activity-classify/list-one` | `[].{id,name}`（7 个分类） |

### 4.0 `transcript` 的 `scoreStatus` 必填（易踩）

站点前端该字段初值是**数字 `0`**，不是空串。少传或传 `""` 一律回 `10002 参数错误`
——是**报错**，不是空数据，很容易被当成"这个学生没有学分记录"。

| `scoreStatus` | 实测结果 |
| --- | --- |
| 不传 / `""` | `code=10002`，`msg="参数错误"`，76B |
| `0`（数字）或 `"0"` | `code=0`，`total=7`，965B |
| `1` | `code=0`，`total=4`（把未达标的记录过滤掉了） |

App 侧已改为跟站点一致传 `0`（`SecondClassroomClient.credits()`）。

### 4.1 排行榜层级 `level`

站点前端写死的四个取值，不要自行推断：

| level | 含义 |
| --- | --- |
| `10` | 班级（默认） |
| `9` | 专业 |
| `8` | 院系 |
| `6` | 全校 |

### 4.2 单位

- `waysConvert == 3` 时学校按**分数**展示，其余按**学分**（站点 `scoreUnit` 的判定）。
  站点原文：`localStorage.setItem("scoreUnit", 3==e.data.waysConvert?"分数":"学分")`，
  `hourUnit` 则原样存成 `schoolHourunit`。App 的 `unitLabel` 与之逐字一致。
- `hourUnit` 是学时单位，`user.hours` / `tags[].minHours` / `rtHours` 都用它。
  **本校实测值是 `"积分"`**，不是"学时"；`waysConvert` 实测为 `1` → 分数单位显示"学分"。

### 4.3 字段实测差异（淮南师范学院）

| 预期 | 实测 | 影响 |
| --- | --- | --- |
| `tags[]` 有 `schoolValue` / `showSchoolValue` | **都没有**，只有 `{showUserValue,userValue,minHours,name}` | `SecondClassModule.average` 恒为 0；界面用 `average > 0.0` 守卫，"全校参考"整行不显示 |
| `tags[].userValue` 是数字 | 是**字符串** `"3.00"` | `JSONObject.optDouble` 能直接解析字符串，无需改动 |
| `detail.user.avatar` | 不存在，只有 `origLogo`（校徽 URL） | App 头像为空串，界面走占位图 |
| `self/rank` 的 `total` | 无 `total` 字段（只有 `rownum`） | App 用榜单接口的 `total`，不依赖它 |
| 榜单 `total` | 院系/全校层被截断为 `100` | 只是分页上限，不代表真实人数 |

---

## 5. App 内的落地位置

| 改动 | 文件 |
| --- | --- |
| 数据模型 | `secondclass/SecondClassroomModels.kt` |
| 只读客户端 | `secondclass/SecondClassroomClient.kt` |
| 拼装（分段容错） | `secondclass/SecondClassroomRepository.kt` |
| 本地状态（token/密码） | `secondclass/SecondClassroomStore.kt` |
| 页面 | `ui/screen/SecondClassroomScreen.kt` |
| 路由 | `ui/route/SecondClassroomRoute.kt` |
| 登录对话框（设置页与页面共用） | `ui/screen/SecondClassLoginDialog.kt` |

配套改动：

1. `model/SchoolConfig.java` — 新增 `secondClassroomBaseUrl` / `secondClassroomSchoolCode`，
   空串表示未接入（旧配置 `fromJson` 回退为空，功能自动隐藏）。
2. `manager/UserManager.java` — 内置学校 `hnnu` 填上 `https://ekta.hnnu.edu.cn/api/app/client/v1` 与 `10381`。
3. `manager/StartupPagePreferences.kt` + `MainActivity.kt` — 底栏新增「二课」Tab（第 4 项，设置之前）。
4. `ui/system/AppSymbol.kt` + `AnimatedIconSpec.kt` + `PhosphorNavigationIcon.kt` — 新增奖章图标。
5. `ui/screen/SettingsScreen.kt` + `ui/route/SettingsRoute.kt` — 账号分组里加「第二课堂」登录入口；
   按需求移除「检查更新」入口（启动时的自动检查保留）。

### 5.1 容错策略

只有「成绩单表头」是必需的，其余段落各自失败就各自留空：

- `tags` / `transcript` 在不少学校是按学院配置的，学生没进任何分类时会回空数组而不是报错，
  这不该让整页变成错误页；
- 榜单按层级分别请求，切换层级时 `LaunchedEffect` 取消上一个请求，不会串数据。

### 5.2 只读边界

App 只调用上表里的查询接口，**没有任何写接口**。第二课堂的报名、申报、申诉一律回到官方站点。

---

## 6. 复用到其他学校

1. 打开 `https://<二课域名>/`，从 `app.<hash>.js` 里确认 axios `baseURL`。
2. 用 `POST /token` 试出该平台的 `schoolCode`（编号错会回 `10007`，能快速二分）。
3. 在 `UserManager` 里给对应学校补 `secondClassroomBaseUrl` / `secondClassroomSchoolCode`。
4. 若该校 `level` 取值或字段名与本文不同，改 `SecondClassRankLevel` 与客户端解析即可。

---

## 7. 活动模块（2026-09-21 补充评估）

完整可行性评估见 `docs/design/2026-09-21-second-classroom-activity-module-feasibility.md`。
要点：

- 活动模块**确实存在且可用**，与现有只读集成同源（同 baseUrl / token / `params` 一层编码）。
- 实测通过：`POST /token`、`GET /dict/activity/classify/list`（7 分类）、
  `GET /activity/list`（`total=34`）、`GET /activity/detail/participant?id=`、
  `GET /activity/material/list`、`POST /activity/sign/one/list`。
- 报名契约：`POST /activity/enroll/person {id, personMaterial:[{key,filedValue,filedValueTitle}]}`，
  字段定义来自 `/activity/material/list`（返回空数组时传 `[]`）。
- **签到不可全做**：学生侧只有 `/activity/sign/one/list`（查记录）与 `/activity/sign/repair`（补签申请）；
  带 `userId` 的 `/activity/sign/in-out` 是**组织者侧**接口。前端活动码为
  `/?sourceName=<enc>&activityid=<enc>`（学生扫组织者码），详情含 `mapClockForceAddressFlag` /
  `latitude` / `longitude` → 存在定位打卡形态。

### 7.1 反解 chunk 的注意点

chunk 的 URL 是 `static/js/<chunkId>.<hash>.<buildTimestamp>.js`：
- `<hash>` 从 `manifest.<hash>.js` 的映射表读；
- `<buildTimestamp>` **必须带上**（本文 §1 已记），少一段会拿到站点的"无法访问"页，
  看起来像被 WAF 拦了，其实是路径不对；
- 活动相关 chunk（本校 2026-09-21 的构建）：`133`=活动列表、`43`=活动详情/报名、
  `28`=参与者视角详情、`67`/`86`=团队、`146`=取消报名审核。

---

## 8. 已知坑：间歇性 302 会让 POST 降级成 GET（**待修**）

不带端口请求 API 时，网关有时会回：

```
302 Location: https://ekta.hnnu.edu.cn:443/api/app/client/v1/token
```

实测同一 URL 连打三次得 `302 / 302 / 200` —— **不是必现，是多节点 / 网关行为**。

**为什么伤人**：`SecondClassroomClient.defaultClient()` 没有设 `followRedirects(false)`，
OkHttp 默认跟随重定向，而对 301 / 302 / 303 会**把 POST 降级为 GET 并丢掉 body**；
服务端收到 GET `/token` 回 `405 Request method 'GET' not supported`，
用户看到的就是一句"第二课堂登录失败"，且几乎无法复现。

修法（任选或叠加）：
1. `defaultClient()` 显式 `followRedirects(false)`，自己处理 3xx（把 302 当"换地址重发"，不让 OkHttp 改写方法）；
2. baseUrl 直接写显式端口 `https://ekta.hnnu.edu.cn:443/api/app/client/v1`；
3. `/token` 增加"遇到 302 / 405 就重发一次"的兜底。

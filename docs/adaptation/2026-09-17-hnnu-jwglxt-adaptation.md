# 淮南师范学院教务系统适配说明

> 目标站点：`https://jwgl.hnnu.edu.cn/jwglxt/xtgl/login_slogin.html`
> 记录日期：2026-09-17
> 适用范围：**本应用只支持淮南师范学院**。2026-09-18 起其它学校与其它教务类型的
> 适配实现已全部移除，详见第 10 节。

---

## 1. 教务类型判定

| 项目 | 结论 |
| --- | --- |
| 教务类型 | **新正方（AcademicSystem.ZF，`zf`）** |
| 版本 | 正方 jwglxt **V9.0**，前端 `zftal-ui-v5-1.0.2` |
| 学校 | 淮南师范学院（`xxdm=10381`） |
| 域名 / 协议 | `jwgl.hnnu.edu.cn` / `https` |
| 基础路径 | `/jwglxt` |
| 登录方式 | **直登表单** + RSA 密码加密 |
| 验证码 | 登录页无验证码（`yzcskz=5`，连续输错 5 次后才出现） |
| CAS / SSO | 无（`authJwglxtLoginURL`、`rzzxxs` 均为空，不会走 `ZhengfangCasSsoClient`） |

判定依据：登录页路径为 `xtgl/login_slogin.html`、页脚为"正方软件股份有限公司 版本V-9.0"、资源前缀 `zftal-ui-v5`。
据此可排除旧正方（`default2.aspx`）与强智（`framework/xsMainV.htmlx`）。

---

## 2. 登录流程（ZfAcademicAdapter.loginViaForm）

```
1. GET  /jwglxt/xtgl/login_slogin.html
2. 收集页面全部 <input type="hidden" name=...>
3. 若 mmsfjm=1 → GET /jwglxt/xtgl/login_getPublicKey.html 取 {modulus, exponent}
4. RSA/ECB/PKCS1Padding 加密密码 → Base64（对应 AcademicCrypto.rsaBase64）
5. POST /jwglxt/xtgl/login_slogin.html
   body = 全部 hidden 字段 + yhm(学号) + mm(加密密码) + language
6. 成功判据：响应中不再出现 name="yhm"
```

### 注意事项

- **隐藏域的 value 有两种写法**，解析时必须同时兼容：
  ```html
  <input type="hidden" name="mmsfjm" id="mmsfjm" value= 1>        <!-- 无引号 -->
  <input type="hidden" id="csrftoken" name="csrftoken" value="..."/>  <!-- 有引号 -->
  ```
  Jsoup 的 `input[type=hidden][name]` 取值可正确处理；自己写正则时若只匹配 `value="..."`，
  会漏掉 `mmsfjm`，导致**密码未加密直接提交 → 登录失败**。排查时踩过这个坑。
- 本页 `img` 中不含 `yzm`/`captcha` 关键字，因此 `ZfAcademicAdapter` 不会误判为需要人机验证。
- 提交时带上了 `xxdm=10381`、`yzcskz` 等全部隐藏域，学校接受，无需裁剪。

### 身份解析

| 字段 | 来源 | 说明 |
| --- | --- | --- |
| 姓名 | `GET /xtgl/index_cxYhxxIndex.html?gnmkdm=index` → `<h4 class="media-heading">胡敏翔&nbsp;&nbsp;学生</h4>` | `parseName` 会剥离"学生/同学/教师/老师"后缀 |
| 学号 | **该页没有**，需回退到 `GET /xtgl/index_initMenu.html` 读 `<input id="sessionUserKey" value="<学号>">` | 由 `zfMenuIdentityFallback` 完成 |

---

## 3. 功能模块代码 gnmkdm（关键适配点）

从 `GET /jwglxt/xtgl/index_initMenu.html` 的 `clickMenu('Nxxxx','/path','名称')` 调用中提取：

| 功能 | gnmkdm | 路径 | 与 App 默认值是否一致 |
| --- | --- | --- | --- |
| 自主选课 | `N253512` | `/xsxk/zzxkyzb_cxZzxkYzbIndex.html` | ✅ 一致 |
| **个人课表查询** | **`N2151`** | `/kbcx/xskbcx_cxXskbcxIndex.html` | ❌ **默认是 `N253508`，不一致** |
| 学生成绩查询 | `N305005` | `/cjcx/cjcx_cxDgXscj.html` | ✅ 一致 |
| 考试信息查询 | `N358105` | `/kwgl/kscx_cxXsksxxIndex.html` | ✅ 一致 |
| 学生学业情况查询 | `N105515` | `/xsxy/xsxyqk_cxXsxyqkIndex.html` | ✅ 一致 |

**核心差异：课表的 gnmkdm 是 `N2151`，不是正方默认的 `N253508`。**

用 `N253508` 访问 `/kbcx/xskbcx_cxXsKb.html` 会返回错误页：

```html
<p class="error_title">无功能权限，</p>
```

POST 同一地址则返回 `"没有访问权限!"`。因此课表必须改用 `N2151`。

---

## 4. 课表适配（重点）

课表在本校是**两个 URL**，必须分开配置：

### 4.1 学期列表（catalog）

```
GET /jwglxt/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151
```

- 返回 `select[name=xnm]`：学年（`2028`…`2013`，`selected` 为 `2026`）
- 返回 `select[name=xqm]`：学期（`3` / `12` / `16`）
- **不能**直接 GET 数据接口来读学期：`GET /kbcx/xskbcx_cxXsKb.html?gnmkdm=N2151` 只返回 **4 字节空响应**，
  解析不到任何 `<option>`。这正是需要新增 `scheduleIndexPath` 配置项的原因。

### 4.2 课表数据（schedule）

```
POST /jwglxt/kbcx/xskbcx_cxXsKb.html?gnmkdm=N2151
Header: X-Requested-With: XMLHttpRequest
Body:   xnm=2026&xqm=3
```

返回 JSON，课表数组在 **`kbList`**。字段映射（与 `AcademicStudyParser.jsonSchedule` 完全对齐）：

| 用途 | 字段 | 示例 |
| --- | --- | --- |
| 课程名 | `kcmc` | xxxxxx |
| 教师 | `xm` | xxx |
| 星期 | `xqj` | `1`（1-7） |
| 节次 | `jcs` | `1-2` |
| 地点 | `cdmc` | xxxxxxxx |
| 周次 | `zcd` | `1-16周` |
| 校区 | `xqmc` | xxxxxxxxxxxx校区 |
| 稳定 ID | `jxb_id` / `kch_id` | — |

### 4.3 学期编码映射

`xqm`：`3` → 第 1 学期，`12` → 第 2 学期，`16` → 第 3 学期。
（App 内 `AcademicStudyReader.zfTerm` 已按此映射，无需改动。）

---

## 5. 选课适配

- gnmkdm 用 `N253512`（`index_initMenu.html` 里"自主选课"的实际取值），
  路径沿用正方默认 `zzxkyzb_*` 系列，**路径无需改代码**。
- 菜单里与选课相关的只有这一项；另有"选课名单查询 `N255010`"（`/xkcx/xkmdcx_*`），
  属于名单查询，不是选课入口。

### 5.1 未开放轮次时学校返回什么（2026-09-18 实测）

这是本校最容易误判的地方：**轮次没开放时接口不报错，回的是空数据。**

| 请求 | 返回 |
| --- | --- |
| `GET /xsxk/zzxkyzb_cxZzxkYzbIndex.html?gnmkdm=N253512&layout=default` | 17651 字节页面，`queryCourse(...)` 0 个、`firstKklxdm`/`firstXkkzId` 均为空；正文含 `.nodata` 提示 |
| `POST /xsxk/zzxkyzb_cxZzxkYzbPartDisplay.html` | `{"tmpList":[],"sfxsjc":"1"}` |
| `POST /xsxk/zzxkyzb_cxZzxkYzbChoosedDisplay.html` | `[]` |

入口页的提示原文在 `.nodata` 容器里：

```html
<div class="nodata"><span>对不起，当前不属于选课阶段，如有需要，请与管理员联系！</span></div>
```

**因此"课程列表为空"和"学校没开放"在数据上完全同形**，只看接口返回分不出来。
必须把入口页这句提示取出来，界面上才能说明原因。

### 5.2 已选课程接口对参数敏感

同一接口按 body 不同会给出两种结果，**带上入口页的全量隐藏域会返回错误页**：

| POST body | 结果 |
| --- | --- |
| `kspage=1&jspage=1000`（App 当前实际提交） | `[]`，正常 |
| 仅 `csrftoken` | `[]`，正常 |
| 入口页全量隐藏域（`pkey`/`kqhjs`/`cdTsxx` 等） | `text/html` 1541 字节 **错误提示页** |
| 无 body（GET） | `[]`，正常 |

App 走的是 `zzxkRequestParams`，它只挑正方 `zzxkYzb.js` 那约 40 个控制字段；
本校入口页隐藏域与这份清单**交集为空**，最终只提交 `kspage`/`jspage`，因此落在正常分支。
换学校时若把入口页隐藏域整份塞进去，就会踩到错误页——这一点和 `ZfAcademicAdapter`
里对"整页隐藏域全量提交会被判异常"的注释是同一个坑。

### 5.3 轮次开放后的分支

`ZfAcademicAdapter.loadCourseContext` 已同时兼容两种分类来源，开放后无需再改：

1. 页面含 `queryCourse(kklxdm, xkkz_id, ...)` 调用 → 按正则取分类；
2. 正方 v5 变体，分类放在 `firstKklxdm` / `firstXkkzId` 隐藏域 → 走兜底分支。

### 5.4 本轮代码改动（选课侧）

1. **`academic/AcademicModels.kt`** — `CourseContext` 新增 `notice` 字段，
   承载学校页面上的提示语。
2. **`academic/AcademicParsing.kt`** — `AcademicHtml.pageNotice(html)`，
   从 `.nodata` / `.error_title` 里取提示正文。
3. **`academic/ZfAcademicAdapter.kt`（`loadCourseContext`）** — 入口页没有分类时，
   若判定为轮次未开放，则把 `.nodata` 原文写入 `CourseContext.notice`。
4. **`academic/AcademicProtocolSupport.kt`** — `AcademicJson.status()` 的
   `ROUND_CLOSED` 关键词补上"不属于选课阶段 / 不在选课阶段 / 非选课阶段"等本校措辞；
   列表响应解析不出数组时（`unresolvedList`）带出学校原文，不再一律报"无法识别的列表"。
5. **`ui/route/AcademicRoutes.kt`** — 课程 Tab 记住 `notice`，列表为空且轮次未开放时
   显示"当前暂无选课轮次 + 学校原文"，而不是干巴巴的"暂无可选课程"。

### 5.5 "已选"页为空 ≠ 没有课（2026-09-18 实测）

本校"已选"子页长期为空，但课表里有 12 门课，容易被当成数据没同步。实测结论是**两回事**：

| 数据源 | 该账号结果 |
| --- | --- |
| `zzxkyzb_cxZzxkYzbChoosedDisplay`（自主选课的已选结果） | `[]` |
| `xkcx/xkmdcx`（选课名单查询 `N255010`） | `totalCount: 0` |
| `kbcx/xskbcx_cxXsKb`（课表） | 19 条 / 去重 12 门 |

即：**自主选课模块里没有任何记录，课表里的课是学校统一排课**（宏观经济学、大学语文、
大学英语Ⅲ、操作系统、Java语言程序设计、管理运筹学、体适能训练 B、中国近现代史纲要、
概率论与数理统计C、形势与政策C、高等数学选讲、创业基础）。这不是适配问题，
"已选"只反映学生在 `zzxkyzb` 里的选课结果。

另外确认：给 `ChoosedDisplay` 加 `xkxnm/xkxqm` **不会**带出排课课程——加 `xkxqm=3` 时
学校会卡住不返回（12s 超时），加 `xkxqm=12` 仍回 `[]`。所以不要试图用它当"我的课程"数据源。

对应改动：`ui/screen/SelectedCoursesScreen.kt` 的空状态补一句说明，
避免用户把"已选为空"误读成"没有课"。

---

## 6. 代码改动点（初版 4 处，均在本地，未提交）

1. **`model/SchoolConfig.java`**
   新增字段 `scheduleIndexPath`（默认 `/kbcx/xskbcx_cxXsKb.html`），并在 `toJson()` / `fromJson()`
   中持久化。旧配置缺少该键时回退到 `schedulePath`，保证向后兼容。

2. **`academic/AcademicStudyReader.kt`（`catalog()`）**
   原 ZF 分支硬编码 `kbcx/xskbcx_cxXsKb.html`，改为读取 `school.scheduleIndexPath`：

   ```kotlin
   AcademicSystem.ZF -> checked(http.get(http.appUrl(
       school.scheduleIndexPath.ifBlank { school.schedulePath.ifBlank { "kbcx/xskbcx_cxXsKb.html" } }
           + "?gnmkdm=${school.scheduleGnmkdm}")))
   ```

3. **`manager/UserManager.java`**
   新增内置学校 `hnnu`：

   ```java
   SchoolConfig hnnu = new SchoolConfig("hnnu", "淮南师范学院", "jwgl.hnnu.edu.cn", "https");
   hnnu.academicSystem = "zf";
   hnnu.basePath = "/jwglxt";
   hnnu.scheduleGnmkdm = "N2151";
   hnnu.scheduleIndexPath = "/kbcx/xskbcx_cxXskbcxIndex.html";
   hnnu.allowedAcademicHosts.add("jwgl.hnnu.edu.cn");
   defaultSchools.add(hnnu);
   ```

4. **`ui/screen/EditSchoolConfigDialog.kt`**（*已于 2026-09-18 删除*）
   高级配置新增"课表首页"输入框（`scheduleIndexPath`），便于其他学校自行调整。
   应用收敛为单校后，该对话框连同"添加 / 编辑学校"入口一并移除，
   `scheduleIndexPath` 改由 `UserManager` 的内置配置固定写入。

---

## 7. 验证结果（使用临时测试账号，账号口令不入库）

### 2026-09-17

| 步骤 | 结果 |
| --- | --- |
| 登录（RSA 加密直登） | ✅ 成功 |
| 身份解析 | ✅ 姓名 + 学号均正确解析 |
| 课表学期列表 | ✅ 解析出 48 个学期，当前 `2026-2027-1` |
| 课表数据 | ✅ `kbList` 19 条，`kcmc`+`xqj`+`jcs` 全部可解析 |
| 选课 | ⚠️ 学校显示"当前不属于选课阶段"，未开放，暂无法验证分类与提交 |

### 2026-09-18（复验，只读）

| 步骤 | 结果 |
| --- | --- |
| 登录（RSA 加密直登） | ✅ 成功，落点 `xtgl/index_initMenu.html` |
| 功能模块代码 | ✅ 菜单 26 项，自主选课 `N253512`、个人课表查询 `N2151` 与配置一致 |
| 身份 | ✅ `sessionUserKey` 取到学号 |
| 选课首页 | ⚠️ 仍为"当前不属于选课阶段"，无分类；`.nodata` 提示已可解析 |
| 可选课程列表 | ✅ 接口正常（`{"tmpList":[],"sfxsjc":"1"}`），因未开放为空 |
| 已选课程 | ✅ 接口正常（`[]`）；自主选课模块无记录，课表 12 门为学校统一排课，见 5.5 |
| 课表数据 | ✅ `kbList` 19 条 / 去重 12 门，`kcmc=宏观经济学 / xqj=1 / jcs=1-2 / zcd=1-16周 / cdmc=泉教D-201` |
| 课表学期编码 | ✅ `xnm` 当前 `2026`；`xqm` 选项 `3=1 / 12=2 / 16=3` |

> 复验只做查询，未向学校提交任何选课或退课请求。分类列表、教学班、提交与退课
> 仍需等学校开放轮次后实测。

---

## 8. 复用到其他学校的通用流程（历史参考）

> 2026-09-18 起应用已收敛为**只支持淮南师范学院**：`AcademicSystem` 枚举只剩 `ZF`，
> 类型探测、添加 / 编辑学校、CAS / SSO 与其它学校的适配器均已删除。下列步骤仅作为
> 将来重新适配其它学校时的操作清单保留，当前代码中没有对应实现。

1. **判定类型**：抓登录页，看路径与页脚（新正方 `xtgl/login_slogin.html`；旧正方 `default2.aspx`；强智 `framework/xsMainV.htmlx`）。
2. **打通登录**：注意 `mmsfjm`（是否 RSA）、验证码 `img`、是否跳转 CAS。
3. **取功能模块代码**：登录成功后 `GET /jwglxt/xtgl/index_initMenu.html`，
   用正则提取 `clickMenu('Nxxxx','/path','名称')`。**这是拿 gnmkdm 最可靠的方式**，不要照抄默认值。
4. **逐模块试访问**：带上真实 gnmkdm 请求一次，确认返回不是"无功能权限"错误页。
5. **课表拆两个 URL**：确认"学期首页"和"数据接口"是否同一个地址；不同时配置 `scheduleIndexPath`。
6. **校验解析**：对照 `AcademicStudyParser.jsonSchedule` / `ZfAcademicAdapter` 期望的字段名，
   确认返回 JSON 的键名能命中（如课表数组键是 `kbList`）。

---

## 9. 安全与遗留事项

- 测试用账号密码**不得写入仓库**；本文档只记录学号与流程，不记录口令。
- 适配验证完成后应提醒用户**立即修改学校账号密码**。
- `yzcskz=5`：连续 5 次密码错误会触发验证码，届时直登会失败，需要走网页登录通道。
- 选课功能待学校开放选课阶段后再实测分类列表、已选列表与提交/退课。

---

## 10. 单校收敛（2026-09-18）

应用定位调整为"只适用淮南师范学院"，以下内容已移除：

| 类别 | 移除内容 |
| --- | --- |
| 学校清单 | `UserManager` 仅保留 `hnnu`；自定义学校机制（`addCustomSchool` / `removeCustomSchool` / `customSchools` 持久化 / `KEY_CUSTOM_SCHOOLS`）整体删除 |
| 教务类型 | `AcademicSystem` 由 4 值收敛为 `ZF` 单值；`AUTO` 自动探测与 `QZ` / `QZ_OLD` / `ZF_OLD` 全部删除，`academicSystem` 默认值由 `legacy_zf` 改为 `zf` |
| 适配器 | `QzAcademicAdapter`、`QzOldAcademicAdapter`、`QzScriptParser`、`ZfOldAcademicAdapter`、`ZfOldSports` |
| 统一身份认证 | `ZhengfangCasSsoClient` / `ZhengfangCasProtocol`、`TyustSsoLoginManager` / `TyustSsoProtocol`、`ZjutSsoLoginManager` / `ZjutSsoProtocol` |
| CAS 登录链路 | `ZfAcademicAdapter` 中 `discoverSsoEntry` / `loginViaCas` / `submitCasLogin` / `handleCasRejection` / `completeCasLogin` / `importTeachingCookies` / 重试计数全部删除，`login()` 直接调用 `loginViaForm` |
| 编码工具 | `AcademicParsing.kt` 的 `LoginEncoding`（强智新旧两套 Base64 / 移位编码）删除 |
| 学校管理入口 | `EditSchoolConfigDialog`、`AddSchoolDialog`、`SystemPicker` 的选择 / 添加 / 编辑学校流程；设置页学校行改为固定提示 |
| 相关测试 | 上述实现的单元测试与仪器测试 |

收敛后的入口约定：

- `AcademicGatewayFactory.loginUrl()` 固定返回 `school.fullBasePath + "/xtgl/login_slogin.html"`；
  `supports()` / `hasSelectedAdapter()` 恒为 `true`，`create()` 直接构造 `ZfAcademicAdapter`。
- `login/PasswordLoginGateway.kt` 的工厂收敛为"正方教务适配器 / 演示登录"二选一。
- `AcademicCapabilities.systems` 只剩一条"正方教务（淮南师范学院）"。
- `SystemDetector.classify` 只识别正方直登页（含 `login_getpublickey`，或同时含 `csrftoken` 与 `xtgl`）。


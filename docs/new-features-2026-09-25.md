# App 新功能候选（2026-09-25 调研）

调研方法：用测试账号按 App 同一套协议登录教务，读**功能菜单**（`xtgl/index_initMenu.html`）。
这份菜单是服务端直出的、逐条 `clickMenu('gnmkdm','入口路径','标题','null')` 的清单，
也就是"**这个账号到底能进哪些功能**"的权威答案 —— 比翻手册、猜路径都靠得住。

只做了 GET 读取，没有任何写操作。原始页面与凭据仅留在本机临时目录，未入库。

---

## 0. 本轮已经补回来的（原本就有、App 却没露出来）

| 东西 | 之前的状态 | 现在 |
| --- | --- | --- |
| 考级类别 `xmlbfl=1003`「参加大类分流报名」 | 硬编码只认 `1001`，永远看不到 | 从菜单发现类别，多类别时页面顶部可切换 |
| 考级类别 `xmlbfl=1004`「推免报名」 | 同上 | 同上 |
| 「本学期过期项目报名信息」 | 网页端页头一直有这个按钮，App 完全没接 | 考级页底部新增，按需加载 |
| 已报名列表只回 15 条 | 静默截断，一个字都不说 | 按信封 `totalCount` 明确提示「最近 N 条 / 共 M 条」 |

顺带产出的可复用件：`academic/AcademicMenu.kt` —— 把菜单解析成
`(gnmkdm, url, title)` 列表。**下一节的很多功能都建在它上面。**

---

## 1. 教务菜单全集（实测，26 条）

App 现状对照。`✅` = 已有等价模块；`—` = App 里没有。

| gnmkdm | 标题 | 入口路径 | App |
| --- | --- | --- | --- |
| N2151 | 个人课表查询 | `kbcx/xskbcx_cxXskbcxIndex.html` | ✅ 课表 |
| N305005 | 学生成绩查询 | `cjcx/cjcx_cxDgXscj.html` | ✅ 成绩 |
| N2510 | 考级项目报名 | `kjgl/kjbm_cxXskjbm.html?xmlbfl=1001` | ✅ 考级（本轮补强） |
| N358105 | **考试信息查询** | `kwgl/kscx_cxXsksxxIndex.html` | — |
| N2155 | **查询空闲教室** | `cdjy/cdjy_cxKxcdlb.html` | — |
| N0185 | **学生缴费** | `paycenter/paycenter_cxGrjfIndex.html` | — |
| N2511 | **教学项目报名** | `jxrwbmgl/jxrwxmbm_cxJxrwxmbmIndex.html` | — |
| N105515 | **学生学业情况查询** | `xsxy/xsxyqk_cxXsxyqkIndex.html` | — |
| N03D1 | **学位课程平均学分绩点查询** | `design/viewFunc_cxDesignFuncPageIndex.html` | — |
| N214505 | 班级课表查询 | `kbdy/bjkbdy_cxBjkbdyIndex.html` | — |
| N153540 | 教学执行计划查看 | `jxzxjhgl/jxzxjhck_cxJxzxjhckIndex.html` | — |
| N155015 | 教学进度表查看 | `jsjxrl/jsjxrl_cxJsjxrlCkIndex.html` | — |
| N1056 | 重修报名 | `cxbm/cxbm_cxXscxbmIndex.html` | — |
| N1053 | 辅修报名 | `fxgl/fxbm_cxXsfxbmIndex.html` | — |
| N255720 | 重修课程查询 | `cxkccx/cxkccx_cxCxkccxIndex.html` | — |
| N255010 | 选课名单查询 | `xkcx/xkmdcx_cxXkmdcxIndex.html` | — |
| N558020 | 学生成绩总表打印 | `bysxxcx/xscjzbdy_cxXscjzbdyIndex.html` | — |
| N401605 | 学生评价 | `xspjgl/xspj_cxXspjIndex.html?doType=details` | — |
| N100830 | 学生自主报到注册 | `bdzc/cxxsbdzc_cxXszzbdzcIndex.html` | — |
| N100808 | 学生个人信息维护 | `xsxxxggl/xsgrxxwh_cxXsGrxxxgIndex.html` | — |
| N100801 | 查询个人信息 | `xsxxxggl/xsgrxxwh_cxXsgrxx.html` | — |
| N106005 | 学生证补办申请 | `xszbbgl/xszbbgl_cxXszbbsqIndex.html?doType=details` | — |
| N106204 | 学生转专业申请 | `xszzy/xszzysqgl_cxXszzysqIndex.html?doType=details` | — |
| N824203 | 学生退书申请 | `jcdggl/jcck_cxXstssqIndex.html` | — |
| N151530 | 校内课程替代申请 | `kcthgl/xskcthsq_cxXskcthIndex.html?sqlx=xnkc` | — |
| N253512 | 自主选课 | `xsxk/zzxkyzb_cxZzxkYzbIndex.html` | 已下线，**不再引入** |

> 注意：这份菜单是**这个测试账号**的。别的角色（如高年级、不同院系）可能多出条目 ——
> 这正是"从菜单读"而不是"写死清单"的价值。

---

## 2. 候选功能（按投入产出比排序）

### A 档：一次投入，覆盖一大片

**A1. 「全部教务功能」页 + 内置已登录浏览器** —— 推荐优先做

菜单里的 20 多个功能，用一个个页面去实现是不现实的；但**列出来 + 点进去**是可行的一步。
做法：新增一个只读的「教务功能」页，用 `AcademicMenu` 渲染菜单（分组、标题、图标），
点任一条 → 打开内置 WebView 直接落到那条 `url`。

- 现在的 `AcademicWebViewActivity` 是**登录专用**的（`setDataDirectorySuffix("academic")`
  独立存储目录，职责是"用户过验证码 → 把 Cookie 导出回 App"），而且 `onCreate` 里还会
  `CookieManager.removeAllCookies` —— 直接复用会清掉想要注入的会话。所以要新开一个
  "已登录浏览"模式（或在同一个 Activity 里按 Intent 参数分流，跳过清理那一步）。
- 注入 Cookie 的通道**已经有一半**：`AcademicSession.cookieHeader()` 能读出整串
  `name=value; …`，现有方向是 WebView → App（`AcademicGatewayFactory.importCookie`），
  反向只差一个访问器 —— 在 `AcademicGatewayFactory` 里 `transportFor` 旁边加一行
  `cookieHeaderFor(school, accountKey) = sessions.session(...).cookieHeader()`，
  再按条目 `CookieManager.setCookie(base, "name=value")` 即可。**是一小段胶水，不是重构。**
- 页面本身不用写：菜单解析（`AcademicMenu`）、壁纸壳（`GlassPageScaffold`）、
  WebView 宿主与地址栏都是现成的。
- 收益：重修报名、辅修报名、转专业、退书、报到注册、培养方案这些"一年用一次但真要用"
  的功能，一次全有，且永远不会因为学校开新功能而失效（菜单自己会变）。
- 风险：这些页面里含**写操作**（提交申请）。必须留在 WebView 里由用户在教务自己的
  页面上操作，App 不代提交 —— 与现在"选课交给网页端"的口径一致。

**A2. 考试信息查询（N358105）** —— 单品价值最高

考试时间、考场、座位号是学生最高频的刚需，而且**天然适合做桌面组件**
（工作台已有七种卡片，再加一种「下一场考试」成本很低）。接口未实测，需先抓一次页面/接口。

**A3. 查询空闲教室（N2155）**

"现在哪间教室空着"是自习场景的刚需。可以只做一个查询页，也可以在课表页加一个入口。

**A4. 缴费（N0185）+ 考级的缴费状态**

- 菜单里的「学生缴费」是一个独立页面。
- 更近的一步：考级模块里已经有 `kjgl/kjbm_cxXskjbmjfzt.html?xsbmqk_id=` 这个
  **缴费状态**接口（退报前已经在调用，用来判断"已缴费不给退"），但界面**完全没把它显示出来**。
  把「已缴费 / 未缴费」标在已报名记录上，是一次纯展示层的改动，当天能做完。
  考级页的隐藏域里还带着 `payment_cxAppPay.html` 的支付参数（`secret` / `data`），
  后续要接缴费也有现成的入口。

### B 档：与现有模块同构，可以复用骨架

**B1. 教学项目报名（N2511）** —— `jxrwbmgl/jxrwxmbm_*` 与考级报名是同一族写法
（`cxXxxIndex` + `doType=query` 的 jqGrid 信封）。`examreg` 的模型/解析/客户端骨架
大概率能直接复用，是"再做一个报名类模块"里最省的一个。

**B2. 学业情况（N105515）+ 学位课程绩点（N03D1）**

成绩页现在只有逐门成绩。学分完成情况、平均学分绩点、学业预警是"看趋势"的需求，
适合放在成绩页顶部做汇总卡。

**B3. 班级课表查询（N214505）**

课表页加"切到班级课表"（和现在切换账号的交互类似）。注意口径：班级课表是全班一致的，
不含个人调课/重修。

**B4. 培养方案（N153540 教学执行计划 / N155015 教学进度表）**

"我这一届总共要修哪些课、现在修到哪了"。价值高但页面复杂（多级树 + 附件），
建议排在 A1 之后 —— 用 A1 的内置浏览器先兜住，体验稳定了再考虑原生实现。

**B5. 重修（N1056 报名 / N255720 查询）、辅修报名（N1053）、选课名单查询（N255010）**

都在同一个"报名/查询"范式里，可按需逐个接。

### C 档：能做，但要单独评估（都是写操作）

| 功能 | 为什么要谨慎 |
| --- | --- |
| 学生评价（N401605） | 评教是**一锤子**的正式提交，提交后通常不能改。需要强确认 + 逐题扩散核对 |
| 学生证补办 / 转专业 / 退书 / 校内课程替代申请 | 真实行政事务，可能涉及附件上传、身份材料。一旦代提交出问题，后果不是"看不到数据"而是"办错事" |
| 学生个人信息维护（N100808） | 直接改学籍信息，风险最高 |
| 学生自主报到注册（N100830） | 有严格时间窗，错过/误操作影响注册状态 |

建议口径：**C 档一律先用 A1 的内置浏览器承接**，除非某一项被反复要求，再单独做原生实现。

### D 档：本轮协议衍生的低成本小改进（无需新协议）

1. 考级已报名记录里，把**准考证号 / 证书编号 / 成绩**做成一键复制（数据已经在模型里）。
2. 考级记录**按学期分组**：现在是一个平铺列表，`term` 字段已经有了。
3. 考级**报名截止提醒**：卡片上已有 `endTime` 与「还剩余 N 天」，可以本地通知提醒一次。
4. 工作台新增「考级倒计时 / 下一场考试」卡片（与 A2 联动）。
5. 已报名列表的 15 条上限：若将来找到真正可用的分页参数，再补翻页；
   目前已在界面上说明，不必再投入。

---

## 3. 明确不做

- **自主选课 / 抢课**（N253512）：本版已整体下线，抢课类功能是明确红线。
- **任何自动提交**：报名、评教、申请类一律只做"读 + 引导到教务页面"，不代用户点提交。
- **服务端轮询抢名额**：既是隐私红线也是公平性问题。

---

## 4. 建议的落地顺序

1. **A4 的"考级缴费状态展示"** —— 半天，纯展示，立刻能提升现有模块的完整度。
2. **A2 考试信息查询** —— 先抓一次接口，再定原生还是先走 A1。
3. **A1 全部教务功能页 + 已登录浏览器** —— 一次投入覆盖 20+ 长尾功能；
   做完之后 C 档全部自动被兜住。
4. **B1 教学项目报名** —— 复用 `examreg` 骨架。
5. B2/B3 → B4 → B5。

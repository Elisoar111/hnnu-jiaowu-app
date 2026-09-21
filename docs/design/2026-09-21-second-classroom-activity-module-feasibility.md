# 第二课堂「活动报名」模块接入可行性评估

> 评估日期：2026-09-21
> 站点：`https://ekta.hnnu.edu.cn`（学校自建《第二课堂》，页面标题即「第二课堂」）
> 评估方式：读取站点前端 bundle 反解接口 + 用学校提供的**测试账号**做**只读**实测
> ⚠️ 实测严格只读：只调用登录与查询接口，**未触发任何报名 / 签到等写操作**

---

## 0. 结论

**技术上完全可行** —— 活动模块的接口已在校园网实测跑通，连报名的请求体契约都拿到了。
但"能做"和"该做"要分开，建议按三档划线：

| 档位 | 内容 | 判断 |
| --- | --- | --- |
| **A｜建议做** | 活动浏览 / 搜索 / 筛选、活动详情、**报名（个人）**、取消报名、我的报名与参与记录、活动通知、评论 / 投票 | 纯 HTTP 接口，契约明确，风险与现有"查成绩"同级 |
| **B｜有硬边界** | 签到 / 签退 | 学生侧只有「查记录」和「补签申请」；真实签到靠**组织者扫码**或**定位打卡**。只能做"展示 + 引导 + 补签申请"，**做不了也不该做自动签到** |
| **C｜不建议做** | 自动抢名额、代报名、伪造定位签到 | 违反学校考勤与公平性规定，且极易被封号 |

一句话：**把「看活动 + 报名 + 查我的活动」做进 App 是可行的；把「签到」做全是不可行的。**

---

## 1. 实测证据

与现有只读集成**完全同源**，不需要任何新通道：

| 项 | 值 |
| --- | --- |
| API 根 | `https://ekta.hnnu.edu.cn/api/app/client/v1/` |
| 认证 | `Authorization: <access_token>`（无 Bearer 前缀） |
| 参数编码 | `params=<一层百分号编码的 JSON>`（即现有 `encodeParams` 的做法） |
| 账号 | 学校提供的测试学号 + 初始密码（只读，未做任何写操作） |

实测通过的调用：

| 调用 | 结果 |
| --- | --- |
| `POST /token` | `code=0`，拿到 token |
| `GET /dict/activity/classify/list` | `code=0`，返回 7 个活动分类（思想政治素养 / 社会责任担当 / …） |
| `GET /activity/list?pageNum=1&pageSize=5&name=&classifyId=&sortType=1` | `code=0`，`total=34`；每条含 `id / name / address / hours / organizationName / startTime / endTime / logo` |
| `GET /activity/detail/participant?id=7358` | `code=0`，返回完整活动详情（见 §4） |
| `GET /activity/material/list?activityId=7358&collectStage=1&isTeam=0` | `code=0`，`data=[]`（该活动报名无需额外填表） |
| `POST /activity/sign/one/list` | `code=0`，`data=[]`（我的签到记录） |

---

## 2. 活动模块接口清单

由前端 chunk 反解得到，**★ 为已实测确认**：

### 2.1 学生可用（A 档主体）

| 方法 | 端点 | 用途 |
| --- | --- | --- |
| GET ★ | `/activity/list` | 活动列表，参数 `pageNum / pageSize / name / classifyId / sortType` |
| GET ★ | `/dict/activity/classify/list` | 活动分类字典 |
| GET ★ | `/activity/detail/participant` | 活动详情（参与者视角），参数 `id` |
| GET ★ | `/activity/material/list` | 报名需填写的采集字段，参数 `activityId / collectStage / isTeam` |
| POST ★ | `/activity/enroll/person` | **个人报名** |
| POST | `/activity/enroll/team` | 团队报名 |
| POST | `/activity/enroll/cancel/person` | 取消报名 |
| GET | `/activity/enroll/member/list` | 已报名成员 |
| GET | `/activity/noticeList` | 活动通知 |
| GET | `/activity/comment/list` · POST `/activity/comment/add` · POST `/activity/comment/delete` | 评论 |
| GET | `/activity/vote/info` · POST `/activity/vote` | 投票 |
| GET | `/activity/material/submit/list` | 我提交的材料 |
| POST | `/activity/enroll/material/submit` | 提交报名材料 |
| POST ★ | `/activity/sign/one/list` | **我的签到 / 签退记录** |
| POST | `/activity/sign/repair` | 补签申请（需审核） |

### 2.2 组织者 / 管理侧（**App 不碰**）

`/activity/sign/in-out`（给指定人签到签退）、`/activity/detail/manger`、`/activity/audit`、`/activity/audit/enroll`、`/activity/member/list`、`/activity/delete`、`/activity/placement`、`/activity/player/add`、`/activity/singer/add`、`/activity/establish/material` 等。

> 注意：`/activity/sign/in-out` 带 `userId` 参数，是**管理侧"替某人签到"**的接口，不是学生自签通道。这决定了 §5 的结论。

---

## 3. 报名契约（实测反解）

### 3.1 个人报名

```
POST /activity/enroll/person
body: params=<一层编码的 JSON>
{
  "id": 7358,
  "personMaterial": [
    { "key": <filedId>, "value": <filedValue>, "filedValueTitle": <title> }
  ]
}
→ { "code": 0, "data": ..., "msg": "ok" }
```

- `personMaterial` 的字段定义来自 `GET /activity/material/list?activityId=&collectStage=1&isTeam=0`。
- **返回空数组时传 `[]` 即可**（实测活动 7358 就是这种情况）——也就是大多数"无需填表"的活动，报名只需 `{id, personMaterial: []}`，实现量极小。

### 3.2 团队报名

```
POST /activity/enroll/team
{ id, teamName, teamAvatar, teamMembers, teamMaterial, collectStage: 1 }
```

### 3.3 取消报名

```
POST /activity/enroll/cancel/person
{ id: <activityId>, applyInfo: <理由> }
```

- 当活动 `applyStatus == 2` 时，取消属于**需审核**流程（前端会强制填理由），提示"报名取消申请已提交，等待审核"；否则直接"已成功取消报名"。

---

## 4. 活动详情字段 → 界面能做到什么程度

实测 `/activity/detail/participant` 返回的字段足以支撑一个完整详情页：

| 分类 | 字段 | 界面用途 |
| --- | --- | --- |
| 基本信息 | `name` `address` `introduce` `logo` `classifyName` `organizationName` `manager.name` `contact` | 标题、地点、正文、主办方、联系人 |
| 时间 | `startTime` `endTime` `enrollStartTime` `enrollEndTime` | 活动时间 + **报名倒计时 / 报名窗口状态** |
| 名额 | `peopleLimit` `joinMemberCount` | 名额进度条（实测 11 / 6） |
| 学分 | `hours` `grantHours` `ranges` | 学分与学时 |
| 报名资格 | `gradeList` `collegeList` `genderLimit` `activityLimitType` | "本活动限法学院"一类提示 |
| 材料 | `enrollMaterial` `attachment` | 是否需要填表 / 附件下载 |
| 签到配置 | `signWay` `signType` `attendanceType` `signLimit` `signAudit` `signSwitch` `longitude` `latitude` `mapClockForceAddressFlag` | 决定"签到"这一块展示什么（见 §5） |
| 我的状态 | `isManager` `isSigner` `applyStatus` `status` `cancelStatus` `applyRejectReason` | 按钮该显示"报名 / 已报名 / 待审核 / 取消报名" |
| 其他 | `chatGroupId` `isHaveCollect` | 活动群、是否需要采集 |

---

## 5. 签到：为什么是硬边界

这是整个评估里**唯一真正做不了的一环**，理由有三：

1. **学生侧没有"自签"接口。** 学生能调的只有 `POST /activity/sign/one/list`（查记录）和 `POST /activity/sign/repair`（补签申请）。带 `userId` 的 `/activity/sign/in-out` 属于组织者侧。
2. **签到靠扫码 / 定位。** 前端里活动二维码是
   `https://ekta.hnnu.edu.cn/?sourceName=<加密串>&activityid=<加密串>`，
   学生**扫组织者展示的码**进入活动页；详情里还有 `mapClockForceAddressFlag`（强制定位地址）与 `latitude/longitude`，说明存在**定位打卡**形态。
3. **代签没有正当性。** 即便技术上能把"被人扫"或"报坐标"模拟出来，那也是伪造考勤 —— 明确不做。

**所以 App 在签到这一块只能做**：展示我的签到 / 签退状态、展示我的签到码、唤起扫码 / 跳转签到页、提交补签申请。

---

## 6. 风险与合规

| 风险 | 说明 | 对策 |
| --- | --- | --- |
| 平台条款 | 第三方客户端调用内部 app API 属灰区（与现有教务集成同一姿态） | 出现签名 / 风控收紧时**降级为只读**，别硬刚 |
| 频控 / 封号 | 登录密码错 5 次锁 30 分钟已确认；写接口更可能有频控 | 报名**不做自动重试、不做轮询**，失败即报错给用户 |
| 公平性 | 自动抢名额 = 挤占他人机会 | **不做**。用户已明确要求测试期不报名，方向正确 |
| 隐私 | `personMaterial` 由学校配置，可能含身份证 / 手机号 | 仅本地处理，不落日志、不上传第三方 |

---

## 7. 附带发现（真 Bug，建议单独修）

### 间歇性 302 会把 POST 降级成 GET，导致第二课堂登录偶发失败

实测（同一 URL，连打三次）：

```
① POST https://ekta.hnnu.edu.cn/api/app/client/v1/token  → HTTP 302
   Location: https://ekta.hnnu.edu.cn:443/api/app/client/v1/token
② 同上  → HTTP 302
③ 同上  → HTTP 200  ✅
```

**不是必现的，是多节点 / 网关行为。**

为什么会伤到 App：

- `SecondClassroomClient.defaultClient()` **没有**设 `followRedirects(false)`，OkHttp 默认会跟随重定向；
- 而 OkHttp 对 301 / 302 / 303 的处理是**把 POST 降级为 GET 并丢掉请求体**；
- 服务端收到 GET `/token` → 回 `405 Request method 'GET' not supported`；
- 用户看到的是"第二课堂登录失败"，而且**极难复现**。

建议修法（三选一或叠加）：

1. `defaultClient()` 显式 `followRedirects(false)`，然后自己处理 3xx：把 302 当作"换地址重发"而不是让 OkHttp 改写方法；
2. baseUrl 直接写显式端口：`https://ekta.hnnu.edu.cn:443/api/app/client/v1`；
3. 对 `/token` 增加"遇到 302 / 405 就重发一次"的兜底。

顺带把这条写进 `docs/adaptation/2026-09-18-hnnu-second-classroom.md`，它属于同一类"偶发、难复现"的坑。

---

## 8. 分期实施建议

### Phase 1 — 只读浏览（1 个版本，零风险）

活动列表（分页 / 关键词 / 分类筛选 / 排序）+ 活动详情页。
纯查询接口，先在生产环境验证接口稳定性与数据结构，**先不做任何写操作**。

### Phase 2 — 报名闭环（1–2 个版本）

- 报名 / 取消报名 / 我的活动；
- 报名前二次确认，卡片上显示**名额进度、报名窗口、资格限制**；
- 结果按 `code / msg` 精确提示：人数已满 / 不在报名时间 / 不符合资格 / 需审核 / 已报名；
- **不做任何自动重试与自动轮询。**

### Phase 3 — 可选增强

活动评论 / 投票 / 活动通知；签到记录查看 + 补签申请入口 + 扫码引导。

---

## 9. 代码落点

| 改动 | 文件 |
| --- | --- |
| 活动数据模型 | 新增 `secondclass/SecondClassActivityModels.kt` |
| 活动接口 | `secondclass/SecondClassroomClient.kt`（沿用同一 token / 编码管线） |
| 拼装 | `secondclass/SecondClassroomRepository.kt` |
| 页面 | 新增 `ui/screen/SecondClassActivityScreen.kt` 等 |
| 路由 / 入口 | `ui/route/SecondClassroomRoute.kt`（二课页内加分段：成绩单 / 活动） |

现有 `SecondClassroomClient` 的编码、鉴权、错误码（`10001` / `20002` / `10007`）处理**可直接复用**，新增活动接口只是加方法，不涉及架构改造。

---

## 10. 总评

| 维度 | 评价 |
| --- | --- |
| 技术可行性 | **高**。接口实测可用，契约明确，实现成本低（复用现有 client） |
| 收效 | **高**。「看活动 + 报名」是二课里最高频的动作，比只读成绩单更有用 |
| 风险 | **中**。主要来自平台条款与风控，而非技术 |
| 唯一硬边界 | **签到**。必须在场、且多为被扫 / 定位，客户端做不了也不该做 |

**建议：从 Phase 1 开始做，把「浏览 + 报名 + 我的活动」做实，签到只做展示与引导。**

# 待办清单

> 记录尚未完成、需要后续跟进的事项。完成一项就把它从这里删掉，不要留"已完成"的条目。

---

## 1. 第二课堂（二课）

状态：**代码已落地，待真机验证**。功能由项目作者自行编写，本文档只登记待办，不改动其设计。

已就位的内容（供后续接手时定位）：

| 部分 | 位置 |
| --- | --- |
| 站点与学校代码 | `manager/UserManager.java` — `secondClassroomBaseUrl = "https://ekta.hnnu.edu.cn/api/app/client/v1"`、`secondClassroomSchoolCode = "10381"` |
| 模型 | `secondclass/SecondClassroomModels.kt` |
| 只读客户端 | `secondclass/SecondClassroomClient.kt`（`POST /token` 换 `access_token`，之后请求带 `Authorization` 头） |
| 本地状态 | `secondclass/SecondClassroomStore.kt`（token 存普通 prefs，密码走 `CredentialStore` / AndroidKeyStore） |
| 仓库层 | `secondclass/SecondClassroomRepository.kt` |
| 界面 | `ui/screen/SecondClassroomScreen.kt`、`ui/screen/SecondClassLoginDialog.kt`、`ui/route/SecondClassroomRoute.kt` |
| 入口 | 底栏第 4 项「二课」（`MainActivity.kt` 的 `BottomNavItem.SecondClass`）+ 设置页「第二课堂」行 |

待办：

- [ ] **真机验证登录链路**：用教务学号 + 第二课堂密码登录，确认 `POST /token` 返回 `access_token`，
      并覆盖 `code=20002`（账号或密码错误）、`10007`（学校未开通）、`10001`（token 失效）三条错误分支的实际文案。
- [ ] **逐接口核对解析**：`/student/achievement/detail`（表头 + 总分 + 学时）、`/student/achievement/detail-app`（模块雷达数据）、
      `/student/user/transcript`（学分完成情况，注意 `scoreStatus` 必须传数字 `0`）、
      `/student/achievement/rank` 与 `/student/achievement/self/rank`（排行榜，`level` 取 10 / 9 / 8 / 6）。
      站点字段名与 `SecondClassroomModels.kt` 不一致时要同步模型。
- [ ] **确认双重 URL 编码在真机网络栈下无差异**（`params` 两层编码，站点前端 `encodeURI` + qs 各一次）。
- [ ] **确认只读边界**：本功能只做查询，报名 / 申报一律回到官方站点，不要在 App 内加写接口。
- [ ] **补测试**：`SecondClassroomClient.encodeParams` 的编码用例、`SecondClassRankLevel.ofLevel` 的映射用例。

---

## 2. 其它未收尾事项

- [ ] 重新构建并签名 release APK，解包 `classes.dex` 做字面量核验（确认"淮南师范学院 / hnnu / jwgl.hnnu.edu.cn / N2151"命中）。
- [ ] 清理本地探针残留（`D:/测试/_probe/`，在项目目录之外）。
- [ ] **提醒用户修改测试账号密码**：适配验证期间使用过临时账号，口令不得写入仓库或记忆文件。

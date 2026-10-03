# 复习模块（v1.2.7 新增）

## 是什么

导入 JSON 题库 → 刷题 → 错题本/收藏 → 可选让 DeepSeek 讲解。数据全部在本机。

参考实现：`D:\zhiyu`（知隅，Flutter 题库 app）。**只借它的题库格式与练习流程**，
不引它的任何代码，也不带它的题库文件（版权 + 包体）。

## 入口

「我的 → 校园服务 → 复习」。独立 Activity（`ReviewActivity`），内部用同一 composition 流转页面 ——
练习会话是内存对象，换 Activity 会被回收，正在做的题会丢。

## 题库格式

见应用内「复习 → 导入题库 → 查看题库格式说明」，样例见 `docs/review/sample-bank.json`。

顶层两种：`{"name":..., "questions":[...]}` 或直接题目数组。中英文键名都认
（`stem`/`题干`、`options`/`选项`、`answer`/`答案`、`type`/`题型`、`chapter`/`章节`、
`explanation`/`解析`、`source`/`来源`）。`options` 兼容 `"甲|乙|丙"` 写法。

导入规则：
- 单选答案必须 1 个字母，多选至少 2 个；不合规的题**单独跳过**并提示跳过条数，不整份失败。
- 选项 2–8 个，答案不得超出选项范围。
- **同名题库重复导入 = 覆盖**（旧题与旧答题记录一并清掉，避免孤儿行）。

## 表结构

`review.db`，原生 SQLite（项目没引 Room，不为一个模块加 KSP）。表名与列名照 zhiyu 的
`database.dart` 对齐，方便对照迁移：

| 表 | 作用 |
| --- | --- |
| `banks` | 题库（含待复习错题数） |
| `questions` | 题目，`(bank_id, qid)` 联合主键 —— 不同库可有同 id 的题 |
| `records` | 每题对错次数与最近结果 |
| `wrong_book` | 错题本：连对 `MASTER_STREAK`(3) 次算已掌握；掌握后答错重新入册 |
| `favorites` | 收藏 |
| `daily_stats` | 每日答题量 |
| `meta` | 杂项键值 |

## 判分口径

**多选必须完全一致才算对**（少选/多选/错选都算错）。抽成纯函数 `judgeAnswer()` 而非
Composable 里的布尔表达式 —— 返回布尔会把"为什么错"丢掉，界面只能给所有 false 印同一句话。
单测在 `PracticeSessionTest`。

## AI 答疑

- DeepSeek，OpenAI 兼容：`POST https://api.deepseek.com/chat/completions`，`Authorization: Bearer`。
  走 https 公共 CA，**不需要**在 `network_security_config.xml` 登记明文例外。
- API Key 存 `AiKeyStore`：AndroidKeyStore + AES/GCM，密文落 SharedPreferences。
  独立于 `CredentialStore`（那是教务账号密码专用，键名 `pw_` 前缀、绑 accountKey，混用会让两套生命周期纠缠）。
- 题目**只在你点「问 AI」时**才发出去；Key 绝不打日志。
- 回答用项目现成的 `MarkdownText` 渲染（内部判到 Markdown 语法才切富文本）。

## 文件

| 路径 | 作用 |
| --- | --- |
| `review/ReviewQuestion.kt` | 题目模型 + JSON 解析 + 校验 |
| `review/ReviewDatabase.kt` | SQLite 表与全部查询 |
| `review/PracticeSession.kt` | 练习会话纯逻辑（出题/作答/判分/进度） |
| `review/AiKeyStore.kt` | API Key 加密存储 |
| `review/AiChatClient.kt` | DeepSeek 客户端 |
| `review/ui/ReviewHomeScreen.kt` | 题库列表 / 导入 / 练习入口 |
| `review/ui/PracticeScreen.kt` | 逐题练习 |
| `review/ui/QuestionCard.kt` | 题目卡片（纯展示） |
| `review/ui/AiExplainPanel.kt` | AI 讲解面板 |
| `review/ui/AiSettingsScreen.kt` | AI 设置 + 题库格式说明 |
| `ReviewActivity.kt` | 宿主与页面流转 |

## 隐私

- 题干内容、答案、API Key **一律不进日志**（只记 id 与条数）。
- 不做任何统计上报。
- AI 请求只有用户主动触发才发出，文案里已写明。

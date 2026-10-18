# AI 答疑

页面上一键「问 AI」讲解练习题的原生插件。只使用 **DeepSeek 官方接口**（`https://api.deepseek.com`），
不需要学校账号，也不联网访问任何学校站点。

移植自安卓项目 `app/src/main/java/com/hnnujw/course/review/` 的 AI 链路（`AiEndpoint` / `AiChatClient` / `PracticeAiController`）。

## 怎么用

1. 先在**本插件的设置**里填 DeepSeek API Key（可选模型，默认 `deepseek-flash`）。
   插件**不会**把 Key 写进插件存储，它由宿主的设置项保管。
2. 打开「AI 答疑」页：选一道内置示例题，或直接在题干框里粘贴自己的题目。
3. 点「**问 AI**」——这就是安卓端那颗悬浮球的点击动作。
4. 回答出来后可以看到讲解正文；打开「显示思考过程」能回看模型的推理；底部显示本次 token 用量。
5. 想接着问就填「继续追问」再发送，最多保留 8 轮（再长对单题讲解没意义，还白烧 token）。

## 与安卓端一致的地方

- **端点与模型**：固定 DeepSeek，模型 `deepseek-flash` / `deepseek-v4-pro`，不允许手填端点。
- **系统提示词**：与 `AiChatClient.SYSTEM_PROMPT` 原文一致（先结论、再分点、300 字以内、不寒暄）。
- **提问模板**：题型 / 章节 / 题干 / 选项 / 正确答案 / 我的作答，答错时要求"重点分析为什么会选错"。
- **历史上限**：8 轮；换题会清空历史与用量（上一题的问答对下一题是噪声）。
- **思考过程与用量**：`reasoning_content` 与 `usage` 都带回来展示。

## 平台上做不到、因此改了的地方

| 安卓端 | 插件里的做法 | 原因 |
| --- | --- | --- |
| 液态玻璃悬浮球（可拖拽、贴边吸附） | 页面上的「问 AI」按钮 | 宿主 54 个能力里**没有悬浮窗 / overlay**，原生组件也没有拖拽与绝对定位 |
| 悬浮球外圈的生成中进度环 | 按钮变成「正在生成…」并禁用 | 同上 |
| SSE 流式逐字上屏 | 一次性返回整包（`stream: false`） | `network.request` 不支持流式 |
| 应用内设置页填写 Key（AndroidKeyStore 加密） | 宿主设置项（`contributes.settings`） | 平台要求凭据不得写进插件存储，设置项由宿主保管更安全 |
| 端点连通性探测 | 只保留「获取可用模型」 | 探测用的极小 token 请求价值不大，模型列表已经能验证 Key 是否可用 |

## 权限与网络

- `permissions`: `network`
- 网络范围：`https://api.deepseek.com/chat/completions`（POST）、`https://api.deepseek.com/models`（GET）
- Key 只出现在 `Authorization: Bearer …` 请求头里，不会写日志、不会落盘
- **未配置 Key 时一个请求都不发**，只在页面上提示去设置里填写

## 验收状态

- 离线：13 项夹具通过（含未配置 Key 不发请求、讲解解析、用量、追问、换题清历史、模型列表）
- 变异验证：去掉 `Bearer ` 前缀后测试立刻失败 —— 说明请求头这条路径真的被测到了
- **真实设备与 DeepSeek 线上验收待进行**（离线样本用的是合成响应，不是真实模型输出）

## 维护

ID `org.elisoar.study.ai-tutor`，许可证 GPL-3.0-only。提交上架前请确认该 ID 未被占用。

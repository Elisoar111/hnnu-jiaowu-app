package com.hnnujw.course.review

/**
 * DeepSeek 端点与模型常量。
 *
 * 2026-10 用户决定：**AI 答疑只支持 DeepSeek 官方 API**。上一版是「任意 OpenAI 兼容
 * 端点 + 多套档案」，现在整条链路收回单端点 —— 于是上一版为多服务商而写的东西
 * （`AiProvider` 预置表、`normalize` 补 `/v1`、`validate` 的四类 `UrlCheck`）
 * 在本项目里**已经没有真实效果**，一并删除：
 *
 *  - `normalize` 存在的唯一理由是"有的厂商要 `/v1`、有的自带路径"；只剩 DeepSeek 时
 *    官方文档给的 base_url 就是 `https://api.deepseek.com`，路径直接拼
 *    `/chat/completions` 即可，多一层改写只会多一个出错点。
 *  - `validate` 是为了挡用户手填的坏地址；地址不再由用户填，就没有可挡的东西。
 *  - 环回明文放行（`http://127.0.0.1:…`）当初是给本地推理服务（Ollama / LM Studio）
 *    开的口子，现在生产路径用不到 —— 但**单测仍要它**：MockWebServer 只监听
 *    127.0.0.1。所以 [chatUrl] / [modelsUrl] 保留 `baseUrl` 形参供测试注入，
 *    生产调用一律走默认值 [BASE_URL]。
 *
 * 常量取自官方文档 <https://api-docs.deepseek.com/zh-cn/>（2026-10 核对）：
 * base_url 为 `https://api.deepseek.com`，model 取值 `deepseek-flash` 与
 * `deepseek-v4-pro`。文档脚注明确：旧名 `deepseek-v4-flash` / `deepseek-chat` 等
 * **对应模型已下线**，请求会被转到 DeepSeek-V4.1-Flash 并按 Flash 计费 ——
 * 因此老配置里的旧模型名必须改写，见 [AiConfigRules.normalizeModel]。
 */
object AiEndpoint {

    /** 官方 OpenAI 兼容 base_url。**唯一允许的端点**，用户不可改。 */
    const val BASE_URL = "https://api.deepseek.com"

    /** 默认模型（文档脚注点名推荐用的那个）。 */
    const val DEFAULT_MODEL = "deepseek-flash"

    /**
     * 模型芯片的预置取值。
     *
     * 与上一版不同，这里**不再按端点给兜底清单**（那版要区分"给 OpenAI 用户列
     * deepseek-* 就是假信息"）—— 端点只剩一个，预置即文档原文。
     * 模型名会变（刚刚就变过一次），所以设置页仍保留「获取可用模型」按用户自己的
     * Key 拉 `/models` 覆盖这份预置，以及手填入口。
     */
    val MODELS: List<String> = listOf("deepseek-flash", "deepseek-v4-pro")

    /** 官方文档地址（设置页「查看官方文档」入口）。 */
    const val DOC_URL = "https://api-docs.deepseek.com/zh-cn/"

    /** API Key 申请页（设置页「申请 API Key」入口）。 */
    const val KEY_URL = "https://platform.deepseek.com/api_keys"

    /**
     * chat/completions 端点。
     *
     * `baseUrl` 只供单测注入 MockWebServer 地址；生产调用不传，走 [BASE_URL]。
     */
    fun chatUrl(baseUrl: String = BASE_URL): String = "${baseUrl.trimEnd('/')}/chat/completions"

    /** 模型列表端点（OpenAI 兼容 `GET /models`）。同上，`baseUrl` 只为单测。 */
    fun modelsUrl(baseUrl: String = BASE_URL): String = "${baseUrl.trimEnd('/')}/models"
}

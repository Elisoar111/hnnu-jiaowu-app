package com.hnnujw.course.review.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hnnujw.course.review.AiChatClient
import com.hnnujw.course.review.AiConfig
import com.hnnujw.course.review.AiConfigRules
import com.hnnujw.course.review.AiConfigStore
import com.hnnujw.course.review.AiEndpoint
import com.hnnujw.course.ui.screen.GlassFilterChip
import com.hnnujw.course.ui.system.GlassLoadingIndicator
import com.hnnujw.course.ui.system.GlassPageScaffold
import com.hnnujw.course.ui.system.GlassTextField
import com.hnnujw.course.ui.system.GlassToaster
import com.hnnujw.course.ui.system.MarkdownText
import com.hnnujw.course.ui.system.SystemDialog
import com.hnnujw.course.ui.system.SystemPrimaryButton
import com.hnnujw.course.ui.system.SystemSecondaryButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 超时档位（秒）。要落在 [AiConfigRules.TIMEOUT_MIN]..[AiConfigRules.TIMEOUT_MAX] 内。 */
private val TIMEOUT_CHOICES = listOf(30, 60, 120, 300, 600)

/** 额外重试次数档位。 */
private val RETRY_CHOICES = listOf(0, 1, 2, 3, 5)

/**
 * AI 答疑设置页：DeepSeek 官方 API 的 Key / 模型 / 网络参数。
 *
 * 2026-10 用户要求「只支持 DeepSeek，档案删除」，因此相比上一版：
 *  - **服务商选择、API 地址输入、档案名、档案切换、删除档案、主备降级全部删除** ——
 *    端点恒为 [AiEndpoint.BASE_URL]，没有第二个可选项，那些控件点了也没有第二种结果；
 *  - 页面从「五区（档案 / 端点 / 网络 / 保存 / 帮助）」收成**一张配置卡**：
 *    API Key + 模型 + 超时 + 重试；
 *  - 保留「获取可用模型」：模型名会变（官方刚下线过一批旧名），留着它用户能自己
 *    拉到真实清单，不必等应用更新；
 *  - 保留「测试连接」与延迟/回复回报，保留「清除配置」。
 *
 * 隐私红线（一条不放松）：
 *  - Key 走 [AiConfigStore] 加密落盘（AndroidKeyStore + AES/GCM）；
 *  - 界面只显示"是否已保存"，**绝不回显明文**；
 *  - 已保存的 Key 不会被读出来填进输入框（输入框里只有本次新输入的内容）。
 */
@Composable
fun AiSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var loaded by remember { mutableStateOf(false) }
    /** 已保存的 Key 明文（仅内存中，用于"留空则沿用"）。**绝不显示在界面上。** */
    var savedKey by remember { mutableStateOf("") }
    /** 编辑器草稿里的新 Key；空 = 不改动已保存的那条。 */
    var draftKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(AiEndpoint.DEFAULT_MODEL) }
    var timeoutSeconds by remember { mutableStateOf(AiConfigRules.DEFAULT_TIMEOUT_SECONDS) }
    var retries by remember { mutableStateOf(AiConfigRules.DEFAULT_RETRIES) }

    /** 模型芯片清单：初值是预置两项，「获取可用模型」成功后整体替换。 */
    var models by remember { mutableStateOf(AiEndpoint.MODELS) }
    var fetching by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testReport by remember { mutableStateOf<String?>(null) }
    var pendingClear by remember { mutableStateOf(false) }

    val existingHasKey = savedKey.isNotBlank()
    val keyOk = draftKey.isNotBlank() || existingHasKey

    LaunchedEffect(Unit) {
        // 🔴 runCatching 必须包：AndroidKeyStore 读取在真机上可能抛（密钥随卸载 /
        // 恢复备份失效等），这里又在协程里，裸抛就是崩进程。失败按"没有配置"处理。
        val config = withContext(Dispatchers.IO) {
            runCatching { AiConfigStore.load(context) }.getOrNull()
        }
        if (config != null) {
            savedKey = config.apiKey
            model = config.model
            timeoutSeconds = config.timeoutSeconds
            retries = config.retries
        }
        loaded = true
    }

    GlassPageScaffold(title = "AI 答疑设置", onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = padding.calculateTopPadding() + 8.dp,
                    // 🔴 底部必须吃满 scaffold 的 bottom inset：只 pad top 的话，
                    // 末位内容会被系统导航条压住，表现为"底部内容被截断"。
                    bottom = padding.calculateBottomPadding() + 24.dp,
                    start = 16.dp,
                    end = 16.dp
                )
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!loaded) {
                Card { GlassLoadingIndicator(size = 22.dp) }
                return@Column
            }

            // ── ① 服务与端点（不可改，故只作说明 + 快捷入口） ──────────────
            Card {
                Text(
                    text = "服务商：DeepSeek 官方 API",
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "请求地址：${AiEndpoint.chatUrl()}\n只支持这一家，无需填写地址。",
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SecondaryAction(
                        text = "查看官方文档",
                        onClick = { openUrl(context, AiEndpoint.DOC_URL) },
                        modifier = Modifier.weight(1f)
                    )
                    SecondaryAction(
                        text = "申请 API Key",
                        onClick = { openUrl(context, AiEndpoint.KEY_URL) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // ── ② API Key ────────────────────────────────────────────────
            Card {
                Text(
                    text = "API Key",
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(6.dp))
                GlassTextField(
                    value = draftKey,
                    onValueChange = {
                        draftKey = it
                        testReport = null
                    },
                    placeholder = if (existingHasKey) "已保存（留空则不改动）" else "sk-…",
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (existingHasKey) {
                        "已保存一个 Key（不回显）。填新值会覆盖它，留空则保持原样。"
                    } else {
                        "Key 加密后只存在本机，不会上传，也不会回显。"
                    },
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ── ③ 模型 ───────────────────────────────────────────────────
            Card {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "模型",
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (fetching) {
                        GlassLoadingIndicator(size = 20.dp)
                    } else {
                        // 必须具名传 onClick：SecondaryAction 第二位置参数才是 onClick。
                        SecondaryAction(
                            text = "获取可用模型",
                            enabled = keyOk,
                            onClick = {
                                fetching = true
                                testReport = null
                                scope.launch {
                                    val probe = draftConfig(
                                        key = draftKey.ifBlank { savedKey },
                                        model = model,
                                        timeoutSeconds = timeoutSeconds,
                                        retries = retries
                                    )
                                    val result = AiChatClient.listModels(probe, timeoutSeconds)
                                    fetching = false
                                    when (result) {
                                        is AiChatClient.Result.Success -> {
                                            val ids = result.text.lines().filter { it.isNotBlank() }
                                            if (ids.isEmpty()) {
                                                GlassToaster.show("服务端未返回可用的模型")
                                            } else {
                                                models = ids
                                                GlassToaster.show(
                                                    if (model in ids) {
                                                        "已获取 ${ids.size} 个模型"
                                                    } else {
                                                        // 预置名过期时最该说的话：别让用户以为配好了。
                                                        "已获取 ${ids.size} 个模型，当前模型不在其中"
                                                    }
                                                )
                                            }
                                        }
                                        is AiChatClient.Result.Failure ->
                                            GlassToaster.show("获取失败：${result.message}")
                                    }
                                }
                            }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    models.forEach { name ->
                        GlassFilterChip(
                            label = name,
                            selected = name == model,
                            onClick = { model = name }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                // 仍保留手填：服务端清单可能取不到（网络/权限），模型名也可能刚改。
                GlassTextField(
                    value = model,
                    onValueChange = {
                        model = it
                        testReport = null
                    },
                    placeholder = AiEndpoint.DEFAULT_MODEL,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "点上方芯片选择，或直接填模型名。官方文档目前给出的取值是 " +
                        AiEndpoint.MODELS.joinToString(" / ") { "`$it`" } + "。",
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ── ④ 网络参数 ───────────────────────────────────────────────
            Card {
                Text(
                    text = "超时（秒）",
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TIMEOUT_CHOICES.forEach { choice ->
                        GlassFilterChip(
                            label = "$choice",
                            selected = choice == timeoutSeconds,
                            onClick = { timeoutSeconds = choice }
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "失败重试次数",
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    RETRY_CHOICES.forEach { choice ->
                        GlassFilterChip(
                            label = if (choice == 0) "不重试" else "$choice",
                            selected = choice == retries,
                            onClick = { retries = choice }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "超时按「两次收到数据之间的间隔」算，流式回答时只要还在吐字就不会超时。" +
                        "重试只发生在**一个字都还没吐出来**的时候，避免两段半截回答拼在一起。",
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ── ⑤ 保存 / 测试 / 清除 ─────────────────────────────────────
            Card {
                ConfirmButton(
                    text = "保存",
                    enabled = keyOk,
                    onClick = {
                        val config = draftConfig(
                            key = draftKey.ifBlank { savedKey },
                            model = model,
                            timeoutSeconds = timeoutSeconds,
                            retries = retries
                        )
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { AiConfigStore.save(context, config) }
                            if (!ok) {
                                // 加密失败（keystore 不可用/损坏）：留在输入框里让用户重试，不崩。
                                GlassToaster.show("保存失败：本机加密服务不可用")
                                return@launch
                            }
                            // 保存后回读一次：让界面上的值就是"下次真正会用的值"
                            // （模型空白/超时越界都会被存储层归一化，不回读就会显示假值）。
                            val reloaded = withContext(Dispatchers.IO) {
                                runCatching { AiConfigStore.load(context) }.getOrNull()
                            }
                            if (reloaded != null) {
                                savedKey = reloaded.apiKey
                                model = reloaded.model
                                timeoutSeconds = reloaded.timeoutSeconds
                                retries = reloaded.retries
                            }
                            draftKey = ""
                            GlassToaster.show("已保存")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))

                if (testing) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GlassLoadingIndicator(size = 20.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "正在测试连接…",
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    SecondaryAction(
                        text = "测试连接",
                        enabled = keyOk,
                        onClick = {
                            // 防重复点击：置位后按钮分支切到 loading，点不到第二次。
                            testing = true
                            testReport = null
                            scope.launch {
                                val probe = draftConfig(
                                    key = draftKey.ifBlank { savedKey },
                                    model = model,
                                    timeoutSeconds = timeoutSeconds,
                                    retries = retries
                                )
                                val result = AiChatClient.testConnection(probe, timeoutSeconds)
                                testing = false
                                testReport = if (result.ok) {
                                    buildString {
                                        append("连接正常 · ${result.latencyMs} ms")
                                        if (result.reply.isNotBlank()) append("\n模型回复：${result.reply}")
                                    }
                                } else {
                                    result.error
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                testReport?.let { report ->
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = report,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = if (report.startsWith("连接正常")) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                }

                if (existingHasKey) {
                    Spacer(Modifier.height(10.dp))
                    SecondaryAction(
                        text = "清除配置",
                        enabled = true,
                        onClick = { pendingClear = true },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // ── ⑥ 关于隐私 ───────────────────────────────────────────────
            Card {
                Text(
                    text = "关于隐私",
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(6.dp))
                MarkdownText(
                    text = "- 题目**只在你点「问 AI」时**才会发送到 DeepSeek。\n" +
                        "- 你的 Key 只在本机加密保存，不经过本应用的服务器。\n" +
                        "- 本应用不收集、不上报任何使用数据。",
                    fontSize = 13.sp,
                    lineHeight = 21.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (pendingClear) {
            SystemDialog(
                onDismissRequest = { pendingClear = false },
                title = {
                    Text(
                        text = "清除 AI 配置",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                confirmButton = {
                    SystemPrimaryButton(
                        text = "清除",
                        onClick = {
                            pendingClear = false
                            scope.launch {
                                withContext(Dispatchers.IO) { AiConfigStore.clear(context) }
                                savedKey = ""
                                draftKey = ""
                                model = AiEndpoint.DEFAULT_MODEL
                                timeoutSeconds = AiConfigRules.DEFAULT_TIMEOUT_SECONDS
                                retries = AiConfigRules.DEFAULT_RETRIES
                                models = AiEndpoint.MODELS
                                testReport = null
                                GlassToaster.show("已清除 AI 配置")
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                dismissButton = {
                    SystemSecondaryButton(
                        text = "取消",
                        onClick = { pendingClear = false },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            ) {
                Text(
                    text = "将删除本机保存的 API Key 与模型、超时设置。此操作不可撤销。",
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 用编辑器草稿拼一份 [AiConfig] 供「获取可用模型 / 测试连接 / 保存」使用。
 *
 * [key] 由调用方算好（草稿里的新 Key 优先，留空则沿用已保存的那条）——
 * 与保存逻辑同一口径：界面不回显明文，所以"留空"的语义是"沿用已保存的"，
 * 而不是"没有 Key"。抽成一处是为了让这个回落口径只有一处。
 *
 * [AiConfig.baseUrl] 走默认值 [AiEndpoint.BASE_URL]：端点不可配。
 */
private fun draftConfig(key: String, model: String, timeoutSeconds: Int, retries: Int): AiConfig =
    AiConfig(
        apiKey = key,
        model = model.trim().ifEmpty { AiEndpoint.DEFAULT_MODEL },
        timeoutSeconds = timeoutSeconds,
        retries = retries
    )

/** 用系统浏览器打开链接。没有浏览器时给一句提示，不让 ActivityNotFoundException 崩掉。 */
private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }.onFailure {
        GlassToaster.show("没有可用的浏览器，请手动访问：$url")
    }
}

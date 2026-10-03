package com.hnnujw.course.review.ui

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hnnujw.course.review.AiChatClient
import com.hnnujw.course.review.AiKeyStore
import com.hnnujw.course.ui.screen.GlassFilterChip
import com.hnnujw.course.ui.system.GlassLoadingIndicator
import com.hnnujw.course.ui.system.GlassPageScaffold
import com.hnnujw.course.ui.system.GlassTextField
import com.hnnujw.course.ui.system.GlassToaster
import com.hnnujw.course.ui.system.MarkdownText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI 设置页：填 DeepSeek API Key 与模型名。
 *
 * Key 用 [AiKeyStore] 加密后落盘（AndroidKeyStore + AES/GCM），
 * 界面上只显示是否已配置，**不回显明文**。
 */
@Composable
fun AiSettingsScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var keyInput by remember { mutableStateOf("") }
    // hasApiKey 内部走 apiKey() 解密，要碰 AndroidKeyStore —— remember{} 是主线程
    // 同步执行的，进页首帧会卡一下。所以只给保守的空初值，再上 IO 线程读回来。
    var modelInput by remember { mutableStateOf(AiKeyStore.DEFAULT_MODEL) }
    var hasKey by remember { mutableStateOf(false) }
    // 读回来之前不能拿 hasKey=false 去渲染状态文案 —— 已配置的用户会先看到
    // 一闪而过的「尚未配置」，反而像 Key 丢了。keyLoaded 期间显示中性文案。
    var keyLoaded by remember { mutableStateOf(false) }
    // 候选模型：先给内置候选，点「获取模型列表」再覆盖成服务端返回的。
    var models by remember { mutableStateOf(AiChatClient.FALLBACK_MODELS) }
    var fetching by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) {
            AiKeyStore.hasApiKey(context) to AiKeyStore.model(context)
        }
        hasKey = loaded.first
        modelInput = loaded.second
        keyLoaded = true
    }

    GlassPageScaffold(title = "AI 答疑设置", onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = padding.calculateTopPadding() + 8.dp,
                    start = 16.dp,
                    end = 16.dp
                )
                .verticalScroll(rememberScrollState())
        ) {
            Card {
                Text(
                    text = "状态",
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = when {
                        !keyLoaded -> "正在读取…"
                        hasKey -> "已配置 API Key"
                        else -> "尚未配置"
                    },
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = if (keyLoaded && hasKey) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            Spacer(Modifier.height(12.dp))

            Card {
                GlassTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    placeholder = "sk-…",
                    // Key 是密钥，输入时必须遮蔽 —— 玻璃件没有 label 位，
                    // 原来 OutlinedTextField 的 label="DeepSeek API Key" 改由 placeholder 承担。
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "留空并保存可清除已配置的 Key。Key 加密后只存在本机，不会上传。",
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))

                // —— 模型列表（可点选）+ 自定义输入并存。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "选择模型",
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (fetching) {
                        GlassLoadingIndicator(size = 20.dp)
                    } else {
                        // 必须具名传 onClick：SecondaryAction 最后一个参数是 modifier，
                        // 尾随 lambda 会绑到 modifier 上而不是 onClick。
                        SecondaryAction("获取模型列表", enabled = hasKey, onClick = {
                            val key = AiKeyStore.apiKey(context)
                            if (key.isNullOrBlank()) {
                                GlassToaster.show("请先保存 API Key")
                            } else {
                                fetching = true
                                scope.launch {
                                    val result = AiChatClient.listModels(key)
                                    fetching = false
                                    when (result) {
                                        is AiChatClient.Result.Success -> {
                                            val ids = result.text.lines().filter { it.isNotBlank() }
                                            if (ids.isNotEmpty()) {
                                                models = ids
                                                GlassToaster.show("已获取 ${ids.size} 个模型")
                                            } else {
                                                models = AiChatClient.FALLBACK_MODELS
                                                GlassToaster.show("无法从服务端获取，已列出常用模型")
                                            }
                                        }
                                        is AiChatClient.Result.Failure -> {
                                            // 端点不可用/结构不同都不能让用户卡死 —— 退化到内置候选。
                                            models = AiChatClient.FALLBACK_MODELS
                                            GlassToaster.show("无法从服务端获取，已列出常用模型")
                                        }
                                    }
                                }
                            }
                        })
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
                            selected = name == modelInput,
                            onClick = { modelInput = name }
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                // 仍保留手填能力：服务端模型名可能变，列表未必覆盖。
                GlassTextField(
                    value = modelInput,
                    onValueChange = { modelInput = it },
                    placeholder = AiKeyStore.DEFAULT_MODEL,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "点上方芯片选择，或在输入框手填模型名。",
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(14.dp))
                ConfirmButton(
                    text = "保存",
                    enabled = true,
                    onClick = {
                        if (keyInput.isBlank()) {
                            AiKeyStore.removeApiKey(context)
                            hasKey = false
                            keyInput = ""
                            GlassToaster.show("已清除 API Key")
                        } else if (AiKeyStore.saveApiKey(context, keyInput)) {
                            hasKey = true
                            keyInput = ""
                            GlassToaster.show("已保存")
                        } else {
                            // 加密失败（keystore 不可用/损坏）：留在输入框里让用户重试，不崩。
                            GlassToaster.show("保存失败：本机加密服务不可用")
                        }
                        AiKeyStore.setModel(context, modelInput)
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
                        enabled = hasKey,
                        onClick = {
                            val key = AiKeyStore.apiKey(context)
                            if (key.isNullOrBlank()) {
                                GlassToaster.show("请先保存 API Key")
                            } else {
                                // 防重复点击：置位后按钮分支切到 loading，点不到第二次。
                                testing = true
                                scope.launch {
                                    val result = AiChatClient.testConnection(key, modelInput)
                                    testing = false
                                    when (result) {
                                        is AiChatClient.Result.Success ->
                                            GlassToaster.show("连接正常（模型 ${result.text}）")
                                        is AiChatClient.Result.Failure ->
                                            GlassToaster.show(result.message)
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Card {
                Text(
                    text = "怎么拿 Key",
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(6.dp))
                MarkdownText(
                    text = "1. 打开 platform.deepseek.com，注册并登录。\n" +
                        "2. 进入「API keys」，新建一个密钥并**保存好**（只显示一次）。\n" +
                        "3. 在「充值」里给你的账户充值（DeepSeek 按 token 计费，没有免费额度）。\n" +
                        "4. 把 `sk-` 开头的密钥填到上面。",
                    fontSize = 13.sp,
                    lineHeight = 21.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(12.dp))

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

            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 题库格式说明页。 */
@Composable
fun BankFormatHelpScreen(onBack: () -> Unit) {
    GlassPageScaffold(title = "题库格式说明", onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = padding.calculateTopPadding() + 8.dp,
                    start = 16.dp,
                    end = 16.dp
                )
                .verticalScroll(rememberScrollState())
        ) {
            Card {
                Text(
                    text = "JSON 题库结构",
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(8.dp))
                MarkdownText(
                    text = BANK_FORMAT_SAMPLE,
                    fontSize = 13.sp,
                    lineHeight = 21.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(Modifier.height(12.dp))

            Card {
                Text(
                    text = "字段说明",
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(8.dp))
                MarkdownText(
                    text = "- `id`：题目唯一标识，同一题库内不能重复。\n" +
                        "- `type`：`single` 单选 / `multi` 多选。也可写「单选」「多选」。\n" +
                        "- `stem`：题干。\n" +
                        "- `options`：选项数组，如 `[\"甲\", \"乙\"]`；也支持 `\"甲|乙\"` 这种写法。\n" +
                        "- `answer`：正确答案字母，如 `\"A\"`、多选 `\"ABD\"`。\n" +
                        "- `chapter`：章节，用于按章节练习。\n" +
                        "- `explanation`：解析，答完题会显示。\n" +
                        "- `source` / `tags`：可选，出处与标签。\n\n" +
                        "所有中英文键名都认（`stem`/`题干`、`answer`/`答案` 等）。",
                    fontSize = 13.sp,
                    lineHeight = 21.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(12.dp))

            Card {
                Text(
                    text = "导入规则",
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(8.dp))
                MarkdownText(
                    text = "- 顶层可以是 `{\"name\":..., \"questions\":[...]}`，也可以直接是题目数组。\n" +
                        "- **单选必须只有一个答案，多选至少两个**；不合规的题会被跳过并在导入后提示跳过条数。\n" +
                        "- 选项数量 2–8 个，答案不能超出选项范围。\n" +
                        "- 同名题库重复导入会**覆盖**该库原来的题目与答题记录。",
                    fontSize = 13.sp,
                    lineHeight = 21.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * 题库格式样例。用 [String] 常量而不是 `const val` + `trimIndent()` ——
 * `trimIndent()` 是运行期函数，不能出现在 const 初始化器里。
 */
private val BANK_FORMAT_SAMPLE = """
```json
{
  "name": "我的题库",
  "questions": [
    {
      "id": "1",
      "type": "single",
      "stem": "中国近现代史开端是？",
      "options": ["1840年", "1919年", "1949年"],
      "answer": "A",
      "chapter": "绪论",
      "explanation": "1840年鸦片战争是中国近代史的开端。",
      "tags": ["时间轴"]
    },
    {
      "id": "2",
      "type": "multi",
      "stem": "以下哪些属于抗日战争时期？",
      "options": ["九一八事变", "西安事变", "七七事变"],
      "answer": "AC",
      "chapter": "抗日战争"
    }
  ]
}
```
""".trimIndent()

package com.hnnujw.course.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.hnnujw.course.ui.system.MarkdownText
import com.hnnujw.course.ykt.YktLostConfirm

/**
 * 挂失 / 解挂的**二次确认**弹窗（两段式）。
 *
 * ## 为什么是两段式而不是一个确认框
 *
 * 挂失不可逆（卡立即冻结，没有自助撤销）。单个「确定/取消」确认框在真机上
 * 很容易被手滑连点穿透——按钮位置固定，"取消"和"确定"只差几毫米。
 * 所以这里拆成两段，**语义递进**：
 *
 * 1. **告知**：把后果写清楚（冻结、不可撤销、补卡要去卡务中心），
 *    按钮是「我已了解，继续」，只是翻页，不执行任何操作。
 * 2. **验证**：要求用户**逐字输入** [YktLostConfirm.phraseFor] 指定的文字
 *    （`确认挂失` / `确认解挂`），输入完全匹配才点亮确认按钮。
 *
 * 输入的必须是文字而不是"再点一次"：一来强制发生一次真实键盘输入
 * （误触/自动化都过不了），二来用户为了打字必须读到那句话，
 * 而那句话本身就在陈述后果。
 *
 * ## 密码为什么放在这里一起收
 *
 * 服务端是否需要密码由 `frontConfig.lockFlag` 决定，而那是注入在 HTML 里的配置，
 * 客户端**读不到**（详见 [com.hnnujw.course.ykt.YktClient.lostCard]）。
 * 因此一律要求输入；学校没配要密码时服务端会忽略该字段，不影响结果。
 *
 * 密码**只存在于本弹窗的局部状态**，随弹窗销毁而消失，不写任何存储。
 *
 * @param isLost true = 挂失，false = 解挂
 * @param cardNo 卡号（展示用，让用户确认操作的是哪张卡）
 * @param submitting 正在提交（禁用按钮，防止连点重复提交）
 * @param onDismiss 取消
 * @param onConfirm 通过全部校验后回调 (明文密码)
 */
@Composable
fun YktLostConfirmDialog(
    isLost: Boolean,
    cardNo: String,
    submitting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (password: String) -> Unit,
) {
    // 第 1 段：告知；第 2 段：输入验证文字 + 密码
    var stage by remember { mutableStateOf(1) }
    var phraseInput by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    val required = YktLostConfirm.phraseFor(isLost)
    val phraseOk = YktLostConfirm.matches(phraseInput, required)
    val canSubmit = phraseOk && password.isNotEmpty() && !submitting

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (isLost) "确认挂失校园卡？" else "确认解挂校园卡？",
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        text = {
            Column {
                if (isLost) {
                    MarkdownText(
                        text = "挂失后这张卡会立即冻结，不能再消费、不能进宿舍门禁，" +
                            "并且**无法在本应用里自行撤销**。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "如果真的丢了卡，挂失是正确的做法，可以保住余额；" +
                            "补办新卡请到学校卡务中心。如果卡还在你手上、只是想找回，" +
                            "请先点「取消」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = "解挂后这张卡会恢复正常，可以继续消费。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "解挂不会重置卡内余额，也不会补发新卡。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (cardNo.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "卡号：$cardNo",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (stage == 2) {
                    Spacer(Modifier.height(14.dp))
                    // 把要输入的句子做成**可独立阅读的引文块**：用户是"抄写一句话"，
                    // 而不是"照着一个词填框"。句子本身是第一人称承诺，读完即明后果。
                    Text(
                        text = "请在下面的输入框里，手动打出这句话：",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            text = required,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = phraseInput,
                        onValueChange = { phraseInput = it },
                        singleLine = true,
                        placeholder = { Text(required) },
                        modifier = Modifier.fillMaxWidth(),
                        // 已正确则给出视觉肯定，减少"我到底输对没有"的焦虑
                        isError = phraseInput.isNotEmpty() && !phraseOk,
                        supportingText = if (phraseInput.isNotEmpty() && !phraseOk) {
                            { Text("与上面那句话不完全一致，请逐字照抄（标点、空格都会被忽略）。") }
                        } else {
                            null
                        },
                    )

                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "请输入一卡通查询密码（不保存、不记录）：",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "本应用不会保存你的密码：它只用于这一次操作，弹窗关闭即丢弃。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            if (stage == 1) {
                TextButton(onClick = { stage = 2 }) {
                    Text("我已了解，继续")
                }
            } else {
                TextButton(
                    onClick = { onConfirm(password) },
                    enabled = canSubmit,
                ) {
                    Text(if (submitting) "提交中…" else if (isLost) "确认挂失" else "确认解挂")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !submitting) {
                Text("取消")
            }
        },
    )
}

package com.tyust.course.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tyust.course.manager.UserManager
import com.tyust.course.secondclass.SecondClassException
import com.tyust.course.secondclass.SecondClassroomStore
import com.tyust.course.ui.system.GlassTextField
import com.tyust.course.ui.system.GlassToaster
import com.tyust.course.ui.system.SystemDialog
import com.tyust.course.ui.system.SystemPrimaryButton
import com.tyust.course.ui.system.SystemSecondaryButton
import com.tyust.course.ui.system.SystemStatusBadge
import com.tyust.course.ui.system.SystemTone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 第二课堂登录入口。设置页与"二课"页共用一个宿主，避免两处各写一套登录状态。
 *
 * 账号固定是教务侧的学号（两套系统里同一个学号），所以只让用户填密码；
 * 学号以只读形式展示，填错账号的可能性被排除掉。
 */
@Composable
fun SecondClassLoginHost(
    visible: Boolean,
    onDismiss: () -> Unit,
    onLoggedIn: () -> Unit,
) {
    if (!visible) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val userManager = remember { UserManager.getInstance() }
    val school = userManager.currentSchool
    val accountKey = userManager.currentAccountKey
    val studentId = remember(accountKey) { SecondClassroomStore.academicStudentId() }

    var password by remember(accountKey) {
        // 预填学校统一发的初始密码（学号 + &Zhtx）：绝大多数同学不用改就能登录。
        // 改过密码的账号直接在输入框里覆盖即可，所以这不是"锁死"的默认值。
        mutableStateOf(SecondClassroomStore.defaultPassword(studentId))
    }
    var visiblePassword by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var passwordRejected by remember { mutableStateOf(false) }
    var hasSavedCredential by remember(accountKey) {
        mutableStateOf(SecondClassroomStore.hasPassword(context, accountKey))
    }
    var hasToken by remember(accountKey) {
        mutableStateOf(SecondClassroomStore.token(context, accountKey).isNotBlank())
    }
    // 存过密码或已登录后，弹窗不再出现密码输入框，只保留"删除 + 取消"
    val savedMode = hasSavedCredential || hasToken

    LaunchedEffect(accountKey) { error = "" }

    fun submit() {
        val client = SecondClassroomStore.clientFor(school) ?: run {
            error = "当前学校还没有配置第二课堂站点"
            return
        }
        if (studentId.isBlank()) {
            error = "没有取到教务学号，请先重新登录教务系统"
            return
        }
        busy = true
        error = ""
        passwordRejected = false
        scope.launch {
            try {
                val token = withContext(Dispatchers.IO) { client.login(studentId, password) }
                SecondClassroomStore.saveToken(context, accountKey, token)
                // 存下密码用于 token 过期后自动续期，和教务密码分开存
                SecondClassroomStore.savePassword(context, accountKey, password)
                hasSavedCredential = true
                password = ""
                GlassToaster.show("第二课堂登录成功")
                onLoggedIn()
            } catch (e: Exception) {
                val message = SecondClassroomStore.handleFailure(context, accountKey, e)
                // 密码错是这里最可能的失败：把输入框留成"待修改"状态，
                // 并把预填的初始密码清掉，免得用户对着一个已知不对的值反复点"登录"。
                if (isCredentialRejection(e)) passwordRejected = true
                error = message
                // 只有密码错才清空重填；网络类失败要保留用户已输入的内容
                if (passwordRejected && password == SecondClassroomStore.defaultPassword(studentId)) {
                    password = ""
                }
            } finally {
                busy = false
            }
        }
    }

    SystemDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "第二课堂",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "用教务系统的学号登录成绩单系统",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (savedMode) {
                    SystemSecondaryButton(
                        text = "删除已保存的第二课堂密码",
                        onClick = {
                            SecondClassroomStore.clearAccount(context, accountKey)
                            hasSavedCredential = false
                            hasToken = false
                            password = ""
                            error = ""
                            GlassToaster.show("已删除第二课堂的登录信息")
                            onLoggedIn()
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    SystemPrimaryButton(
                        text = if (busy) "登录中…" else "登录",
                        onClick = { submit() },
                        enabled = !busy && password.isNotEmpty() && studentId.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                SystemSecondaryButton(
                    text = "取消",
                    onClick = onDismiss,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "账号（教务系统学号）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = studentId.ifBlank { "未取到学号，请先登录教务" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    SystemStatusBadge(text = "教务学号", tone = SystemTone.Info)
                }
            }

            if (savedMode) {
                Text(
                    text = if (hasToken) {
                        "已登录第二课堂。登录状态失效时会用已保存的密码自动续期。"
                    } else {
                        "已保存第二课堂密码。登录状态失效时会自动续期。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )
            } else {
                GlassTextField(
                    value = password,
                    onValueChange = { password = it },
                    placeholder = if (passwordRejected) "请重新输入第二课堂密码" else "第二课堂密码",
                    singleLine = true,
                    enabled = !busy,
                    isError = error.isNotBlank(),
                    visualTransformation = if (visiblePassword) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    trailing = {
                        IconButton(onClick = { visiblePassword = !visiblePassword }) {
                            Icon(
                                imageVector = if (visiblePassword) Icons.Outlined.VisibilityOff
                                else Icons.Outlined.Visibility,
                                contentDescription = if (visiblePassword) "隐藏密码" else "显示密码",
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                )

                if (error.isNotBlank()) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = com.tyust.course.ui.theme.SemanticDanger,
                        lineHeight = 18.sp
                    )
                }

                Text(
                    text = if (passwordRejected) {
                        "请修改上面的密码后重新登录。初始密码是「学号 + &Zhtx」" +
                            "（例：20240001&Zhtx）；如果你在第二课堂官网上改过密码，请填改过之后的那个。"
                    } else {
                        "说明：登录框已按学校统一规则预填初始密码（学号 + &Zhtx），" +
                            "改过密码的话直接覆盖即可。第二课堂是独立于教务的系统，密码与教务密码不一定相同；" +
                            "密码经系统密钥库加密后仅保存在本机，用于登录状态失效时自动续期；" +
                            "本应用只做查询，报名与申报请回到官方站点操作。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

/**
 * 是不是"账号或密码不对"这类**凭据被拒**的失败。
 *
 * 站点对密码错回 `20002`，客户端把它翻成 `SecondClassException("…密码不正确")`；
 * 少数网关会直接回 401/403，表现为 `sessionExpired = true`。两种情况都要让用户
 * 重新输密码，而不是只弹一句"登录已失效"让他困惑。
 *
 * 判据刻意只看**文案与类型**，不碰密码本身——异常信息与日志里都不落明文密码。
 */
private fun isCredentialRejection(error: Throwable): Boolean {
    if (error is SecondClassException && error.sessionExpired) return true
    val message = error.message.orEmpty()
    return message.contains("密码") || message.contains("账号")
}

package com.hnnujw.course.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hnnujw.course.manager.UserManager
import com.hnnujw.course.secondclass.SecondClassroomStore
import com.hnnujw.course.ui.system.SystemDialog
import com.hnnujw.course.ui.system.SystemPrimaryButton
import com.hnnujw.course.ui.system.SystemStatusBadge
import com.hnnujw.course.ui.system.SystemTone
import com.hnnujw.course.xuegong.XuegongStore

/**
 * 「账号管理」里的**各系统密码管理**面板。
 *
 * ## 为什么是四个系统各自一条，而不是原来的"教务账号卡"
 * 这个应用同时接了四套彼此独立的登录态：
 *
 * - 教务（正方）：口令由 [UserManager.savePasswordLogin] 落盘，键是账号键；
 * - 第二课堂（ekta）：口令由 [SecondClassroomStore.savePassword] 落盘，默认值 = 学号 + `&Zhtx`；
 * - 学工（xg）：口令由 [XuegongStore.savePassword] 落盘，用于静默登录；
 * - 一卡通（新开普）：**不存口令**（见 YktStore 的说明，它的 loginType 只有 sso），
 *   只能走统一身份认证换票，所以这里只给一个「去登录」入口。
 *
 * 四套账号其实是同一个学号，但**密码是否已经保存、保存到哪套系统**完全不同。
 * 原来一张"教务账号卡"只显示一个笼统的"已存密码"，用户根本看不出是哪套系统的密码，
 * 也改不了二课/学工的密码 —— 这个面板就是解决这件事。
 *
 * ## 安全边界
 * 面板只做三件事：显示"有没有存"、让用户**显式**输入新口令、允许清除。
 * 已保存的口令**不回显**（连打码都不显示），因为一旦能读出来，就等于在 UI 上开了个
 * 明文口令查看器；而"我到底存没存过"这个问题，用状态徽标回答就够了。
 */
@Composable
fun SystemCredentialsPanel(
    studentId: String,
    modifier: Modifier = Modifier,
    onOpenYktLogin: () -> Unit = {},
    onChanged: () -> Unit = {},
) {
    val context = LocalContext.current
    val accountKey = UserManager.getInstance().currentAccountStorageKey
    // 改完密码后需要让面板重新读一次各 Store：Store 都是普通 object + SharedPreferences，
    // 不是 Compose 状态，所以用一个自增的 tick 当"失效信号"。
    var tick by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<SystemKind?>(null) }

    val rows = remember(tick, accountKey, studentId) {
        SystemKind.entries.map { kind -> kind.describe(context, accountKey, studentId) }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "各系统密码",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "四套系统各自保存，互不影响；已保存的密码不回显，只能覆盖或清除。",
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        rows.forEach { row ->
            SystemCredentialCard(
                row = row,
                onEditPassword = { editing = row.kind },
                onOpenLogin = onOpenYktLogin,
                onClearPassword = {
                    row.kind.clear(context, accountKey)
                    tick += 1
                    onChanged()
                }
            )
        }
    }

    val target = editing
    if (target != null) {
        PasswordEditorDialog(
            kind = target,
            accountLabel = studentId.ifBlank { "当前账号" },
            onDismiss = { editing = null },
            onSave = { password ->
                target.save(context, accountKey, password)
                editing = null
                tick += 1
                onChanged()
            }
        )
    }
}

/** 一套系统的密码现状。`hasPassword` 只表示"存过"，不代表一定还能登录成功。 */
internal data class SystemCredentialRow(
    val kind: SystemKind,
    val account: String,
    val hasPassword: Boolean,
    val note: String,
)

/**
 * 四套系统。`defaultHint` 是给用户看的"这套系统的密码是什么"，
 * 因为二课/学工的初始密码是学号加后缀，不说清楚用户会一直以为自己没设过密码。
 */
internal enum class SystemKind(
    val label: String,
    val icon: ImageVector,
    val tint: Color,
    val supportsPassword: Boolean,
    val defaultHint: String,
) {
    Academic("教务系统", Icons.Filled.School, Color(0xFF32ADE6), true, "正方教务的登录密码，改了会影响课程/成绩同步"),
    SecondClass("第二课堂", Icons.Outlined.AccountBalance, Color(0xFFFF9500), true, "默认是「学号 + &Zhtx」，首次登录后建议改成和教务一致"),
    Xuegong("学工系统", Icons.Outlined.Badge, Color(0xFF30D158), true, "学工系统的密码，用于免输入静默登录"),
    Ykt("一卡通", Icons.Outlined.CreditCard, Color(0xFFBF5AF2), false, "统一身份认证（CAS）换票登录，本应用不保存一卡通密码");

    /** 读一次当前状态；不做任何网络请求。 */
    fun describe(context: android.content.Context, accountKey: String, studentId: String): SystemCredentialRow {
        val userManager = UserManager.getInstance()
        return when (this) {
            Academic -> SystemCredentialRow(
                kind = this,
                account = userManager.studentId.orEmpty().ifBlank { studentId },
                hasPassword = userManager.hasSavedPassword(accountKey),
                note = defaultHint
            )
            SecondClass -> SystemCredentialRow(
                kind = this,
                account = SecondClassroomStore.academicStudentId().ifBlank { studentId },
                hasPassword = SecondClassroomStore.hasPassword(context, accountKey),
                note = defaultHint
            )
            Xuegong -> SystemCredentialRow(
                kind = this,
                account = XuegongStore.academicStudentId().ifBlank { studentId },
                hasPassword = XuegongStore.hasPassword(context, accountKey),
                note = defaultHint
            )
            Ykt -> SystemCredentialRow(
                kind = this,
                account = studentId.ifBlank { "统一身份认证" },
                hasPassword = false,
                note = defaultHint
            )
        }
    }

    fun save(context: android.content.Context, accountKey: String, password: String) {
        when (this) {
            // 教务：走 UserManager 的同一条落盘路径（它会同时写 CredentialStore 与 loginMode）。
            // username/cookie 取当前会话里的值，不能凭空构造。
            Academic -> {
                val userManager = UserManager.getInstance()
                userManager.savePasswordLogin(
                    userManager.username.orEmpty(),
                    userManager.savedCookie.orEmpty(),
                    password
                )
            }
            SecondClass -> SecondClassroomStore.savePassword(context, accountKey, password)
            Xuegong -> XuegongStore.savePassword(context, accountKey, password)
            Ykt -> Unit
        }
    }

    fun clear(context: android.content.Context, accountKey: String) {
        when (this) {
            Academic -> UserManager.getInstance().deletePassword(accountKey)
            SecondClass -> SecondClassroomStore.clearPassword(context, accountKey)
            // 学工没有单独的清密码 API，clearAccount 会同时清掉口令与登录态，
            // 这是这个系统唯一正确的"忘记密码"语义（留着旧口令反而会静默登录失败）。
            Xuegong -> XuegongStore.clearAccount(context, accountKey)
            Ykt -> Unit
        }
    }
}

/** 单个系统卡：图标 + 名字 + 账号 + 状态徽标 + 操作。 */
@Composable
private fun SystemCredentialCard(
    row: SystemCredentialRow,
    onEditPassword: () -> Unit,
    onOpenLogin: () -> Unit,
    onClearPassword: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(row.kind.tint.copy(alpha = 0.16f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = row.kind.icon,
                        contentDescription = null,
                        tint = row.kind.tint,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = row.kind.label,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = row.account.ifBlank { "未登录" },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (row.kind.supportsPassword) {
                    SystemStatusBadge(
                        text = if (row.hasPassword) "已存密码" else "未存密码",
                        tone = if (row.hasPassword) SystemTone.Success else SystemTone.Neutral
                    )
                } else {
                    SystemStatusBadge(text = "统一认证", tone = SystemTone.Info)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (row.kind.supportsPassword) {
                    CredentialActionButton(
                        text = if (row.hasPassword) "修改密码" else "设置密码",
                        onClick = onEditPassword
                    )
                    if (row.hasPassword) {
                        Spacer(Modifier.width(8.dp))
                        CredentialActionButton(
                            text = "清除密码",
                            tint = Color(0xFFFF3B30),
                            onClick = onClearPassword
                        )
                    }
                } else {
                    CredentialActionButton(text = "去登录", onClick = onOpenLogin)
                }
            }
        }
    }
}

/**
 * 口令编辑弹窗。**输入框始终为空**（不回显已存口令），
 * 只接受"写一份新的"，这样面板里不存在任何"读出明文"的路径。
 */
@Composable
private fun PasswordEditorDialog(
    kind: SystemKind,
    accountLabel: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var password by remember(kind) { mutableStateOf("") }
    var visible by remember(kind) { mutableStateOf(false) }
    SystemDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "${kind.label}密码",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = accountLabel,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            SystemPrimaryButton(
                text = "保存",
                onClick = { onSave(password) },
                modifier = Modifier.fillMaxWidth(),
                enabled = password.isNotEmpty()
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    ) {
        Text(
            text = "保存在本机，用于${kind.label}的自动登录；不会上传到任何服务器。",
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("新密码") },
            visualTransformation = if (visible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation()
        )
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = { visible = !visible }) {
            Text(if (visible) "隐藏密码" else "显示密码", fontSize = 12.sp)
        }
        Text(
            text = kind.defaultHint,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 面板内的小圆角按钮（尺寸比设置页的行内按钮更紧凑）。 */
@Composable
private fun CredentialActionButton(
    text: String,
    tint: Color = Color(0xFF32ADE6),
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        color = tint.copy(alpha = 0.12f),
        shape = RoundedCornerShape(999.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = tint
        )
    }
}
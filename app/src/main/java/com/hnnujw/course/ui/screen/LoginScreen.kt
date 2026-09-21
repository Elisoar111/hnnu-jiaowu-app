package com.hnnujw.course.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hnnujw.course.ui.system.isBackdropSupported
import com.hnnujw.course.ui.system.rememberGlassAccessibilityMode
import com.hnnujw.course.ui.system.DialogHost
import com.hnnujw.course.ui.system.GlassWindowHost
import com.hnnujw.course.ui.system.LocalAppBackdrop
import com.hnnujw.course.ui.system.LocalControlBackdrop
import com.hnnujw.course.ui.system.LocalDialogHost
import com.hnnujw.course.ui.system.rememberDialogHostState
import com.hnnujw.course.ui.system.SystemPrimaryButton
import com.hnnujw.course.ui.system.SystemSecondaryButton
import com.hnnujw.course.ui.system.SystemSegmentedControl
import com.hnnujw.course.ui.system.SystemDialog
import com.hnnujw.course.ui.system.glass.glassSheet
import com.hnnujw.course.ui.theme.*

import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import com.hnnujw.course.model.SchoolConfig
import com.hnnujw.course.manager.AppearanceSettingsManager
import com.hnnujw.course.manager.UserManager
import com.hnnujw.course.network.SiteConnectivityProbe
import com.hnnujw.course.ui.system.GlassTextField
import com.hnnujw.course.ui.system.drawWallpaperPattern
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    schools: List<SchoolConfig>,
    onSchoolSelected: (SchoolConfig) -> Unit,
    onLoginClick: (cookie: String) -> Unit,
    onOpenWebView: () -> Unit = {},
    // 手机号登录（统一身份认证原生链路）：发送验证码 / 手机号+验证码登录
    onSendSmsCode: ((phone: String, onResult: (Boolean, String?) -> Unit) -> Unit)? = null,
    onPhoneCodeLogin: ((phone: String, code: String, onResult: (Boolean, String?) -> Unit) -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    isLoading: Boolean = false,
    errorMessage: String? = null,
    cookieValue: String = "",
    // Password login
    onPasswordLogin: ((username: String, password: String) -> Unit)? = null,
    captchaImageBytes: ByteArray? = null,
    onCaptchaSubmit: ((code: String) -> Unit)? = null,
    onCaptchaRefresh: (() -> Unit)? = null,
    // New parameters for binding dialog
    showBindingDialog: Boolean = false,
    bindingStudentName: String = "",
    bindingMaxStudents: Int = 0,
    bindingUsedNames: Set<String> = emptySet(),
    bindingUsedCount: Int = bindingUsedNames.size,
    onConfirmBinding: () -> Unit = {},
    onCancelBinding: () -> Unit = {}
) {
    var cookie by remember { mutableStateOf(cookieValue) }
    var loginTab by remember { mutableStateOf(if (onPasswordLogin != null) 0 else 1) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    // 手机号登录（统一身份认证）
    var phone by remember { mutableStateOf("") }
    var smsCode by remember { mutableStateOf("") }
    var phoneLoggingIn by remember { mutableStateOf(false) }
    var phoneError by remember { mutableStateOf<String?>(null) }
    var showCaptchaDialog by remember { mutableStateOf(false) }
    var captchaInput by remember { mutableStateOf("") }
    var captchaSubmitting by remember { mutableStateOf(false) }
    var captchaDismissed by remember { mutableStateOf(false) }
    
    // Update cookie when external value changes
    LaunchedEffect(cookieValue) {
        if (cookieValue.isNotEmpty()) {
            cookie = cookieValue
        }
    }
    var showPassword by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }

    // 站点连通性自检：主站与备用入口各探一次，结果直接上屏。
    var probeRunning by remember { mutableStateOf(false) }
    var probeResults by remember { mutableStateOf<List<SiteConnectivityProbe.Result>>(emptyList()) }
    val probeScope = rememberCoroutineScope()
    val accessibility = rememberGlassAccessibilityMode()
    val loginPanelEnter = if (accessibility.reduceMotion) {
        androidx.compose.animation.EnterTransition.None
    } else {
        slideInVertically(
            initialOffsetY = { 100 },
            animationSpec = androidx.compose.animation.core.spring(
                stiffness = androidx.compose.animation.core.Spring.StiffnessLow,
                dampingRatio = androidx.compose.animation.core.Spring.DampingRatioLowBouncy
            )
        ) + fadeIn(animationSpec = androidx.compose.animation.core.tween(300))
    }
    
    LaunchedEffect(Unit) {
        visible = true
    }

    // 验证码弹窗触发
    LaunchedEffect(captchaImageBytes) {
        if (captchaImageBytes != null) {
            // 新的验证码图片到达，重置所有状态
            captchaInput = ""
            captchaSubmitting = false
            captchaDismissed = false
            showCaptchaDialog = true
        } else {
            showCaptchaDialog = false
        }
    }
    
    GlassWindowHost {
    val backdrop = LocalControlBackdrop.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("login-screen")
            .semantics { contentDescription = "login-screen" }
    ) {

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // 登录页不再展示应用图标 / 应用名 / 副标题，直接从登录卡片开始。
            // Login Card (Glassmorphism / Outline style)
            AnimatedVisibility(
                visible = visible,
                enter = loginPanelEnter
            ) {
                val cardInner: @Composable ColumnScope.() -> Unit = {
                    // Title with Settings Button
                        Box(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = when {
                                    loginTab == 0 && onPasswordLogin != null -> "登录教务系统"
                                    loginTab == 2 && onSendSmsCode != null -> "手机号登录"
                                    else -> "Cookie 登录"
                                },
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = when {
                                loginTab == 0 && onPasswordLogin != null -> "使用教务系统的学号与密码登录"
                                loginTab == 2 && onSendSmsCode != null -> "使用学校统一身份认证绑定的手机号登录"
                                else -> "请从浏览器复制教务系统登录后的会话 Cookie"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        
                        Spacer(modifier = Modifier.height(32.dp))

                        // 本应用只支持淮南师范学院：学校固定，不提供添加 / 编辑入口，
                        // 但主站与备用入口之间可以切换（只换 host，不产生第二个 SchoolConfig，
                        // 因此不会多占设备绑定名额）。
                        Text(
                            text = "学校",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Neutral700,
                            modifier = Modifier.align(Alignment.Start)
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        val school = schools.firstOrNull()
                        var useAlternate by remember(school?.id) {
                            mutableStateOf(school?.useAlternate ?: false)
                        }

                        LaunchedEffect(school, useAlternate) {
                            school?.let {
                                it.useAlternate = useAlternate
                                onSchoolSelected(it)
                            }
                        }

                        Text(
                            text = school?.name ?: "淮南师范学院",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (school?.hasAlternate() == true) {
                            Spacer(modifier = Modifier.height(10.dp))
                            // 顺序：主站在前、备用入口在后（按用户要求）。
                            SystemSegmentedControl(
                                options = listOf("主站", "备用入口"),
                                selectedIndex = if (useAlternate) 1 else 0,
                                onSelect = { useAlternate = it == 1 },
                                backdrop = backdrop
                            )
                        }

                        // 不再直接把 IP / 域名 拼给用户看：主站是 jwgl.hnnu.edu.cn、
                        // 备用站是 211.70.176.172，对用户都是没意义的内部细节。
                        // 选哪个就在状态标签里写哪个，标签一致、信息密度相同。
                        Text(
                            text = if (useAlternate && school?.hasAlternate() == true) {
                                "正方教务系统 · 备用入口（IP 直连）"
                            } else {
                                "正方教务系统 · 主站（学校域名）"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        // 登录方式切换
                        if (onPasswordLogin != null) {
                            val tabOptions = if (onSendSmsCode != null) {
                                listOf("密码登录", "Cookie 登录", "手机号登录")
                            } else {
                                listOf("密码登录", "Cookie 登录")
                            }
                            SystemSegmentedControl(
                                options = tabOptions,
                                selectedIndex = loginTab.coerceAtMost(tabOptions.lastIndex),
                                onSelect = { loginTab = it },
                                backdrop = backdrop
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                        }

                        if (loginTab == 0 && onPasswordLogin != null) {
                            // 密码登录表单
                            Text(
                                text = "账号密码",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Neutral700,
                                modifier = Modifier.align(Alignment.Start)
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            GlassTextField(
                                value = username,
                                onValueChange = { username = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = "学号",
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                minHeight = 50.dp
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            GlassTextField(
                                value = password,
                                onValueChange = { password = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = "密码",
                                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                trailing = {
                                    IconButton(
                                        onClick = { showPassword = !showPassword },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                },
                                minHeight = 50.dp
                            )
                        } else if (loginTab == 2 && onSendSmsCode != null) {
                            // 手机号登录表单：手机号 + 短信验证码（统一身份认证原生链路）
                            var smsCountdown by remember { mutableStateOf(0) }
                            var smsSending by remember { mutableStateOf(false) }

                            LaunchedEffect(smsCountdown) {
                                if (smsCountdown > 0) {
                                    kotlinx.coroutines.delay(1000)
                                    smsCountdown--
                                }
                            }

                            GlassTextField(
                                value = phone,
                                onValueChange = { phone = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = "手机号",
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                minHeight = 50.dp
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                GlassTextField(
                                    value = smsCode,
                                    onValueChange = { smsCode = it },
                                    modifier = Modifier.weight(1f),
                                    placeholder = "验证码",
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    minHeight = 50.dp
                                )
                                Button(
                                    onClick = {
                                        phoneError = null
                                        smsSending = true
                                        onSendSmsCode?.invoke(phone.trim()) { ok, msg ->
                                            smsSending = false
                                            if (ok) {
                                                smsCountdown = 60
                                            } else {
                                                phoneError = msg ?: "验证码发送失败"
                                            }
                                        }
                                    },
                                    enabled = phone.isNotBlank() && !smsSending && smsCountdown <= 0,
                                    modifier = Modifier.height(50.dp)
                                ) {
                                    Text(
                                        text = when {
                                            smsSending -> "发送中…"
                                            smsCountdown > 0 -> "${smsCountdown}s"
                                            else -> "获取验证码"
                                        },
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                }
                            }

                            AnimatedVisibility(visible = phoneError != null) {
                                Column {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = phoneError ?: "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = SemanticDanger
                                    )
                                }
                            }
                        } else {

                        // Cookie Input（玻璃多行输入）
                        GlassTextField(
                            value = cookie,
                            onValueChange = { cookie = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            placeholder = "粘贴 Cookie 字符串",
                            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                            singleLine = false,
                            trailing = {
                                IconButton(
                                    onClick = { showPassword = !showPassword },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (showPassword) "隐藏" else "显示",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        )
                        } // end else (cookie tab)
                        
                        // Error Message
                        AnimatedVisibility(visible = errorMessage != null) {
                            Column {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = errorMessage ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SemanticDanger
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(32.dp))
                        
                        // Login Button
                        if (loginTab == 0 && onPasswordLogin != null) {
                            SystemPrimaryButton(
                                text = if (isLoading) "登录中…" else "密码登录",
                                onClick = { onPasswordLogin(username, password) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                                enabled = username.isNotBlank() && password.isNotBlank() && !isLoading
                            )

                            // 站点连通性自检：按用户要求放在「密码登录」按钮的下方，
                            // 用来区分"登录不上"是账号/Cookie 的问题，还是站点这条路不通
                            // （校外访问、DNS、备用站只有 http 等）。主站与备用入口各探一次。
                            Spacer(modifier = Modifier.height(16.dp))
                            SystemSecondaryButton(
                                text = if (probeRunning) "正在测试…" else "测试站点连通性",
                                onClick = {
                                    val target = school
                                    if (target != null && !probeRunning) {
                                        probeRunning = true
                                        probeResults = emptyList()
                                        probeScope.launch {
                                            val collected = mutableListOf<SiteConnectivityProbe.Result>()
                                            collected += SiteConnectivityProbe.probe(
                                                label = "主站",
                                                protocol = target.protocol,
                                                host = target.domain,
                                                path = target.loginPagePath
                                            )
                                            if (target.hasAlternate()) {
                                                collected += SiteConnectivityProbe.probe(
                                                    label = "备用入口",
                                                    protocol = target.alternateProtocol,
                                                    host = target.alternateDomain.trim(),
                                                    path = target.loginPagePath
                                                )
                                            }
                                            probeResults = collected
                                            probeRunning = false
                                        }
                                    }
                                },
                                enabled = !probeRunning && school != null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                            )

                            if (probeResults.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                probeResults.forEach { result ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = if (result.reachable) "✓" else "✕",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (result.reachable) SemanticSuccess else SemanticDanger
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = result.label,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (result.reachable) {
                                                "连通 · ${result.latencyMs} ms"
                                            } else {
                                                result.detail
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (result.reachable) {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            } else {
                                                SemanticDanger
                                            }
                                        )
                                    }
                                }
                            }
                        } else if (loginTab == 2 && onPhoneCodeLogin != null) {
                            // 手机号登录按钮
                            SystemPrimaryButton(
                                text = if (isLoading) "登录中…" else "登录",
                                onClick = {
                                    phoneLoggingIn = true
                                    onPhoneCodeLogin.invoke(phone.trim(), smsCode.trim()) { ok, msg ->
                                        if (!ok) {
                                            phoneError = msg ?: "登录失败"
                                            phoneLoggingIn = false
                                        }
                                        // 成功时页面会被主界面替换，不复位状态
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                                enabled = phone.isNotBlank() && smsCode.isNotBlank() &&
                                    !phoneLoggingIn && !isLoading
                            )
                        } else {
                            SystemPrimaryButton(
                                text = if (isLoading) "登录中…" else "Cookie 登录",
                                onClick = { onLoginClick(cookie) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                                enabled = cookie.isNotBlank() && !isLoading
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // WebView Cookie Button - 密码模式下点击自动切换到 Cookie 登录
                        if (loginTab == 0 && onPasswordLogin != null) {
                            // 密码模式：不显示内嵌浏览器按钮，显示提示文字
                            TextButton(
                                onClick = { loginTab = 1 },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "切换到 Cookie 登录 →",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else if (loginTab == 1) {
                            SystemSecondaryButton(
                                text = "内嵌浏览器自动获取",
                                onClick = onOpenWebView,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.OpenInBrowser,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            )
                        }
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    cardInner()
                }
            }
        }

    }

    // Student Binding Confirmation Dialog
    if (showBindingDialog) {
        BindingConfirmationDialog(
            studentName = bindingStudentName,
            onConfirm = onConfirmBinding,
            onDismiss = onCancelBinding
        )
    }

    // 验证码弹窗
    if (showCaptchaDialog && captchaImageBytes != null) {
        SystemDialog(
            onDismissRequest = { showCaptchaDialog = false },
            title = {
                Text(
                    text = "请输入验证码",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            confirmButton = {
                SystemPrimaryButton(
                    text = if (captchaSubmitting) "提交中…" else "确认",
                    onClick = {
                        captchaSubmitting = true
                        onCaptchaSubmit?.invoke(captchaInput)
                    },
                    enabled = captchaInput.isNotBlank() && !captchaSubmitting,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            dismissButton = {
                SystemSecondaryButton(
                    text = "取消",
                    onClick = {
                        showCaptchaDialog = false
                        captchaDismissed = true
                        // 不调用 refreshCaptcha，避免更新 captchaImageBytes 触发 LaunchedEffect 重新弹窗
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val bitmap = remember(captchaImageBytes) {
                    BitmapFactory.decodeByteArray(captchaImageBytes, 0, captchaImageBytes.size)
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "验证码",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = { onCaptchaRefresh?.invoke() }) {
                    Text("看不清？点击刷新")
                }
                Spacer(modifier = Modifier.height(8.dp))
                GlassTextField(
                    value = captchaInput,
                    onValueChange = { captchaInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "验证码",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    minHeight = 50.dp
                )
            }
        }
    }
    } // 关闭 CompositionLocalProvider
}

@Composable
fun BindingConfirmationDialog(
    studentName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    SystemDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "确认绑定账号",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        confirmButton = {
            SystemPrimaryButton(
                text = "确认绑定",
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth()
            )
        },
        dismissButton = {
            SystemSecondaryButton(
                text = "取消",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            )
        }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val iconContainerShape = RoundedCornerShape(16.dp)
            Surface(
                modifier = Modifier.size(64.dp),
                shape = iconContainerShape,
                color = NeuPrimary.copy(alpha = 0.10f),
                border = androidx.compose.foundation.BorderStroke(
                    0.5.dp,
                    NeuPrimary.copy(alpha = 0.16f)
                )
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Default.School,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = NeuPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "检测到新账号：「$studentName」",
                style = MaterialTheme.typography.titleMedium,
                color = NeuPrimary,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

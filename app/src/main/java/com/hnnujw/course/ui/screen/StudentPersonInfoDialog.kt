package com.hnnujw.course.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.hnnujw.course.ui.system.GlassToaster
import com.hnnujw.course.ui.system.SystemDialog
import com.hnnujw.course.ui.system.SystemPrimaryButton
import com.hnnujw.course.ui.system.SystemSecondaryButton

/**
 * 「我的」页顶部的「个人信息」入口内容：一张学号二维码 + 账号信息。
 *
 * 为什么单独做一个弹窗而不是新开页面：用户要的使用场景是「在食堂/图书馆/考试门口
 * 举一下手机」，从「我的」页一步就能看到、看完随手关掉，比跳转一个 Activity 再返回
 * 少两次转场动画；而且弹窗本身走的是本项目统一的液态玻璃 [SystemDialog]，和其它
 * 设置弹窗观感一致。
 *
 * 二维码内容就是**纯学号字符串**（不是 URL、也不是 JSON）：
 * - 校园里扫学号的设备（食堂 POS、图书馆闸机、考试签到终端）绝大多数只认纯数字串；
 * - 包成 URL 之后，这些老设备要么扫不动、要么把整串当作非法学号拒掉。
 * 因此这里不做任何编码包装，只加 UTF-8 与 margin=1 两个编码提示。
 *
 * 学号来源：`UserManager.studentId`（登录教务时由首页解析写入），为空时回退到
 * `UserManager.getUsername()`（正方教务的登录账号就是学号），两者都没有则正常显示
 * 一个「未获取到学号」的空态，而不是拿设备 ID 之类的占位符冒充学号。
 */
@Composable
fun StudentPersonInfoDialog(
    studentName: String,
    studentNumber: String,
    schoolName: String = "",
    collegeName: String = "",
    majorName: String = "",
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val number = studentNumber.trim()
    val hasNumber = number.isNotEmpty()

    SystemDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "个人信息",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        confirmButton = {
            SystemPrimaryButton(
                text = "完成",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            )
        }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 头像按用户要求去掉；姓名下方的学号保留（用户明确要求不删）。
            identityBlock(
                studentName = studentName.ifBlank { "同学" },
                studentNumber = if (hasNumber) number else "未获取到学号"
            )

            if (hasNumber) {
                Spacer(Modifier.height(2.dp))
                studentQrCard(content = number)
                SystemSecondaryButton(
                    text = "复制学号",
                    onClick = {
                        copyToClipboard(context, "学号", number)
                        GlassToaster.show("已复制学号 $number")
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Text(
                    text = "登录教务后即可显示学号二维码",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            val details = buildList {
                if (schoolName.isNotBlank()) add("学校" to schoolName)
                if (collegeName.isNotBlank()) add("院系" to collegeName)
                if (majorName.isNotBlank()) add("专业" to majorName)
                // 学号这一行已删除：上方二维码与「复制学号」已经承载了它
            }
            if (details.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    details.forEach { (label, value) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = value,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.End
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 姓名块：姓名 + 学号各一行居中。
 *
 * 圆形头像（姓名首字的装饰圆）没有任何信息量，已按用户要求去掉；
 * 学号保留在姓名下方。
 */
@Composable
private fun identityBlock(studentName: String, studentNumber: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = studentName,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = studentNumber,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.5.sp
        )
    }
}

/**
 * 二维码必须画在**纯白底**上：液态玻璃的透色底 + 半透明像素会让手机摄像头
 * 二值化时把浅灰当成黑子，识别率断崖式下降。所以这里固定白卡 + 深色码点，
 * 不跟随主题（深色模式下同样是白底，这属于刻意的例外）。
 */
@Composable
private fun studentQrCard(content: String) {
    val bitmap = remember(content) { makeStudentQrBitmap(content, 720) }
    Box(
        modifier = Modifier
            .size(228.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(androidx.compose.ui.graphics.Color.White)
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "学号二维码",
                modifier = Modifier.size(204.dp)
            )
        } else {
            Text(
                text = "二维码生成失败",
                style = MaterialTheme.typography.bodySmall,
                color = androidx.compose.ui.graphics.Color(0xFF8A8A8E)
            )
        }
    }
}

private fun makeStudentQrBitmap(content: String, size: Int): Bitmap? = try {
    val matrix = QRCodeWriter().encode(
        content,
        BarcodeFormat.QR_CODE,
        size,
        size,
        mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
    )
    val pixels = IntArray(size * size) { index ->
        if (matrix.get(index % size, index / size)) android.graphics.Color.BLACK
        else android.graphics.Color.WHITE
    }
    Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
} catch (t: Throwable) {
    null
}

private fun copyToClipboard(context: Context, label: String, value: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return
    runCatching { manager.setPrimaryClip(ClipData.newPlainText(label, value)) }
}
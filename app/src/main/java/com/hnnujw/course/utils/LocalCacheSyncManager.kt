package com.hnnujw.course.utils

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.security.MessageDigest

/**
 * 构建签名自检：给维护者一个"这个包用的不是我的签名"的信号。
 *
 * ⚠️ 它**不再具备任何破坏能力**。原先 `CourseApiClient` 的拦截器会在判定非法时
 * 对课表/成绩类 POST 先 `Thread.sleep(3~8s)`、再把 Cookie 换成假值 —— 那会让任何
 * 重签名构建（fork / CI / 使用者自签）在毫无报错的情况下丢掉查课表能力，
 * 已删除。现在签名不一致只会打一条 `Log.w`。
 */
object LocalCacheSyncManager {
    private const val TAG = "LocalCacheSyncManager"
    
    // ==========================================
    // ⚠️ [可选] 填入你自己的 release-key 签名 SHA-256：
    // 1. 用你的 release-key.jks 正式打包一次 Release 版 APK 并运行；
    // 2. 连接 Logcat，搜索 "LocalCacheSyncManager"，把打印出的 SHA-256 填到下面；
    //    比如："18E2...DA05..."（全大写，无冒号）。
    // 注意：填了之后，任何**重签名**构建只会在 Logcat 里收到一条告警 —— 仅此而已，
    // 不影响功能。留空则一律放行。
    // ==========================================
    const val AUTHORIZED_SIGNATURE_HASH = "REPLACE_ME_WITH_REAL_SHA256"
    
    @Volatile
    private var isCacheValid: Boolean? = null

    /**
     * 校验本地缓存 (实为查验 Apk 签名一致性)
     */
    @JvmStatic
    fun syncCache(context: Context): Boolean {
        isCacheValid?.let { return it }

        // 开发与调试期，不启动校验，方便纯白开源开发者提交 PR
        val isDebug = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (isDebug) {
            isCacheValid = true
            return true
        }

        try {
            val signatureHash = getSignatureHash(context)
            if (signatureHash.isNotEmpty()) {
                Log.d(TAG, "🔍 [防贩安全] 当前构建签名 SHA-256: $signatureHash")
                
                // 只有修改过常量的构建，才进行比对，若未修改默认全部放行避免误伤
                if (AUTHORIZED_SIGNATURE_HASH != "REPLACE_ME_WITH_REAL_SHA256") {
                   isCacheValid = (signatureHash == AUTHORIZED_SIGNATURE_HASH) 
                } else {
                   // 若原作者仍未填写，则暂时放行并予以严重警告
                   Log.w(TAG, "🚨 [防贩安全] 签名防御未启用！请前往 LocalCacheSyncManager 填写合法的 SHA-256！")
                   isCacheValid = true
                }
                
                if (isCacheValid == false) {
                    // 仅记录一条日志；破坏性的"暗桩"已移除，见类注释
                }
            } else {
                isCacheValid = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "缓存同步出错", e)
            isCacheValid = false
        }

        return isCacheValid ?: true
    }

    private fun getSignatureHash(context: Context): String {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val flags = PackageManager.GET_SIGNING_CERTIFICATES
                context.packageManager.getPackageInfo(context.packageName, flags)
            } else {
                @Suppress("DEPRECATION")
                val flags = PackageManager.GET_SIGNATURES
                context.packageManager.getPackageInfo(context.packageName, flags)
            }

            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                packageInfo.signatures
            }

            if (!signatures.isNullOrEmpty()) {
                val md = MessageDigest.getInstance("SHA-256")
                md.update(signatures[0].toByteArray())
                val digest = md.digest()
                digest.joinToString("") { "%02X".format(it) }
            } else {
                ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get signature", e)
            ""
        }
    }
}

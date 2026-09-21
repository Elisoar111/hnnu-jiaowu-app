package com.hnnujw.course.activation

import android.content.Context
import android.content.SharedPreferences

/**
 * 激活状态管理器 (开源版)
 * 已移除白名单限制，所有设备默认激活。
 * 仅保留本地设备标识符生成与学生数目配额控制功能。
 */
object ActivationManager {
    private const val PREFS_NAME = "activation_prefs"
    private const val KEY_DEVICE_ID = "device_id"
    
    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    /**
     * 检查激活状态（历史遗留入口）。
     *
     * 开源版**永远返回 true**：本应用永久免费、不设任何设备白名单或授权门槛。
     * 现在调用它只是为了那个副作用 —— 把设备 ID 落盘，供「我的」页展示。
     * 曾经存在的「未授权就整页显示激活页」分支已删除（见 MainActivity）。
     */
    suspend fun checkActivation(context: Context): Boolean {
        // 保存设备 ID 供界面显示（虽然已不再用于白名单校验，但界面可能仍需展示）
        val deviceId = DeviceUtils.getDeviceId(context)
        getPrefs(context).edit().putString(KEY_DEVICE_ID, deviceId).apply()
        
        return true
    }
    
    /**
     * 获取保存的设备 ID（供界面显示）
     */
    fun getSavedDeviceId(context: Context): String {
        return getPrefs(context).getString(KEY_DEVICE_ID, null) 
            ?: DeviceUtils.getDeviceId(context)
    }
    
    /**
     * 清除激活状态（调试用）
     */
    fun clearActivation(context: Context) {
        getPrefs(context).edit().clear().apply()
    }
    
    /**
     * 获取最大允许学生数
     * Debug 和 Release 统一允许绑定 3 个学生账号。
     */
    fun getMaxStudents(context: Context): Int {
        return com.hnnujw.course.manager.StudentLimitManager.MAX_STUDENTS
    }
}

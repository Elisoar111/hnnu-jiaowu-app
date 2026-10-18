package com.hnnujw.course.backup

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 本地数据备份与恢复。
 *
 * ## 为什么需要
 *
 * 用户所有的错题本、答题记录、收藏、自定义课程、练习进度全部锁在这台设备上。
 * 换手机、清数据、卸载重装就是**全部归零**，而且没有任何办法找回来。
 * `allowBackup="true"` 看着像已经解决了，但 `backup_rules.xml` 排除了会话 Cookie，
 * 且国产 ROM 的云备份经常不可靠 —— 不能指望它。
 *
 * ## 备份包里有什么、没有什么
 *
 * **会备份**（用户自己攒下来的资产）：
 *  - `review.db`：题库、答题记录、错题本、收藏
 *  - `schedule_settings`：自定义课程、节次时间、学期起点、字体缩放
 *  - `schedule_cache`：课表快照
 *  - 各 `review_*` 偏好：练习设置、未完成进度、复习提醒开关
 *  - `app_prefs`：界面设置、账号列表（**不含 Cookie**）
 *  - `second_classroom_prefs` / `xuegong_prefs` 等业务偏好
 *
 * **绝不备份**（凭据，见 [EXCLUDED_PREFS]）：
 *  - `secure_credentials`：账号密码 + 会话 Cookie（加密存着，但备份文件会离开设备）
 *  - `review_ai_prefs`：AI 的 API Key
 *
 * 这条边界是硬性的。备份文件是**明文 zip**，用户可以随手发到群里、传到网盘——
 * 把凭据放进去等于把登录态和 API Key 一起送出去。所以恢复后需要重新登录、
 * 重新填 Key，这是正确的代价。
 *
 * ## 为什么不备份提醒计划
 *
 * `course_reminders` / `exam_reminders` 里存的是**已排好的闹钟触发时刻**，
 * 它们与这台设备上已经注册的 AlarmManager 条目一一对应。换设备后照搬这些时刻，
 * 应用会以为"闹钟都排好了"，而实际上系统里一个都没排 —— 提醒彻底不响。
 * 正确做法是恢复后按课表重新 reconcile，所以这里排除它们。
 */
object BackupManager {

    private const val TAG = "BackupManager"

    /** 备份格式版本。恢复时用来判断兼容性。 */
    private const val FORMAT_VERSION = 1

    private const val MANIFEST_ENTRY = "manifest.json"
    private const val DB_ENTRY = "data/review.db"
    private const val PREFS_PREFIX = "data/prefs/"

    /**
     * **绝不进备份**的 prefs 文件名。
     *
     * 见类注释：备份是明文 zip，会离开设备。凭据一旦进去就等于泄露。
     */
    private val EXCLUDED_PREFS = setOf(
        "secure_credentials",   // 账号密码 + 会话 Cookie
        "review_ai_prefs",      // AI API Key（加密存着，但备份文件不加密）
        // 提醒计划：与设备上已注册的 AlarmManager 条目绑定，换机照搬会导致"以为排了实际没排"
        "course_reminders",
        "exam_reminders",
    )

    /** 备份的 prefs 白名单。用**白名单**而不是黑名单：新增的 prefs 默认不进备份，
     *  这样"哪天有人存了个敏感字段"不会因为忘记加进黑名单而泄露。 */
    private val INCLUDED_PREFS = setOf(
        "app_prefs",
        "schedule_settings",
        "schedule_cache",
        "review_prefs",
        "review_practice_resume",
        "review_reminder",
        "second_classroom_prefs",
        "xuegong_prefs",
        "ykt_prefs",
        "ykt_selection_prefs",
        "ykt_alert_prefs",
        "ykt_alert_state",
    )

    /** 一次备份/恢复的结果。 */
    data class Result(
        val ok: Boolean,
        val message: String,
        /** 写出的文件（备份时）或读到的清单（恢复时）。 */
        val file: File? = null,
        val version: Int = 0,
        val savedPrefs: List<String> = emptyList()
    )

    /** 备份文件名：带时间戳，避免多次导出互相覆盖。 */
    fun suggestedFileName(now: Long = System.currentTimeMillis()): String {
        val sdf = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.CHINA)
        return "校园助理备份-${sdf.format(java.util.Date(now))}.zip"
    }

    /**
     * 写出一个备份 zip。
     *
     * @param target 由调用方通过 SAF（`ACTION_CREATE_DOCUMENT`）拿到的输出流。
     *   **不要**自己往公共目录写 —— 那需要存储权限，而本应用没有申请。
     *
     * ⚠️ 必须在 IO 线程调用。
     */
    fun backup(context: Context, target: java.io.OutputStream): Result {
        val app = context.applicationContext
        return try {
            val savedPrefs = ArrayList<String>()
            var dbBytes = 0L
            ZipOutputStream(target.buffered()).use { zip ->
                // ① 题库数据库。用 WAL checkpoint 保证写到文件里的是最新数据。
                val dbFile = app.getDatabasePath("review.db")
                if (dbFile.exists()) {
                    // 先 flush 掉 WAL：否则 zip 里拿到的是不含最新事务的主库文件。
                    runCatching {
                        app.openOrCreateDatabase("review.db", Context.MODE_PRIVATE, null)
                            .rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
                    }
                    dbBytes = dbFile.length()
                    zip.putNextEntry(ZipEntry(DB_ENTRY))
                    FileInputStream(dbFile).use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                // ② prefs 白名单。
                val prefsDir = File(app.applicationInfo.dataDir, "shared_prefs")
                INCLUDED_PREFS.filter { it !in EXCLUDED_PREFS }.forEach { name ->
                    val f = File(prefsDir, "$name.xml")
                    if (!f.exists()) return@forEach
                    zip.putNextEntry(ZipEntry(PREFS_PREFIX + name + ".xml"))
                    FileInputStream(f).use { it.copyTo(zip) }
                    zip.closeEntry()
                    savedPrefs.add(name)
                }
                // ③ 清单。放在最后写，这样恢复时能先读到它判断完整性。
                zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                zip.write(manifest(app, dbBytes, savedPrefs).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            Result(ok = true, message = "备份完成：${savedPrefs.size} 项设置 + 题库数据", version = FORMAT_VERSION, savedPrefs = savedPrefs)
        } catch (e: Exception) {
            Log.w(TAG, "备份失败: ${e.javaClass.simpleName} ${e.message}")
            Result(ok = false, message = "备份失败：${e.message ?: "写入出错"}")
        }
    }

    /**
     * 从备份 zip 恢复。
     *
     * 🔴 恢复**不会**删除备份里没有的东西：只覆盖包里有的文件。
     * 这样"从旧备份恢复"不会把用户后来新建的题库一起抹掉（数据库是整体替换，
     * 所以题库会回到备份时的状态 —— 这一点必须在界面文案里讲清楚）。
     *
     * ⚠️ 必须在 IO 线程调用，且调用方应保证**没有**正在使用数据库的协程。
     */
    fun restore(context: Context, source: InputStream): Result {
        val app = context.applicationContext
        var manifest: JSONObject? = null
        var dbRestored = false
        val prefsRestored = ArrayList<String>()
        return try {
            // ① 先解到临时目录，再整体替换。中途失败不会留下一个半更新的库。
            val staging = File(app.cacheDir, "restore-staging").apply {
                deleteRecursively()
                mkdirs()
            }
            ZipInputStream(source.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == MANIFEST_ENTRY) {
                        manifest = JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                    } else if (!entry.isDirectory && isSafeEntry(entry.name)) {
                        val out = File(staging, entry.name)
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }

            val mf = manifest
                ?: return Result(false, "这不是有效的备份文件（缺少清单）")
            val version = mf.optInt("version", 0)
            if (version <= 0 || version > FORMAT_VERSION) {
                return Result(false, "备份来自更新的版本（格式 $version），当前应用读不了")
            }

            // ② 数据库：先关连接再替换，否则 SQLite 会继续用旧句柄。
            val stagedDb = File(staging, DB_ENTRY)
            if (stagedDb.exists()) {
                ReviewDatabaseAccess.close()
                val dbFile = app.getDatabasePath("review.db")
                dbFile.parentFile?.mkdirs()
                // 连带删掉 WAL/SHM，否则旧日志会被回放到新库上。
                File(dbFile.path + "-wal").delete()
                File(dbFile.path + "-shm").delete()
                stagedDb.copyTo(dbFile, overwrite = true)
                dbRestored = true
            }

            // ③ prefs：逐份覆盖。恢复后这些 prefs 的**内存缓存**也随之过期，
            // 所以下面会通知相关组件重新读取。
            val prefsDir = File(app.applicationInfo.dataDir, "shared_prefs")
            val stagedPrefs = File(staging, "data/prefs")
            stagedPrefs.listFiles()?.forEach { staged ->
                val name = staged.nameWithoutExtension
                if (name in EXCLUDED_PREFS || name !in INCLUDED_PREFS) return@forEach
                prefsDir.mkdirs()
                staged.copyTo(File(prefsDir, staged.name), overwrite = true)
                prefsRestored.add(name)
            }
            staging.deleteRecursively()

            val msg = buildString {
                append("恢复完成")
                if (dbRestored) append("：题库与答题记录") else append("：未包含题库")
                if (prefsRestored.isNotEmpty()) append("，${prefsRestored.size} 项设置")
                append("。建议重启应用使全部设置生效。")
            }
            Result(true, msg, version = version, savedPrefs = prefsRestored)
        } catch (e: Exception) {
            Log.w(TAG, "恢复失败: ${e.javaClass.simpleName} ${e.message}")
            Result(false, "恢复失败：${e.message ?: "文件无法读取"}")
        }
    }

    /**
     * zip 条目名是否安全。
     *
     * 防的是 **zip 路径穿越**：恶意备份里若有 `../../databases/review.db` 这样的条目，
     * 直接按名字落盘会写到应用目录之外。这里只接受白名单前缀 + 不允许任何 `..`。
     */
    private fun isSafeEntry(name: String): Boolean {
        if (name.contains("..")) return false
        if (name.startsWith("/") || name.startsWith("\\")) return false
        return name == DB_ENTRY || (name.startsWith(PREFS_PREFIX) && name.endsWith(".xml"))
    }

    private fun manifest(context: Context, dbBytes: Long, prefs: List<String>): String = JSONObject().apply {
        put("version", FORMAT_VERSION)
        put("createdAt", System.currentTimeMillis())
        put("appVersion", appVersion(context))
        put("dbBytes", dbBytes)
        put("prefs", JSONArray(prefs))
        // 明写"没有备份什么"，这样用户和我日后都能一眼确认边界没被改动。
        put("excluded", JSONArray(EXCLUDED_PREFS.toList()))
    }.toString(2)

    private fun appVersion(context: Context): String = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")
}

/**
 * 打开/关闭 [com.hnnujw.course.review.ReviewDatabase] 的桥。
 *
 * 单独放一个接口而不是直接调 `ReviewDatabase.resetInstanceForTest()`：那个方法
 * 名字里带 "ForTest"，从生产代码调用会让人误以为走错了路。这里给它一个
 * 语义正确的入口，内部仍是同一次关闭动作。
 */
internal object ReviewDatabaseAccess {
    fun close() = com.hnnujw.course.review.ReviewDatabase.closeForRestore()
}

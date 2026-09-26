package com.hnnujw.course.ykt

import android.content.Context

/**
 * 电费查询的「上次选择」记忆。
 *
 * ## 为什么需要
 *
 * 淮师查一次电费要依次选 **费项 → 校区 → 楼栋 → 楼层 → 房间**（真机实测，
 * 见 [YktClient] 类注释）。学生查的几乎永远是**同一个宿舍**，
 * 每次都重选是纯粹的折磨。所以把选择落盘，下次进页面直接复用。
 *
 * ## 为什么按账号隔离
 *
 * 同一部手机可能被多人使用（同学互相查），不同学生的宿舍不同，
 * 记忆串号会让人看到别人的房间。所以键里带账号。
 *
 * ## 账号键归一化（本项目最高频踩坑）
 *
 * 工程里并存 `hnnu::2024001`（raw）与 `hnnu__2024001`（storage）两种口径，
 * **永不相等**。所有按账号落盘的读写一律先过 [YktStore.normalize]，
 * 否则「写一个键、读另一个键」，表现为记忆永远失效。
 *
 * ## 存储格式（v2：动态级数）
 *
 * 早期版本按固定四字段（campus/building/floor/room）存，但真实级数由服务端决定
 * （见 [YktSceneSelection] 说明），故改为**变长记录**：
 *
 * ```
 * v2 <US> feeItemId <US> code1 <FS> value1 <FS> name1 <US> code2 <FS> value2 <FS> name2 …
 * ```
 * - `<US>` = 单元分隔符（`\u001F`），分隔"字段"
 * - `<FS>` = 记录分隔符（`\u001E`），分隔一条 pick 里的 code/value/name
 *
 * 值里可能含 `&`（`<id>&<name>`）与中文，故**不能用** `,`/`:` 这类常见字符。
 * 旧格式读到时按"无记忆"处理（不报错），用户重选一次即可。
 */
object YktSelectionStore {

    private const val PREFS = "ykt_selection_prefs"
    private const val KEY_SELECTION_PREFIX = "sel_"
    private const val US = "\u001F"
    private const val FS = "\u001E"
    private const val MAGIC_V2 = "v2"

    fun read(context: Context, accountKey: String): YktSceneSelection {
        val raw = prefs(context).getString(key(accountKey), null) ?: return YktSceneSelection.EMPTY
        return decode(raw)
    }

    fun write(context: Context, accountKey: String, selection: YktSceneSelection) {
        prefs(context).edit().putString(key(accountKey), encode(selection)).apply()
    }

    fun clear(context: Context, accountKey: String) {
        prefs(context).edit().remove(key(accountKey)).apply()
    }

    /** 序列化：`v2 <US> feeItemId <US> [code <FS> value <FS> name] <US> …`。 */
    internal fun encode(selection: YktSceneSelection): String {
        val parts = mutableListOf(MAGIC_V2, selection.feeItemId)
        for (pick in selection.picks) {
            parts += listOf(pick.code, pick.value, pick.name).joinToString(FS)
        }
        return parts.joinToString(US)
    }

    /**
     * 反序列化。**任何异常都回退成空选择**，绝不让脏数据把页面搞崩。
     *
     * 非 `v2` 开头（旧格式或损坏）→ 当作没记忆。单条 pick 字段数不对 → 丢弃该条。
     */
    internal fun decode(raw: String): YktSceneSelection {
        val parts = raw.split(US)
        if (parts.size < 2 || parts[0] != MAGIC_V2) return YktSceneSelection.EMPTY
        val feeItemId = parts[1]
        val picks = mutableListOf<ScenePick>()
        for (i in 2 until parts.size) {
            val fields = parts[i].split(FS)
            if (fields.size != 3) continue
            val (code, value, name) = fields
            if (code.isBlank() || value.isBlank()) continue
            picks += ScenePick(code = code, value = value, name = name, level = picks.size + 1)
        }
        return YktSceneSelection(feeItemId = feeItemId, picks = picks)
    }

    private fun key(accountKey: String): String =
        KEY_SELECTION_PREFIX + YktStore.normalize(accountKey)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

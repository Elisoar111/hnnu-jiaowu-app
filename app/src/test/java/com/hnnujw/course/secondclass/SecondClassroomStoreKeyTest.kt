package com.hnnujw.course.secondclass

import com.hnnujw.course.schedule.MemoryPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第二课堂 token 的**账号键口径**回归测试。
 *
 * 背景：本工程并存两种账号键 —— `currentAccountKey`（`hnnu::2024001`，见
 * `UserManager.buildAccountKey`）与 `currentAccountStorageKey`（`hnnu__2024001`，
 * 经 `UserManager.toStorageKey` 把 `::` 换成 `__`）。二课各页面传前者，
 * 桌面组件（`widgetboard/CardWidgetDataLoader`、`ui/route/WidgetBoardRoute`）传后者。
 *
 * 存储层若不归一化，就会**写进一个键、读另一个键**：表现为"明明绑定过第二课堂，
 * 卡片却说未绑定"，并且 `WidgetBoardRoute` 会因 token 为空提前 return，
 * 让 `SecondClassOverviewCache.save` 永远执行不到，积分卡片永远是空的。
 *
 * 这两个键在任何情况下都不可能相等（`buildAccountKey` 恒定插入 `::`），
 * 所以只要不归一化，上述缺陷就是 100% 复现的。
 *
 * 这里直接测存储原语（吃 `SharedPreferences`），不经过 `Context` —— 键口径的
 * 一致性完全由这几个函数决定，与 Android 框架无关。
 */
class SecondClassroomStoreKeyTest {

    private val accountKey = "hnnu::2024001"
    private val storageKey = "hnnu__2024001"

    @Test
    fun tokenSavedWithAccountKeyIsReadableWithStorageKey() {
        val prefs = MemoryPreferences()
        SecondClassroomStore.writeToken(prefs, accountKey, "tk-1")

        assertEquals("tk-1", SecondClassroomStore.readToken(prefs, storageKey))
        assertEquals("tk-1", SecondClassroomStore.readToken(prefs, accountKey))
    }

    @Test
    fun tokenSavedWithStorageKeyIsReadableWithAccountKey() {
        val prefs = MemoryPreferences()
        SecondClassroomStore.writeToken(prefs, storageKey, "tk-2")

        assertEquals("tk-2", SecondClassroomStore.readToken(prefs, accountKey))
    }

    @Test
    fun savingWithStorageKeyKeepsTheValueInsteadOfDeletingItRightAfterWriting() {
        // 归一化键与旧键相同（调用方本来就传 storage key）时，
        // "先 putString 再 remove 同一个键"会把 token 抹掉 —— 这里盯住这个回归。
        val prefs = MemoryPreferences()
        SecondClassroomStore.writeToken(prefs, storageKey, "tk-3")

        assertEquals("tk-3", prefs.getString("token_$storageKey", null))
    }

    @Test
    fun legacyTokenUnderTheRawKeyIsMigratedOnce() {
        val prefs = MemoryPreferences()
        prefs.edit().putString("token_$accountKey", "tk-legacy").apply()

        assertEquals("tk-legacy", SecondClassroomStore.readToken(prefs, storageKey))
        // 迁移之后只保留归一化后的键，旧键被摘掉（否则会长期留一份可被"复活"的副本）
        assertEquals("tk-legacy", prefs.getString("token_$storageKey", null))
        assertNull(prefs.getString("token_$accountKey", null))
    }

    @Test
    fun writingWithAccountKeyAlsoClearsTheRawKeyItWouldOtherwiseLeaveBehind() {
        val prefs = MemoryPreferences()
        prefs.edit().putString("token_$accountKey", "old").apply()

        SecondClassroomStore.writeToken(prefs, accountKey, "new")

        assertEquals("new", prefs.getString("token_$storageKey", null))
        assertNull(prefs.getString("token_$accountKey", null))
    }

    @Test
    fun clearTokenRemovesBothKeyShapes() {
        val prefs = MemoryPreferences()
        prefs.edit().putString("token_$accountKey", "a").putString("token_$storageKey", "b").apply()

        // 调用方传的是 storage key（`SecondClassroomStore.handleFailure` / `clearAccount`
        // 就可能是这种口径），两种键都必须清掉，否则原始键上的老 token 会被迁移逻辑复活
        SecondClassroomStore.removeToken(prefs, storageKey)

        assertTrue(SecondClassroomStore.readToken(prefs, accountKey).isEmpty())
        assertTrue(SecondClassroomStore.readToken(prefs, storageKey).isEmpty())
    }

    @Test
    fun blankAccountKeyIsNeverPersisted() {
        val prefs = MemoryPreferences()
        SecondClassroomStore.writeToken(prefs, "", "tk")

        assertTrue(prefs.all.isEmpty())
        assertTrue(SecondClassroomStore.readToken(prefs, "").isEmpty())
    }
}

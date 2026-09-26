package com.hnnujw.course.widgetboard

import com.hnnujw.course.schedule.MemoryPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工作台布局的编解码。
 *
 * 这里最容易出事的不是"读不回来"，而是**容错方向搞反**：
 * - 卡片下架后残留的 id 不丢掉 → 界面上出现一个永远空白的格子；
 * - 用户把卡片全删了（空布局）被当成解析失败 → 回退默认布局，用户删不掉卡片。
 */
class WidgetBoardStoreTest {

    private fun prefs() = MemoryPreferences()

    @Test
    fun `往返一次布局完全一致`() {
        val store = prefs()
        val layout = WidgetBoardLayout(
            listOf(
                WidgetInstance("a", "schedule.today", WidgetSize.Large),
                WidgetInstance("b", "message.unread", WidgetSize.Small),
            )
        )
        WidgetBoardStore.save(store, layout)
        assertEquals(layout, WidgetBoardStore.load(store))
    }

    @Test
    fun `没保存过时给默认布局`() {
        assertEquals(WidgetBoardStore.defaultLayout(), WidgetBoardStore.load(prefs()))
    }

    @Test
    fun `默认布局能整行铺满不留半行`() {
        // 默认摆法是用户看到的第一眼：半行留空会被当成"加载失败"。
        var used = 0
        WidgetBoardStore.defaultLayout().instances.forEach { inst ->
            used += inst.size.columns
            assertTrue("${inst.sourceId} 把这一行挤爆了", used <= 4)
            if (used == 4) used = 0
        }
        assertEquals("最后一行没铺满 4 列", 0, used)
    }

    @Test
    fun `空布局是合法状态而不是解析失败`() {
        val store = prefs()
        WidgetBoardStore.save(store, WidgetBoardLayout(emptyList()))
        assertEquals(emptyList<WidgetInstance>(), WidgetBoardStore.load(store).instances)
    }

    @Test
    fun `下架卡片的残留条目会被丢掉`() {
        val store = prefs()
        // 键名与 JSON 形状在这里写死是有意的：它同时钉住持久化格式，
        // 免得将来重命名 key 时把用户的布局悄悄清成默认值。
        store.edit().putString(
            "layout",
            """[{"id":"x","source":"schedule.today","size":"Wide"},
                {"id":"y","source":"card.removed.in.future","size":"Small"}]"""
        ).apply()
        val loaded = WidgetBoardStore.load(store)
        assertEquals(1, loaded.instances.size)
        assertEquals("schedule.today", loaded.instances[0].sourceId)
    }

    @Test
    fun `认不出的尺寸档位回退成小卡`() {
        val store = prefs()
        store.edit().putString("layout", """[{"id":"x","source":"schedule.today","size":"Huge"}]""").apply()
        assertEquals(WidgetSize.Small, WidgetBoardStore.load(store).instances[0].size)
    }

    @Test
    fun `JSON 损坏时回退默认布局而不是崩溃`() {
        val store = prefs()
        store.edit().putString("layout", "{不是数组").apply()
        assertEquals(WidgetBoardStore.defaultLayout(), WidgetBoardStore.load(store))
    }

    @Test
    fun `同一张卡片可以摆多次且 key 不冲突`() {
        // 布局的 key 是 instance.id，不是 sourceId —— 摆两张课表卡将来若要支持，
        // 这里就是它的下限保障。
        val store = prefs()
        WidgetBoardStore.save(
            store,
            WidgetBoardLayout(
                listOf(
                    WidgetInstance("a", "schedule.today", WidgetSize.Small),
                    WidgetInstance("b", "schedule.today", WidgetSize.Large),
                )
            )
        )
        val loaded = WidgetBoardStore.load(store)
        assertEquals(2, loaded.instances.size)
        assertEquals(2, loaded.instances.map { it.id }.distinct().size)
    }

    @Test
    fun `尺寸档位的列数与高度自洽`() {
        assertEquals(1, WidgetSize.Small.rows)
        assertEquals(2, WidgetSize.Small.columns)
        assertTrue(WidgetSize.Wide.columns > WidgetSize.Small.columns)
        // 一行高的卡片必须比两行高的矮，否则网格里会留出空白。
        assertTrue(WidgetSize.Small.heightDp < WidgetSize.Medium.heightDp)
        assertEquals(WidgetSize.Small.heightDp, WidgetSize.Wide.heightDp)
    }
}

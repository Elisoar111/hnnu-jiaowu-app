package com.hnnujw.course.ui.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 省市区三级区划：级联列表与 `ComeWhere` 拼接串反解的回归。
 *
 * 学工站的 `ComeWhere = Province + City + County`（三段无分隔符拼接），
 * 反解必须能吃掉直辖市的同名市段（「北京市北京市东城区」），
 * 也要容忍老数据里缺失市段的手工输入（「北京市东城区」）。
 */
class AreaDataTest {

    private val tables = AreaData.Tables(
        provinces = listOf(
            AreaData.Node("110000", "北京市"),
            AreaData.Node("340000", "安徽省"),
        ),
        cities = listOf(
            AreaData.Node("110100", "北京市"),
            AreaData.Node("340100", "合肥市"),
            AreaData.Node("340400", "淮南市"),
        ),
        counties = listOf(
            AreaData.Node("110101", "东城区"),
            AreaData.Node("340102", "瑶海区"),
            AreaData.Node("340402", "大通区"),
            AreaData.Node("340403", "田家庵区"),
        ),
    )

    @Test
    fun `resolve standard province-city-county string`() {
        val (p, c, d) = AreaData.resolve(tables, "安徽省淮南市田家庵区")
        assertEquals("安徽省", p?.name)
        assertEquals("淮南市", c?.name)
        assertEquals("田家庵区", d?.name)
    }

    @Test
    fun `resolve municipality with repeated city name`() {
        val (p, c, d) = AreaData.resolve(tables, "北京市北京市东城区")
        assertEquals("北京市", p?.name)
        assertEquals("北京市", c?.name)
        assertEquals("东城区", d?.name)
    }

    @Test
    fun `resolve municipality with missing city segment`() {
        val (p, c, d) = AreaData.resolve(tables, "北京市东城区")
        assertEquals("北京市", p?.name)
        assertEquals("东城区", d?.name)
    }

    @Test
    fun `resolve unknown value yields nulls without throwing`() {
        val (p, c, d) = AreaData.resolve(tables, "火星第3区")
        assertNull(p)
        assertNull(c)
        assertNull(d)
        val (p2, c2, d2) = AreaData.resolve(tables, "")
        assertNull(p2); assertNull(c2); assertNull(d2)
    }

    @Test
    fun `concat matches site ComeWhere rule`() {
        assertEquals("安徽省淮南市田家庵区", AreaData.concat("安徽省", "淮南市", "田家庵区"))
    }

    @Test
    fun `cascading lists follow code prefixes`() {
        val anhui = tables.provinces.first { it.name == "安徽省" }
        val cities = tables.citiesOf(anhui)
        assertEquals(listOf("合肥市", "淮南市"), cities.map { it.name })
        val huainan = cities.first { it.name == "淮南市" }
        assertEquals(listOf("大通区", "田家庵区"), tables.countiesOf(huainan).map { it.name })
    }

    @Test
    fun `fallback keeps chain when no city entries exist`() {
        // 某省在 city_list 里没有同名市（数据缺失）时，市列表回退为省自身，
        // 保证滚轮永远有值可选而不是空列表。
        val tables2 = AreaData.Tables(
            provinces = listOf(AreaData.Node("990000", "测试省")),
            cities = emptyList(),
            counties = listOf(AreaData.Node("990101", "测试区")),
        )
        val cities = tables2.citiesOf(tables2.provinces[0])
        assertEquals(1, cities.size)
        assertEquals("测试省", cities[0].name)
        assertTrue(tables2.countiesOf(cities[0]).isNotEmpty())
    }
}

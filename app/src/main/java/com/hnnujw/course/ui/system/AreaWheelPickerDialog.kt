package com.hnnujw.course.ui.system

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/**
 * 全国省 / 市 / 区县三级区划数据（`assets/area_data.json`）。
 *
 * 数据与学工 H5 的 vant `Area` 组件同源（`@vant/area-data`）：
 * `province_list` / `city_list` / `county_list` 三个字典，键是 6 位行政区划码，
 * 值是名称。学工站在去向登记里正是用这份数据的运行时副本把
 * `ComeWhere = Province + City + County` 三段**无分隔符拼接**。
 *
 * 因此：
 * - 选择器产出的拼接串可以直接提交；
 * - 对已保存的 `ComeWhere` 串做最长前缀反解，就能恢复三段初始值。
 *
 * 数据只加载一次（进程内缓存）；加载失败返回空表，调用方表现为"选项为空"。
 */
object AreaData {

    data class Node(val code: String, val name: String)

    data class Tables(
        val provinces: List<Node>,
        val cities: List<Node>,
        val counties: List<Node>,
    ) {
        val isEmpty: Boolean get() = provinces.isEmpty()

        /** 省下的市：直辖市在 city_list 里同名再现（110000 北京市 → 110100 北京市）。 */
        fun citiesOf(province: Node?): List<Node> {
            if (province == null) return emptyList()
            val prefix = province.code.take(2)
            val direct = cities.filter { it.code.take(2) == prefix }
            return if (direct.isEmpty()) listOf(province) else direct
        }

        /** 市下的区县：市码前 4 位 + 任意末两位（340400 淮南市 → 340402 大通区…）。 */
        fun countiesOf(city: Node?): List<Node> {
            if (city == null) return emptyList()
            val prefix = city.code.take(4)
            val direct = counties.filter { it.code.take(4) == prefix }
            return if (direct.isEmpty()) listOf(city) else direct
        }
    }

    @Volatile
    private var cached: Tables? = null

    fun load(context: Context): Tables {
        cached?.let { return it }
        val json = runCatching {
            context.assets.open("area_data.json").bufferedReader().use { it.readText() }
        }.getOrNull()
        val tables = if (json == null) {
            Tables(emptyList(), emptyList(), emptyList())
        } else {
            runCatching {
                val root = JSONObject(json)
                fun dict(key: String): List<Node> {
                    val obj = root.optJSONObject(key) ?: return emptyList()
                    return obj.keys().asSequence().sorted().map { Node(it, obj.optString(it)) }.toList()
                }
                Tables(dict("province_list"), dict("city_list"), dict("county_list"))
            }.getOrDefault(Tables(emptyList(), emptyList(), emptyList()))
        }
        cached = tables
        return tables
    }

    /**
     * 把站点拼接串（如「安徽省淮南市田家庵区」）反解成省 / 市 / 区三段。
     *
     * 规则：省名按最长前缀匹配；剩余部分先按最长前缀匹配市名；再剩下的按区县名
     * 收尾。直辖市拼接串是「北京市北京市东城区」，同名市能正常吃掉重复段；
     * 老数据若是手工输入的「北京市东城区」（市段缺失），市名匹配失败时退回
     * 该省的直辖市同名市，再按区县名收尾。
     *
     * 任一段识别不出来返回该段 null —— 调用方把它当"无初始值"处理，
     * **不要**臆造默认值。
     */
    fun resolve(tables: Tables, value: String): Triple<Node?, Node?, Node?> {
        var rest = value.trim()
        if (rest.isEmpty()) return Triple(null, null, null)
        val province = tables.provinces
            .filter { rest.startsWith(it.name) }
            .maxByOrNull { it.name.length }
            ?: return Triple(null, null, null)
        rest = rest.removePrefix(province.name)

        var city = tables.citiesOf(province)
            .filter { it.name.isNotEmpty() && rest.startsWith(it.name) }
            .maxByOrNull { it.name.length }
        var restAfterCity = city?.let { rest.removePrefix(it.name) } ?: rest

        // 市段缺失（如「北京市东城区」）：直接按区县名收尾。
        var county: Node? = null
        if (city != null) {
            county = tables.countiesOf(city).firstOrNull { it.name == restAfterCity }
                ?: tables.countiesOf(city).filter { restAfterCity.startsWith(it.name) }
                    .maxByOrNull { it.name.length }
        }
        if (county == null) {
            val provinceCounties = tables.counties.filter { it.code.take(2) == province.code.take(2) }
            val direct = provinceCounties.firstOrNull { it.name == rest }
                ?: provinceCounties.filter { rest.startsWith(it.name) }.maxByOrNull { it.name.length }
            if (direct != null) {
                if (city == null) city = provinceCounties.firstOrNull { it.code.take(4) == direct.code.take(4) && it.code.endsWith("00") }
                county = direct
                restAfterCity = rest.removePrefix(city?.name ?: "")
                if (county.name != restAfterCity) restAfterCity = county.name
            }
        }
        return Triple(province, city, county)
    }

    /** 选择结果的三段拼接，与学工站 `ComeWhere` 的口径完全一致（无分隔符）。 */
    fun concat(province: String, city: String, county: String): String = province + city + county
}

/**
 * 省 / 市 / 区县三级玻璃滚轮弹窗（外出地点）。
 *
 * 三列级联：滚省 → 市列表重建 → 滚市 → 区县列表重建；市/区列表变化时把
 * 选中索引收敛回合法区间。三列共用一条通铺的中心指示片（与
 * [GlassDatePickerDialog] 同一套做法）。
 *
 * @param initialValue 站点拼接串（如「安徽省淮南市田家庵区」），用于反解初始三段；
 *                     解析不出时默认落在「安徽省」，这是本校学生的绝大多数场景。
 */
@Composable
fun AreaWheelPickerDialog(
    initialValue: String,
    onConfirm: (province: String, city: String, county: String) -> Unit,
    onDismiss: () -> Unit,
    title: String = "选择外出地点",
) {
    val context = LocalContext.current
    val tables = remember { AreaData.load(context) }

    val initial = remember(tables, initialValue) { AreaData.resolve(tables, initialValue) }
    val defaultProvince = remember(tables) { tables.provinces.firstOrNull { it.name == "安徽省" } ?: tables.provinces.firstOrNull() }

    var provinceIndex by remember(tables) {
        mutableIntStateOf(
            when {
                initial.first != null -> tables.provinces.indexOfFirst { it.code == initial.first!!.code }.coerceAtLeast(0)
                defaultProvince != null -> tables.provinces.indexOfFirst { it.code == defaultProvince.code }.coerceAtLeast(0)
                else -> 0
            }
        )
    }
    val province = tables.provinces.getOrNull(provinceIndex)
    val cities = remember(province) { tables.citiesOf(province) }
    var cityIndex by remember(province) {
        mutableIntStateOf(
            if (initial.second != null && province != null && initial.first?.code == province.code) {
                cities.indexOfFirst { it.code == initial.second!!.code }.coerceIn(0, (cities.size - 1).coerceAtLeast(0))
            } else 0
        )
    }
    val city = cities.getOrNull(cityIndex)
    val counties = remember(city) { tables.countiesOf(city) }
    var countyIndex by remember(city) {
        mutableIntStateOf(
            if (initial.third != null && city != null && initial.second?.code == city.code) {
                counties.indexOfFirst { it.code == initial.third!!.code }.coerceIn(0, (counties.size - 1).coerceAtLeast(0))
            } else 0
        )
    }

    if (tables.isEmpty) {
        // 数据缺失（资产损坏/加载失败）时不弹空轮子，直接取消。
        onDismiss()
        return
    }

    SystemDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        dismissButton = {
            SystemSecondaryButton(text = "取消", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = {
            SystemPrimaryButton(
                text = "确定",
                onClick = {
                    val p = tables.provinces.getOrNull(provinceIndex)
                    val c = cities.getOrNull(cityIndex)
                    val d = counties.getOrNull(countyIndex)
                    if (p != null && c != null && d != null) {
                        onConfirm(p.name, c.name, d.name)
                    } else {
                        onDismiss()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // 通铺一条中心指示片，三列不再各画一份（同 GlassDatePickerDialog）。
            WheelCenterLens(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .align(Alignment.Center)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlassWheelColumn(
                    items = tables.provinces.map { it.name },
                    selectedIndex = provinceIndex,
                    onSelect = { provinceIndex = it },
                    modifier = Modifier.weight(1f),
                    showCenterIndicator = false
                )
                GlassWheelColumn(
                    items = cities.map { it.name },
                    selectedIndex = cityIndex,
                    onSelect = { cityIndex = it },
                    modifier = Modifier.weight(1f),
                    showCenterIndicator = false
                )
                GlassWheelColumn(
                    items = counties.map { it.name },
                    selectedIndex = countyIndex,
                    onSelect = { countyIndex = it },
                    modifier = Modifier.weight(1.1f),
                    showCenterIndicator = false
                )
            }
        }
    }
}

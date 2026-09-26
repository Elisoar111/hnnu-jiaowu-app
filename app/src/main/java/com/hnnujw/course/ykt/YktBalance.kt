package com.hnnujw.course.ykt

/**
 * 一卡通余额相关的**纯函数**（无 Android 依赖，可直接单测）。
 *
 * 这些逻辑的每一处都对应官方前端的一行代码，改动前先看清对照关系：
 * - [parseElectricityBalance] ↔ `chunk-3d2fd493.js` 的 `getBalanceFromString`（并按真机实测扩展）
 * - [computeTotal] ↔ `chunk-493ca944.js` / `chunk-395c2b3f.js` 的 `getCardInfo`
 * - [extractFeeItemId] ↔ `appScheme/info` 应用栏 `url` 的 `feeitemid` query（淮师口径）
 *
 * 金额单位：入参出参都是**元**，分转元在 [fenToYuan] 完成。
 */
object YktBalance {

    /** 服务端金额单位是分，转元。 */
    fun fenToYuan(fen: Long): Double = fen / 100.0

    /**
     * 从电费接口返回的文本里抽取余额，并**推断单位**。
     *
     * 官方实现（`getBalanceFromString`）只看标签后跟的第一个数字：
     * ```js
     * const n = ["剩余电量", "剩余金额", "剩余水费"];
     * const a = new RegExp(`(${n.join("|")})\\s*(?::)?\\s*([-+]?\\d*\\.?\\d+)`);
     * ```
     *
     * ## 真机实测的两种形态（**必须都吃下**）
     *
     * | 费项 | 服务端原文 |
     * |---|---|
     * | 181 | `信息: 用户名称1A-101,剩余电量为55.28元` |
     * | 201 | `信息: 房间名称: 7A-101 剩余电量:312.2` |
     *
     * 共同锚点只有「剩余电量」：一处是 `为<数>元`，一处是 `:<数>`（无「元」）。
     * 因此正则必须同时接受"为/是"这类连接字与可选冒号，**不能假设有"元"或假设逗号分隔**。
     *
     * 与官方的一处**有意差异**：官方抽不到时回退成 `0.00` 并直接显示，用户无法分辨
     * "真的没电了"和"解析失败"。这里返回 `null`，让界面显示"—"。宁可显示未知，
     * 也不要把解析失败伪装成余额 0。
     *
     * @param text 服务端返回的原文（如 `"剩余电量：56.78"`）
     */
    fun parseElectricityBalance(text: String): ElectricityBalance {
        val raw = text.trim()
        if (raw.isEmpty()) return ElectricityBalance(null, "", ElectricityUnit.UNKNOWN, raw)

        // 标签按官方顺序；冒号中英文都收（站点两种都出现过）
        val labels = listOf("剩余电量", "剩余金额", "剩余水费")
        for (label in labels) {
            val index = raw.indexOf(label)
            if (index < 0) continue
            val tail = raw.substring(index + label.length)
            val match = NUMBER_AFTER_LABEL.find(tail) ?: continue
            val value = match.groupValues[1].toDoubleOrNull() ?: continue
            return ElectricityBalance(value, label, inferUnit(raw, label), raw)
        }
        return ElectricityBalance(null, "", ElectricityUnit.UNKNOWN, raw)
    }

    /**
     * 推断数值单位。
     *
     * 判据优先级：
     * 1. 标签是「剩余金额」→ 一定是金额（这个标签本身就说明是钱）。
     * 2. **数字紧后面**跟着「元」→ 金额（如 `为55.28元`）。
     * 3. 其余情况按标签走：`剩余电量` / `剩余水费` 是量纲（度 / 立方），不是钱。
     *
     * ## 为什么必须"紧跟着"而不是"附近有"
     *
     * 真机 201 的原文是 `剩余电量:312.2`，但同一段文本里常还有
     * 「本次应缴合计 5 元」这类别的信息。若只看一个宽窗口里有没有「元」，
     * 就会把电量 312.2 误判成金额 312.2 元，进而被"低于 20 元"的提醒
     * 错误地当成"余额充足"。所以只认**紧跟在数字后**的那个「元」。
     */
    internal fun inferUnit(raw: String, label: String): ElectricityUnit {
        if (label == "剩余金额") return ElectricityUnit.YUAN
        val index = raw.indexOf(label)
        if (index < 0) return ElectricityUnit.UNKNOWN
        val tail = raw.substring(index + label.length)
        // 只在"数字之后紧邻的少量字符"里找单位字，避免被远处文字污染
        val match = NUMBER_AFTER_LABEL.find(tail)
        if (match != null) {
            val after = tail.substring(match.range.last + 1).take(UNIT_WINDOW)
            if (after.contains('元')) return ElectricityUnit.YUAN
        }
        return when (label) {
            "剩余电量" -> ElectricityUnit.KWH
            "剩余水费" -> ElectricityUnit.KWH
            else -> ElectricityUnit.UNKNOWN
        }
    }

    /**
     * 按 [YktEcardConfig] 口径计算可用总额（元）。
     *
     * 官方算法：
     * ```js
     * let n = 0;
     * "1" !== type && (n += (db_balance + unsettle_amount) / 100);
     * "2" !== type && (n += elec_accamt / 100);
     * ```
     */
    fun computeTotal(card: YktCard, config: YktEcardConfig): Double {
        var total = 0.0
        if (config.includesCardBalance) total += card.dbBalance + card.unsettleAmount
        if (config.includesElectricity) total += card.elecAccAmount
        return total
    }

    /** 金额展示：固定两位小数，避免 "12.5 元" / "12.50 元" 两种写法并存。 */
    fun formatYuan(amount: Double): String = String.format(java.util.Locale.US, "%.2f", amount)

    /** 数值 + 单位的展示串（度数型不能加"元"）。 */
    fun formatWithUnit(amount: Double, unit: ElectricityUnit): String = when (unit) {
        ElectricityUnit.YUAN -> "¥" + formatYuan(amount)
        ElectricityUnit.KWH -> formatYuan(amount) + " 度"
        ElectricityUnit.UNKNOWN -> formatYuan(amount)
    }

    /**
     * 计算「相对本月偏移 [offset] 个月」的**起止日期**（含首尾）。
     *
     * `offset = 0` → 本月；`-1` → 上月；`1` → 下月。
     * 返回 `yyyy-MM-dd` 对（服务端 `timeFrom` / `timeTo` 就吃这个格式）。
     *
     * ## 为什么用 [java.util.Calendar] 而不是手算
     *
     * 月末天数不固定（2 月 28/29、大小月 30/31），跨年还要进位月份。
     * 手写这些分支极易在边界上出错（"上月 31 日"在下月不存在）。
     * `Calendar` 会自动把 `set(MONTH, -1)` 归一化，跨年也正确，
     * 且它**不是 Android API**，纯 JVM 就能单测。
     *
     * 结束日取该月**最后一天**（用 `getActualMaximum(DAY_OF_MONTH)`），
     * 这样"本月"在月中查询时也能覆盖到月底，不会漏掉未来几日（服务端忽略未来无流水即可）。
     *
     * @param offset 相对本月的偏移（可为负）
     * @param today 参考"今天"（毫秒时间戳）；默认当前时间。抽成参数便于单测钉死结果。
     */
    fun monthRange(offset: Int, today: Long = System.currentTimeMillis()): Pair<String, String> {
        val calendar = java.util.Calendar.getInstance().apply { timeInMillis = today }
        calendar.set(java.util.Calendar.DAY_OF_MONTH, 1)
        calendar.add(java.util.Calendar.MONTH, offset)
        val from = formatDate(calendar)
        calendar.set(java.util.Calendar.DAY_OF_MONTH, calendar.getActualMaximum(java.util.Calendar.DAY_OF_MONTH))
        val to = formatDate(calendar)
        return from to to
    }

    /**
     * 月份的中文标签：`0` → "本月"，`-1` → "上月"，其余 → "2026年7月"。
     *
     * @param offset 相对本月的偏移
     * @param today 参考"今天"（毫秒时间戳），便于单测
     */
    fun monthLabel(offset: Int, today: Long = System.currentTimeMillis()): String {
        if (offset == 0) return "本月"
        if (offset == -1) return "上月"
        val calendar = java.util.Calendar.getInstance().apply {
            timeInMillis = today
            set(java.util.Calendar.DAY_OF_MONTH, 1)
            add(java.util.Calendar.MONTH, offset)
        }
        return "${calendar.get(java.util.Calendar.YEAR)}年${calendar.get(java.util.Calendar.MONTH) + 1}月"
    }

    /** 把 `Calendar` 输出成 `yyyy-MM-dd`（月份 0 基 → 加 1）。 */
    private fun formatDate(calendar: java.util.Calendar): String {
        val year = calendar.get(java.util.Calendar.YEAR)
        val month = calendar.get(java.util.Calendar.MONTH) + 1
        val day = calendar.get(java.util.Calendar.DAY_OF_MONTH)
        return String.format(java.util.Locale.US, "%04d-%02d-%02d", year, month, day)
    }

    /**
     * 从 URL（或内嵌 URL 的 JSON 串）里找出电费费项 ID。
     *
     * ## 淮师口径（2026-09-25 真机确证）
     *
     * `appScheme/info` 的首页应用栏项**明文带 `feeitemid`**：
     * ```
     * 应用项 website : /charge/feeitem/toAppitem?feeitemid=181   ← elcpay
     * 应用项 url     : {"name":"App","code":"app",…,
     *                   "url":"/charge/feeitem/toAppitem?feeitemid=181"}
     * ```
     * 两种字段都要能吃下：`website` 是**干净 URL**，而 `url` 是**一段 JSON 串**，
     * 里面再套一层 `url`。早期实现直接对 `url` 做 `substringAfter('?')`，
     * 会切出 `feeitemid=181"}`，value 带上尾部的 `"}` → 查询必然失败。
     *
     * ## 提取顺序（每一层都不能少）
     *
     * 1. 先剥离外层引号 / 反斜杠转义——`url` 是 JSON 串，形如 `{"...":"...?x=1"}`；
     *    若解出内层 `"url"` 字段就改用它（递归一层即可）。
     * 2. 再取 `?` 之后的 query，按 `&` 拆 key=value。
     * 3. value 侧再剥一次引号 / `}`（防服务端少转义），**只保留合法 ID 字符**。
     *
     * 两种 query 名都认：优先 `feeitemid`，兼容旧的 `showBal`。
     *
     * @param url 应用项 `website`（干净 URL）或 `url`（内嵌 JSON 串）
     */
    fun extractFeeItemId(url: String): String? {
        val direct = feeItemIdFromQuery(url)
        if (direct != null) return direct
        // url 是内嵌 JSON 串（{"…","url":"…?feeitemid=181"}）：解出内层 url 再试一次
        val inner = embeddedUrlValue(url)
        return if (inner != null) feeItemIdFromQuery(inner) else null
    }

    /** 从一段**已经展开的** query 串里取费项 ID；无则返回 null。 */
    private fun feeItemIdFromQuery(raw: String): String? {
        val query = raw.substringAfter('?', "")
        if (query.isEmpty()) return null
        val params = mutableMapOf<String, String>()
        for (pair in query.split('&')) {
            if (pair.isBlank()) continue
            val key = pair.substringBefore('=', "").trim().lowercase()
            val value = normalizeIdValue(pair.substringAfter('=', ""))
            if (value.isEmpty()) continue
            // 同名参数取先出现的（服务端一般不会重复，出现重复时保守取第一个）
            params.putIfAbsent(key, value)
        }
        // feeitemid 优先；showBal 是旧口径的等价物
        return params["feeitemid"] ?: params["showbal"]
    }

    /**
     * 归一化 query value：剥引号 / 花括号，剔除 JSON 残留。
     *
     * 形如 `181`、`181"`、`181"}` 都归一到 `181`；`181` 里的非 ID 字符
     * （`"`、`}`、空白）一律丢弃。费项 ID 只可能是数字（淮师实测 181/201），
     * 但不同学校可能用短码，所以只删明显是 JSON 残渣的字符，不强制数字。
     */
    private fun normalizeIdValue(raw: String): String {
        val cleaned = raw.trim().trim('"').trim('\'').trim()
        return cleaned.trimEnd('}', ']', '"', '\'').trim()
    }

    /**
     * 从内嵌 JSON 串里解出 `"url"` 字段的值（纯字符串扫描，不依赖 org.json）。
     *
     * 输入形如 `{"name":"App",…,"url":"/charge/feeitem/toAppitem?feeitemid=181"}`。
     * 找不到返回 `null`；输入本身就是裸 URL（不含 `"url"`）时同样返回 `null`。
     */
    internal fun embeddedUrlValue(raw: String): String? {
        val key = "\"url\""
        val at = raw.indexOf(key)
        if (at < 0) return null
        var cursor = at + key.length
        while (cursor < raw.length && (raw[cursor] == ':' || raw[cursor].isWhitespace())) cursor++
        if (cursor >= raw.length || raw[cursor] != '"') return null
        cursor++
        val sb = StringBuilder()
        while (cursor < raw.length) {
            val ch = raw[cursor]
            if (ch == '\\' && cursor + 1 < raw.length) {
                sb.append(raw[cursor + 1]); cursor += 2; continue
            }
            if (ch == '"') break
            sb.append(ch); cursor++
        }
        return sb.toString().ifBlank { null }
    }

    /**
     * 从应用栏（首页应用栏 或）`getAllApps` 的应用项里抽出**全部**电费费项。
     *
     * ## 输入可来自两处（结构不同，字段名都对上）
     *
     * - `appScheme/info` 的首页应用栏项：`appName` + `website` + `url`（JSON 串）
     * - `getAllApps` 的"全部应用"项：`appName` + `website` + `url`（JSON 数组串）
     *
     * 两处都实测出现 `1-6单元电控缴费`(`elcpay`,181) 与
     * `7-11单元及东区电控缴费`(`elec`,201)，**取一处即可**；本函数字段名兼容，
     * 不关心数据源，只要每项含 `website` / `url` 之一。
     *
     * ## 名称取值
     *
     * 先 `appName`（服务端主字段，两处都有），再 `name`（部分构建改名）。
     * 都空则用 `电费 <id>` 兜底，保证界面上是可读选项。
     *
     * 这里只做"给定一组应用项就抽出费项列表"这一层，不做网络。
     *
     * @param appList 反序列化后的一组应用项；每项需含 `url` 或 `website`
     */
    fun collectFeeItems(appList: List<Map<String, Any?>>): List<YktFeeItem> {
        val items = mutableListOf<YktFeeItem>()
        val seen = mutableSetOf<String>()
        for (app in appList) {
            // website 是干净 URL，url 是内嵌 JSON 串；两者都要试（顺序：先 website）
            val candidates = listOfNotNull(
                app["website"]?.toString()?.takeIf { it.isNotBlank() },
                app["url"]?.toString(),
            )
            val id = candidates.firstNotNullOfOrNull { extractFeeItemId(it) } ?: continue
            // 同一 feeitemid 只留一条（缴费/查询可能配成同一个 id）
            if (!seen.add(id)) continue
            val name = app["appName"]?.toString().orEmpty().trim()
                .ifBlank { app["name"]?.toString().orEmpty().trim() }
            val appCode = app["appCode"]?.toString().orEmpty().trim()
            items += YktFeeItem(
                feeItemId = id,
                name = name.ifBlank { "电费 $id" },
                appCode = appCode,
            )
        }
        return items
    }

    /**
     * 从 `/charge/feeitem/getThirdDataByFeeItemId` 的响应里取出待解析文本。
     *
     * 响应形状：`{ map: { showData: { "信息": "剩余电量：56.78" } } }`（`chunk-3d2fd493.js`）。
     * 字段名就是中文"信息"，与服务端契约一致。
     */
    fun extractShowDataText(showData: Map<String, Any?>?): String {
        if (showData == null) return ""
        val value = showData["信息"] ?: return ""
        return value?.toString().orEmpty()
    }

    // ── 「场景字典」级联（charge-pc 真实口径，2026-09-25 实测）────────────────

    /**
     * 解析费项详情里的 `interfacechoice` 为级定义列表。
     *
     * 格式：`<中文名>_<参数名>`，逗号分隔，**顺序即层级顺序**（从第 1 级开始）。
     * 例：`校区_campus,楼栋_building,楼层_floor,房间_room`
     * → `[YktSceneLevel("campus",1,"校区"), ("building",2,"楼栋"), …]`。
     *
     * 容错：忽略空段；`_` 后无参数名的段跳过（提交时没有参数名无从发起）；
     * 参数名带空白会被 trim。**级数由这里决定，UI 不得写死。**
     */
    fun parseInterfaceChoice(raw: String): List<YktSceneLevel> {
        if (raw.isBlank()) return emptyList()
        val result = mutableListOf<YktSceneLevel>()
        for (segment in raw.split(',')) {
            val trimmed = segment.trim()
            if (trimmed.isEmpty()) continue
            val idx = trimmed.lastIndexOf('_')
            if (idx <= 0 || idx == trimmed.length - 1) continue
            val name = trimmed.substring(0, idx).trim()
            val code = trimmed.substring(idx + 1).trim()
            if (code.isEmpty()) continue
            result += YktSceneLevel(code = code, level = result.size + 1, name = name.ifBlank { code })
        }
        return result
    }

    /**
     * 解析 `getThirdData` 响应里的级定义 `map.total[]`。
     *
     * 形状：`[{code:"campus", level:1, name:"校区"}, {code:"building", level:2, …}, …]`。
     *
     * **级数是动态的** —— 淮师电费是 4 级，别的费项可能更少。UI 必须按返回的
     * 列表渲染，不得写死 4。按 `level` 升序返回，保证渲染顺序稳定。
     */
    fun parseSceneLevels(root: org.json.JSONArray?): List<YktSceneLevel> {
        if (root == null) return emptyList()
        val result = mutableListOf<YktSceneLevel>()
        for (i in 0 until root.length()) {
            val item = root.optJSONObject(i) ?: continue
            val code = item.optString("code").trim()
            val name = item.optString("name").trim()
            val level = item.optInt("level", 0)
            if (code.isEmpty() || level <= 0) continue
            result += YktSceneLevel(code = code, level = level, name = name.ifBlank { code })
        }
        return result.sortedBy { it.level }
    }

    /**
     * 解析 `getThirdData` 响应的 `map.data[]` 为候选项。
     *
     * ## 与 EasyUI 版本的关键差异（**最易踩坑**）
     *
     * 真实值是 **`"<id>&<name>"`** 形态（`1&本校区`、`011&01号学生公寓A区`），
     * 需要**原样回传**。所以这里**保留完整 value**，`name` 用响应里的 `name` 字段。
     *
     * 不要用 [sceneValue] 那套"多候选主键"逻辑 —— 那是给老 EasyUI 页面的，
     * 会误把 `1&本校区` 当成非法值或截断成 `1`，导致下一级查不到。
     *
     * @param root 已解析的 JSON 数组（`map.data`）
     * @param dedupeByValue 是否按 value 去重（同 value 只留一条）
     */
    fun parseSceneData(root: org.json.JSONArray?, dedupeByValue: Boolean = true): List<SceneOption> {
        if (root == null) return emptyList()
        val result = mutableListOf<SceneOption>()
        val seen = mutableSetOf<String>()
        for (i in 0 until root.length()) {
            val item = root.optJSONObject(i) ?: continue
            val rawValue = item.opt("value") ?: item.opt("id") ?: continue
            if (rawValue === org.json.JSONObject.NULL) continue
            val value = rawValue.toString().trim()
            if (value.isEmpty() || value == "null") continue
            if (dedupeByValue && !seen.add(value)) continue
            val name = item.optString("name").trim().ifBlank { value.substringAfter('&', value) }
            result += SceneOption(value = value, name = name)
        }
        return result
    }

    /**
     * 解析读数响应里的结构化上下文 `map.data{area,building,floor,room,account,…}`。
     *
     * 淮师实测字段：`areaName` / `buildingName` / `floorName` / `roomName` / `account`。
     * 取不到时返回 null（界面就不显示位置摘要），**不要伪造**。
     */
    fun parseSceneContext(item: org.json.JSONObject?): YktSceneContext? {
        if (item == null) return null
        val area = item.optString("areaName").trim()
        val building = item.optString("buildingName").trim()
        val floor = item.optString("floorName").trim()
        val room = item.optString("roomName").trim()
        val account = item.optString("account").trim()
        if (listOf(area, building, floor, room, account).all { it.isEmpty() }) return null
        return YktSceneContext(
            areaName = area,
            buildingName = building,
            floorName = floor,
            roomName = room,
            account = account,
        )
    }

    // ── 消费/充值流水（`/berserker-search/search/personal/turnover`）──────────

    /**
     * 解析流水列表响应。
     *
     * 响应形状（实测）：
     * ```
     * { code:200, success:true, msg:"操作成功",
     *   data:{ records:[{...}], total:1002, size:20, current:1, pages:51 } }
     * ```
     *
     * ## 为什么金额要按"分"处理
     *
     * `tranamt` / `cardBalance` / `ebagamt` 服务端一律给**分**。
     * 实测 `tranamt=1` 对应 **0.01 元**（4G 净水一笔），
     * 所以这里**保持分为单位**交给 [YktBillRecord]，
     * 换算只发生在展示层 —— 提前除 100 会让"分"精度在多处丢失。
     *
     * `cardBalance` 缺失时给 **null 而不是 0**：0 是"余额真的为 0"，
     * 而 null 是"服务端没给"，界面上要能区分（0 会吓到用户）。
     */
    fun parseBillPage(json: org.json.JSONObject?): YktBillPage {
        if (json == null) return YktBillPage()
        val data = json.optJSONObject("data") ?: return YktBillPage()
        val array = data.optJSONArray("records")
        val records = mutableListOf<YktBillRecord>()
        if (array != null) {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                records += parseBillRecord(item)
            }
        }
        return YktBillPage(
            records = records,
            total = data.optInt("total", records.size),
            current = data.optInt("current", 1),
            pages = data.optInt("pages", 0),
        )
    }

    /**
     * 解析单条流水。
     *
     * 字段名全部来自官方 `/campus-card-pc/` 的 `billDetails` 页面（实测核对过）。
     * 缺字段一律给空串/null，**不用 `optString` 直接取** ——
     * 它对 `JSONObject.NULL` 会返回字符串 `"null"`，于是界面上真的会显示 "null"。
     */
    private fun parseBillRecord(item: org.json.JSONObject): YktBillRecord {
        return YktBillRecord(
            orderId = billText(item, "orderId"),
            tradeTime = billText(item, "jndatetimeStr"),
            effectTime = billText(item, "effectdateStr"),
            amountCent = billLong(item, "tranamt"),
            title = billText(item, "resume"),
            merchant = billText(item, "toMerchant"),
            payName = billText(item, "payName"),
            typeFrom = billText(item, "typeFrom"),
            // 缺失与 0 语义不同：缺失给 null（界面不显示余额），0 才显示 ¥0.00
            cardBalanceCent = billLongOrNull(item, "cardBalance"),
            location = billText(item, "locationName"),
            remark = billText(item, "remark"),
            icon = billText(item, "icon"),
        )
    }

    /**
     * 取字符串字段并把 `JSONObject.NULL` / `"null"` / 空串统一归一成空串。
     *
     * **必须归一**：服务端大量字段是可空对象（`consumeTypeName: null`、
     * `labelName: ""`），直接用 `optString` 会在界面上渲染出字面量 "null"。
     * 姓名类字段还带尾部空格（实测 `userName="胡敏翔     "`），一并 trim。
     */
    internal fun billText(item: org.json.JSONObject, key: String): String {
        val raw = item.opt(key) ?: return ""
        if (raw === org.json.JSONObject.NULL) return ""
        val text = raw.toString().trim()
        return if (text.equals("null", ignoreCase = true)) "" else text
    }

    /** 取整数字段（金额/编号），缺失或非法一律给 0。 */
    internal fun billLong(item: org.json.JSONObject, key: String): Long {
        val raw = item.opt(key) ?: return 0L
        if (raw === org.json.JSONObject.NULL) return 0L
        return when (raw) {
            is Number -> raw.toLong()
            else -> raw.toString().trim().toLongOrNull() ?: 0L
        }
    }

    /** 取**可空**整数字段：缺失 / null → null（与"真的是 0"区分开）。 */
    internal fun billLongOrNull(item: org.json.JSONObject, key: String): Long? {
        if (!item.has(key)) return null
        val raw = item.opt(key) ?: return null
        if (raw === org.json.JSONObject.NULL) return null
        return when (raw) {
            is Number -> raw.toLong()
            else -> raw.toString().trim().toLongOrNull()
        }
    }

    /**
     * 从 EasyUI combobox 的响应里解析选项列表。
     *
     * 兼容三种可能形状（无法在真机外确定服务端用哪种，所以都吃下）：
     * 1. `[{name, value}, ...]`（EasyUI 直接返回数组）
     * 2. `{rows: [...]}`（EasyUI datagrid 风格包装）
     * 3. `{data: [...]}`（新中新网关风格包装）
     *
     * `value` 为空的项直接丢弃——空 value 提交上去必然查不到。
     */
    fun parseSceneOptions(box: String): List<SceneOption> {
        val trimmed = box.trim()
        if (trimmed.isEmpty()) return emptyList()
        val root = runCatching { org.json.JSONTokener(trimmed).nextValue() }.getOrNull() ?: return emptyList()
        return parseSceneOptionsFromAny(root)
    }

    /** [parseSceneOptions] 的内部实现，接收已解析的 JSON 值（便于单测直接喂 Map/List）。 */
    internal fun parseSceneOptionsFromAny(root: Any?): List<SceneOption> {
        val array = when (root) {
            is org.json.JSONArray -> root
            is org.json.JSONObject -> root.optJSONArray("rows")
                ?: root.optJSONArray("data")
                ?: root.optJSONArray("list")
                ?: return emptyList()
            else -> return emptyList()
        }
        val result = mutableListOf<SceneOption>()
        val seen = mutableSetOf<String>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val value = sceneValue(item) ?: continue
            if (!seen.add(value)) continue
            val name = sceneText(item) ?: value
            result += SceneOption(value = value, name = name)
        }
        return result
    }

    /**
     * 取 combobox 项的提交值。
     *
     * 官方前端配的是 `valueField:'value'`，所以 `value` 优先；
     * 但不同模块的字段名不统一（房间有时叫 `roomid`），故做了多候选兜底。
     * 候选顺序 = 先服务端契约里的 `value`，再按各模块常见主键名。
     */
    private fun sceneValue(item: org.json.JSONObject): String? {
        val keys = listOf("value", "id", "code", "roomid", "buildingid", "campusid", "floorid")
        for (key in keys) {
            val raw = item.opt(key) ?: continue
            if (raw === org.json.JSONObject.NULL) continue
            val text = raw.toString().trim()
            if (text.isNotEmpty() && text != "null") return text
        }
        return null
    }

    /** 取 combobox 项的展示文本。官方配的是 `textField:'name'`。 */
    private fun sceneText(item: org.json.JSONObject): String? {
        for (key in listOf("name", "text", "label", "title", "roomname")) {
            val raw = item.opt(key) ?: continue
            if (raw === org.json.JSONObject.NULL) continue
            val text = raw.toString().trim()
            if (text.isNotEmpty() && text != "null") return text
        }
        return null
    }

    /**
     * 标签后允许：可选「为 / 是 / 冒号」等连接字 → 空白 → 带符号数字。
     *
     * 覆盖真机两种形态：`剩余电量为55.28元` 与 `剩余电量:312.2`。
     */
    private val NUMBER_AFTER_LABEL = Regex("""^[\s:：为是]*([-+]?\d+(?:\.\d+)?)""")

    /**
     * 判断单位时，只看**数字之后紧邻**的这么几个字符。
     *
     * 取 4 是因为真机形态只有 `元`、` 元`、`度` 这几种，留 4 个字符足以容纳
     * 一个空格或一个冒号，又不会远到把下一句话里的「元」算进来
     *（实测 201 后面常跟「本次应缴合计 5 元」，窗口一大就会误判）。
     */
    private const val UNIT_WINDOW = 4

    // ── 挂失 / 解挂结果 ───────────────────────────────────────────────────

    /**
     * 解析挂失/解挂的业务结果。
     *
     * ## 业务码口径（照抄官方 `cardOperation`）
     *
     * - 成功：`retcode == "0"`
     * - **挂失**额外接受 `"60007"`：官方对该码也提示成功。
     *   语义应是"该卡已处于挂失态"——对用户而言结果相同（卡确实挂了），
     *   若判成失败会让用户反复重试，而重试并不会改变结果。
     * - 解挂**不接受** `60007`：解挂的目标态是"正常"，此时 60007 的含义
     *   与目标相反，不能当成功。
     * - 其余码（如密码错误）→ 失败，把服务端 `errmsg` 原样带回。
     *
     * @param isLostOperation true = 挂失（额外接受 60007）；false = 解挂
     */
    fun parseLostResult(json: org.json.JSONObject?, isLostOperation: Boolean): YktLostResult {
        if (json == null) return YktLostResult(false, "", "一卡通系统返回了空结果，请稍后重试")
        val data = json.optJSONObject("data")
        val retcode = json.pickString("retcode")
            .ifBlank { data?.let { json0 -> json0.pickString("retcode") }.orEmpty() }
        val message = json.pickString("errmsg")
            .ifBlank { data?.let { json0 -> json0.pickString("errmsg") }.orEmpty() }
        val success = retcode == RETCODE_OK ||
            (isLostOperation && retcode == RETCODE_ALREADY_LOST)
        return YktLostResult(success = success, retcode = retcode, message = message)
    }

    /** 从对象里取字符串字段，`JSONObject.NULL` 与字面量 "null" 都归一成空串。 */
    private fun org.json.JSONObject.pickString(key: String): String {
        val raw = opt(key) ?: return ""
        if (raw === org.json.JSONObject.NULL) return ""
        val text = raw.toString().trim()
        return if (text.equals("null", ignoreCase = true)) "" else text
    }

    /** 业务成功码。 */
    internal const val RETCODE_OK: String = "0"

    /** 挂失时视为成功的"已是挂失态"码。 */
    internal const val RETCODE_ALREADY_LOST: String = "60007"
}

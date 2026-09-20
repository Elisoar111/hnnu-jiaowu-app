package com.hnnujw.course.secondclass

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 第二课堂站点的只读客户端。
 *
 * ## 协议要点（实测自 ekta.hnnu.edu.cn）
 *
 * - 请求体/查询串统一是 `params=<编码后的 JSON>`，且是**双重 URL 编码**：
 *   站点前端 `encodeURI(JSON)` 之后又交给 qs 编码一次，服务端也解两次。
 *   只编一层会被拒（`参数格式不正确，参数必须是JSON格式`）。
 * - 站点前端的 `Encrypt` 在该构建里是恒等函数，所以没有签名/加密，照抄编码即可。
 * - 认证靠 `Authorization: <access_token>`，没有 Cookie 依赖。
 *
 * 这里**只做查询**，不提供任何写接口：第二课堂的报名/申报一律回到官方站点操作。
 */
class SecondClassroomClient(
    baseUrl: String,
    private val schoolCode: String,
    private val client: OkHttpClient = defaultClient(),
) {
    private val base = baseUrl.trim().trimEnd('/')

    // ── 认证 ──────────────────────────────────────────────────────────────

    /**
     * 换取 access_token。凭据错误会抛 [SecondClassException]（sessionExpired=false）。
     *
     * **令牌在统一信封的 `data` 里，不在顶层** —— 本站所有接口都是
     * `{code:0, data:{…}}` 的形状（本文件其余方法也都从 `data` 取数）。
     * 只读顶层 `access_token` 会在登录**已经成功**的情况下误报"缺少 access_token"。
     * 这里按 `data.access_token` → `data.token` → 顶层同名键 的顺序兜底，
     * 并兼容 `data` 直接是字符串的构建。
     */
    suspend fun login(studentId: String, password: String): String {
        val body = JSONObject()
            .put("schoolCode", schoolCode)
            .put("code", studentId)
            .put("password", password)
        val payload = post("/token", body, token = null)
        val data = payload.opt("data")
        val token = (
            when {
                data is JSONObject -> firstToken(data)
                data is String -> data.takeIf { it.isNotBlank() }
                else -> null
            } ?: firstToken(payload)
            ).orEmpty()
        if (token.isBlank()) {
            val where = if (data is JSONObject) {
                "data 字段：" + data.keys().asSequence().joinToString(",")
            } else {
                "顶层字段：" + payload.keys().asSequence().joinToString(",")
            }
            throw SecondClassException("登录返回缺少 access_token（$where）")
        }
        return token
    }
    /**
     * 令牌字段在不同构建/网关下命名不一，按常见命名依次取。
     *
     * 必须用 [text] 而不是 `optString`：后者对 JSON `null` 会返回字符串
     * `"null"`，于是"登录其实失败了"会被当成"拿到了 token 'null'"，
     * 后面的请求再带着一个假令牌去撞 10001。
     */
    private fun firstToken(json: JSONObject): String? =
        sequenceOf("access_token", "token", "accessToken")
            .map { json.text(it) }
            .firstOrNull { it.isNotBlank() }

    // ── 查询 ──────────────────────────────────────────────────────────────

    /** 成绩单表头：姓名/学号/院系/专业/年级 + 总积分 + 学时。 */
    suspend fun profile(token: String): SecondClassProfile {
        val data = get("/student/achievement/detail", null, token).optJSONObject("data") ?: return SecondClassProfile()
        val user = data.optJSONObject("user") ?: JSONObject()
        val myInfo = runCatching { get("/student/user/my-info", null, token).optJSONObject("data") }
            .getOrNull() ?: JSONObject()
        fun text(key: String): String = user.text(key).ifBlank { myInfo.text(key) }
        return SecondClassProfile(
            name = text("name"),
            code = text("code"),
            collegeName = text("collegeName"),
            majorName = text("majorName"),
            grade = text("grade"),
            score = user.optDouble("score", 0.0),
            hours = user.optDouble("hours", 0.0),
            scoreUnit = unitLabel(myInfo.opt("waysConvert").asInt()),
            hourUnit = myInfo.text("hourUnit"),
            avatar = text("avatar"),
            gender = user.opt("gender").asInt(),
        )
    }

    /**
     * 各模块积分状况。站点首页的雷达图用的就是这份数据：
     * `tags[].userValue` 是我的积分，`tags[].minHours` 是学校要求的下限。
     */
    suspend fun modules(token: String): List<SecondClassModule> {
        val data = get("/student/achievement/detail-app", null, token).optJSONObject("data") ?: return emptyList()
        return data.optJSONArray("tags").mapObjects { tag ->
            SecondClassModule(
                name = tag.text("name"),
                mine = tag.optDouble("userValue", 0.0),
                required = tag.optDouble("minHours", 0.0),
                average = tag.optDouble("schoolValue", 0.0),
            )
        }.filter { it.name.isNotBlank() }
    }

    /**
     * 指定层级的**完整**榜单 + 我的名次。
     *
     * ## 为什么要翻页，而不是一次 pageSize=20 了事
     *
     * 站点一次只回 `pageSize` 条，而响应里**没有 `lastPage`**（实测 `data` 只有
     * `total / pageNum / pageSize / list` 四个键）。原先写死 `pageSize = 20`，
     * 班级 37 人时后 17 个人**根本不进列表** —— 用户看到的"班级排名不全"就是它。
     *
     * ## 两个实测到的坑
     *
     * 1. **`rownum` 会并列**：班级 37 人里第 29、35 名各出现两次。所以翻页拼接
     *    不能按名次去重，必须按学号 `code`。
     * 2. **`pageSize` 放大是安全的**：实测 20/50/100/200/1000/5000 都能接受，
     *    `pageSize=50` 起一次就把 37 人全给了。所以这里先用一个较大的页长
     *    （[BOARD_PAGE_SIZE]）一次拿全，只有 `total` 比它还大时才继续翻页。
     *    这样绝大多数请求只打一次网络，同时"多少人都不漏"由循环兜住。
     *
     * 服务端若某天忽略 `pageNum`（一直回第一页），下面靠"本页没有新学号就收手"
     * 退出，不会死循环；[MAX_BOARD_PAGES] 是第二道保险。
     */
    suspend fun rankBoard(
        token: String,
        level: SecondClassRankLevel,
        pageSize: Int = BOARD_PAGE_SIZE,
    ): SecondClassRankBoard {
        val entries = mutableListOf<SecondClassRankEntry>()
        val seen = HashSet<String>()
        var total = 0
        var page = 1
        while (page <= MAX_BOARD_PAGES) {
            val request = JSONObject()
                .put("pageNum", page)
                .put("pageSize", pageSize)
                .put("level", level.level)
            val data = get("/student/achievement/rank", request, token).optJSONObject("data")
            val rows = data?.optJSONArray("list").mapObjects { it.toRankEntry() }
            if (page == 1) total = data?.optInt("total", rows.size) ?: rows.size
            if (rows.isEmpty()) break
            var added = 0
            for (row in rows) {
                if (seen.add(row.identity)) {
                    entries += row
                    added++
                }
            }
            // 本页一个新行都没贡献：说明服务端忽略了 pageNum，继续翻只会重复
            if (added == 0) break
            if (entries.size >= total) break
            // 不满一页说明已经是最后一页
            if (rows.size < pageSize) break
            page++
        }

        // 服务端的 rownum 只能当"顺序提示"，不能当名次显示（见 rerank 的说明）。
        val ranked = rerank(entries)

        val mine = runCatching { myRank(token, level) }.getOrNull()
            ?.let { entry ->
                // 我的名次同样以榜单内算出的名次为准：self/rank 的 rownum 也是服务端值，
                // 会出现"榜单里我排第 24、卡片上却写第 24 名"这种对不上的情况（并列时尤其明显）。
                val inBoard = ranked.firstOrNull { it.code.isNotBlank() && it.code == entry.code }
                    ?: ranked.firstOrNull { it.name == entry.name && it.score == entry.score }
                if (inBoard != null) entry.copy(rank = inBoard.rank) else entry
            }
        return SecondClassRankBoard(level = level, entries = ranked, myRank = mine, total = total)
    }

    /**
     * 用分数重算名次，**并列同名次**。
     *
     * ## 为什么不能用服务端的 `rownum`
     *
     * 实测班级 37 人，服务端给的 `rownum` 最大值只有 **35**：第 29 名和第 35 名
     * 各出现两次（并列），而 36、37 两个名次号被"挤掉"。直接显示就会出现
     * "两个 29 名、两个 35 名，最后一名不是 37"——用户看到的"排序的数字有问题"。
     *
     * 另外 `rownum` 在部分层级是浮点（院系层级实测 `233.0`），也不能直接当整数用。
     *
     * ## 规则
     *
     * 按 `score` 降序：同分者共享同一个名次（竞赛排名法，1/2/2/4），
     * 名次号等于"排名在我前面的人数 + 1"，因此**最大名次不会超过总人数**。
     * 名次相同时按学号稳定排序，保证每次刷新顺序一致。
     */
    private fun rerank(entries: List<SecondClassRankEntry>): List<SecondClassRankEntry> {
        if (entries.isEmpty()) return entries
        val sorted = entries.sortedWith(
            compareByDescending<SecondClassRankEntry> { it.score }.thenBy { it.code }.thenBy { it.name }
        )
        val result = ArrayList<SecondClassRankEntry>(sorted.size)
        var rank = 0
        var previousScore = Double.NaN
        sorted.forEachIndexed { index, entry ->
            if (index == 0 || entry.score != previousScore) rank = index + 1
            previousScore = entry.score
            result += entry.copy(rank = rank)
        }
        return result
    }

    /** 只要我的名次（`/student/achievement/self/rank`）；没进榜时返回 null。 */
    suspend fun myRank(token: String, level: SecondClassRankLevel): SecondClassRankEntry? {
        val data = get("/student/achievement/self/rank", JSONObject().put("level", level.level), token)
            .optJSONObject("data") ?: return null
        if (data.optString("name").isBlank()) return null
        return data.toRankEntry().copy(isSelf = true)
    }

    /**
     * 榜单行 → 模型。
     *
     * `avatar` 在站点上常常是 JSON `null`，而 `org.json` 的 `optString` 会把
     * `JSONObject.NULL` 序列化成字符串 **`"null"`** —— 直接拿去给图片库加载就是一个
     * 非法地址。所以文本字段统一过 [text]。
     */
    private fun JSONObject.toRankEntry(): SecondClassRankEntry = SecondClassRankEntry(
        rank = opt("rownum").asInt(),
        name = text("name"),
        majorName = text("majorName"),
        score = optDouble("score", 0.0),
        avatar = text("avatar"),
        gender = opt("gender").asInt(),
        code = text("code"),
    )

    // ── 传输 ──────────────────────────────────────────────────────────────

    private suspend fun get(path: String, params: JSONObject?, token: String?): JSONObject {
        val url = base + path + (params?.let { "?params=" + encodeParams(it.toString()) } ?: "")
        return request(url, null, token)
    }

    private suspend fun post(path: String, body: JSONObject, token: String?): JSONObject {
        // 必须用 addEncoded：FormBody.Builder.add() 会对 value 再做一次百分号编码，
        // 而服务端只解两层 —— 自己编一层再被 OkHttp 编一层就是**三层**，直接回
        // `10002 参数格式不正确，参数必须是JSON格式`（这就是登录一直失败的根因）。
        val form = FormBody.Builder().addEncoded("params", encodeParams(body.toString())).build()
        return request(base + path, form, token)
    }

    private suspend fun request(url: String, form: FormBody?, token: String?): JSONObject {
        val parsed = url.toHttpUrlOrNull() ?: throw SecondClassException("第二课堂地址无效")
        val builder = Request.Builder().url(parsed).header("User-Agent", USER_AGENT)
        if (token != null) builder.header("Authorization", token)
        if (form != null) builder.post(form) else builder.get()

        val text = try {
            execute(builder.build()).use { response ->
                if (response.code == 401 || response.code == 403) {
                    throw SecondClassException("第二课堂登录已失效，请重新登录", sessionExpired = true)
                }
                if (!response.isSuccessful) {
                    throw SecondClassException("第二课堂暂时不可用（HTTP ${response.code}），请稍后重试")
                }
                response.body?.string().orEmpty()
            }
        } catch (e: IOException) {
            throw SecondClassException("无法连接第二课堂，请检查网络或稍后重试", sessionExpired = false)
        }

        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw SecondClassException("第二课堂返回了无法识别的数据")
        }

        when (json.optInt("code", -1)) {
            0 -> return json
            // 10001 是站点统一的"未登录/登录失效"码，前端据此清 token 并跳登录页
            10001 -> throw SecondClassException("第二课堂登录已失效，请重新登录", sessionExpired = true)
            20002 -> throw SecondClassException("第二课堂账号或密码不正确")
            10007 -> throw SecondClassException("该学校尚未开通第二课堂服务")
            else -> throw SecondClassException(json.optString("msg").ifBlank { "第二课堂请求失败" })
        }
    }

    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) else response.close()
            }
        })
    }

    private inline fun <T> JSONArray?.mapObjects(transform: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        val result = ArrayList<T>(length())
        for (index in 0 until length()) {
            optJSONObject(index)?.let { result.add(transform(it)) }
        }
        return result
    }

    /**
     * 取文本，把 JSON `null` 归一化成空串。
     *
     * `optString` 对 `JSONObject.NULL` 会返回字符串 `"null"`（org.json 的
     * `JSON.toString` 走的是 `String.valueOf`），于是 `avatar = "null"` 这种值会
     * 一路流到图片加载器。所有文本字段都从这里出。
     */
    private fun JSONObject.text(key: String): String {
        val value = opt(key)
        if (value == null || value === JSONObject.NULL) return ""
        val rendered = value.toString().trim()
        return if (rendered == "null" || rendered == "undefined") "" else rendered
    }

    /** 数值字段在站点上时而 `1`、时而 `1.0`（`rownum` 就是浮点），统一取整。 */
    private fun Any?.asInt(): Int = when (this) {
        null -> 0
        is Number -> toDouble().toInt()
        is String -> toDoubleOrNull()?.toInt() ?: 0
        else -> 0
    }

    /** `waysConvert == 3` 时学校按分数展示，其余按学分。与站点 `scoreUnit` 的判定一致。 */
    private fun unitLabel(waysConvert: Int): String = if (waysConvert == 3) "分数" else "学分"

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        /**
         * 榜单页长。实测站点对上限很宽松（5000 也照收），班级 37 人用 50 就能一次拿全；
         * 取 100 是为了让"一次请求拿完"覆盖到绝大多数班级，同时留出翻页兜底。
         */
        private const val BOARD_PAGE_SIZE = 100

        /** 翻页保险丝。100 × 50 = 5000 行，远超任何真实榜单规模。 */
        private const val MAX_BOARD_PAGES = 50

        /**
         * 站点要求的编码：JSON 整体做**一层**百分号编码。
         *
         * 前端的 `encodeURI(json)` 不编码 `{}"` 等字符，随后被 `qs.stringify`
         * 一次编掉，净效果就是"完整编码一层"。服务端相应解两层 —— 所以
         * **一层和两层都能过**（对一层输入来说第二次解码是空操作），
         * 但**三层一定失败**（`10002 参数格式不正确，参数必须是JSON格式`）。
         *
         * 因此发送时必须保证线上恰好一层：GET 在这里手工编一层；
         * POST 走 `FormBody.addEncoded` 送编好的串，**不能用 `add()`**（它会再编一层）。
         *
         * `+` 换成 `%20` 只为与 `encodeURIComponent` 逐字对齐
         * （org.json 的 `toString()` 不含空格，实际等价）。
         */
        internal fun encodeParams(json: String): String =
            URLEncoder.encode(json, "UTF-8").replace("+", "%20")

        private fun defaultClient() = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

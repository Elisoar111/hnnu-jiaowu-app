package com.hnnujw.course.xuegong

import com.hnnujw.course.utils.RSAUtils
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 淮南师范学院**学工系统**（xg.hnnu.edu.cn）的只读客户端。
 *
 * ## 协议要点（实测自 xg.hnnu.edu.cn 手机端）
 *
 * - 全站**只有 HTTP**（`https://` 连接超时），因此必须登记在
 *   `res/xml/network_security_config.xml` 的明文白名单里。
 * - 认证：`POST /Account/Login?OpenId=`，表单 `grant_type=password&username=&password=`，
 *   密码用 H5 里写死的 JSEncrypt 公钥做 **RSA PKCS#1 v1.5** 加密后 Base64。
 *   成功返回 `{access_token, expires_in:86399, UserType:"S", Msg:"OK", …}`。
 * - 后续请求头是 `Authorization: Bearer <token>`（**带 Bearer 前缀**，与二课不同）。
 * - ⚠️ 站点**没有统一错误码**：`/DailyLeave/StuDisLeave` 实测回 `errcode:1, errmsg:"销假失败"`
 *   但 `data` 完全正常。所以这里只按 HTTP 状态判成败，**绝不拿 errcode 判失败**
 *   （否则用户会看到"加载失败"，其实数据就在响应里）。
 *
 * ## 提交边界（1.2.5 起）
 *
 * 提供两条**与官方 H5 完全同形**的提交链路：日常请假（`/DailyLeave/SaveForm`）
 * 与节假日去向登记（`/HolidayWhereabouts/SaveForm`）。实现照抄 H5 表单页
 * （`chunk-808c1020` / `chunk-3ab3b4f5`）的序列化：
 *
 * - 表单骨架先从 `GET /DailyLeave/Get?isExam=false&id=` /
 *   `GET /HolidayWhereabouts/Get?configId=&id=` 拿（字典、默认值、附件上传地址都在里面），
 *   提交时把用户改过的 `ApplyInfo` **原样回传**（qs 嵌套形式 `ApplyInfo[Key]=value`）——
 *   不猜字段名、不臆造默认值，服务端给的键原样送回去。
 * - 请假附件走 `POST {UpFilePath}`（multipart，字段名 `bytes`），
 *   成功判据 `et == "1"`，url 取 `data.split("|")[0]`。
 * - 成败判定：SaveForm 的 `errcode`（H5 就这么判的）；上传的 `et`。
 *   查询接口仍然只按 HTTP 状态判（见上）。
 *
 * **其余写入口一律没有**：销假、审批、撤销等操作不在本客户端里出现。
 */
class XuegongClient(
    baseUrl: String = BASE_URL,
    private val client: OkHttpClient = defaultClient(),
) {
    private val base = baseUrl.trim().trimEnd('/')

    // ── 认证 ──────────────────────────────────────────────────────────────

    /**
     * 用学号 + 密码换 access_token。凭据错误抛 [XuegongException]（sessionExpired=false）。
     *
     * 密码在客户端加密后**原样进表单**（站点要的就是 Base64 密文），
     * 明文密码不进任何日志。
     */
    suspend fun login(studentId: String, password: String): String {
        if (studentId.isBlank()) throw XuegongException("没有取到学号，请先登录教务系统")
        if (password.isEmpty()) throw XuegongException("请输入学工系统密码")

        val encrypted = try {
            RSAUtils.encryptWithPublicKey(PUBLIC_KEY, password)
        } catch (e: Exception) {
            // 公钥解析或加密失败：把原因说清楚，别让用户以为是密码错
            throw XuegongException("密码加密失败，请稍后重试")
        }

        val form = FormBody.Builder()
            .add("grant_type", "password")
            .add("username", studentId)
            .add("password", encrypted)
            .build()
        // OpenId 是 H5 留给微信授权登录的占位参数，账号密码登录时留空
        val json = request(base + "/Account/Login?OpenId=", form, token = null)

        val token = json.pick("access_token", "accessToken", "token")
        if (token.isBlank()) {
            val message = json.pick("Msg", "msg", "Message", "MessageInfo")
            throw XuegongException(message.ifBlank { "学工系统登录失败，请检查学号与密码" })
        }
        return token
    }

    // ── 查询 ──────────────────────────────────────────────────────────────

    /**
     * 日常请假列表：`POST /DailyLeave/StuDisLeave`，JSON 体 `{pageIndex, pageSize}`。
     *
     * 响应形状：`{data:{List:{…Items[]}, BtnText, CanApply, CanNotMsg, TudeInfo, UpFilePath}, errcode, errmsg}`。
     */
    suspend fun dailyLeaves(
        token: String,
        pageIndex: Int = 1,
        pageSize: Int = PAGE_SIZE,
    ): XuegongLeavePage {
        val body = JSONObject().put("pageIndex", pageIndex).put("pageSize", pageSize)
        val json = request(base + "/DailyLeave/StuDisLeave", body.asJsonBody(), token)
        val data = json.optJSONObject("data") ?: JSONObject()
        return XuegongLeavePage(
            page = data.optJSONObject("List").toPage(pageIndex) { it.toLeaveRecord() },
            canApply = data.opt("CanApply").asBooleanFlag(),
            applyButtonText = data.pick("BtnText"),
            applyBlockedReason = data.pick("CanNotMsg"),
            locationRule = data.optJSONObject("TudeInfo").toLocationRule(),
        )
    }

    /**
     * 节假日去向登记列表：`GET /HolidayWhereabouts/GetStuList?pageIndex=n`。
     *
     * ⚠️ 这个端点**没有统一信封**（裸对象，顶层直接是 `Config` / `PageList` /
     * `CollegeAsName`），不要再套一层 `optJSONObject("data")`。
     */
    suspend fun whereabouts(token: String, pageIndex: Int = 1): XuegongWhereaboutsPage {
        val json = request(base + "/HolidayWhereabouts/GetStuList?pageIndex=$pageIndex", null, token)
        return XuegongWhereaboutsPage(
            page = json.optJSONObject("PageList").toPage(pageIndex) { it.toWhereaboutsRecord() },
            batches = json.optJSONArray("Config").mapObjects { it.toHolidayBatch() },
            collegeLabel = json.pick("CollegeAsName").ifBlank { "院系" },
        )
    }

    // ── 表单（请假 / 去向登记的提交链路）──────────────────────────────────

    /**
     * 拉取「日常请假」表单骨架。
     *
     * 响应即 H5 表单页的整份状态：`ApplyInfo`（默认值，含 IsEdit / IsOut 等）、
     * `LeaveReason` / `OutGoVehicle` / `OutBackVehicle`（`{values:[{text,value}], defaultIndex}` 形态的字典）、
     * `FileList`、`SetInfo`、`Term`、`UpFilePath`（附件上传地址）等。
     *
     * @param id 空 = 新建请假；非空 = 修改已有请假（H5 的编辑入口带 Id）。
     */
    suspend fun dailyLeaveForm(token: String, id: String = ""): JSONObject =
        request(base + "/DailyLeave/Get?isExam=false&id=" + id, null, token)

    /**
     * 拉取「节假日去向登记」表单骨架。
     *
     * 响应形状与请假同构：`Config`（批次时间）、`ApplyInfo`（默认值）、
     * `OutGoVehicle`（交通字典）等。
     */
    suspend fun whereaboutsForm(token: String, configId: String, id: String = ""): JSONObject =
        request(base + "/HolidayWhereabouts/Get?configId=" + configId + "&id=" + id, null, token)

    /**
     * 上传请假附件。H5 的实现（`ve` 函数）：multipart，字段名**就是 `bytes`**，
     * 成功判据 `et === "1"`，url 取 `data.split("|")[0]`。
     *
     * @param upFilePath 表单骨架里的 `UpFilePath`（相对路径或完整 URL，两种都接）。
     * @return 图片 url（可直接填进 `FileList.Imgs[].url`）。
     */
    suspend fun uploadLeaveAttachment(
        token: String,
        upFilePath: String,
        bytes: ByteArray,
        fileName: String,
    ): String {
        val trimmed = upFilePath.trim()
        if (trimmed.isBlank()) throw XuegongException("学工系统没有给出附件上传地址，请稍后重试")
        val url = when {
            trimmed.startsWith("http") -> trimmed
            else -> "$base/" + trimmed.trimStart('/')
        }
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("bytes", fileName, bytes.toRequestBody(null))
            .build()
        val json = request(url, body, token)
        // 成功判据照 H5：et == "1"。et 不是 1 时 msg 里带原因（如文件类型不允许）。
        if (json.pick("et") != "1") {
            throw XuegongException(json.pick("msg").ifBlank { "附件上传失败，请重试" })
        }
        // data 形如 "url|其它信息"，与 H5 一致只取竖线前的 url
        return json.pick("data").substringBefore("|").trim()
    }

    /**
     * 提交日常请假。`applyInfo` 与 `fileList` 都来自 [dailyLeaveForm] 的响应
     * （`fileList` 传 null 时按空对象处理），用户改过若干字段后**原样回传** ——
     * 不要自己 new 一个空的：服务端默认值只在 GET 里下发。
     *
     * `imgs` 是已上传的附件（url → 备注名），与 H5 一致写进 `FileList.Imgs`。
     * 成败照 H5 判 `errcode`：0 = 成功，否则把 `errmsg` 原样抛给用户。
     */
    suspend fun saveDailyLeave(
        token: String,
        applyInfo: JSONObject,
        fileList: JSONObject? = null,
        imgs: List<Pair<String, String>> = emptyList(),
    ) {
        val list = (fileList ?: JSONObject()).let { JSONObject(it.toString()) }
        val imgArray = list.optJSONArray("Imgs") ?: JSONArray()
        imgs.forEach { (url, name) ->
            imgArray.put(JSONObject().put("url", url).put("AddressName", name))
        }
        list.put("Imgs", imgArray)

        val payload = JSONObject()
            .put("ApplyInfo", applyInfo)
            .put("FileList", list)
        val body = qsFormBody(payload)
        val json = request(base + "/DailyLeave/SaveForm", body, token)
        if (json.optInt("errcode", -1) != 0) {
            throw XuegongException(json.pick("errmsg").ifBlank { "提交失败，请稍后重试" })
        }
    }

    /**
     * 提交节假日去向登记。与请假同一条回传策略：`applyInfo` 来自
     * [whereaboutsForm] 的响应，用户改完字段后原样回传（qs 形式 `model[Key]=value`）。
     */
    suspend fun saveWhereabouts(token: String, applyInfo: JSONObject) {
        val body = qsFormBody(JSONObject().put("model", applyInfo))
        val json = request(base + "/HolidayWhereabouts/SaveForm", body, token)
        if (json.optInt("errcode", -1) != 0) {
            throw XuegongException(json.pick("errmsg").ifBlank { "提交失败，请稍后重试" })
        }
    }

    /**
     * 把嵌套 JSON 拍平成 H5 `$qs.stringify` 的形态：
     * `{ApplyInfo:{A:1}}` → `ApplyInfo[A]=1`；数组带下标 `FileList[Imgs][0][url]=…`。
     *
     * 站点后端（ASP.NET）对 `Parent[Key]` 与 `Parent.Key` 两种绑定都认，但这里必须与
     * H5 完全一致 —— 服务端除了模型绑定还会按原样的键做校验。
     */
    private fun qsFormBody(payload: JSONObject): RequestBody {
        val builder = FormBody.Builder()
        flattenQs(payload, null, builder)
        return builder.build()
    }

    private fun flattenQs(node: Any?, prefix: String?, builder: FormBody.Builder) {
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    flattenQs(node.opt(key), prefix?.let { "$it[$key]" } ?: key, builder)
                }
            }
            is JSONArray -> {
                for (index in 0 until node.length()) {
                    flattenQs(node.opt(index), "${prefix}[$index]", builder)
                }
            }
            else -> {
                val key = prefix ?: return
                builder.add(key, when (node) {
                    null, JSONObject.NULL -> ""
                    else -> node.toString()
                })
            }
        }
    }

    // ── 解析 ──────────────────────────────────────────────────────────────

    private fun JSONObject.toLeaveRecord(): XuegongLeaveRecord = XuegongLeaveRecord(
        id = pick("Id", "id", "LeaveId", "DailyLeaveId"),
        // LeaveType 在实测数据里是字典 id（"1"），文字在 LeaveTypeText / LeaveTypeName
        type = pick("LeaveTypeText", "LeaveTypeName", "LeaveTypeNameText", "TypeName")
            .ifBlank { pick("LeaveTypeNameValue") },
        beginTime = tidyDate(pick("LeaveBeginTime", "BeginTime", "LeaveStartTime", "StartTime")),
        endTime = tidyDate(pick("LeaveEndTime", "EndTime", "LeaveStopTime", "StopTime")),
        days = pick("LeaveDays", "Days", "LeaveDay", "TotalDays"),
        reason = pick("LeaveReason", "Reason", "ApplyReason", "LeaveReasonText", "Remark"),
        status = pick("StatusName", "StatusText", "ApproveStatusName", "FlowStatusName")
            .ifBlank { pick("Status") },
        applyTime = tidyDate(pick("InsertDate", "ApplyDate", "CreateTime", "AddTime", "ApplyTime")),
        extras = extrasOf(LEAVE_KNOWN_KEYS)
    )

    private fun JSONObject.toWhereaboutsRecord(): XuegongWhereaboutsRecord =
        XuegongWhereaboutsRecord(
            id = pick("Id", "id"),
            holidayId = pick("Hid", "HolidayId"),
            holidayName = pick("HolidayName", "HolidayTitle"),
            leaveType = pick("LeaveTypeText", "LeaveTypeName"),
            registered = pick("IsRs", "RegisterState", "StatusName"),
            beginTime = tidyDate(pick("LeaveBeginTime", "BeginTime")),
            endTime = tidyDate(pick("LeaveEndTime", "EndTime")),
            days = pick("LeaveDays", "Days"),
            reason = pick("LeaveReason", "Reason"),
            destination = pick("ComeWhere")
                .ifBlank { listOf(pick("Province"), pick("City"), pick("County")).filter { it.isNotBlank() }.joinToString("") },
            vehicle = pick("OutGoVehicleText", "OutGoVehicleName"),
            contactName = pick("OutContacts"),
            contactRelation = pick("OutContactsRelationship"),
            contactTel = pick("OutContactsMoveTel", "OutContactsTel", "OutContactsPhone"),
            studentTel = pick("StuMoveTel", "MoveTel", "StuTel"),
            homePlace = pick("FMComeWhere", "FamillyAddress"),
            registerTime = tidyDate(pick("InsertDate", "RegDate", "CreateTime")),
            extras = extrasOf(WHEREABOUTS_KNOWN_KEYS)
        )

    private fun JSONObject.toHolidayBatch(): XuegongHolidayBatch = XuegongHolidayBatch(
        id = pick("value", "Id", "Hid"),
        name = pick("text", "HolidayName", "Name"),
        holidayBegin = tidyDate(pick("BeginDate", "HolidayBeginDate")),
        holidayEnd = tidyDate(pick("EndDate", "HolidayEndDate")),
        registerBegin = tidyDate(pick("RegBeginDate", "ApplyBeginDate")),
        registerEnd = tidyDate(pick("RegEndDate", "ApplyEndDate")),
        statusName = pick("StatusName"),
        memo = pick("Memo", "Remark"),
        open = pick("Status") == "1",
    )

    private fun JSONObject?.toLocationRule(): XuegongLocationRule {
        if (this == null) return XuegongLocationRule(required = false, rangeDistance = "")
        return XuegongLocationRule(
            required = opt("IsLocationDisLeave").asBooleanFlag(),
            rangeDistance = pick("RangeDistance"),
        )
    }

    /**
     * 未识别的字段（键 → 值），跳过空值与纯技术键。
     *
     * 存在的意义：日常请假的字段名是按站点惯例**推测**的（该测试账号 0 条记录，
     * 拿不到真实样本）。有了它，即使真实字段名与推测不同，用户点开详情仍能看到
     * 完整信息，而不是一片空白。
     */
    private fun JSONObject.extrasOf(known: Set<String>): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val keys = keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key in known || key in TECHNICAL_KEYS) continue
            val value = pick(key)
            if (value.isBlank()) continue
            out.add(key to value)
        }
        return out
    }

    // ── 传输 ──────────────────────────────────────────────────────────────

    private suspend fun request(url: String, body: RequestBody?, token: String?): JSONObject {
        var target = url
        var hops = 0
        while (true) {
            val parsed = target.toHttpUrlOrNull() ?: throw XuegongException("学工系统地址无效")
            val builder = Request.Builder()
                .url(parsed)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json, text/plain, */*")
                // 站点网关对缺失 Referer 的请求偶发拦截，照 H5 带上
                .header("Referer", REFERER)
                .header("X-Requested-With", "XMLHttpRequest")
            if (token != null) builder.header("Authorization", "Bearer $token")
            if (body != null) builder.post(body) else builder.get()

            val code: Int
            val text: String
            val location: String?
            try {
                execute(builder.build()).use { response ->
                    code = response.code
                    text = response.body?.string().orEmpty()
                    location = response.header("Location")
                }
            } catch (e: IOException) {
                throw XuegongException("无法连接学工系统，请检查网络或稍后重试")
            }

            // 只跟同源跳转：请求头里带着 Bearer 令牌，跨站跳转等于把令牌送出去。
            // 未登录时站点会 302 到 Phone/index.html，跟过去拿到的就是 HTML 登录页，
            // 下面的 parse() 会把这种情况翻成"登录已失效"。
            if (code in 300..399 && location != null && hops < MAX_REDIRECTS) {
                val next = parsed.resolve(location)
                    ?: throw XuegongException("学工系统返回了无法识别的跳转地址")
                if (next.scheme != parsed.scheme || next.host != parsed.host) {
                    throw XuegongException("学工系统返回了跨站跳转，已拒绝")
                }
                target = next.toString()
                hops++
                continue
            }

            if (code == 401 || code == 403) {
                throw XuegongException("学工系统登录已失效，请重新登录", sessionExpired = true)
            }
            if (code !in 200..299) {
                throw XuegongException("学工系统暂时不可用（HTTP $code），请稍后重试")
            }
            return parse(text)
        }
    }

    private fun parse(text: String): JSONObject {
        val trimmed = text.trim()
        // 站点未登录时直接吐 HTML 登录页（不是 JSON 错误体）
        if (trimmed.startsWith("<")) {
            throw XuegongException("学工系统登录已失效，请重新登录", sessionExpired = true)
        }
        return try {
            JSONObject(trimmed)
        } catch (e: Exception) {
            throw XuegongException("学工系统返回了无法识别的数据")
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

    // ── 小工具 ────────────────────────────────────────────────────────────

    /**
     * 取文本，按候选键依次尝试，把 JSON `null` / 空串归一化成空串。
     *
     * 不用 `optString`：它对 `JSONObject.NULL` 会返回字符串 `"null"`，
     * 于是"服务端没给这个字段"会变成界面上真的显示一个 `null`。
     */
    private fun JSONObject.pick(vararg keys: String): String {
        for (key in keys) {
            val value = opt(key) ?: continue
            if (value === JSONObject.NULL) continue
            val rendered = value.toString().trim()
            if (rendered.isEmpty() || rendered == "null" || rendered == "undefined") continue
            return rendered
        }
        return ""
    }

    private inline fun <T> JSONArray?.mapObjects(transform: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        val out = ArrayList<T>(length())
        for (index in 0 until length()) {
            optJSONObject(index)?.let { out.add(transform(it)) }
        }
        return out
    }

    private inline fun <T> JSONObject?.toPage(requestedIndex: Int, transform: (JSONObject) -> T): XuegongPage<T> {
        if (this == null) return XuegongPage(emptyList(), 0, requestedIndex, 1)
        val items = ArrayList<T>()
        optJSONArray("Items")?.let { array ->
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let { items.add(transform(it)) }
            }
        }
        val total = optInt("TotalItems", items.size)
        val current = optInt("CurrentPage", requestedIndex)
        val pages = optInt("TotalPages", 1).coerceAtLeast(1)
        return XuegongPage(items, total, current, pages)
    }

    /** `true` / `"true"` / `1` / `"1"` 都算真。站点这几处写法不统一。 */
    private fun Any?.asBooleanFlag(): Boolean = when (this) {
        null, JSONObject.NULL -> false
        is Boolean -> this
        is Number -> toDouble() != 0.0
        is String -> equals("true", ignoreCase = true) || this == "1"
        else -> false
    }

    /** `2026/07/13 00:00` → `2026-07-13 00:00`，与 App 其它页面的日期写法统一。 */
    private fun tidyDate(value: String): String =
        if (value.isBlank()) "" else value.replace(Regex("(\\d{4})/(\\d{1,2})/(\\d{1,2})"), "$1-$2-$3")

    private fun JSONObject.asJsonBody(): RequestBody = toString().toRequestBody(JSON_MEDIA_TYPE)

    companion object {
        /** 学工系统手机端的 API 前缀（H5 里 `Vue.prototype.$http` 的 baseURL）。 */
        const val BASE_URL: String = "http://xg.hnnu.edu.cn/PhoneApi/api"

        /** 站点 H5 的入口地址，作 Referer 用。 */
        const val REFERER: String = "http://xg.hnnu.edu.cn/Phone/index.html"

        /**
         * H5 bundle 里写死的 JSEncrypt 公钥（X.509 SubjectPublicKeyInfo 形态）。
         * 换公钥的话站点会先上线，所以这个常量要跟着 `app.js` 走。
         */
        const val PUBLIC_KEY: String =
            "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCK4n2xrbtnRyBqMJ2iiDeDRdJ/F8EVmzcjSGy/" +
                "vVNfEVahl6sQOjQXZTc8AEbiZdyLnP9QwX3ZkIsEGUz1VMaPUJeHLHQC5uVljRWR0ORt4oiU7mt" +
                "N5ZsEl8gPQBzSbC7IpnXVRN1Mx7s/RlFsWZgkuZKbPjxcfgoA9zXyhmcHywIDAQAB"

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        /** 页长。学工列表项很少（本人一次几到几十条），20 足够。 */
        private const val PAGE_SIZE = 20

        /** 同源重定向的最大跳数，只用于挡重定向环。 */
        private const val MAX_REDIRECTS = 3

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** 纯技术字段，不进详情页的"其它信息"。 */
        private val TECHNICAL_KEYS = setOf(
            "Id", "id", "Hid", "Ip", "Area", "DataSource", "StudentId", "UserId",
            "InsertUserId", "EditUserId", "InsertUserName", "EditUserName",
            "Longitude", "Latitude", "Token", "token"
        )

        /** 日常请假里已被显式解析的键，剩下的进 extras。 */
        private val LEAVE_KNOWN_KEYS = setOf(
            "LeaveTypeText", "LeaveTypeName", "LeaveTypeNameText", "TypeName", "LeaveTypeNameValue",
            "LeaveBeginTime", "BeginTime", "LeaveStartTime", "StartTime",
            "LeaveEndTime", "EndTime", "LeaveStopTime", "StopTime",
            "LeaveDays", "Days", "LeaveDay", "TotalDays",
            "LeaveReason", "Reason", "ApplyReason", "LeaveReasonText", "Remark",
            "StatusName", "StatusText", "ApproveStatusName", "FlowStatusName", "Status",
            "InsertDate", "ApplyDate", "CreateTime", "AddTime", "ApplyTime"
        )

        /** 去向登记里已被显式解析的键（其余 20 多个进 extras）。 */
        private val WHEREABOUTS_KNOWN_KEYS = setOf(
            "HolidayName", "HolidayTitle", "LeaveTypeText", "LeaveTypeName", "IsRs",
            "RegisterState", "LeaveBeginTime", "BeginTime", "LeaveEndTime", "EndTime",
            "LeaveDays", "Days", "LeaveReason", "Reason", "ComeWhere",
            "Province", "City", "County", "OutGoVehicleText", "OutGoVehicleName",
            "OutContacts", "OutContactsRelationship", "OutContactsMoveTel", "OutContactsTel",
            "OutContactsPhone", "StuMoveTel", "MoveTel", "StuTel", "FMComeWhere",
            "FamillyAddress", "InsertDate", "RegDate", "CreateTime"
        )

        private fun defaultClient() = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            // 与二课同样的理由：不让 OkHttp 自动跟随 302（它会把 POST 降级成 GET
            // 并丢掉请求体），重定向由 request() 自己处理，好保住方法与 Authorization。
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

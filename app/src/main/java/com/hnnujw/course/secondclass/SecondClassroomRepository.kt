package com.hnnujw.course.secondclass

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * 把站点上散在 4 个接口的数据拼成一页需要的东西。
 *
 * 容错策略：**只有"成绩单表头"是必需的**，其余段落各自失败就各自留空。
 * 第二课堂的分类/学分接口在很多学校是按学院配置的，学生没进任何分类时
 * 会回空数组而不是报错——那不该让整页变成错误页。
 */
object SecondClassroomRepository {

    suspend fun overview(client: SecondClassroomClient, token: String): SecondClassSnapshot = coroutineScope {
        val profile = async { client.profile(token) }
        val modules = async { runCatching { client.modules(token) }.getOrDefault(emptyList()) }
        SecondClassSnapshot(
            profile = profile.await(),
            modules = modules.await(),
        )
    }

    suspend fun board(
        client: SecondClassroomClient,
        token: String,
        level: SecondClassRankLevel,
    ): SecondClassRankBoard = client.rankBoard(token, level)

    /**
     * 只要"我的名次"，不拉整份榜单。专业/院系/全校层级用这个：
     * 界面本来就不展示名单，没必要把整院/全校的名单取回来。
     */
    suspend fun myRank(
        client: SecondClassroomClient,
        token: String,
        level: SecondClassRankLevel,
    ): SecondClassRankBoard = SecondClassRankBoard(
        level = level,
        entries = emptyList(),
        myRank = client.myRank(token, level),
        total = 0,
    )

    // ── 活动模块 ──────────────────────────────────────────────────────────

    /**
     * 打开活动详情需要的一切。
     *
     * 详情抓**两份**再合并：non-member 视角拿活动本身字段（它的 `applyStatus`
     * 恒为"未报名"），participant 视角补"我的"字段（报名状态/签到员/签到计数）。
     * participant 端点对没报名的用户会回一份缺省记录（`applyStatus=3`、无驳回
     * 原因），必须经 [withParticipantView] 的可信性校验后才合并 —— 否则
     * **每个活动都会显示"已驳回"**。
     *
     * 容错策略沿用成绩单那套：**只有详情本身是必需的**。
     * 报名表单字段（很多活动没有）与签到计数（活动可能没开签到）失败就留空，
     * 不该因为学校没配这两项而让整页变成错误页；participant 视角失败同样只降级。
     */
    suspend fun activityDetailBundle(
        client: SecondClassroomClient,
        token: String,
        activityId: Int,
    ): SecondClassActivityDetailBundle = coroutineScope {
        val detail = client.activityDetail(token, activityId, asParticipant = false)
        val mine = runCatching { client.activityDetail(token, activityId, asParticipant = true) }.getOrNull()
        val fields = async {
            runCatching { client.activityEnrollFields(token, activityId) }.getOrDefault(emptyList())
        }
        val count = async {
            runCatching { client.activitySignCount(token, activityId) }.getOrNull()
        }
        SecondClassActivityDetailBundle(
            detail = detail.withParticipantView(mine),
            enrollFields = fields.await(),
            signCount = count.await(),
        )
    }

    /**
     * 活动列表。分类字典只在该校配置了分类时才有内容，
     * 拿不到就退化成"全部分类"，不影响列表本身。
     */
    suspend fun activityFeed(
        client: SecondClassroomClient,
        token: String,
        pageNum: Int,
        pageSize: Int,
        keyword: String,
        classifyId: String,
        sort: SecondClassActivitySort,
    ): SecondClassActivityPage = client.activityList(token, pageNum, pageSize, keyword, classifyId, sort)

    suspend fun activityCategories(
        client: SecondClassroomClient,
        token: String,
    ): List<SecondClassActivityCategory> =
        runCatching { client.activityCategories(token) }.getOrDefault(emptyList())
}

/** 活动详情页一次性需要的全部数据。 */
data class SecondClassActivityDetailBundle(
    val detail: SecondClassActivityDetail,
    val enrollFields: List<SecondClassEnrollField> = emptyList(),
    val signCount: SecondClassSignCount? = null,
)

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
}

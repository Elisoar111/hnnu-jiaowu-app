package com.hnnujw.course.academic

/**
 * 正方消息中心的数据模型与结果类型。
 *
 * 说明：正方（jwglxt）的消息中心没有统一的公开 API，这里以"已登录会话 + 页面解析"的方式
 * 取得列表与详情。不同学校的入口路径与页面结构可能不同，相关猜测集中在 [ZfMessageCenter]，
 * 便于在拿到真实已登录会话后微调。
 */

/** 一条消息（列表项）。 */
data class AcademicMessage(
    /**
     * 稳定标识。用正方消息主键 `zjxx`；协议兜底时退化为「标题 + 时间」，
     * **绝不能用详情链接**——正方待办列表里 `ljdz` 常常是空的。
     */
    val id: String,
    val title: String,
    val sender: String = "",
    val sendTime: String = "",
    val summary: String = "",
    val read: Boolean = false,
    /** 详情页链接（绝对地址）；为空表示该消息没有独立正文页。 */
    val detailUrl: String = "",
    /**
     * 列表接口里直接带回来的正文（正方 `xxnr` / `xxbtjc`）。
     *
     * 待办类消息大多没有可点的详情链接，正文就藏在列表行里。没有这一项时
     * 用户点进详情只会看到一句「无法加载消息详情」——所以顺手存下来。
     */
    val content: String = ""
)

/** 消息详情正文。 */
data class AcademicMessageDetail(
    val title: String = "",
    val sender: String = "",
    val sendTime: String = "",
    val content: String = ""
)

/** 拉取消息列表的统一结果。 */
sealed class MessageCenterResult {
    data class Success(val messages: List<AcademicMessage>, val source: String) : MessageCenterResult()
    data class NeedLogin(val message: String) : MessageCenterResult()
    data class Failure(val message: String, val webUrl: String) : MessageCenterResult()
}

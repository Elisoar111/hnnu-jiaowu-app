package com.hnnujw.course.widgetboard

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.hnnujw.course.MainActivity
import com.hnnujw.course.MessageCenterActivity
import com.hnnujw.course.manager.StartupPage

/**
 * 桌面卡片被点按之后落到哪儿。
 *
 * 复用通知那条现成的落地通道（`MainActivity.EXTRA_OPEN_TAB` → `AppTabNavigation.accept`），
 * 不再新造一套 scheme：同一条 intent 通道，通知和桌面组件的跳转行为就永远一致。
 *
 * 卡片与页面的对应关系，和组件工作台里点卡片跳哪儿是同一张表
 * （`ui/route/WidgetBoardRoute.open`），改一处要同步另一处。
 */
internal object CardWidgetNavigation {

    fun targetPage(cardId: String): StartupPage = when (cardId) {
        "schedule.next", "schedule.today", "schedule.timeline" -> StartupPage.Schedule
        "exam.countdown", "grades.overview" -> StartupPage.Grades
        "secondclass.overview" -> StartupPage.SecondClass
        else -> StartupPage.Settings
    }

    /** 课表类卡片点开的必须是"现在"，不是用户上次翻到的那一周。见 [MainActivity.EXTRA_OPEN_TODAY]。 */
    private fun resetsToToday(cardId: String): Boolean = cardId.startsWith("schedule.")

    /**
     * @param courseId 非空表示"这次点击是针对某一门课的"：落到课表页之后还要打开它的详情
     *   （见 [MainActivity.EXTRA_OPEN_COURSE]）。课程在课表里找不到时（卡片过期、换了学期）
     *   就只是落到课表页，不会弹出一个不相干的详情 —— 判据在
     *   [com.hnnujw.course.schedule.decideCourseOpen]。
     */
    fun intent(context: Context, cardId: String, courseId: String? = null): Intent =
        if (cardId == "message.unread") {
            // 消息中心是独立 Activity，不占底栏 —— 直接开它比"先进主界面再跳"少一闪。
            Intent(context, MessageCenterActivity::class.java)
                .setData(cardUri(cardId, courseId))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        } else {
            Intent(context, MainActivity::class.java)
                .setData(cardUri(cardId, courseId))
                .putExtra(MainActivity.EXTRA_OPEN_TAB, targetPage(cardId).route)
                // 即使带了 courseId 也照样复位到今天：课程找不到时用户落到的就是课表页，
                // 而"看现在"是桌面卡片点开的默认语义（见 [resetsToToday]）。
                .putExtra(MainActivity.EXTRA_OPEN_TODAY, resetsToToday(cardId))
                .apply { courseId?.let { putExtra(MainActivity.EXTRA_OPEN_COURSE, it) } }
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

    /**
     * 每张卡片、每一行一个 data，**这不是深链，是为了让 PendingIntent 互相区分**。
     *
     * `PendingIntent` 判重只看 action / data / type / component / categories，**不看 extras**。
     * 而除了消息卡之外，所有卡片的 Intent 都是同一个 MainActivity、同样没有 action ——
     * 少了这一段 data，七张卡片会共用同一个 PendingIntent：`FLAG_UPDATE_CURRENT` 会把
     * 后画的那张的 extras 覆盖上去，于是点"考试倒计时"打开的是课表，点"下一节课"
     * 打开的是成绩。这种错位只跟渲染顺序有关，在单张卡片的手机上根本复现不出来。
     *
     * 逐行点击同理：时间轴卡上每一行的 [courseId] 不同，data 也就不同，才可能各跳各的课。
     * courseId 必须**编码**进 path —— 它里面有 `:`（`network:` / `custom:`），不编码虽然
     * 多数情况下也能解析，但一旦出现空格或 `#` 就会截断，变成"所有行又共用一个意图"。
     */
    private fun cardUri(cardId: String, courseId: String? = null): Uri =
        if (courseId.isNullOrBlank()) Uri.parse("course-card://$cardId")
        else Uri.parse("course-card://$cardId/${Uri.encode(courseId)}")
}

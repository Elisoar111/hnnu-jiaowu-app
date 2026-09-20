package com.hnnujw.course.schedule

import android.content.SharedPreferences
import com.hnnujw.course.academic.AcademicStudyAdapter
import com.hnnujw.course.academic.AcademicStudyBridge
import com.hnnujw.course.academic.AcademicStudyParser
import com.hnnujw.course.academic.AcademicStudyReader
import com.hnnujw.course.academic.AcademicTerm
import org.json.JSONObject

internal data class CachedSchedule(
    val currentTerm: AcademicTerm,
    val term: AcademicTerm,
    val json: String,
    val fromCache: Boolean
)

/** Account/term data survives login sessions; only an explicit sync bypasses a valid cache. */
internal class ScheduleCacheStore(
    private val preferences: SharedPreferences,
    private val calendarTerm: () -> AcademicTerm = { AcademicStudyReader.calendarTerm() }
) {
    private fun prefix(account: String, school: String) = "schedule_${account}_${school}"

    fun currentTerm(account: String, school: String): AcademicTerm {
        val calendar = calendarTerm()
        val known = runCatching {
            JSONObject(preferences.getString("${prefix(account, school)}_current", null) ?: return@runCatching null)
        }.getOrNull()
        // Resolve the school's term again when the local academic half-year changes.
        // A school can legitimately start later than the calendar fallback.
        return known?.takeIf { it.optString("calendar") == calendar.id }
            ?.optString("term")?.let(AcademicStudyParser::term) ?: calendar
    }

    fun read(account: String, school: String, term: AcademicTerm): String? {
        val base = prefix(account, school)
        val keys = buildList {
            add("${base}_${term.id}")
            if (term.semester in 1..2) add("${base}_${term.year}_${if (term.semester == 1) 3 else 12}")
        }
        return keys.firstNotNullOfOrNull { key ->
            runCatching { preferences.getString(key, null) }.getOrNull()
                ?.takeIf { ScheduleJson.parse(it) != null }
        }
    }

    fun selected(account: String, school: String): CachedSchedule? {
        val term = currentTerm(account, school)
        return read(account, school, term)?.let { CachedSchedule(term, term, it, true) }
    }

    suspend fun load(
        account: String,
        school: String,
        forceRefresh: Boolean,
        reader: () -> AcademicStudyAdapter
    ): CachedSchedule {
        if (!forceRefresh) selected(account, school)?.let { return it }
        val remote = reader()
        val current = remote.catalog().currentTerm
        // Existing installations may have data but no persisted current-term metadata yet.
        if (!forceRefresh) read(account, school, current)?.let { return CachedSchedule(current, current, it, true) }
        return CachedSchedule(current, current, AcademicStudyBridge.scheduleJson(remote.schedule(current)), false)
    }

    fun save(account: String, school: String, schedule: CachedSchedule) {
        require(ScheduleJson.parse(schedule.json) != null) { "Invalid timetable must not replace the cache" }
        val base = prefix(account, school)
        preferences.edit()
            .putString("${base}_${schedule.term.id}", schedule.json)
            .putLong("${base}_${schedule.term.id}_time", System.currentTimeMillis())
            .putString("${base}_current", JSONObject().put("term", schedule.currentTerm.id)
                .put("calendar", calendarTerm().id).toString())
            .apply()
    }
}

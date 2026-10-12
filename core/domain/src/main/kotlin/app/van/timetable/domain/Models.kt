// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.domain

import java.time.*
import java.util.UUID
import kotlinx.serialization.Serializable

fun newId(): String = UUID.randomUUID().toString()

enum class TimeMode {
    SECTIONS,
    CLOCK,
    UNTIMED,
}

@Serializable data class Section(val number: Int, val start: String, val end: String)

@Serializable
data class TimeRoutine(
    val id: String = newId(),
    val name: String = "默认作息",
    val sections: List<Section> = defaultSections(),
)

@Serializable data class RoutineRule(val routineId: String, val from: String, val through: String)

@Serializable
data class Holiday(
    val id: String = newId(),
    val name: String = "假期",
    val from: String,
    val through: String,
)

@Serializable
data class Course(
    val id: String = newId(),
    val name: String,
    val color: Int = 0,
    val credit: String = "",
    val note: String = "",
    val kind: String = "普通课程",
    val reminder: Boolean = true,
    val leadMinutes: Int? = null,
    val parentCourseId: String? = null,
    val customColor: Long? = null,
)

@Serializable
data class CourseSession(
    val id: String = newId(),
    val courseId: String,
    val day: Int = 1,
    val weeks: Set<Int> = (1..20).toSet(),
    val mode: TimeMode = TimeMode.SECTIONS,
    val startSection: Int = 1,
    val endSection: Int = 2,
    val start: String = "08:00",
    val end: String = "09:40",
    val teacher: String = "",
    val place: String = "",
    val priority: Int = 0,
    val date: String? = null,
)

@Serializable
data class DayOverride(
    val date: String,
    val sourceDate: String? = null,
    val cancelled: Boolean = false,
)

@Serializable
data class SessionOverride(
    val sessionId: String,
    val originalDate: String,
    val targetDate: String? = null,
    val cancelled: Boolean = false,
    val start: String? = null,
    val end: String? = null,
    val place: String? = null,
)

@Serializable
data class Schedule(
    val id: String = newId(),
    val name: String = "我的课表",
    val school: String = "",
    val week1Monday: String,
    val maxWeeks: Int = 20,
    val zone: String = "Asia/Shanghai",
    val source: String = "手动创建",
    val importedAt: Long = System.currentTimeMillis(),
    val courses: List<Course> = emptyList(),
    val sessions: List<CourseSession> = emptyList(),
    val routines: List<TimeRoutine> = listOf(TimeRoutine()),
    val routineRules: List<RoutineRule> = emptyList(),
    val dayOverrides: List<DayOverride> = emptyList(),
    val sessionOverrides: List<SessionOverride> = emptyList(),
    val holidays: List<Holiday> = emptyList(),
)

@Serializable
data class Preferences(
    val activeScheduleId: String? = null,
    val theme: String = "system",
    val fontScale: Float = 1f,
    val rowHeight: Int = 56,
    val fitWeek: Boolean = true,
    val showWeekend: Boolean = true,
    val showInactive: Boolean = true,
    val reminders: Boolean = false,
    val leadMinutes: Int = 10,
    val statusNotification: Boolean = false,
    val keepLogin: Boolean = false,
)

@Serializable
data class Backup(
    val format: String = "van-timetable",
    val version: Int = 1,
    val scope: String = "full",
    val schedules: List<Schedule>,
    val preferences: Preferences? = null,
)

data class Occurrence(
    val id: String,
    val course: Course,
    val session: CourseSession,
    val date: LocalDate,
    val start: LocalTime,
    val end: LocalTime,
    val place: String,
    val adjusted: Boolean = false,
) {
    fun startsAt(zone: ZoneId): Instant = date.atTime(start).atZone(zone).toInstant()

    fun endsAt(zone: ZoneId): Instant = date.atTime(end).atZone(zone).toInstant()
}

fun defaultSections(): List<Section> =
    listOf(
            "08:00" to "08:45",
            "08:55" to "09:40",
            "10:00" to "10:45",
            "10:55" to "11:40",
            "14:30" to "15:15",
            "15:25" to "16:10",
            "16:30" to "17:15",
            "17:25" to "18:10",
            "19:00" to "19:45",
            "19:55" to "20:40",
        )
        .mapIndexed { i, p -> Section(i + 1, p.first, p.second) }

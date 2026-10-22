// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.domain

import java.time.*
import java.time.temporal.ChronoUnit

object CourseEngine {
    /** Occurrence IDs retain the source date when a single lesson is moved. */
    fun originalDate(o: Occurrence): String = o.id.substringAfterLast('@', o.date.toString())

    fun cancelOccurrence(schedule: Schedule, occurrence: Occurrence): Schedule {
        val originalDate = originalDate(occurrence)
        val old = schedule.sessionOverrides.firstOrNull {
            it.sessionId == occurrence.session.id && it.originalDate == originalDate
        }
        val rule = (old ?: SessionOverride(occurrence.session.id, originalDate)).copy(cancelled = true)
        return schedule.copy(sessionOverrides = schedule.sessionOverrides.filterNot {
            it.sessionId == rule.sessionId && it.originalDate == rule.originalDate
        } + rule)
    }

    fun holiday(schedule: Schedule, date: LocalDate): Holiday? =
        schedule.holidays.firstOrNull {
            date >= LocalDate.parse(it.from) && date <= LocalDate.parse(it.through)
        }

    fun week(schedule: Schedule, date: LocalDate): Int =
        Math.floorDiv(
            ChronoUnit.DAYS.between(LocalDate.parse(schedule.week1Monday), date).toInt(),
            7,
        ) + 1

    fun routine(schedule: Schedule, date: LocalDate): TimeRoutine =
        schedule.routineRules
            .firstOrNull { date >= LocalDate.parse(it.from) && date <= LocalDate.parse(it.through) }
            ?.let { rule -> schedule.routines.first { it.id == rule.routineId } }
            ?: schedule.routines.first()

    fun dateAt(schedule: Schedule, week: Int, day: Int): LocalDate {
        require(week in 1..schedule.maxWeeks) { "周次必须在1–${schedule.maxWeeks}之间" }
        require(day in 1..7) { "星期必须在1–7之间" }
        return LocalDate.parse(schedule.week1Monday)
            .plusWeeks((week - 1).toLong())
            .plusDays((day - 1).toLong())
    }

    private fun raw(schedule: Schedule, source: LocalDate, display: LocalDate): List<Occurrence> {
        val w = week(schedule, source)
        val routine = routine(schedule, display)
        val courses = schedule.courses.associateBy { it.id }
        return schedule.sessions
            .filter {
                it.mode != TimeMode.UNTIMED &&
                    (if (it.date != null) LocalDate.parse(it.date) == source
                    else
                        w in 1..schedule.maxWeeks &&
                            it.day == source.dayOfWeek.value &&
                            w in it.weeks)
            }
            .map { s ->
                val start =
                    if (s.mode == TimeMode.CLOCK) LocalTime.parse(s.start)
                    else
                        LocalTime.parse(
                            routine.sections.first { it.number == s.startSection }.start
                        )
                val end =
                    if (s.mode == TimeMode.CLOCK) LocalTime.parse(s.end)
                    else LocalTime.parse(routine.sections.first { it.number == s.endSection }.end)
                Occurrence(
                    "${s.id}@$display",
                    courses.getValue(s.courseId),
                    s,
                    display,
                    start,
                    end,
                    s.place,
                    source != display || s.date != null,
                )
            }
    }

    private fun planned(schedule: Schedule, date: LocalDate): List<Occurrence> {
        val rule = schedule.dayOverrides.firstOrNull { it.date == date.toString() }
        if (rule?.cancelled == true) return emptyList()
        return raw(schedule, rule?.sourceDate?.let(LocalDate::parse) ?: date, date)
    }

    fun resolveDay(schedule: Schedule, date: LocalDate): List<Occurrence> {
        if (holiday(schedule, date) != null) return emptyList()
        val local =
            schedule.sessionOverrides
                .filter { it.originalDate == date.toString() }
                .associateBy { it.sessionId }
        val result =
            planned(schedule, date)
                .mapNotNull { o ->
                    val override = local[o.session.id] ?: return@mapNotNull o
                    if (
                        override.cancelled ||
                            (override.targetDate != null && override.targetDate != date.toString())
                    )
                        null
                    else apply(o, override, date)
                }
                .toMutableList()
        schedule.sessionOverrides
            .filter {
                !it.cancelled &&
                    it.targetDate == date.toString() &&
                    it.originalDate != date.toString()
            }
            .forEach { override ->
                planned(schedule, LocalDate.parse(override.originalDate))
                    .firstOrNull { it.session.id == override.sessionId }
                    ?.let { original ->
                        val newRoutine = routine(schedule, date)
                        val moved =
                            if (original.session.mode == TimeMode.SECTIONS)
                                original.copy(
                                    start =
                                        LocalTime.parse(
                                            newRoutine.sections
                                                .first {
                                                    it.number == original.session.startSection
                                                }
                                                .start
                                        ),
                                    end =
                                        LocalTime.parse(
                                            newRoutine.sections
                                                .first { it.number == original.session.endSection }
                                                .end
                                        ),
                                )
                            else original
                        result += apply(moved, override, date)
                    }
            }
        return result.sortedWith(
            compareBy<Occurrence> { it.start }
                .thenByDescending { it.session.priority }
                .thenBy { it.id }
        )
    }

    // Display-only preview. Widgets, reminders and next-course calculations use resolveDay.
    fun timetableDay(schedule: Schedule, date: LocalDate): List<Occurrence> =
        resolveDay(
            if (holiday(schedule, date) != null) schedule.copy(holidays = emptyList())
            else schedule,
            date,
        )

    private fun apply(o: Occurrence, rule: SessionOverride, date: LocalDate) =
        o.copy(
            id = "${o.session.id}@${rule.originalDate}",
            date = date,
            start = rule.start?.let(LocalTime::parse) ?: o.start,
            end = rule.end?.let(LocalTime::parse) ?: o.end,
            place = rule.place ?: o.place,
            adjusted = true,
        )

    fun remaining(schedule: Schedule, now: Instant): List<Occurrence> =
        resolveDay(schedule, now.atZone(ZoneId.of(schedule.zone)).toLocalDate()).filter {
            it.endsAt(ZoneId.of(schedule.zone)) > now
        }

    fun next(schedule: Schedule, now: Instant): Occurrence? {
        val date = now.atZone(ZoneId.of(schedule.zone)).toLocalDate()
        val last =
            maxOf(
                LocalDate.parse(schedule.week1Monday).plusWeeks(schedule.maxWeeks.toLong()),
                schedule.sessions.mapNotNull { it.date?.let(LocalDate::parse) }.maxOrNull() ?: date,
                schedule.sessionOverrides
                    .mapNotNull { it.targetDate?.let(LocalDate::parse) }
                    .maxOrNull() ?: date,
            )
        var d = date
        while (d <= last) {
            val zone = ZoneId.of(schedule.zone)
            val available = resolveDay(schedule, d).filter { it.endsAt(zone) > now }
            val ongoing = available.filter { it.startsAt(zone) <= now }
            val pool = ongoing.ifEmpty { available }
            pool.firstOrNull()?.let { first ->
                return conflicts(pool)
                    .firstOrNull { group -> group.any { it.id == first.id } }
                    ?.let(::main) ?: first
            }
            d = d.plusDays(1)
        }
        return null
    }

    fun conflicts(items: List<Occurrence>): List<List<Occurrence>> {
        val groups = mutableListOf<MutableList<Occurrence>>()
        for (o in items.sortedBy { it.start }) {
            val group = groups.lastOrNull()
            if (group != null && group.any { overlaps(it, o) }) group += o
            else groups += mutableListOf(o)
        }
        return groups.filter { it.size > 1 }
    }

    fun overlaps(a: Occurrence, b: Occurrence): Boolean =
        a.date == b.date && a.start < b.end && b.start < a.end

    fun main(items: List<Occurrence>): Occurrence =
        items
            .sortedWith(compareByDescending<Occurrence> { it.session.priority }.thenBy { it.id })
            .first()

    fun special(schedule: Schedule, week: Int): List<String> =
        schedule.sessions
            .filter { it.mode == TimeMode.UNTIMED && week in it.weeks }
            .map { s -> schedule.courses.first { it.id == s.courseId }.name + " · " + s.place }
            .distinct()
}

object Validation {
    fun errors(s: Schedule): List<String> = buildList {
        fun check(ok: Boolean, message: String) {
            if (!ok) add(message)
        }
        val start = runCatching { LocalDate.parse(s.week1Monday) }.getOrNull()
        check(start != null && start.dayOfWeek == DayOfWeek.MONDAY, "第1周起点必须是周一")
        check(s.name.isNotBlank(), "课表名称不能为空")
        check(s.maxWeeks > 0, "总周数必须大于0")
        check(runCatching { ZoneId.of(s.zone) }.isSuccess, "课表时区无效")
        check(s.routines.isNotEmpty(), "至少需要一套作息")
        check(s.courses.map { it.id }.distinct().size == s.courses.size, "课程ID重复")
        check(s.sessions.map { it.id }.distinct().size == s.sessions.size, "时段ID重复")
        s.courses.forEach {
            check(it.name.isNotBlank(), "课程名称不能为空")
            check(it.color in CoursePalette.light.indices, "课程配色无效")
            check(
                it.customColor == null || it.customColor in 0xFF000000..0xFFFFFFFF,
                "自定义颜色必须为不透明颜色",
            )
            check(it.leadMinutes == null || it.leadMinutes in 0..1440, "提醒提前量无效")
        }
        check(s.routines.map { it.id }.distinct().size == s.routines.size, "作息ID重复")
        s.routines.forEach { r ->
            check(r.sections.isNotEmpty(), "${r.name}作息为空")
            check(
                r.sections.map { it.number }.sorted() == (1..r.sections.size).toList(),
                "${r.name}节次必须连续且唯一",
            )
            var previous: LocalTime? = null
            r.sections
                .sortedBy { it.number }
                .forEach { n ->
                    val a = runCatching { LocalTime.parse(n.start) }.getOrNull()
                    val b = runCatching { LocalTime.parse(n.end) }.getOrNull()
                    check(a != null && b != null && a < b, "第${n.number}节起止时间无效")
                    if (a != null && previous != null) check(a >= previous, "作息时间发生重叠")
                    previous = b
                }
        }
        s.sessions.forEach { n ->
            check(s.courses.any { it.id == n.courseId }, "时段关联的课程不存在")
            check(n.day in 1..7 || (n.mode == TimeMode.UNTIMED && n.day == 0), "星期超出范围")
            check(
                n.date != null || (n.weeks.isNotEmpty() && n.weeks.all { it in 1..s.maxWeeks }),
                "上课周次为空或超出学期",
            )
            check(n.date == null || runCatching { LocalDate.parse(n.date) }.isSuccess, "单次课程日期无效")
            when (n.mode) {
                TimeMode.SECTIONS ->
                    check(
                        n.startSection > 0 &&
                            n.endSection >= n.startSection &&
                            s.routines.all { r -> n.endSection <= r.sections.size },
                        "课程节次超出作息范围",
                    )
                TimeMode.CLOCK ->
                    check(
                        runCatching { LocalTime.parse(n.start) < LocalTime.parse(n.end) }
                            .getOrDefault(false),
                        "课程起止时间无效",
                    )
                TimeMode.UNTIMED -> Unit
            }
        }
        s.routineRules.forEach { r ->
            check(s.routines.any { it.id == r.routineId }, "作息规则关联不存在")
            check(
                runCatching { LocalDate.parse(r.from) <= LocalDate.parse(r.through) }
                    .getOrDefault(false),
                "作息生效日期无效",
            )
        }
        s.routineRules.forEachIndexed { i, a ->
            s.routineRules.drop(i + 1).forEach { b ->
                check(!(a.from <= b.through && b.from <= a.through), "作息规则日期重叠")
            }
        }
        check(s.dayOverrides.map { it.date }.distinct().size == s.dayOverrides.size, "同一天有多个整日安排")
        check(s.holidays.map { it.id }.distinct().size == s.holidays.size, "假期ID重复")
        s.holidays.forEach { holiday ->
            check(holiday.name.isNotBlank(), "假期名称不能为空")
            check(
                runCatching {
                        LocalDate.parse(holiday.from) <= LocalDate.parse(holiday.through)
                    }
                    .getOrDefault(false),
                "假期结束日期不能早于开始日期，日期需有效",
            )
        }
        s.dayOverrides.forEach { d ->
            check(
                runCatching {
                        LocalDate.parse(d.date)
                        d.sourceDate?.let { LocalDate.parse(it) }
                    }
                    .isSuccess,
                "整日调整日期无效",
            )
        }
        check(
            s.sessionOverrides.map { it.sessionId to it.originalDate }.distinct().size ==
                s.sessionOverrides.size,
            "同一课程重复调整",
        )
        s.sessionOverrides.forEach { o ->
            check(s.sessions.any { it.id == o.sessionId }, "调课关联的时段不存在")
            check(
                runCatching {
                        LocalDate.parse(o.originalDate)
                        o.targetDate?.let { LocalDate.parse(it) }
                    }
                    .isSuccess,
                "单次调整日期无效",
            )
            check((o.start == null) == (o.end == null), "调整时间需同时填写起止")
            if (o.start != null)
                check(
                    runCatching { LocalTime.parse(o.start) < LocalTime.parse(o.end) }
                        .getOrDefault(false),
                    "调整起止时间无效",
                )
        }
    }

    fun requireValid(s: Schedule) {
        val errors = errors(s)
        require(errors.isEmpty()) { errors.joinToString("\n") }
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.domain

import java.time.LocalTime
import java.time.LocalDate

/** A routine defines the geometry. Courses never insert or enlarge a band. */
class GridTimeline private constructor(val bands: List<Band>) {
    data class Band(val start: LocalTime, val end: LocalTime, val units: Float, val section: Int?)

    val units = bands.sumOf { it.units.toDouble() }.toFloat()

    fun position(time: LocalTime): Float {
        var offset = 0f
        for (band in bands) {
            if (time <= band.start) return offset
            if (time < band.end) {
                val span = band.end.toNanoOfDay() - band.start.toNanoOfDay()
                return offset + band.units * ((time.toNanoOfDay() - band.start.toNanoOfDay()).toDouble() / span).toFloat()
            }
            offset += band.units
        }
        return offset
    }

    fun gapContaining(start: LocalTime, end: LocalTime): Band? =
        bands.firstOrNull { it.section == null && start >= it.start && end <= it.end }

    companion object {
        /** Check the whole timetable so paging never changes the spacing policy. */
        fun canCollapseGaps(schedule: Schedule): Boolean {
            val first = LocalDate.parse(schedule.week1Monday)
            val dates = (0 until schedule.maxWeeks * 7).map { first.plusDays(it.toLong()) }.toMutableSet()
            dates += schedule.sessions.mapNotNull { it.date?.let(LocalDate::parse) }
            dates += schedule.dayOverrides.map { LocalDate.parse(it.date) }
            dates += schedule.sessionOverrides.flatMap { listOfNotNull(it.originalDate, it.targetDate).map(LocalDate::parse) }
            // Include the courses shown in holiday previews as well as ordinary days.
            val visible = schedule.copy(holidays = emptyList())
            val timelines = mutableMapOf<List<Section>, GridTimeline>()
            return dates.all { date ->
                // The weekly grid uses Monday's routine for its shared axis.
                val monday = date.minusDays((date.dayOfWeek.value - 1).toLong())
                val sections = CourseEngine.routine(schedule, monday).sections
                val timeline = timelines.getOrPut(sections) { create(sections) }
                val times = CourseEngine.timetableDay(visible, date).map { it.start to it.end }.toMutableList()
                // Non-current section courses can also appear as grey previews.
                val daySections = CourseEngine.routine(schedule, date).sections.associateBy { it.number }
                times += schedule.sessions.filter {
                    it.date == null && it.mode == TimeMode.SECTIONS && it.day == date.dayOfWeek.value
                }.map { LocalTime.parse(daySections.getValue(it.startSection).start) to LocalTime.parse(daySections.getValue(it.endSection).end) }
                times.all { (start, end) ->
                    // A multi-section lesson may cross breaks, but neither boundary
                    // may fall inside a break, where collapsing would hide time.
                    timeline.bands.any { it.section != null && start >= it.start && start < it.end } &&
                        timeline.bands.any { it.section != null && end > it.start && end <= it.end }
                }
            }
        }

        fun create(sections: List<Section>, gapUnits: Float = 20f / 56f): GridTimeline {
            require(sections.isNotEmpty()) { "作息不能为空" }
            require(gapUnits >= 0f && gapUnits.isFinite())
            val result = mutableListOf<Band>()
            var previous = LocalTime.MIN
            sections.sortedBy { it.number }.forEach { section ->
                val start = LocalTime.parse(section.start)
                val end = LocalTime.parse(section.end)
                if (previous < start) result += Band(previous, start, gapUnits, null)
                result += Band(start, end, 1f, section.number)
                previous = end
            }
            if (previous < LocalTime.MAX) result += Band(previous, LocalTime.MAX, gapUnits, null)
            return GridTimeline(result)
        }
    }
}

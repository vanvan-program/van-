// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.domain

import java.time.LocalTime

/**
 * Section-sized rows, with extra space only when a course occupies a break or lies outside routine
 * hours.
 */
class GridTimeline private constructor(val bands: List<Band>) {
    data class Band(val start: LocalTime, val end: LocalTime, val units: Float, val section: Int?)

    val units = bands.sumOf { it.units.toDouble() }.toFloat()

    fun position(time: LocalTime): Float {
        var offset = 0f
        for (band in bands) {
            if (time <= band.start) return offset
            if (time < band.end) {
                val span = band.end.toSecondOfDay() - band.start.toSecondOfDay()
                return offset +
                    band.units * (time.toSecondOfDay() - band.start.toSecondOfDay()) / span
            }
            offset += band.units
        }
        return offset
    }

    companion object {
        fun create(sections: List<Section>, courses: List<Occurrence>): GridTimeline {
            val rows =
                sections
                    .sortedBy { it.number }
                    .map {
                        Band(LocalTime.parse(it.start), LocalTime.parse(it.end), 1f, it.number)
                    }
            val result = mutableListOf<Band>()
            val clockCourses = courses.filter { o ->
                o.session.mode == TimeMode.CLOCK ||
                    o.start != rows.firstOrNull { it.section == o.session.startSection }?.start ||
                    o.end != rows.firstOrNull { it.section == o.session.endSection }?.end
            }
            fun gap(start: LocalTime, end: LocalTime) {
                if (start >= end) return
                val occupied =
                    clockCourses
                        .filter { it.start < end && it.end > start }
                        .map { maxOf(start, it.start) to minOf(end, it.end) }
                        .sortedBy { it.first }
                // A custom course may only occupy part of a break. The remaining empty time
                // stays compressed, just like an unused break between ordinary sections.
                val spans = mutableListOf<Pair<LocalTime, LocalTime>>()
                occupied.forEach { span ->
                    val previous = spans.lastOrNull()
                    if (previous != null && span.first <= previous.second) {
                        spans[spans.lastIndex] = previous.first to maxOf(previous.second, span.second)
                    } else spans += span
                }
                spans.forEach { (from, through) ->
                    val length = (through.toSecondOfDay() - from.toSecondOfDay()) / 2700f
                    result += Band(from, through, length.coerceIn(0.65f, 3f), null)
                }
            }
            gap(
                minOf(rows.first().start, courses.minOfOrNull { it.start } ?: rows.first().start),
                rows.first().start,
            )
            rows.forEachIndexed { i, row ->
                if (i > 0) gap(rows[i - 1].end, row.start)
                result += row
            }
            gap(
                rows.last().end,
                maxOf(rows.last().end, courses.maxOfOrNull { it.end } ?: rows.last().end),
            )
            return GridTimeline(result)
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.domain

import java.time.LocalTime

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
        fun create(sections: List<Section>, gapUnits: Float = 20f / 56f): GridTimeline {
            require(sections.isNotEmpty()) { "作息不能为空" }
            require(gapUnits > 0f)
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

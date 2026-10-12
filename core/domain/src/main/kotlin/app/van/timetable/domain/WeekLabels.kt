// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.domain

fun formatWeeks(weeks: Set<Int>): String {
    val sorted = weeks.sorted()
    if (sorted.isEmpty()) return "未设置"
    val groups = mutableListOf<String>()
    var first = sorted.first()
    var last = first
    fun flush() {
        groups += if (first == last) "$first" else "$first–$last"
    }
    sorted.drop(1).forEach {
        if (it == last + 1) last = it
        else {
            flush()
            first = it
            last = it
        }
    }
    flush()
    return "第${groups.joinToString("、")}周"
}

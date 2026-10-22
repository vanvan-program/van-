// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import app.van.timetable.domain.*
import java.time.*
import kotlinx.coroutines.flow.MutableStateFlow

internal fun occurrenceRoute(o: Occurrence) =
    "detail:${o.course.id}:${o.date}:${o.session.id}:${CourseEngine.originalDate(o)}"

private data class GridEntry(val occurrence: Occurrence, val inactive: Boolean, val prefix: String, val conflict: Boolean)
private data class GridDay(val date: LocalDate, val entries: List<GridEntry>)
private data class PlacedEntry(val entry: GridEntry, val top: Dp, val height: Dp, val compact: Boolean, val lane: Int, val lanes: Int)

/** Expensive course/conflict filtering is performed once per schedule/week/settings. */
private fun weekData(s: Schedule, monday: LocalDate, days: Int, showInactive: Boolean): List<GridDay> =
    (0 until days).map { d ->
        val date = monday.plusDays(d.toLong())
        val holiday = CourseEngine.holiday(s, date) != null
        val present = CourseEngine.timetableDay(s, date)
        val groups = CourseEngine.conflicts(present)
        val groupById = groups.flatMap { group -> group.map { it.id to group } }.toMap()
        val mains = groups.map { CourseEngine.main(it).id }.toSet()
        val entries = present.filter { it.id !in groupById || it.id in mains }.map { o ->
            val group = groupById[o.id]
            GridEntry(o, holiday, "${if (holiday) "[放假]" else ""}${if (o.adjusted) "【调】" else ""}${if (group != null) "+${group.size - 1} " else ""}", group != null)
        }.toMutableList()
        if (showInactive) {
            val week = CourseEngine.week(s, date)
            val sections = CourseEngine.routine(s, date).sections.associateBy { it.number }
            val courses = s.courses.associateBy { it.id }
            s.sessions.filter { it.date == null && it.mode == TimeMode.SECTIONS && it.day == d + 1 && week !in it.weeks }
                .distinctBy { it.startSection to it.endSection }.forEach { n ->
                    val start = LocalTime.parse(sections.getValue(n.startSection).start)
                    val end = LocalTime.parse(sections.getValue(n.endSection).end)
                    if (present.none { start < it.end && end > it.start }) {
                        entries += GridEntry(Occurrence("${n.id}@$date", courses.getValue(n.courseId), n, date, start, end, n.place), true, "[非本周]", false)
                    }
                }
        }
        GridDay(date, entries)
    }

@Composable
internal fun WeekGrid(
    s: Schedule, p: Preferences, week: Int, dateNow: LocalDate,
    gridPan: MutableStateFlow<Int>, activePage: Boolean,
    nav: (String) -> Unit, onDaySelected: (LocalDate) -> Unit,
) {
    val monday = remember(s.week1Monday, week) { LocalDate.parse(s.week1Monday).plusWeeks((week - 1).toLong()) }
    val colors = LocalVan.current
    val days = if (p.showWeekend) 7 else 5
    val gridScroll = rememberScrollState()
    LaunchedEffect(gridScroll, activePage) {
        gridScroll.scrollTo(gridPan.value)
        if (activePage) snapshotFlow { gridScroll.value }.collect { gridPan.value = it }
        else gridPan.collect { if (gridScroll.value != it) gridScroll.scrollTo(it) }
    }
    val sections = remember(s, monday) { CourseEngine.routine(s, monday).sections }
    val data = remember(s, monday, days, p.showInactive) { weekData(s, monday, days, p.showInactive) }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("week-grid-$week").gridPagingGuard(gridScroll)) {
        val density = LocalDensity.current
        val scale = density.fontScale.coerceAtLeast(1f)
        val gutter = (34f * scale).dp
        val width = maxOf(maxWidth.value, 38f * scale * days + gutter.value).dp
        val dayWidth = (width - gutter) / days
        val headerHeight = maxOf(44f, 36f * scale).dp
        val gapHeight = (20f * scale).dp
        val gapCount = remember(sections) { GridTimeline.create(sections).bands.count { it.section == null } }
        val fitted = (maxHeight - headerHeight - 8.dp - gapHeight * gapCount) / sections.size
        val rowHeight = if (p.fitWeek) minOf(p.rowHeight.dp, maxOf((36f * scale).dp, fitted)) else p.rowHeight.dp
        val timeline = remember(sections, gapHeight, rowHeight) { GridTimeline.create(sections, gapHeight / rowHeight) }
        val placed = remember(data, timeline, rowHeight) {
            data.map { day ->
                val gaps = day.entries.groupBy { timeline.gapContaining(it.occurrence.start, it.occurrence.end) }
                day.entries.map { entry ->
                val o = entry.occurrence
                val gap = timeline.gapContaining(o.start, o.end)
                val top = if (gap != null) rowHeight * timeline.position(gap.start) else rowHeight * timeline.position(o.start)
                // A gap-only card uses the fixed band for a readable single line. It never
                // enlarges the band or overlaps a section. Actual times remain in details.
                val height = if (gap != null) gapHeight else rowHeight * (timeline.position(o.end) - timeline.position(o.start))
                val neighbors = if (gap == null) listOf(entry) else gaps.getValue(gap)
                PlacedEntry(entry, top, height, gap != null || height < 28.dp, neighbors.indexOf(entry), neighbors.size)
            } }
        }
        val gapLabels = remember(data, timeline) {
            val clockEntries = data.flatMap { it.entries }.filter { entry ->
                !entry.inactive && (entry.occurrence.session.mode == TimeMode.CLOCK ||
                    s.sessionOverrides.any { it.sessionId == entry.occurrence.session.id &&
                        it.originalDate == CourseEngine.originalDate(entry.occurrence) && it.start != null })
            }
            timeline.bands.filter { it.section == null }.mapNotNull { band ->
                val occupants = clockEntries.map { it.occurrence }.filter { it.start < band.end && it.end > band.start }
                if (occupants.isEmpty()) null else band to
                    (maxOf(band.start, occupants.minOf { it.start }) to minOf(band.end, occupants.maxOf { it.end }))
            }
        }
        Column(Modifier.horizontalScroll(gridScroll).width(width)) {
            Row(Modifier.height(headerHeight)) {
                Text("${monday.monthValue}月", Modifier.width(gutter).padding(top = 12.dp), fontSize = 11.sp, lineHeight = 14.sp)
                repeat(days) { d ->
                    val date = data[d].date
                    Column(Modifier.width(dayWidth).testTag("week-day-$week-$d").clickable { onDaySelected(date) }.padding(vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(weekdays[d].removePrefix("周"), fontSize = 12.sp, lineHeight = 15.sp, color = if (date == dateNow) colors.blue else colors.text)
                        Text("${date.dayOfMonth}", fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = if (date == dateNow) colors.blue else colors.text)
                    }
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Box(Modifier.height(rowHeight * timeline.units).width(width)) {
                    sections.forEach { n ->
                        Column(Modifier.offset(y = rowHeight * timeline.position(LocalTime.parse(n.start))).width(gutter - 3.dp).height(rowHeight).testTag("section-${n.number}"), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.Center) {
                            Text("${n.number}", fontSize = 11.sp, lineHeight = 13.sp, color = colors.muted)
                            Text(n.start, fontSize = 9.sp, lineHeight = 11.sp, color = colors.muted)
                            Text(n.end, fontSize = 9.sp, lineHeight = 11.sp, color = colors.muted)
                        }
                    }
                    if (p.showCustomTimeLabels) gapLabels.forEach { (band, times) ->
                        Column(Modifier.offset(y = rowHeight * timeline.position(band.start)).width(gutter - 3.dp).height(gapHeight).testTag("clock-band-${times.first}-${times.second}"), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.Center) {
                            Text(times.first.toString(), fontSize = 8.sp, lineHeight = 9.sp, maxLines = 1, softWrap = false, color = colors.muted)
                            Text(times.second.toString(), fontSize = 8.sp, lineHeight = 9.sp, maxLines = 1, softWrap = false, color = colors.muted)
                        }
                    }
                    placed.forEachIndexed { d, entries -> entries.forEach { item ->
                        val entry = item.entry
                        val o = entry.occurrence
                        val color = if (entry.inactive) colors.inactive else colors.courseColor(o.course)
                        val cardWidth = (dayWidth - 4.dp) / item.lanes
                        GridCard(o.course.name, o.place, color, entry.prefix, cardWidth,
                            maxOf(1.dp, item.height - 2.dp), item.compact, entry.inactive,
                            Modifier.offset(x = gutter + dayWidth * d + 2.dp + cardWidth * item.lane, y = item.top + 1.dp)
                                .testTag(if (entry.inactive && entry.prefix == "[非本周]") "inactive-course-${o.id}" else if (entry.inactive) "holiday-course-${o.id}" else "grid-course-${o.id}"),
                        ) {
                            if (entry.conflict) onDaySelected(o.date)
                            else nav(occurrenceRoute(o))
                        }
                    } }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/** Width and height are known by the parent; avoid one subcomposition per card. */
@Composable
private fun GridCard(name: String, place: String, color: Color, prefix: String, width: Dp, height: Dp,
    compact: Boolean, inactive: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val density = LocalDensity.current
    val nameSize = if (compact) 9.sp else if (width < 52.dp) 11.sp else 12.sp
    val placeLines = if (height < 60.dp) 1 else 2
    val placeHeight = if (place.isBlank() || compact) 0.dp else with(density) { (9.sp * placeLines * 1.2f).toDp() }
    val nameLines = if (compact) 1 else ((height - placeHeight - 6.dp) / with(density) { (nameSize * 1.15f).toDp() }).toInt().coerceIn(1, 5)
    val textColor = if (inactive) LocalVan.current.inactiveText else courseTextColor(color)
    Box(modifier.width(width).height(height).clip(RoundedCornerShape(if (compact) 3.dp else 6.dp)).background(color).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxSize().padding(horizontal = 3.dp, vertical = if (compact) 0.dp else 3.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(prefix + name, color = textColor, fontSize = nameSize, lineHeight = nameSize * 1.15f, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = nameLines, overflow = TextOverflow.Ellipsis)
            if (place.isNotBlank() && !compact) Text(place, color = textColor, fontSize = 9.sp, lineHeight = 10.8.sp, textAlign = TextAlign.Center, maxLines = placeLines, overflow = TextOverflow.Ellipsis)
        }
    }
}

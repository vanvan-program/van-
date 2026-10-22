// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import app.van.timetable.domain.*
import java.time.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun Welcome(vm: VanViewModel, nav: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Spacer(Modifier.height(30.dp))
        Text("van课表", fontSize = 36.sp, fontWeight = FontWeight.Bold)
        Text("每一天，都有清楚的安排。", fontSize = 20.sp)
        Muted("课程保存在本机 · 无广告 · 开源")
        Spacer(Modifier.height(12.dp))
        Action("从 WakeUp 迁移", "备份和 CSV 本地导入") { nav("import") }
        Action("从教务系统导入", "输入教务系统地址，在学校网页中获取课程") { nav("school") }
        Action("手动创建课表", "设置学期、作息并添加课程") { nav("newSchedule") }
        OutlinedButton(onClick = { vm.demo() }, modifier = Modifier.fillMaxWidth()) {
            Text("先查看示例课表")
        }
        Muted("示例数据不会替代你的真实课程。可在课表管理中删除。")
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Timetable(
    s: Schedule,
    p: Preferences,
    now: Instant,
    nav: (String) -> Unit,
    initialDate: String? = null,
) {
    val dateNow = now.atZone(ZoneId.of(s.zone)).toLocalDate()
    val actualWeek = CourseEngine.week(s, dateNow)
    val anchor = runCatching { initialDate?.let(LocalDate::parse) }.getOrNull() ?: dateNow
    var daily by rememberSaveable { mutableStateOf(false) }
    var selectedWeekday by rememberSaveable(s.id) { mutableIntStateOf(anchor.dayOfWeek.value - 1) }
    var weekSelectedDate by rememberSaveable(s.id) { mutableStateOf(anchor.toString()) }
    val dayAnchor by rememberSaveable(s.id) { mutableLongStateOf(anchor.toEpochDay()) }
    val weekPager =
        key(s.id, s.week1Monday, s.maxWeeks) {
            rememberPagerState(
                initialPage = CourseEngine.week(s, anchor).coerceIn(1, s.maxWeeks) - 1
            ) {
                s.maxWeeks
            }
        }
    val dayPager =
        key(s.id) {
            rememberPagerState(initialPage = DAY_PAGER_ANCHOR) { DAY_PAGER_COUNT }
        }
    val dayDate = LocalDate.ofEpochDay(dayAnchor + dayPager.currentPage - DAY_PAGER_ANCHOR)
    val week =
        if (daily) CourseEngine.week(s, dayDate).coerceIn(1, s.maxWeeks)
        else weekPager.currentPage + 1
    val monday = LocalDate.parse(s.week1Monday).plusWeeks((week - 1).toLong())
    val selectedDate = if (daily) dayDate.toString() else weekSelectedDate
    val scope = rememberCoroutineScope()
    var navigationJob by remember { mutableStateOf<Job?>(null) }
    fun animateNavigation(action: suspend () -> Unit) {
        navigationJob?.cancel()
        navigationJob = scope.launch { action() }
    }
    var selectWeek by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val gridPan = remember(s.id) { MutableStateFlow(0) }
    val canCollapseGaps = remember(s) { GridTimeline.canCollapseGaps(s) }
    val colors = LocalVan.current
    LaunchedEffect(weekPager) {
        snapshotFlow { weekPager.currentPage }
            .collect { page ->
                // Preserve an explicitly selected date outside the semester while the
                // weekly grid is clamped to its first/last week. User paging selects
                // the same weekday in the newly visible week.
                if (
                    !daily &&
                        CourseEngine.week(s, LocalDate.parse(weekSelectedDate))
                            .coerceIn(1, s.maxWeeks) != page + 1
                ) {
                    weekSelectedDate =
                        CourseEngine.dateAt(s, page + 1, selectedWeekday + 1).toString()
                }
            }
    }
    fun dayPage(date: LocalDate) =
        (DAY_PAGER_ANCHOR.toLong() + date.toEpochDay() - dayAnchor)
            .coerceIn(0, DAY_PAGER_COUNT.toLong() - 1)
            .toInt()
    fun showDay(date: LocalDate) {
        navigationJob?.cancel()
        // Request the starting position before composing the previously hidden pager.
        // scrollToPage would wait for its first layout and prevent the mode switch.
        dayPager.requestScrollToPage(dayPage(date))
        daily = true
    }
    fun moveDate(days: Long) {
        animateNavigation { dayPager.animateScrollToPage(dayPage(dayDate.plusDays(days))) }
    }
    fun moveWeek(target: Int) {
        val targetWeek = target.coerceIn(1, s.maxWeeks)
        animateNavigation {
            if (daily) {
                val date =
                    LocalDate.parse(s.week1Monday)
                        .plusWeeks((targetWeek - 1).toLong())
                        .plusDays((dayDate.dayOfWeek.value - 1).toLong())
                dayPager.animateScrollToPage(dayPage(date))
            } else {
                weekSelectedDate =
                    CourseEngine.dateAt(s, targetWeek, selectedWeekday + 1).toString()
                weekPager.animateScrollToPage(targetWeek - 1)
            }
        }
    }
    fun jumpToday() {
        animateNavigation {
            selectedWeekday = dateNow.dayOfWeek.value - 1
            if (daily) dayPager.animateScrollToPage(dayPage(dateNow))
            else weekPager.animateScrollToPage(actualWeek.coerceIn(1, s.maxWeeks) - 1)
            weekSelectedDate = dateNow.toString()
        }
    }
    val showReturn =
        if (daily) dayDate != dateNow
        else weekPager.currentPage != actualWeek.coerceIn(1, s.maxWeeks) - 1
    Column(
        Modifier.fillMaxSize().padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = { selectWeek = true },
                modifier = Modifier.testTag("week-selector"),
                contentPadding = PaddingValues(horizontal = 4.dp),
            ) {
                Text(
                    "第${week}周 ▾",
                    fontSize = 17.sp,
                    lineHeight = 21.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                s.name,
                fontSize = 13.sp,
                lineHeight = 17.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier.weight(1f).padding(horizontal = 6.dp).clickable { nav("schedules") },
            )
            IconButton(onClick = { nav("import") }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Outlined.FileUpload, "导入课表", Modifier.size(22.dp))
            }
            IconButton(onClick = { nav("edit:new") }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Outlined.Add, "添加课程", Modifier.size(22.dp))
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.MoreHoriz, "课表菜单", Modifier.size(22.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("上一周") },
                        enabled = week > 1,
                        onClick = {
                            moveWeek(week - 1)
                            menu = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("下一周") },
                        enabled = week < s.maxWeeks,
                        onClick = {
                            moveWeek(week + 1)
                            menu = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("回到本周") },
                        onClick = {
                            jumpToday()
                            menu = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (daily) "切换为周课表" else "切换为日列表") },
                        onClick = {
                            if (daily) {
                                navigationJob?.cancel()
                                selectedWeekday = dayDate.dayOfWeek.value - 1
                                weekSelectedDate = dayDate.toString()
                                weekPager.requestScrollToPage(
                                    CourseEngine.week(s, dayDate).coerceIn(1, s.maxWeeks) - 1
                                )
                                daily = false
                            } else showDay(LocalDate.parse(selectedDate))
                            menu = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("课表管理") },
                        onClick = {
                            menu = false
                            nav("schedules")
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("外观设置") },
                        onClick = {
                            menu = false
                            nav("appearance")
                        },
                    )
                }
            }
        }
        // Searching future days must not block a drag or repeat on page changes.
        val nextState =
            produceState<Occurrence?>(null, s, now) {
                value = withContext(Dispatchers.Default) { CourseEngine.next(s, now) }
            }
        val next = nextState.value
        if (next != null) {
            Surface(
                Modifier.fillMaxWidth().testTag("next-class").clickable {
                    nav(occurrenceRoute(next))
                },
                shape = RoundedCornerShape(8.dp),
                color = colors.surface.copy(alpha = 0.65f),
            ) {
                Row(
                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        Icons.Outlined.NotificationsNone,
                        null,
                        Modifier.size(14.dp),
                        tint = colors.blue,
                    )
                    Text(
                        (if (now >= next.startsAt(ZoneId.of(s.zone))) "正在上课 · " else "下一节 · ") +
                            next.course.name,
                        fontWeight = FontWeight.Medium,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (now >= next.startsAt(ZoneId.of(s.zone))) "进行中 · 至${next.end}"
                        else
                            "${if (next.date == dateNow) "今天" else if (next.date == dateNow.plusDays(1)) "明天" else "${next.date.monthValue}/${next.date.dayOfMonth}"} ${next.start}",
                        color = colors.blue,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        maxLines = 1,
                    )
                }
            }
        }
        if (actualWeek !in 1..s.maxWeeks) Muted(if (actualWeek < 1) "学期尚未开始" else "本学期已结束")
        if (daily) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    onClick = {
                        moveDate(-1)
                    }
                ) {
                    Text("前一天")
                }
                Text(
                    "$selectedDate ${weekdays[LocalDate.parse(selectedDate).dayOfWeek.value - 1]}",
                    Modifier.align(Alignment.CenterVertically),
                    fontSize = 13.sp,
                )
                TextButton(
                    onClick = {
                        moveDate(1)
                    }
                ) {
                    Text("后一天")
                }
            }
            Row(Modifier.weight(1f)) {
                TimetablePager(dayPager, Modifier.weight(1f).fillMaxHeight().testTag("day-list")) {
                    page ->
                    DayList(
                        s,
                        LocalDate.ofEpochDay(dayAnchor + page - DAY_PAGER_ANCHOR),
                        now,
                        nav,
                        Modifier.fillMaxSize(),
                    )
                }
                Box(Modifier.width(44.dp).fillMaxHeight(), contentAlignment = Alignment.CenterEnd) {
                    if (showReturn) ReturnToTodayButton(true, Modifier) { jumpToday() }
                }
            }
        } else {
            Row(Modifier.weight(1f)) {
                TimetablePager(
                    weekPager,
                    Modifier.weight(1f).fillMaxHeight().testTag("week-viewport"),
                ) { page ->
                    WeekGrid(
                        s,
                        p,
                        page + 1,
                        dateNow,
                        gridPan,
                        weekPager.settledPage == page,
                        canCollapseGaps,
                        nav,
                    ) { date ->
                        selectedWeekday = date.dayOfWeek.value - 1
                        showDay(date)
                    }
                }
                Box(Modifier.width(44.dp).fillMaxHeight(), contentAlignment = Alignment.CenterEnd) {
                    if (showReturn) ReturnToTodayButton(false, Modifier) { jumpToday() }
                }
            }
        }
        val notes =
            CourseEngine.special(s, week) +
                s.holidays
                    .filter {
                        LocalDate.parse(it.from) <= monday.plusDays(6) &&
                            LocalDate.parse(it.through) >= monday
                    }
                    .map { "${it.name}：${it.from} 至 ${it.through}" } +
                s.dayOverrides
                    .filter { LocalDate.parse(it.date) in monday..monday.plusDays(6) }
                    .map {
                        if (it.cancelled) "${it.date} 停课" else "${it.date} 上 ${it.sourceDate} 的课"
                    } +
                s.sessionOverrides
                    .filter { LocalDate.parse(it.originalDate) in monday..monday.plusDays(6) }
                    .map { "${it.originalDate} 有单次调课" }
        // Keep the viewport identical while paging through weeks with different notes.
        Box(
            Modifier.fillMaxWidth()
                .height((20f * LocalDensity.current.fontScale.coerceAtLeast(1f)).dp)
                .testTag("week-notes"),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (notes.isNotEmpty())
                Text(
                    notes.joinToString("；"),
                    Modifier.fillMaxWidth().basicMarquee(),
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    maxLines = 1,
                    color = colors.muted,
                )
        }
    }
    if (selectWeek)
        AlertDialog(
            onDismissRequest = { selectWeek = false },
            title = { Text("选择周次") },
            text = {
                LazyColumn(Modifier.heightIn(max = 350.dp)) {
                    items((1..s.maxWeeks).toList()) { w ->
                        TextButton(
                            onClick = {
                                moveWeek(w)
                                selectWeek = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("第${w}周")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selectWeek = false }) { Text("关闭") } },
        )
}

@Composable
private fun ReturnToTodayButton(daily: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val label = if (daily) "回到今日" else "回到本周"
    Surface(
        onClick = onClick,
        modifier =
            modifier.width(44.dp).heightIn(min = 112.dp).testTag("jump-today").semantics {
                contentDescription = label
            },
        shape = RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp),
        color = LocalVan.current.blue,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shadowElevation = 3.dp,
    ) {
        Text(
            label.toCharArray().joinToString("\n"),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
            textAlign = TextAlign.Center,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}


@Composable
fun DayList(
    s: Schedule,
    date: LocalDate,
    now: Instant,
    nav: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val list = remember(s, date) { CourseEngine.timetableDay(s, date) }
    val holiday = remember(s, date) { CourseEngine.holiday(s, date) }
    val conflicts = remember(list) { CourseEngine.conflicts(list).flatten().map { it.id }.toSet() }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (list.isEmpty() || holiday != null)
            item {
                Panel {
                    Text(if (holiday == null) "当天无课" else "放假期间，今日无课")
                    Muted(
                        holiday?.let { "${it.name} · ${it.from} 至 ${it.through}" }
                            ?: "可以添加课程或查看其他日期"
                    )
                }
            }
        items(list, key = { it.id }) { o ->
            CourseRow(
                o,
                now,
                { nav(occurrenceRoute(o)) },
                ZoneId.of(s.zone),
                holiday == null && o.id in conflicts,
                inactive = holiday != null,
            )
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
fun Courses(s: Schedule, now: Instant, nav: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var onlyWeek by rememberSaveable { mutableStateOf(false) }
    val week = CourseEngine.week(s, now.atZone(ZoneId.of(s.zone)).toLocalDate())
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "课程",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = { nav("notes") },
                modifier = Modifier.testTag("all-course-notes-button"),
            ) {
                Icon(Icons.Outlined.EditNote, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("课程备注")
            }
            IconButton(onClick = { nav("edit:new") }) { Icon(Icons.Outlined.Add, "新增课程") }
        }
        Field(query, { query = it }, "搜索课程、老师、地点")
        Toggle("只看本周有课", onlyWeek, { onlyWeek = it })
        val filtered =
            s.courses.filter { c ->
                val sessions = s.sessions.filter { it.courseId == c.id }
                (!onlyWeek || sessions.any { week in it.weeks }) &&
                    (c.name.contains(query, true) ||
                        c.note.contains(query, true) ||
                        sessions.any {
                            it.teacher.contains(query, true) || it.place.contains(query, true)
                        })
            }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (filtered.isEmpty()) item { Muted("没有符合条件的课程") }
            items(filtered, key = { it.id }) { c ->
                Panel(Modifier.clickable { nav("detail:${c.id}") }) {
                    Row {
                        Box(
                            Modifier.size(12.dp)
                                .background(
                                    LocalVan.current.courseColor(c),
                                    RoundedCornerShape(3.dp),
                                )
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(c.name, fontWeight = FontWeight.SemiBold)
                    }
                    s.sessions
                        .filter { it.courseId == c.id }
                        .forEach { n ->
                            if (n.date == null) Muted("周次：${formatWeeks(n.weeks)}")
                            Muted(
                                "${if(n.date!=null)n.date else if(n.day in 1..7)weekdays[n.day-1]else"整周"} · ${if(n.mode==TimeMode.SECTIONS)"${n.startSection}–${n.endSection}节"else if(n.mode==TimeMode.CLOCK)"${n.start}–${n.end}"else"无固定时间"} · ${n.place}"
                            )
                        }
                }
            }
        }
    }
}

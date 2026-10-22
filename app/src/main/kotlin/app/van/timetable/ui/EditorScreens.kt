// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.van.timetable.domain.*
import java.time.*

@Composable
fun Detail(
    s: Schedule,
    c: Course,
    date: String?,
    vm: VanViewModel,
    nav: (String) -> Unit,
    back: () -> Unit,
    sessionId: String? = null,
    originalDate: String? = null,
) {
    var delete by remember { mutableStateOf(false) }
    var editNote by rememberSaveable(c.id) { mutableStateOf(false) }
    var noteDraft by rememberSaveable(c.id) { mutableStateOf("") }
    val saving by vm.busy.collectAsStateWithLifecycle()
    var deleteOccurrence by remember { mutableStateOf<Occurrence?>(null) }
    LaunchedEffect(s.id, c.id) { vm.refreshWidgets() }
    val state by vm.state.collectAsStateWithLifecycle()
    val sessions = s.sessions.filter { it.courseId == c.id }
    val occurrences =
        date
            ?.let {
                CourseEngine.resolveDay(s, LocalDate.parse(it)).filter {
                    it.course.id == c.id && (sessionId == null || it.session.id == sessionId) &&
                        (originalDate == null || CourseEngine.originalDate(it) == originalDate)
                }
            }
            .orEmpty()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val cardColor = LocalVan.current.courseColor(c)
        Surface(color = cardColor, shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.fillMaxWidth().padding(24.dp)) {
                Text(
                    c.name,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = courseTextColor(cardColor),
                )
                Text(
                    "${c.kind}${if(c.credit.isNotBlank())" · ${c.credit}学分"else""}",
                    color = courseTextColor(cardColor),
                )
            }
        }
        Panel {
            Text("课程备注", fontWeight = FontWeight.Medium)
            if (c.note.isBlank()) Muted("记录分数分布、老师是否点名等课程信息。")
            else SelectionContainer { Text(c.note, modifier = Modifier.testTag("course-note-content")) }
            TextButton(
                onClick = { noteDraft = c.note; editNote = true },
                enabled = !saving,
                modifier = Modifier.testTag("edit-course-note"),
            ) { Text(if (c.note.isBlank()) "添加课程备注" else "编辑课程备注") }
        }
        Panel {
            if (sessions.isEmpty()) Muted("上课时间待定，可编辑课程补充日期或周次。")
            sessions.forEach { n ->
                Text(
                    n.date ?: if (n.day in 1..7) weekdays[n.day - 1] else "整周实践",
                    fontWeight = FontWeight.Medium,
                )
                Muted(
                    if (n.mode == TimeMode.SECTIONS) "第${n.startSection}–${n.endSection}节"
                    else if (n.mode == TimeMode.CLOCK) "${n.start}–${n.end}" else "无固定时间"
                )
                if (n.date == null) Muted("周次：${formatWeeks(n.weeks)}")
                Muted("${n.teacher} · ${n.place}")
            }
        }
        Panel {
            Toggle(
                "课前提醒",
                c.reminder,
                { vm.course(s, c.copy(reminder = it), sessions, {}) },
                "跟随全局提前量，可在编辑中覆盖",
            )
            if (!state.preferences.reminders) Muted("全局提醒尚未开启，请在我的 → 提醒设置中开启")
        }
        occurrences.forEach { o ->
            Action("调整 ${o.date} ${o.start} 的课程", "取消、移动或修改这一次课程") {
                nav("adjust:${o.session.id}:${CourseEngine.originalDate(o)}")
            }
            OutlinedButton(
                onClick = { deleteOccurrence = o },
                modifier = Modifier.fillMaxWidth().testTag("delete-occurrence-${o.id}"),
            ) {
                Text("仅删除本日本节", color = MaterialTheme.colorScheme.error)
            }
            Muted("${o.date} ${o.start}–${o.end} · ${o.place}")
            val conflict =
                CourseEngine.conflicts(CourseEngine.resolveDay(s, o.date)).firstOrNull { group ->
                    group.size > 1 && group.any { it.id == o.id }
                }
            if (conflict != null) {
                Panel {
                    Text("同一时段有 ${conflict.size} 门课程")
                    Muted("当前主要显示：${CourseEngine.main(conflict).course.name}")
                    TextButton(
                        onClick = {
                            val priority = (s.sessions.maxOfOrNull { it.priority } ?: 0) + 1
                            vm.save(
                                s.copy(
                                    sessions =
                                        s.sessions.map {
                                            if (it.id == o.session.id) it.copy(priority = priority)
                                            else it
                                        }
                                )
                            )
                        }
                    ) {
                        Text("优先显示并提醒这门课程")
                    }
                    Muted("此设置适用于这个上课时段的全部周次；冲突课程仍保留在日列表。")
                }
            }
        }
        Button(onClick = { nav("edit:${c.id}") }, modifier = Modifier.fillMaxWidth()) {
            Text("编辑整门课程")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { nav("quick:${c.id}:lab") }, modifier = Modifier.weight(1f)) {
                Text("添加实验课")
            }
            OutlinedButton(
                onClick = { nav("quick:${c.id}:exam") },
                modifier = Modifier.weight(1f),
            ) {
                Text("添加期末考试")
            }
        }
        TextButton(onClick = { delete = true }, modifier = Modifier.fillMaxWidth()) {
            Text("删除课程", color = MaterialTheme.colorScheme.error)
        }
    }
    if (editNote) CourseNoteDialog(
        value = noteDraft,
        onChange = { noteDraft = it },
        saving = saving,
        onSave = { vm.courseNote(s.id, c.id, noteDraft) { editNote = false } },
        onDismiss = { editNote = false },
    )
    if (delete)
        Confirm("删除课程", "将删除“${c.name}”的全部时段和单次调整，并取消相关提醒。", { delete = false }) {
            vm.deleteCourse(s, c.id, back)
        }
    deleteOccurrence?.let { o ->
        Confirm("仅删除本日本节", "取消“${c.name}”在 ${o.date} ${o.start}–${o.end} 的这一次课程。其他日期和时段保留。", { deleteOccurrence = null }) {
            vm.save(CourseEngine.cancelOccurrence(s, o), after = back)
            deleteOccurrence = null
        }
    }
}

private data class SlotForm(
    val id: String = newId(),
    val day: String = "1",
    val weeks: String = "1-20",
    val mode: TimeMode = TimeMode.SECTIONS,
    val first: String = "1",
    val last: String = "2",
    val start: String = "08:00",
    val end: String = "09:40",
    val teacher: String = "",
    val place: String = "",
    val date: String = "",
    val priority: Int = 0,
)

private val slotSaver =
    listSaver<SnapshotStateList<SlotForm>, ArrayList<String>>(
        save = { forms ->
            forms.map { f ->
                arrayListOf(
                    f.id,
                    f.day,
                    f.weeks,
                    f.mode.name,
                    f.first,
                    f.last,
                    f.start,
                    f.end,
                    f.teacher,
                    f.place,
                    f.date,
                    f.priority.toString(),
                )
            }
        },
        restore = { values ->
            values
                .map { v ->
                    SlotForm(
                        v[0],
                        v[1],
                        v[2],
                        TimeMode.valueOf(v[3]),
                        v[4],
                        v[5],
                        v[6],
                        v[7],
                        v[8],
                        v[9],
                        v[10],
                        v[11].toInt(),
                    )
                }
                .toMutableStateList()
        },
    )

private fun CourseSession.form() =
    SlotForm(
        id,
        day.toString(),
        weeks.sorted().joinToString("、"),
        mode,
        startSection.toString(),
        endSection.toString(),
        start,
        end,
        teacher,
        place,
        date.orEmpty(),
        priority,
    )

private fun SlotForm.session(courseId: String) =
    CourseSession(
        id,
        courseId,
        day.toInt(),
        if (date.isBlank()) Importers.weeks(weeks) else emptySet(),
        mode,
        first.toIntOrNull() ?: 1,
        last.toIntOrNull() ?: 2,
        start,
        end,
        teacher,
        place,
        priority,
        date.takeIf { it.isNotBlank() },
    )

@Composable
fun CourseEditor(
    s: Schedule,
    existing: Course?,
    vm: VanViewModel,
    back: () -> Unit,
    parent: Course? = null,
    eventKind: String? = null,
) {
    val draftId = rememberSaveable(existing?.id) { existing?.id ?: newId() }
    val original =
        remember(draftId) {
            existing
                ?: Course(
                    id = draftId,
                    name = parent?.let { "${it.name} · $eventKind" }.orEmpty(),
                    color = parent?.color ?: (s.courses.size % CoursePalette.size),
                    customColor = parent?.customColor,
                    kind = eventKind ?: "普通课程",
                    parentCourseId = parent?.id,
                )
        }
    var name by rememberSaveable(original.id) { mutableStateOf(original.name) }
    var note by rememberSaveable(original.id) { mutableStateOf(original.note) }
    var credit by rememberSaveable(original.id) { mutableStateOf(original.credit) }
    var lead by
        rememberSaveable(original.id) { mutableStateOf(original.leadMinutes?.toString().orEmpty()) }
    var color by rememberSaveable(original.id) { mutableIntStateOf(original.color) }
    var customColor by rememberSaveable(original.id) { mutableStateOf(original.customColor) }
    var showPalette by rememberSaveable { mutableStateOf(false) }
    var kind by rememberSaveable(original.id) { mutableStateOf(original.kind) }
    val slots =
        rememberSaveable(original.id, saver = slotSaver) {
            s.sessions
                .filter { it.courseId == original.id }
                .map { it.form() }
                .ifEmpty {
                    val today = LocalDate.now(ZoneId.of(s.zone))
                    val teacher =
                        s.sessions.firstOrNull { it.courseId == parent?.id }?.teacher.orEmpty()
                    listOf(
                        if (parent != null)
                            SlotForm(
                                day = today.dayOfWeek.value.toString(),
                                weeks = "",
                                date = today.toString(),
                                teacher = teacher,
                                mode =
                                    if (eventKind == "期末考试") TimeMode.CLOCK else TimeMode.SECTIONS,
                            )
                        else SlotForm(weeks = "1-${s.maxWeeks}")
                    )
                }
                .toMutableStateList()
        }
    var error by remember { mutableStateOf("") }
    var discard by remember { mutableStateOf(false) }
    BackHandler { discard = true }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (parent != null) Muted("关联课程：${parent.name}。请填写实际日期、时间和地点；实验课也可清空日期后按周次重复。")
        Field(name, { name = it }, "课程名称")
        Field(credit, { credit = it }, "学分（可选）")
        Field(kind, { kind = it }, "课程类型")
        CourseNoteField(note, { note = it })
        Field(lead, { lead = it }, "提前提醒分钟（空白表示跟随全局）")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LocalVan.current.tones.forEachIndexed { i, c ->
                Box(
                    Modifier.size(40.dp)
                        .semantics { contentDescription = "课程颜色${i + 1}" }
                        .background(c, RoundedCornerShape(12.dp))
                        .clickable {
                            color = i
                            customColor = null
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (color == i && customColor == null)
                        Text("✓", color = androidx.compose.ui.graphics.Color(0xFF142036))
                }
            }
        }
        OutlinedButton(onClick = { showPalette = true }, modifier = Modifier.fillMaxWidth()) {
            Text(customColor?.let { "自定义颜色 #%06X · 打开调色盘".format(it and 0xFFFFFF) } ?: "打开调色盘")
        }
        slots.forEachIndexed { i, slot ->
            key(slot.id) {
                Panel {
                    Text("上课时段 ${i+1}", fontWeight = FontWeight.Medium)
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        TimeMode.entries.forEach { mode ->
                            FilterChip(
                                slot.mode == mode,
                                { slots[i] = slot.copy(mode = mode) },
                                label = {
                                    Text(
                                        when (mode) {
                                            TimeMode.SECTIONS -> "按节次"
                                            TimeMode.CLOCK -> "自定义时间"
                                            TimeMode.UNTIMED -> "无固定时间"
                                        }
                                    )
                                },
                            )
                        }
                    }
                    Field(slot.day, { slots[i] = slot.copy(day = it) }, "星期（1–7，整周实践可填0）")
                    Field(slot.weeks, { slots[i] = slot.copy(weeks = it) }, "周次，如1-8、11-16单")
                    CalendarDateField("仅一次的日期（可选）", slot.date,
                        { slots[i] = slot.copy(date = it,
                            weeks = if (it.isBlank() && slot.weeks.isBlank()) "1-${s.maxWeeks}" else slot.weeks) }, allowClear = true)
                    if (slot.mode == TimeMode.SECTIONS) {
                        SectionRangeField(slot.first.toIntOrNull() ?: 1,
                            slot.last.toIntOrNull() ?: 2,
                            s.routines.minOf { it.sections.size }) { first, last ->
                            slots[i] = slot.copy(first = first.toString(), last = last.toString())
                        }
                    }
                    if (slot.mode == TimeMode.CLOCK) {
                        Field(slot.start, { slots[i] = slot.copy(start = it) }, "开始时间 HH:mm")
                        Field(slot.end, { slots[i] = slot.copy(end = it) }, "结束时间 HH:mm")
                    }
                    Field(slot.teacher, { slots[i] = slot.copy(teacher = it) }, "老师")
                    Field(slot.place, { slots[i] = slot.copy(place = it) }, "地点")
                    if (slots.size > 1)
                        TextButton(onClick = { slots.removeAt(i) }) { Text("移除此时段") }
                }
            }
        }
        OutlinedButton(
            onClick = { slots += SlotForm(weeks = "1-${s.maxWeeks}") },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("添加上课时段")
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        Button(
            onClick = {
                runCatching {
                        require(name.isNotBlank()) { "课程名称不能为空，请填写名称" }
                        require(lead.isBlank() || lead.toIntOrNull() != null) { "提前分钟必须是整数" }
                        val c =
                            original.copy(
                                name = name.trim(),
                                note = note,
                                credit = credit,
                                color = color,
                                customColor = customColor,
                                kind = kind,
                                leadMinutes = lead.toIntOrNull(),
                            )
                        val values = slots.map { it.session(c.id) }
                        val candidate =
                            s.copy(
                                courses = s.courses.filterNot { it.id == c.id } + c,
                                sessions = s.sessions.filterNot { it.courseId == c.id } + values,
                                sessionOverrides =
                                    s.sessionOverrides.filter { o ->
                                        o.sessionId in
                                            (s.sessions.filterNot { it.courseId == c.id } + values)
                                                .map { it.id }
                                    },
                            )
                        Validation.requireValid(candidate)
                        vm.save(candidate, after = back)
                    }
                    .onFailure { error = it.message ?: "请检查填写内容" }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("保存课程")
        }
    }
    if (discard) Confirm("离开编辑", "未保存的内容将丢弃。", { discard = false }, back)
    if (showPalette)
        CourseColorPicker(
            customColor ?: CoursePalette.light[color],
            { showPalette = false },
            {
                customColor = it
                showPalette = false
            },
        )
}

@Composable
fun ScheduleEditor(s: Schedule?, vm: VanViewModel, back: () -> Unit) {
    var name by rememberSaveable(s?.id) { mutableStateOf(s?.name ?: "我的课表") }
    var monday by
        rememberSaveable(s?.id) { mutableStateOf(s?.week1Monday ?: mondayNow().toString()) }
    var weeks by rememberSaveable(s?.id) { mutableStateOf(s?.maxWeeks?.toString() ?: "20") }
    var error by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    var candidate by remember { mutableStateOf<Schedule?>(null) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Field(name, { name = it }, "课表名称")
        CalendarDateField(
            "第1周周一",
            monday,
            { monday = it },
            Modifier.testTag("semester-start-date"),
            onlyMondays = true,
        )
        Field(weeks, { weeks = it }, "总周数")
        Muted("调整日期会重新计算课程、单次调整和提醒。已有日期调整仍保留具体日期，请检查调课管理。默认作息仅为模板，需要按实际情况修改。")
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        Button(
            onClick = {
                runCatching {
                        val v =
                            (s ?: Schedule(week1Monday = monday)).copy(
                                name = name,
                                week1Monday = monday,
                                maxWeeks = weeks.toInt(),
                            )
                        Validation.requireValid(v)
                        candidate = v
                        if (s == null) vm.save(v, true, back) else confirm = true
                    }
                    .onFailure { error = it.message.orEmpty() }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (s == null) "创建并启用" else "保存学期设置")
        }
    }
    if (confirm)
        Confirm(
            "更新学期",
            "将重新计算 ${s?.courses?.size} 门课程。已有课程的周次不变，日期会跟随新的第1周起点变化。",
            { confirm = false },
        ) {
            candidate?.let { vm.save(it, after = back) }
        }
}

@Composable
fun Routines(s: Schedule, onSave: (Schedule) -> Unit) {
    var selected by remember(s.id) { mutableStateOf(s.routines.first().id) }
    var name by remember(selected) { mutableStateOf(s.routines.first { it.id == selected }.name) }
    var text by
        remember(selected) {
            mutableStateOf(
                s.routines
                    .first { it.id == selected }
                    .sections
                    .joinToString("\n") { "${it.number},${it.start},${it.end}" }
            )
        }
    var from by remember { mutableStateOf("") }
    var through by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            s.routines.forEach { r ->
                FilterChip(selected == r.id, { selected = r.id }, label = { Text(r.name) })
            }
        }
        Field(name, { name = it }, "作息名称")
        Field(text, { text = it }, "每行：节次,开始时间,结束时间", multi = true)
        Muted("例如：1,08:00,08:45。需包含所有节次且连续，所有课程用到的节次必须存在。")
        Field(from, { from = it }, "生效起日（可选 YYYY-MM-DD）")
        Field(through, { through = it }, "生效末日（可选 YYYY-MM-DD）")
        s.routineRules.forEach { r ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Muted("${s.routines.first{it.id==r.routineId}.name}: ${r.from} 至 ${r.through}")
                TextButton(onClick = { onSave(s.copy(routineRules = s.routineRules - r)) }) {
                    Text("删除规则")
                }
            }
        }
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        fun build(new: Boolean) {
            runCatching {
                    val sections =
                        text
                            .lines()
                            .filter { it.isNotBlank() }
                            .map { line ->
                                val parts = line.split(',', '，').map { it.trim() }
                                require(parts.size == 3) { "每行必须是三项" }
                                Section(parts[0].toInt(), parts[1], parts[2])
                            }
                    val r =
                        TimeRoutine(
                            id = if (new) newId() else selected,
                            name = name,
                            sections = sections,
                        )
                    require((from.isBlank()) == (through.isBlank())) { "生效起止日需同时填写" }
                    val rules =
                        s.routineRules +
                            (if (from.isNotBlank()) listOf(RoutineRule(r.id, from, through))
                            else emptyList())
                    val v =
                        s.copy(
                            routines =
                                if (new) s.routines + r
                                else s.routines.map { if (it.id == selected) r else it },
                            routineRules = rules,
                        )
                    Validation.requireValid(v)
                    onSave(v)
                    if (new) selected = r.id
                }
                .onFailure { error = it.message.orEmpty() }
        }
        Button(onClick = { build(false) }, modifier = Modifier.fillMaxWidth()) { Text("保存当前作息") }
        OutlinedButton(onClick = { build(true) }, modifier = Modifier.fillMaxWidth()) {
            Text("保存为另一套作息")
        }
    }
}

@Composable
fun DayAdjustments(s: Schedule, vm: VanViewModel) {
    var date by rememberSaveable { mutableStateOf(LocalDate.now(ZoneId.of(s.zone)).toString()) }
    var source by rememberSaveable { mutableStateOf("") }
    var stop by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var byWeek by rememberSaveable { mutableStateOf(false) }
    var targetWeek by rememberSaveable {
        mutableStateOf(
            CourseEngine.week(s, LocalDate.parse(date)).coerceIn(1, s.maxWeeks).toString()
        )
    }
    var targetDay by rememberSaveable {
        mutableStateOf(LocalDate.parse(date).dayOfWeek.value.toString())
    }
    var sourceWeek by rememberSaveable { mutableStateOf(targetWeek) }
    var sourceDay by rememberSaveable { mutableStateOf(targetDay) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!byWeek, { byWeek = false }, label = { Text("按日期") })
            FilterChip(byWeek, { byWeek = true }, label = { Text("按周次和星期") })
        }
        if (byWeek)
            WeekDayFields(
                "目标",
                targetWeek,
                targetDay,
                { targetWeek = it },
                { targetDay = it },
            )
        else CalendarDateField("目标日期（这一天上课）", date, { date = it })
        Toggle("这一天停课", stop, { stop = it })
        if (!stop) {
            if (byWeek)
                WeekDayFields(
                    "来源",
                    sourceWeek,
                    sourceDay,
                    { sourceWeek = it },
                    { sourceDay = it },
                )
            else CalendarDateField("来源日期（原课程安排）", source, { source = it })
        }
        Muted("目标日改上来源日的课，来源日仍保留原安排；如需移走来源日课程，再将来源日设为停课。")
        if (byWeek) {
            val preview =
                runCatching {
                        val to = CourseEngine.dateAt(s, targetWeek.toInt(), targetDay.toInt())
                        if (stop) "$to 停课"
                        else
                            "$to 上 ${CourseEngine.dateAt(s, sourceWeek.toInt(), sourceDay.toInt())} 的课"
                    }
                    .getOrNull()
            if (preview != null) Muted(preview)
        }
        Muted("仅调整当前课表；学校安排需要手动填写。整日替换读取来源日期的原始课表。")
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        Button(
            onClick = {
                runCatching {
                        val to =
                            if (byWeek)
                                CourseEngine.dateAt(s, targetWeek.toInt(), targetDay.toInt())
                                    .toString()
                            else date
                        val from =
                            if (stop) null
                            else if (byWeek)
                                CourseEngine.dateAt(s, sourceWeek.toInt(), sourceDay.toInt())
                                    .toString()
                            else source
                        require(stop || to != from) { "来源和目标不能是同一天" }
                        val r = DayOverride(to, from, stop)
                        val candidate =
                            s.copy(dayOverrides = s.dayOverrides.filterNot { it.date == to } + r)
                        Validation.requireValid(candidate)
                        error = ""
                        vm.save(candidate)
                    }
                    .onFailure { error = it.message.orEmpty() }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("保存整日安排")
        }
        Text("已有调整", fontWeight = FontWeight.Medium)
        s.dayOverrides
            .sortedBy { it.date }
            .forEach { r ->
                Action("${r.date} ${if(r.cancelled)"停课"else"上 ${r.sourceDate} 的课"}", "点击撤销") {
                    vm.save(s.copy(dayOverrides = s.dayOverrides - r))
                }
            }
        s.sessionOverrides.forEach { r ->
            val c =
                s.sessions
                    .firstOrNull { it.id == r.sessionId }
                    ?.let { n -> s.courses.firstOrNull { it.id == n.courseId } }
            Action(
                "${r.originalDate} ${c?.name.orEmpty()}",
                if (r.cancelled) "取消这一次 · 点击撤销" else "调整至 ${r.targetDate?:r.originalDate} · 点击撤销",
            ) {
                vm.save(s.copy(sessionOverrides = s.sessionOverrides - r))
            }
        }
    }
}

@Composable
fun SingleAdjustment(
    s: Schedule,
    sessionId: String,
    date: String,
    vm: VanViewModel,
    back: () -> Unit,
) {
    val original =
        s.sessionOverrides.firstOrNull { it.sessionId == sessionId && it.originalDate == date }
    var target by rememberSaveable { mutableStateOf(original?.targetDate ?: date) }
    var start by rememberSaveable { mutableStateOf(original?.start.orEmpty()) }
    var end by rememberSaveable { mutableStateOf(original?.end.orEmpty()) }
    var place by rememberSaveable { mutableStateOf(original?.place.orEmpty()) }
    var cancel by rememberSaveable { mutableStateOf(original?.cancelled ?: false) }
    var error by remember { mutableStateOf("") }
    var byWeek by rememberSaveable { mutableStateOf(false) }
    var targetWeek by rememberSaveable {
        mutableStateOf(
            CourseEngine.week(s, LocalDate.parse(target)).coerceIn(1, s.maxWeeks).toString()
        )
    }
    var targetDay by rememberSaveable {
        mutableStateOf(LocalDate.parse(target).dayOfWeek.value.toString())
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Muted("原上课日期：$date。只影响这一次，其他周次保持原安排。")
        Toggle("取消这一次课程", cancel, { cancel = it })
        if (!cancel) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!byWeek, { byWeek = false }, label = { Text("按日期") })
                FilterChip(byWeek, { byWeek = true }, label = { Text("按周次和星期") })
            }
            if (byWeek)
                WeekDayFields(
                    "目标",
                    targetWeek,
                    targetDay,
                    { targetWeek = it },
                    { targetDay = it },
                )
            else CalendarDateField("调整到日期", target, { target = it })
            Field(start, { start = it }, "新的开始时间（可选 HH:mm）")
            Field(end, { end = it }, "新的结束时间（可选 HH:mm）")
            Field(place, { place = it }, "新的地点（空白沿用原地点）")
        }
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        Button(
            onClick = {
                runCatching {
                        val r =
                            SessionOverride(
                                sessionId,
                                date,
                                if (cancel) null
                                else if (byWeek)
                                    CourseEngine.dateAt(s, targetWeek.toInt(), targetDay.toInt())
                                        .toString()
                                else target,
                                cancel,
                                start.takeIf { it.isNotBlank() },
                                end.takeIf { it.isNotBlank() },
                                place.takeIf { it.isNotBlank() },
                            )
                        val v =
                            s.copy(
                                sessionOverrides =
                                    s.sessionOverrides.filterNot {
                                        it.sessionId == sessionId && it.originalDate == date
                                    } + r
                            )
                        Validation.requireValid(v)
                        vm.save(v, after = back)
                    }
                    .onFailure { error = it.message.orEmpty() }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("保存单次调整")
        }
    }
}

@Composable
private fun WeekDayFields(
    prefix: String,
    week: String,
    day: String,
    onWeek: (String) -> Unit,
    onDay: (String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Field(week, onWeek, "${prefix}周次", Modifier.weight(1f).testTag("${prefix}-week"))
        Field(day, onDay, "${prefix}星期（1–7）", Modifier.weight(1f).testTag("${prefix}-day"))
    }
}

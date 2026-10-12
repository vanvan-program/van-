// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters
import kotlinx.serialization.json.*

object Codec {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun encode(s: Schedule) = json.encodeToString(Schedule.serializer(), s)

    fun decode(s: String) = json.decodeFromString(Schedule.serializer(), s)

    fun backup(b: Backup) = json.encodeToString(Backup.serializer(), b)

    fun restore(text: String): Backup {
        val b = json.decodeFromString(Backup.serializer(), text)
        require(b.format == "van-timetable" && b.version == 1) { "不支持的备份格式或版本" }
        require(b.schedules.isNotEmpty()) { "备份不包含课表" }
        require(b.schedules.map { it.id }.distinct().size == b.schedules.size) { "备份课表ID重复" }
        require(b.scope in setOf("full", "single")) { "备份范围无效" }
        b.schedules.forEach(Validation::requireValid)
        b.preferences?.let { p ->
            require(p.theme in setOf("system", "light", "dark")) { "备份主题设置无效" }
            require(p.fontScale in 0.8f..1.5f && p.rowHeight in 40..120) { "备份字号或行高无效" }
            require(p.leadMinutes in 0..1440) { "备份提醒提前量无效" }
            require(p.activeScheduleId == null || b.schedules.any { it.id == p.activeScheduleId }) {
                "备份启用课表不存在"
            }
        }
        return b
    }
}

data class ImportDraft(val schedule: Schedule, val warnings: List<String> = emptyList())

object Importers {
    private fun JsonObject.string(key: String, default: String = "") =
        this[key]?.jsonPrimitive?.contentOrNull ?: default

    private fun JsonObject.int(key: String, default: Int = 0) =
        this[key]?.jsonPrimitive?.intOrNull ?: default

    private fun JsonObject.bool(key: String, default: Boolean = false) =
        this[key]?.jsonPrimitive?.booleanOrNull ?: default

    fun file(text: String, monday: LocalDate): Pair<Backup, List<String>> {
        val clean = text.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        val root = runCatching { Codec.json.parseToJsonElement(clean) as? JsonObject }.getOrNull()
        if (root?.string("format") == "van-timetable") return Codec.restore(clean) to emptyList()
        val draft = if (clean.startsWith("{")) wakeup(clean) else csv(clean, monday)
        return Backup(scope = "single", schedules = listOf(draft.schedule)) to draft.warnings
    }

    private fun wakeupClock(value: String): String {
        val parts = value.trim().split(':')
        require(parts.size in 2..3 && parts.all { it.toIntOrNull() != null }) {
            "WakeUp作息时间无效：$value"
        }
        return LocalTime.of(parts[0].toInt(), parts[1].toInt(), parts.getOrNull(2)?.toInt() ?: 0)
            .toString()
    }

    fun weeks(input: String): Set<Int> {
        val normalized =
            input
                .trim()
                .replace("周", "")
                .replace("（", "(")
                .replace("）", ")")
                .replace("(单)", "单")
                .replace("(双)", "双")
                .replace("，", "、")
                .replace(",", "、")
                .replace(" ", "")
        require(normalized.isNotEmpty()) { "周次不能为空" }
        val result = linkedSetOf<Int>()
        normalized.split('、').forEach { part ->
            val m = Regex("^(\\d+)(?:-(\\d+))?([单双])?$").matchEntire(part) ?: error("无法识别周次：$part")
            val first = m.groupValues[1].toInt()
            val last = m.groupValues[2].toIntOrNull() ?: first
            require(first > 0 && first <= last && last <= 520) { "周次范围无效：$part" }
            (first..last)
                .filter {
                    when (m.groupValues[3]) {
                        "单" -> it % 2 == 1
                        "双" -> it % 2 == 0
                        else -> true
                    }
                }
                .forEach(result::add)
        }
        require(result.isNotEmpty()) { "周次没有有效课程" }
        return result
    }

    fun csvRows(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        val content = text.removePrefix("\uFEFF")
        while (i < content.length) {
            val c = content[i]
            when {
                c == '"' ->
                    if (quoted && i + 1 < content.length && content[i + 1] == '"') {
                        field.append('"')
                        i++
                    } else {
                        require(quoted || field.isEmpty()) { "CSV引号位置无效" }
                        quoted = !quoted
                    }
                c == ',' && !quoted -> {
                    row += field.toString()
                    field.setLength(0)
                }
                (c == '\n' || c == '\r') && !quoted -> {
                    if (c == '\r' && i + 1 < content.length && content[i + 1] == '\n') i++
                    row += field.toString()
                    field.setLength(0)
                    if (row.any { it.isNotBlank() }) rows += row
                    row = mutableListOf()
                }
                else -> field.append(c)
            }
            i++
        }
        require(!quoted) { "CSV引号未闭合" }
        row += field.toString()
        if (row.any { it.isNotBlank() }) rows += row
        return rows
    }

    fun csv(text: String, monday: LocalDate): ImportDraft {
        val rows = csvRows(text)
        val headers = listOf("课程名称", "星期", "开始节数", "结束节数", "老师", "地点", "周数")
        require(rows.size > 1 && rows.first().map { it.trim() } == headers) {
            "CSV必须包含正式七列标题：${headers.joinToString(",")}"
        }
        val courses = mutableListOf<Course>()
        val sessions = mutableListOf<CourseSession>()
        val errors = mutableListOf<String>()
        rows.drop(1).forEachIndexed { i, row ->
            runCatching {
                    require(row.size == 7) { "应为7列" }
                    val r = row.map { it.trim() }
                    require(r[0].isNotBlank()) { "课程名称为空" }
                    val course =
                        courses.firstOrNull { it.name == r[0] }
                            ?: Course(name = r[0], color = courses.size % 5).also(courses::add)
                    sessions +=
                        CourseSession(
                            courseId = course.id,
                            day = r[1].toInt(),
                            startSection = r[2].toInt(),
                            endSection = r[3].toInt(),
                            teacher = r[4].takeUnless { it == "无" }.orEmpty(),
                            place = r[5].takeUnless { it == "无" }.orEmpty(),
                            weeks = weeks(r[6]),
                        )
                }
                .onFailure { errors += "第${i+2}行：${it.message}" }
        }
        require(errors.isEmpty()) { errors.joinToString("\n") }
        val count = maxOf(10, sessions.maxOfOrNull { it.endSection } ?: 10)
        val sections = templateSections(count)
        val s =
            Schedule(
                name = "CSV导入课表",
                week1Monday = monday.toString(),
                maxWeeks = maxOf(20, sessions.flatMap { it.weeks }.maxOrNull() ?: 20),
                source = "WakeUp CSV",
                courses = courses,
                sessions =
                    sessions.distinctBy {
                        listOf(
                            it.courseId,
                            it.day,
                            it.startSection,
                            it.endSection,
                            it.weeks,
                            it.teacher,
                            it.place,
                        )
                    },
                routines = listOf(TimeRoutine(sections = sections)),
            )
        Validation.requireValid(s)
        return ImportDraft(s, listOf("CSV不包含开学日期、总周数和作息，请确认后保存"))
    }

    fun wakeup(text: String): ImportDraft {
        val lines = text.removePrefix("\uFEFF").lines().filter { it.isNotBlank() }
        require(lines.size == 5) { "WakeUp备份应为5个非空JSON行" }
        val routineJson = Codec.json.parseToJsonElement(lines[0]).jsonObject
        val times = Codec.json.parseToJsonElement(lines[1]).jsonArray.map { it.jsonObject }
        val table = Codec.json.parseToJsonElement(lines[2]).jsonObject
        val bases = Codec.json.parseToJsonElement(lines[3]).jsonArray.map { it.jsonObject }
        val details = Codec.json.parseToJsonElement(lines[4]).jsonArray.map { it.jsonObject }
        // Database IDs may be rewritten by export versions. Five-line backups already
        // bundle one timetable with its time rows; rebuild links after validating content.
        val warnings = mutableListOf("课程颜色采用van课表主题配色；请核对开学日期与真实作息")
        val rid = routineJson.string("id")
        val tableId = table.string("id")
        val timeIds =
            times
                .map { it.string("timeTable", it.string("timeTableId")) }
                .filter { it.isNotBlank() }
                .toSet()
        require(timeIds.size <= 1) { "备份包含多套作息的时间行，无法确定对应关系；请在WakeUp分别导出单份课表" }
        val tableTimeId = table.string("timeTable", table.string("timeTableId"))
        if (
            rid.isNotBlank() &&
                (timeIds.any { it != rid } || tableTimeId.isNotBlank() && tableTimeId != rid)
        )
            warnings += "导出文件的作息关联ID不同，已按文件内的节次和时间重建作息，请核对预览"
        if (tableId.isNotBlank() && bases.any { it.string("tableId", tableId) != tableId })
            warnings += "导出文件的课表关联ID不同，已为本次导入重建关联"
        val courseMap = mutableMapOf<String, String>()
        val courses = bases.mapIndexed { i, b ->
            val key = b.string("id")
            require(key.isNotBlank()) { "课程ID缺失" }
            require(key !in courseMap) { "课程ID重复" }
            val c =
                Course(
                    name = b.string("courseName"),
                    color = i % 5,
                    credit = b.string("credit"),
                    note = b.string("note"),
                )
            courseMap[key] = c.id
            c
        }
        val nodeCount =
            table.int("nodes").takeIf { it > 0 } ?: times.maxOfOrNull { it.int("node") } ?: 0
        val sections =
            times
                .filter { it.int("node") <= nodeCount }
                .map {
                    Section(
                        it.int("node"),
                        wakeupClock(it.string("startTime")),
                        wakeupClock(it.string("endTime")),
                    )
                }
                .sortedBy { it.number }
        require(nodeCount > 0 && sections.map { it.number } == (1..nodeCount).toList()) {
            "作息缺少节次或节次重复，请核对导出文件"
        }
        val routine = TimeRoutine(name = routineJson.string("name", "导入作息"), sections = sections)
        val sessions =
            details
                .map { d ->
                    val type = d.int("type")
                    require(type in 0..2) { "未知单双周类型" }
                    val a = d.int("startWeek")
                    val b = d.int("endWeek")
                    require(a > 0 && a <= b && b <= 520) { "课程周次范围无效" }
                    val own = d.bool("ownTime")
                    val start =
                        if (own) d.int("startNode", 1).coerceAtLeast(1) else d.int("startNode")
                    val step = if (own) d.int("step", 1).coerceAtLeast(1) else d.int("step")
                    require(step > 0) { "连上节数必须大于0" }
                    CourseSession(
                        courseId = courseMap[d.string("id")] ?: error("课程明细没有对应课程"),
                        day = d.int("day"),
                        startSection = start,
                        endSection = start + step - 1,
                        mode = if (own) TimeMode.CLOCK else TimeMode.SECTIONS,
                        start = if (own) wakeupClock(d.string("startTime")) else "08:00",
                        end = if (own) wakeupClock(d.string("endTime")) else "09:40",
                        teacher = d.string("teacher"),
                        place = d.string("room"),
                        priority = d.int("level"),
                        weeks =
                            (a..b)
                                .filter {
                                    type == 0 ||
                                        type == 1 && it % 2 == 1 ||
                                        type == 2 && it % 2 == 0
                                }
                                .toSet(),
                    )
                }
                .groupBy {
                    listOf(
                        it.courseId,
                        it.day,
                        it.startSection,
                        it.endSection,
                        it.mode,
                        it.start,
                        it.end,
                        it.teacher,
                        it.place,
                        it.priority,
                    )
                }
                .values
                .map { group -> group.first().copy(weeks = group.flatMap { it.weeks }.toSet()) }
        val p = table.string("startDate").split('-')
        require(p.size == 3) { "开学日期无效" }
        val date =
            LocalDate.of(p[0].toInt(), p[1].toInt(), p[2].toInt())
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val s =
            Schedule(
                name = table.string("tableName", "WakeUp导入课表"),
                week1Monday = date.toString(),
                maxWeeks = table.int("maxWeek", 20),
                source = "WakeUp备份",
                courses = courses,
                sessions = sessions,
                routines = listOf(routine),
            )
        Validation.requireValid(s)
        return ImportDraft(s, warnings)
    }

    fun freshCopy(s: Schedule, name: String = s.name): Schedule {
        val courses = s.courses.associate { it.id to newId() }
        val sessions = s.sessions.associate { it.id to newId() }
        val routines = s.routines.associate { it.id to newId() }
        return s.copy(
            id = newId(),
            name = name,
            importedAt = System.currentTimeMillis(),
            courses = s.courses.map { it.copy(id = courses.getValue(it.id)) },
            sessions =
                s.sessions.map {
                    it.copy(id = sessions.getValue(it.id), courseId = courses.getValue(it.courseId))
                },
            routines = s.routines.map { it.copy(id = routines.getValue(it.id)) },
            routineRules =
                s.routineRules.map { it.copy(routineId = routines.getValue(it.routineId)) },
            sessionOverrides =
                s.sessionOverrides.map { it.copy(sessionId = sessions.getValue(it.sessionId)) },
        )
    }

    fun school(text: String, school: String, monday: LocalDate): ImportDraft {
        val root = Codec.json.parseToJsonElement(text).jsonObject
        root["error"]?.let { error(it.jsonPrimitive.content) }
        val rows = root["courses"]?.jsonArray ?: error("教务解析没有返回课程")
        val extraText = root.string("extraCoursesText")
        require(rows.isNotEmpty() || extraText.isNotBlank()) { "未获取到课程，请先进入所选学期的个人课表页面" }
        val courses = mutableListOf<Course>()
        val sessions =
            rows
                .map { element ->
                    val r = element.jsonObject
                    val name = r.string("name")
                    val course =
                        courses.firstOrNull { it.name == name }
                            ?: Course(name = name, color = courses.size % 5).also(courses::add)
                    val weeks =
                        r["weeks"]?.jsonArray?.map { it.jsonPrimitive.int }?.toSet()
                            ?: weeks(r.string("weeksText"))
                    CourseSession(
                        courseId = course.id,
                        day = r.int("day"),
                        weeks = weeks,
                        startSection = r.int("startSection"),
                        endSection = r.int("endSection"),
                        teacher = r.string("teacher"),
                        place = r.string("position"),
                        mode = if (r.bool("isCustomTime")) TimeMode.CLOCK else TimeMode.SECTIONS,
                        start = r.string("customStartTime", "08:00"),
                        end = r.string("customEndTime", "09:40"),
                    )
                }
                .toMutableList()
        val extraRows = DocumentImport.footerRows(extraText, courses.map { it.name }, strict = true)
        for (row in extraRows) {
            val course =
                courses.firstOrNull { it.name == row.name }
                    ?: Course(
                            name = row.name,
                            color = courses.size % CoursePalette.size,
                            note = "无固定上课时间",
                        )
                        .also(courses::add)
            sessions +=
                CourseSession(
                    courseId = course.id,
                    day = 0,
                    mode = TimeMode.UNTIMED,
                    weeks = weeks(row.weeks),
                    teacher = row.teacher,
                    place = row.place,
                )
        }
        require(sessions.isNotEmpty()) { "未获取到课程，请先进入所选学期的个人课表页面" }
        val importedTimes =
            root["sections"]
                ?.jsonArray
                ?.map { it.jsonObject }
                ?.map { Section(it.int("number"), it.string("start"), it.string("end")) }
                .orEmpty()
        val maxNode = maxOf(10, sessions.maxOf { it.endSection })
        val sections = if (importedTimes.isNotEmpty()) importedTimes else templateSections(maxNode)
        val s =
            Schedule(
                name = "$school · 导入课表",
                school = school,
                week1Monday = monday.toString(),
                maxWeeks = maxOf(20, sessions.flatMap { it.weeks }.max()),
                source = "教务导入",
                courses = courses,
                sessions = sessions,
                routines = listOf(TimeRoutine(name = "教务作息", sections = sections)),
            )
        Validation.requireValid(s)
        val warnings = mutableListOf("请对照教务页面确认课程、周次、开学日期与作息；默认时间不是学校官方作息")
        if (extraRows.isNotEmpty())
            warnings += "已保留${extraRows.size}个实践或无固定时间时段及其周次，不排入每周节次，也不安排上课提醒；请核对后保存"
        root
            .string("term")
            .trim()
            .take(100)
            .takeIf { it.isNotEmpty() }
            ?.let {
                warnings += "教务学期：$it，请确认与要导入的学期一致"
            }
        val missingTeachers = sessions.count { it.teacher.isBlank() }
        val missingPlaces = sessions.count { it.mode != TimeMode.UNTIMED && it.place.isBlank() }
        if (missingTeachers > 0 || missingPlaces > 0)
            warnings += "有${missingTeachers}个时段未读取到老师、${missingPlaces}个时段未读取到地点，请核对教务页面后再保存"
        return ImportDraft(s, warnings)
    }

    private fun templateSections(count: Int): List<Section> {
        if (count <= 10) return defaultSections()
        require(count <= 180) { "节次数量异常" }
        val step = minOf(45, 1020 / count)
        return (1..count).map { n ->
            val a = java.time.LocalTime.of(6, 0).plusMinutes((n - 1) * step.toLong())
            Section(n, a.toString(), a.plusMinutes((step - 1).toLong()).toString())
        }
    }
}

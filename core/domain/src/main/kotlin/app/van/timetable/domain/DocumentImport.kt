// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.domain

/** Row and column are zero based; a merged cell is supplied only at its top-left corner. */
data class DocumentCell(
    val row: Int,
    val column: Int,
    val text: String,
    val rowSpan: Int = 1,
    val columnSpan: Int = 1,
)

data class DocumentSheet(val name: String, val cells: List<DocumentCell>)

/** All bounds must use the same coordinate system. Normalized bounds and pixel bounds both work. */
data class DocumentText(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val page: Int = 0,
)

/**
 * Strings deliberately retain unknown values as blanks so recognition can be corrected before
 * import.
 */
data class DocumentCourseRow(
    val name: String = "",
    val day: String = "",
    val startSection: String = "",
    val endSection: String = "",
    val teacher: String = "",
    val place: String = "",
    val weeks: String = "",
    val untimed: Boolean = false,
)

data class DocumentRecognition(
    val name: String,
    val rows: List<DocumentCourseRow>,
    val csv: String,
    val rawText: String,
    val warnings: List<String>,
    val unresolvedCount: Int,
)

/** Converts structured cells or local OCR lines into a reviewable draft, never a saved schedule. */
object DocumentImport {
    fun fromPdf(name: String, lines: List<DocumentText>): DocumentRecognition {
        val referenceHeaders = lines.filter { it.page == 0 && headerDay(it.text) != null }
        val pages =
            lines
                .groupBy { it.page }
                .values
                .map { page ->
                    val hasHeaders = page.count { headerDay(it.text) != null } >= 5
                    val withHeaders =
                        if (hasHeaders || referenceHeaders.size < 5) page
                        else {
                            val top = page.minOf { it.top }
                            page +
                                referenceHeaders.map { it.copy(top = top - 30, bottom = top - 20) }
                        }
                    fromPositioned(name, withHeaders)
                }
        val timed = pages.flatMap { it.rows }
        val pending = lines.mapNotNull { line ->
            Regex("^(.*?)\\s*\\[H\\d+]\\s*上课周次\\s*(.*?)\\s*上课教师\\s*(.+)$")
                .matchEntire(line.text.trim())
                ?.let { match ->
                    DocumentCourseRow(
                        name = match.groupValues[1].trim(),
                        teacher = match.groupValues[3].trim(),
                        weeks =
                            match.groupValues[2]
                                .takeIf { it.isNotBlank() }
                                ?.let(::normalizeWeeks)
                                .orEmpty(),
                        untimed = true,
                    )
                }
        }
        val footerLines = lines.filter {
            it.text.contains("实践课程：") || it.text.contains("其他课程：") || it.text.contains("其它课程：")
        }
        val footer =
            if (footerLines.isEmpty()) ""
            else
                lines
                    .filter {
                        it.page == footerLines.first().page &&
                            it.top >= footerLines.minOf { f -> f.top } &&
                            it.top <
                                (lines
                                    .filter { l -> l.page == it.page && l.text.contains("打印时间") }
                                    .minOfOrNull { l -> l.top } ?: Float.MAX_VALUE)
                    }
                    .joinToString("") { it.text.trim() }
        val extra = footerRows(footer, timed.map { it.name })
        return result(
            name,
            timed + pending + extra,
            pages.joinToString("\n\n") { it.rawText },
            listOf("请对照原文件核对课程。无固定时间的课程会保留；周次为空时仅保存课程信息，不安排上课或提醒。"),
            0,
        )
    }

    /** Shared school/PDF footer parser. Strict mode never drops an unreadable school entry. */
    fun footerRows(
        text: String,
        knownNames: List<String>,
        strict: Boolean = false,
    ): List<DocumentCourseRow> {
        val names = knownNames.distinct().sortedByDescending { it.length }
        return text.split(';', '；').mapNotNull { entry ->
            val clean =
                entry
                    .trim()
                    .replace(Regex("^(实践课程|其[它他]课程)[:：]"), "")
                    .trim()
                    .replace('（', '(')
                    .replace('）', ')')
            if (clean.isBlank()) return@mapNotNull null
            val match = Regex("(.+?)\\(共\\d+周\\)/([^/;]+)(?:/(.*))?").matchEntire(clean)
            if (match == null) {
                require(!strict) { "实践或其它课程条目无法识别，请核对教务页面，尚未保存课程" }
                return@mapNotNull null
            }
            val prefix = match.groupValues[1].trim()
            val practice = Regex("^(.+(?:实习|实训|课程设计))(.{2,4})$").matchEntire(prefix)
            val known = names.firstOrNull { prefix.startsWith(it.removePrefix("【调】")) }
            val policy = Regex("^(形势与政策[A-Z0-9]*)(.+)$").matchEntire(prefix)
            val name =
                practice?.groupValues?.get(1) ?: known ?: policy?.groupValues?.get(1) ?: prefix
            val teacher =
                practice?.groupValues?.get(2)
                    ?: known?.let { prefix.removePrefix(it.removePrefix("【调】")).trim() }
                    ?: policy?.groupValues?.get(2).orEmpty()
            val weeks = normalizeWeeks(match.groupValues[2])
            if (strict) Importers.weeks(weeks)
            DocumentCourseRow(
                name = name,
                teacher = teacher,
                weeks = weeks,
                place = match.groupValues[3].trim().takeUnless { it == "无" }.orEmpty(),
                untimed = true,
            )
        }
    }

    fun toSchedule(rows: List<DocumentCourseRow>, monday: java.time.LocalDate): Schedule {
        require(rows.isNotEmpty()) { "没有可导入的课程" }
        val timed = rows.filterNot { it.untimed }
        var schedule =
            if (timed.isEmpty()) Schedule(week1Monday = monday.toString())
            else Importers.csv(toCsv(timed), monday).schedule
        rows
            .filter { it.untimed }
            .forEach { row ->
                require(row.name.isNotBlank()) { "课程名称为空" }
                val course =
                    schedule.courses.firstOrNull { it.name == row.name }
                        ?: Course(
                            name = row.name,
                            color = schedule.courses.size % CoursePalette.size,
                            note =
                                "无固定上课时间${if (row.teacher.isBlank()) "" else " · 老师：${row.teacher}"}",
                        )
                val weeks = if (row.weeks.isBlank()) emptySet() else Importers.weeks(row.weeks)
                schedule =
                    schedule.copy(
                        courses =
                            if (schedule.courses.any { it.id == course.id }) schedule.courses
                            else schedule.courses + course,
                        sessions =
                            schedule.sessions +
                                if (weeks.isEmpty()) emptyList()
                                else
                                    listOf(
                                        CourseSession(
                                            courseId = course.id,
                                            day = 0,
                                            mode = TimeMode.UNTIMED,
                                            weeks = weeks,
                                            teacher = row.teacher,
                                            place = row.place,
                                        )
                                    ),
                        maxWeeks = maxOf(schedule.maxWeeks, weeks.maxOrNull() ?: 20),
                    )
            }
        Validation.requireValid(schedule)
        return schedule
    }

    private val headers = listOf("课程名称", "星期", "开始节数", "结束节数", "老师", "地点", "周数")
    private val aliases =
        listOf(
            setOf("课程名称", "课程", "科目", "课程名", "coursename", "course", "subject", "name"),
            setOf("星期", "星期几", "周几", "上课星期", "weekday", "day", "dayofweek"),
            setOf(
                "开始节数",
                "开始节次",
                "开始节",
                "起始节数",
                "起始节次",
                "startsection",
                "startperiod",
                "startnode",
            ),
            setOf("结束节数", "结束节次", "结束节", "终止节数", "endsection", "endperiod", "endnode"),
            setOf("老师", "教师", "任课教师", "授课教师", "教师姓名", "teacher", "instructor", "lecturer"),
            setOf("地点", "教室", "上课地点", "教学地点", "上课教室", "room", "classroom", "location", "place"),
            setOf("周数", "周次", "上课周次", "教学周", "weeks", "week", "weeknumbers"),
        )
    private val sectionHeaders = setOf("节次", "节数", "上课节次", "sections", "periods", "nodes")
    private val sectionPattern =
        Regex(
            "(?:第)?(\\d{1,2})(?:(?:节(?:次|课)?)?[-~至](?:第)?(\\d{1,2}))?(?:节(?:次|课)?|(?=\\s*(?:section|period)))",
            RegexOption.IGNORE_CASE,
        )
    private val weekPiece = "(?:第)?\\d{1,3}(?:[-~至]\\d{1,3})?(?:\\(?[单双](?:周)?\\)?)?"
    private val weekPattern =
        Regex(
            "(?:$weekPiece(?:周|weeks?)?[,、;/]\\s*)*(?:$weekPiece(?:周|weeks?)(?:\\(?[单双](?:周)?\\)?)?|(?:第)?\\d{1,3}(?:[-~至]\\d{1,3})?\\([单双]周\\))",
            RegexOption.IGNORE_CASE,
        )
    private val labelledWeeks =
        Regex("(?:周次|周数|weeks?)\\s*[:：=]\\s*([\\d第周单双()至~,、;/\\-\\s]+)", RegexOption.IGNORE_CASE)
    private val dayPattern =
        Regex(
            "(?:星期|礼拜|周)([一二三四五六日天1-7])|(?i)\\b(monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tue|wed|thu|fri|sat|sun)\\b"
        )
    private val teacherPattern =
        Regex(
            "(?:任课教师|授课教师|教师姓名|老师|教师|teacher|instructor|lecturer|👤|♟)\\s*[:：=]?\\s*([^\\n;；|]+)",
            RegexOption.IGNORE_CASE,
        )
    private val placePattern =
        Regex(
            "(?:上课地点|教学地点|上课教室|地点|教室|location|classroom|room|place|📍)\\s*[:：=]?\\s*([^\\n;；|]+)",
            RegexOption.IGNORE_CASE,
        )
    private val coursePattern =
        Regex(
            "(?:课程名称|课程名|课程|course(?:name)?|subject)\\s*[:：=]\\s*([^\\n;；|]+)",
            RegexOption.IGNORE_CASE,
        )
    private val fieldLabel =
        Regex(
            "^(?:周次|周数|上课周次|教学周|星期|周几|礼拜|节次|节数|上课节次|开始节数|结束节数|weeks?|weekday|day|sections?|periods?)\\s*[:：=]?",
            RegexOption.IGNORE_CASE,
        )
    private val roomLine = Regex(".*(?:校区|教学楼|示例楼|示例楼|实验楼|图书馆|机房|[A-Za-z]\\s?\\d{2,4}).*")
    private val campusSection = Regex("\\((\\d{1,2})(?:[-~](\\d{1,2}))?节\\)")
    private val campusLabel = Regex("(?:校区|场地|地点|教室|教师|老师|教学班|考核方式|选课备注|课程学时|周学时|总学时|学分)\\s*[:：]")
    private val campusTeacher = Regex("教师[:：]\\s*([^/\\n;；|]*)")
    private val campusPlace = Regex("(?:场地|地点|教室)[:：]\\s*([^/\\n;；|]*)")
    private val classListRow = Regex("^[\\d\\s;；,，、计]*\\d[\\d\\s;；,，、计]*$")

    fun toCsv(rows: List<DocumentCourseRow>): String =
        (listOf(headers) +
                rows.map {
                    listOf(
                        it.name,
                        it.day,
                        it.startSection,
                        it.endSection,
                        it.teacher,
                        it.place,
                        it.weeks,
                    )
                })
            .joinToString("\n") { row -> row.joinToString(",", transform = ::quote) }

    fun fromSheet(sheet: DocumentSheet): DocumentRecognition {
        val cells =
            sheet.cells
                .filter { it.text.isNotBlank() }
                .sortedWith(compareBy({ it.row }, { it.column }))
        val raw =
            cells
                .groupBy { it.row }
                .values
                .joinToString("\n") { row -> row.joinToString("\t") { it.text } }
        val warnings = mutableListOf<String>()
        val tableHeader =
            cells
                .groupBy { it.row }
                .entries
                .firstOrNull { (_, row) ->
                    val fields = row.mapNotNull { headerIndex(it.text) }.toSet()
                    0 in fields &&
                        (1 in fields || 6 in fields || row.any { key(it.text) in sectionHeaders })
                }
        if (tableHeader != null) {
            val mapped =
                tableHeader.value
                    .mapNotNull { cell -> headerIndex(cell.text)?.let { it to cell.column } }
                    .toMap()
            val combined = tableHeader.value.firstOrNull { key(it.text) in sectionHeaders }?.column
            val rows = mutableListOf<DocumentCourseRow>()
            var unparsed = 0
            cells
                .filter { it.row > tableHeader.key }
                .groupBy { it.row }
                .values
                .forEach { row ->
                    val values = row.associate { it.column to it.text.trim() }
                    val name = compact(mapped[0]?.let(values::get).orEmpty())
                    if (name.isBlank()) {
                        if (mapped.values.any { values[it].orEmpty().isNotBlank() }) unparsed++
                        return@forEach
                    }
                    val sections = combined?.let { values[it] }?.let(::sectionRange)
                    rows +=
                        DocumentCourseRow(
                            name = name,
                            day = mapped[1]?.let(values::get).orEmpty().let(::dayValue),
                            startSection =
                                mapped[2]?.let(values::get).orEmpty().let(::sectionValue).ifBlank {
                                    sections?.first?.toString().orEmpty()
                                },
                            endSection =
                                mapped[3]?.let(values::get).orEmpty().let(::sectionValue).ifBlank {
                                    sections?.last?.toString().orEmpty()
                                },
                            teacher = cellMetadata(mapped[4]?.let(values::get).orEmpty()),
                            place = cellMetadata(mapped[5]?.let(values::get).orEmpty()),
                            weeks = normalizeWeeks(mapped[6]?.let(values::get).orEmpty()),
                        )
                }
            if (unparsed > 0) warnings += "有${unparsed}行没有课程名称，请对照原文件核对"
            return result(sheet.name, rows, raw, warnings, unparsed)
        }

        val dayHeaders = cells.filter { headerDay(it.text) != null }
        val headerBand =
            dayHeaders
                .groupBy { it.row }
                .values
                .maxByOrNull { row -> row.mapNotNull { headerDay(it.text) }.distinct().size }
        if (
            headerBand != null && headerBand.mapNotNull { headerDay(it.text) }.distinct().size >= 2
        ) {
            val dayColumns = headerBand.associate { it.column to headerDay(it.text)!! }
            val headerRow = headerBand.first().row
            val sectionColumn =
                cells
                    .filter { it.row <= headerRow && key(it.text) in sectionHeaders }
                    .firstOrNull()
                    ?.column
                    ?: cells
                        .filter {
                            it.row > headerRow &&
                                it.column < dayColumns.keys.min() &&
                                rowSection(it.text) != null
                        }
                        .groupBy { it.column }
                        .maxByOrNull { it.value.size }
                        ?.key
            val rowSections =
                cells
                    .filter { it.column == sectionColumn && it.row > headerRow }
                    .mapNotNull { cell -> rowSection(cell.text)?.let { cell.row to it } }
                    .toMap()
            val rows = mutableListOf<DocumentCourseRow>()
            var unparsed = 0
            cells
                .filter { it.row > headerRow && it.column in dayColumns }
                .forEach { cell ->
                    if (ignored(cell.text)) return@forEach
                    val across =
                        dayColumns.keys.any {
                            it > cell.column && it < cell.column + cell.columnSpan
                        }
                    val day = if (across) "" else dayColumns[cell.column].toString()
                    if (across) warnings += "跨星期的合并单元格需要确认星期：${compact(cell.text).take(24)}"
                    val first = rowSections[cell.row]
                    val last = rowSections[cell.row + cell.rowSpan - 1]
                    val spanRows =
                        (cell.row until cell.row + cell.rowSpan).mapNotNull(rowSections::get)
                    val completeSpan =
                        spanRows.size == cell.rowSpan &&
                            spanRows.zipWithNext().all { (a, b) -> a.last + 1 == b.first }
                    val contextSections =
                        when {
                            cell.rowSpan == 1 -> first
                            first != null && last != null && completeSpan -> first.first..last.last
                            else -> null
                        }
                    val blocks = splitBlocks(cell.text)
                    blocks.forEach { block ->
                        parseBlock(block, day, contextSections, requireEvidence = false)
                            ?.let(rows::add) ?: run { unparsed++ }
                    }
                }
            if (unparsed > 0) warnings += "有${unparsed}个单元格内容未能确认为课程，请对照原表核对"
            return result(sheet.name, rows, raw, warnings, unparsed)
        }
        warnings += "没有识别到完整的星期表头；请补充或确认每门课的星期"
        val rows = cells.flatMap { cell ->
            splitBlocks(cell.text).mapNotNull { parseBlock(it, "", null, requireEvidence = true) }
        }
        return result(
            sheet.name,
            rows,
            raw,
            warnings,
            if (rows.isEmpty() && cells.isNotEmpty()) 1 else 0,
        )
    }

    fun fromPositioned(name: String, lines: List<DocumentText>): DocumentRecognition {
        val valid = lines.filter {
            it.text.isNotBlank() && it.right > it.left && it.bottom > it.top
        }
        val sorted = valid.sortedWith(compareBy({ it.top }, { it.left }))
        val raw = sorted.joinToString("\n") { it.text }
        if (valid.isEmpty())
            return result(name, emptyList(), raw, listOf("没有识别到文字，请换用清晰完整的课表图片"), 0)
        val height =
            valid.map { it.bottom - it.top }.sorted().let { it[it.size / 2] }.coerceAtLeast(0.0001f)
        val headers = valid.flatMap(::positionedHeaders)
        val bands = headers.map { anchor ->
            headers.filter { kotlin.math.abs(it.top - anchor.top) < height * 1.5f }
        }
        val band = bands.maxByOrNull {
            it.mapNotNull { item -> headerDay(item.text) }.distinct().size
        }
        val warnings = mutableListOf<String>()
        val dayCenters =
            band
                .orEmpty()
                .mapNotNull { item ->
                    headerDay(item.text)?.let { day -> day to (item.left + item.right) / 2 }
                }
                .distinctBy { it.first }
                .sortedBy { it.second }
        val gaps = dayCenters.zipWithNext { a, b -> b.second - a.second }.sorted()
        val continuousDays = dayCenters.zipWithNext().all { (a, b) -> b.first == a.first + 1 }
        val regularColumns =
            gaps.isNotEmpty() &&
                gaps.first() > 0 &&
                gaps.all { it.isFinite() } &&
                gaps.last() / gaps.first() <= 1.8f
        if (dayCenters.size < 2 || !continuousDays || !regularColumns) {
            warnings +=
                if (dayCenters.size < 2) "没有识别到星期表头，无法从图片确认课程属于星期几；请补充完整图片或手动填写"
                else "星期表头缺失、顺序或间距无法确认，无法从图片确认课程属于星期几；请补充完整图片或手动填写"
            // A cropped timetable can still contain several columns. Retain their independent
            // courses, but do not assign any weekday without observed weekday labels.
            val grouped = fallbackColumns(sorted, height).flatMap { clusterLines(it, height) }
            val rows = grouped.flatMap { group ->
                splitBlocks(group.joinToString("\n") { it.text }).mapNotNull {
                    parseBlock(it, "", null, true)
                }
            }
            return result(name, rows, raw, warnings, if (rows.isEmpty()) 1 else 0)
        }
        val headerBottom = band.orEmpty().maxOf { it.bottom }
        val footerTop =
            valid
                .filter {
                    it.text.contains("实践课程：") ||
                        it.text.contains("实践课程:") ||
                        it.text.contains("其他课程：") ||
                        it.text.contains("其他课程:")
                }
                .minOfOrNull { it.top } ?: Float.MAX_VALUE
        val columnWidth = gaps[gaps.size / 2]
        val firstEdge = dayCenters.first().second - columnWidth / 2
        val gutter =
            valid
                .filter {
                    (it.left + it.right) / 2 < firstEdge &&
                        it.top > headerBottom &&
                        rowSection(it.text) != null
                }
                .sortedBy { it.top }
        val codedGutter =
            valid
                .filter { it.left < firstEdge && it.top > headerBottom }
                .filter { it.text.contains("小节") || it.text.startsWith("第") }
                .let {
                    clusterLines(it, height).map { group ->
                        group
                            .first()
                            .copy(
                                text = group.joinToString("") { it.text.trim() },
                                bottom = group.last().bottom,
                            )
                    }
                }
        val rows = mutableListOf<DocumentCourseRow>()
        var unparsed = 0
        dayCenters.forEachIndexed { i, (day, center) ->
            val left = if (i == 0) firstEdge else (dayCenters[i - 1].second + center) / 2
            val right =
                if (i == dayCenters.lastIndex) center + columnWidth / 2
                else (center + dayCenters[i + 1].second) / 2
            val column = sorted.filter { item ->
                val x = item.left + minOf((item.right - item.left) / 2, height)
                x >= left &&
                    x < right &&
                    item.top >= headerBottom - height * 0.2f &&
                    item.top < footerTop &&
                    headerDay(item.text) == null
            }
            clusterLines(column, height).forEach { group ->
                val text = group.joinToString("\n") { it.text }
                if (ignored(text)) return@forEach
                val y = (group.first().top + group.last().bottom) / 2
                val labels = if (Regex("H\\d{8}-").containsMatchIn(text)) codedGutter else gutter
                val nearest = labels.minByOrNull { kotlin.math.abs((it.top + it.bottom) / 2 - y) }
                val blocks = splitBlocks(text)
                // Only a nearby, explicitly ranged row label can supply a missing section range.
                // Never propagate a single observed label to distant cards or several courses.
                val context =
                    nearest
                        ?.takeIf { label ->
                            blocks.size == 1 &&
                                kotlin.math.abs((label.top + label.bottom) / 2 - y) <=
                                    minOf(height, label.bottom - label.top) * 2f
                        }
                        ?.let { rowSection(it.text) }
                        ?.takeIf { it.first != it.last }
                blocks.forEach { block ->
                    parseBlock(block, day.toString(), context, false)?.let(rows::add)
                        ?: run { unparsed++ }
                }
            }
        }
        warnings += "图片/PDF识别可能拆错课程边界，保存前请逐门核对星期、节次、周次、老师和地点"
        return result(name, rows, raw, warnings, unparsed)
    }

    private fun result(
        name: String,
        rows: List<DocumentCourseRow>,
        raw: String,
        notes: List<String>,
        unparsed: Int,
    ): DocumentRecognition {
        val unique = rows.distinct()
        val incomplete = unique.count { row ->
            val start = row.startSection.toIntOrNull()
            val end = row.endSection.toIntOrNull()
            if (row.untimed)
                row.name.isBlank() ||
                    (row.weeks.isNotBlank() && runCatching { Importers.weeks(row.weeks) }.isFailure)
            else
                row.name.isBlank() ||
                    row.day.toIntOrNull() !in 1..7 ||
                    start !in 1..24 ||
                    end !in 1..24 ||
                    (start != null && end != null && start > end) ||
                    runCatching { Importers.weeks(row.weeks) }.isFailure
        }
        val warnings = notes.toMutableList()
        if (unique.isEmpty()) warnings += "未识别出课程。可以参考原文手动补充七列课程信息"
        if (incomplete > 0) warnings += "${incomplete}门课程缺少或无法确认星期、节次或周次，需补全后导入；不会自动填入整学期"
        if (unique.any { it.teacher.isBlank() || it.place.isBlank() })
            warnings += "部分课程没有明确标注老师或地点，请对照原文件补充"
        warnings += "请确认开学日期和作息时间；文档识别不会自动设置学期"
        return DocumentRecognition(
            name,
            unique,
            toCsv(unique),
            raw,
            warnings.distinct(),
            incomplete + unparsed,
        )
    }

    private fun parseBlock(
        text: String,
        day: String,
        context: IntRange?,
        requireEvidence: Boolean,
    ): DocumentCourseRow? {
        exportBlock(text, day)?.let {
            return it
        }
        if (Regex("H\\d{8}-").containsMatchIn(text)) return codedBlock(text, day, context)
        campusBlock(text, day)?.let {
            return it
        }
        val normalized = timingText(text)
        val sections = sectionRange(normalized) ?: context
        val weeks = extractWeeks(normalized)
        val explicitDay = dayPattern.find(normalized)?.value?.let(::dayValue).orEmpty()
        val labelledName = coursePattern.find(normalized)?.groupValues?.get(1)?.let(::compact)
        if (requireEvidence && sections == null && weeks.isBlank() && labelledName == null)
            return null
        val teacher =
            teacherPattern.find(normalized)?.groupValues?.get(1)?.let(::metadataValue).orEmpty()
        val labelledPlace =
            placePattern.find(normalized)?.groupValues?.get(1)?.let(::metadataValue).orEmpty()
        val place = labelledPlace.ifBlank {
            normalized
                .lines()
                .firstOrNull { line ->
                    roomLine.matches(line.trim()) && !isTiming(line) && !isMetadata(line)
                }
                ?.let(::compact)
                .orEmpty()
        }
        val titleLines =
            normalized.lines().takeWhile { line ->
                line.isBlank() || (!isTiming(line) && !isMetadata(line) && !ignored(line))
            }
        val inlineTitle =
            normalized
                .substringBefore(sectionPattern.find(normalized)?.value ?: "\n")
                .substringBefore(weekPattern.find(normalized)?.value ?: "\n")
                .replace(dayPattern, "")
                .trim(' ', '(', ')', '：', ':')
        val name =
            labelledName
                ?: compact(titleLines.filter { it.isNotBlank() }.joinToString("")).ifBlank {
                    if (inlineTitle.isNotBlank() && !isMetadata(inlineTitle)) compact(inlineTitle)
                    else ""
                }
        if (name.isBlank() || ignored(name)) return null
        val confirmedDay =
            if (explicitDay.isNotBlank() && day.isNotBlank() && explicitDay != day) ""
            else explicitDay.ifBlank { day }
        return DocumentCourseRow(
            name,
            confirmedDay,
            sections?.first?.toString().orEmpty(),
            sections?.last?.toString().orEmpty(),
            teacher,
            place,
            weeks,
        )
    }

    private fun codedBlock(text: String, day: String, context: IntRange?): DocumentCourseRow? {
        val joined = normalize(text).lines().joinToString("") { it.trim() }
        val title = Regex("H\\d{8}-(.*?)\\[\\d+]").find(joined) ?: return null
        val timing = joined.substring(title.range.last + 1)
        val observedDay = Regex("星期([1-7])").find(timing)?.groupValues?.get(1) ?: return null
        val sections = Regex("第(\\d+)小节-第(\\d+)小节").find(timing)
        val first = sections?.groupValues?.get(1) ?: context?.first?.toString().orEmpty()
        val last = sections?.groupValues?.get(2) ?: context?.last?.toString().orEmpty()
        val place =
            sections
                ?.let { timing.substring(it.range.last + 1).trim(',', ' ') }
                .orEmpty()
                .takeUnless { it == "无" }
                .orEmpty()
        return DocumentCourseRow(
            title.groupValues[1],
            observedDay.takeIf { day.isBlank() || day == it }.orEmpty(),
            first,
            last,
            place = place,
            weeks = normalizeWeeks(timing.substringBefore("星期").trimEnd(',', ' ')),
        )
    }

    /**
     * Academic grid exports write one course per cell as `课程名 (1-2节)3-4周,6-8周/校区:…/场地:
     * A414/教师:己老师/教学班:…/学分:…`. Values wrap mid-word across lines, so the metadata tail is joined
     * before the labelled fields are cut out; leading fragment lines from a previous cell (班级/考核
     * metadata) are dropped so the name stays clean.
     */
    private fun campusBlock(text: String, day: String): DocumentCourseRow? {
        val normalized = timingText(normalize(text))
        val lines = normalized.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return null
        var timingLine = -1
        var timingStart = -1
        for ((index, line) in lines.withIndex()) {
            val match = campusSection.find(line)
            if (match != null) {
                timingLine = index
                timingStart = match.range.first
                break
            }
        }
        if (timingLine < 0) return null
        val tail = lines.subList(timingLine, lines.size).joinToString("")
        // The slash-separated label run (/校区:/场地:/教师:…) is what distinguishes this export
        // from ordinary cells that merely label 教师:/地点: on their own lines.
        if (!campusLabel.containsMatchIn(tail) || !Regex("节\\)[^\\n]*/").containsMatchIn(tail))
            return null
        val prefix = lines[timingLine].substring(0, timingStart).trim(' ', '（', '　')
        val name =
            (lines.subList(0, timingLine) + prefix)
                .filter { it.isNotBlank() && !campusMetadata(it) }
                .joinToString("") { it.trim() }
                .let(::compact)
        if (name.isBlank()) return null
        val section = campusSection.find(tail) ?: return null
        val start = section.groupValues[1].toIntOrNull() ?: return null
        val end = section.groupValues[2].toIntOrNull() ?: start
        if (start !in 1..24 || end !in start..24) return null
        val weeks = normalizeWeeks(tail.substring(section.range.last + 1).substringBefore('/'))
        val teacher = campusTeacher.find(tail)?.groupValues?.get(1)?.let(::compact).orEmpty()
        val place = campusPlace.find(tail)?.groupValues?.get(1)?.let(::compact).orEmpty()
        val explicitDay = dayPattern.find(tail)?.value?.let(::dayValue).orEmpty()
        val confirmedDay =
            if (explicitDay.isNotBlank() && day.isNotBlank() && explicitDay != day) ""
            else explicitDay.ifBlank { day }
        return DocumentCourseRow(
            name,
            confirmedDay,
            start.toString(),
            end.toString(),
            teacher,
            place,
            weeks,
        )
    }

    private fun campusMetadata(value: String): Boolean =
        campusLabel.containsMatchIn(value.trim()) ||
            classListRow.matches(value.trim()) ||
            Regex("^(?:[:/]|(?:试|查)/|(?:分|时|组成):|学班[:：])").containsMatchIn(value.trim())

    private fun splitBlocks(text: String): List<String> {
        val normalized = timingText(text)
        val templateLines = normalized.lines().filter { it.isNotBlank() }
        if (templateLines.any { exportTitle.matches(it.trim()) }) {
            // One title can own several weekly teaching/lab runs. The merged cell's
            // overall height is not the duration of each of those runs.
            var title: String? = null
            return buildList {
                templateLines.forEach { line ->
                    when {
                        exportTitle.matches(line.trim()) -> title = line.trim()
                        exportTiming.matches(line.trim()) && title != null ->
                            add("$title\n${line.trim()}")
                        Regex("^\\d{4}级.*班$").matches(line.trim()) -> Unit
                        else -> add(line)
                    }
                }
            }
        }
        if (Regex("H\\d{8}-").containsMatchIn(normalized)) {
            val starts =
                templateLines.indices.filter {
                    Regex("H\\d{8}-").containsMatchIn(templateLines[it])
                }
            return starts.mapIndexed { i, start ->
                templateLines
                    .subList(start, starts.getOrNull(i + 1) ?: templateLines.size)
                    .joinToString("\n")
            }
        }
        val campusTimings =
            templateLines.indices.filter { campusSection.containsMatchIn(templateLines[it]) }
        if (campusTimings.isNotEmpty() && campusLabel.containsMatchIn(normalized)) {
            val starts = campusTimings.mapIndexed { i, timing ->
                var start = timing
                val limit = campusTimings.getOrNull(i - 1)?.plus(1) ?: 0
                while (
                    start > limit &&
                        !campusMetadata(templateLines[start - 1]) &&
                        !isTiming(templateLines[start - 1])
                ) start--
                start
            }
            return starts.mapIndexed { i, start ->
                templateLines
                    .subList(start, starts.getOrNull(i + 1) ?: templateLines.size)
                    .joinToString("\n")
            }
        }
        val paragraphs =
            normalized.split(Regex("\\n\\s*\\n|\\n[-─━]{3,}\\n")).filter { it.isNotBlank() }
        return paragraphs.flatMap { paragraph ->
            val lines = paragraph.lines().filter { it.isNotBlank() }
            val starts = mutableListOf(0)
            var sawTiming = false
            lines.forEachIndexed { i, line ->
                if (isTiming(line)) {
                    if (sawTiming) {
                        val timingField = shortLabelledTiming(line)
                        if (timingField != null) {
                            val current =
                                parseBlock(
                                    lines.subList(starts.last(), i).joinToString("\n"),
                                    "",
                                    null,
                                    false,
                                )
                            val fillsMissingField =
                                current != null &&
                                    when (timingField) {
                                        "weeks" -> current.weeks.isBlank()
                                        "sections" -> current.startSection.isBlank()
                                        "day" -> current.day.isBlank()
                                        else -> false
                                    }
                            // OCR can misread a field label (e.g. 周次 -> LR). An explicit timing
                            // value fills the existing course's missing field; it is not a title.
                            // Once that field exists, a new title with timing still starts a
                            // course.
                            if (fillsMissingField) return@forEachIndexed
                        }
                        var start = i
                        while (
                            start > starts.last() &&
                                !isTiming(lines[start - 1]) &&
                                !isMetadata(lines[start - 1])
                        ) start--
                        val prefix =
                            line
                                .substringBefore(
                                    sectionPattern.find(line)?.value
                                        ?: weekPattern.find(line)?.value
                                        ?: line
                                )
                                .trim(' ', '(', ')')
                        if (
                            start > starts.last() &&
                                (start < i || (prefix.isNotBlank() && !isMetadata(prefix)))
                        )
                            starts += start
                    }
                    sawTiming = true
                }
            }
            starts.mapIndexed { i, start ->
                lines.subList(start, starts.getOrNull(i + 1) ?: lines.size).joinToString("\n")
            }
        }
    }

    private val exportTitle =
        Regex("^(.+?)\\[[A-Za-z0-9][A-Za-z0-9-]*]\\s*\\d+\\[(?:必修课|选修课|限选课|任选课)]$")
    private val exportTiming =
        Regex(
            "^(.+?)\\s+((?:第)?\\d{1,2}(?:(?:节)?[-~至](?:第)?\\d{1,2})?节)\\[[^]]+]\\s*(\\S+)(?:\\s+(.+))?$"
        )

    private fun exportBlock(text: String, day: String): DocumentCourseRow? {
        val lines = timingText(text).lines().filter { it.isNotBlank() }
        if (lines.size != 2) return null
        val title = exportTitle.matchEntire(lines[0].trim()) ?: return null
        val timing = exportTiming.matchEntire(lines[1].trim()) ?: return null
        val sections = sectionRange(timing.groupValues[2]) ?: return null
        return DocumentCourseRow(
            name = title.groupValues[1].trim(),
            day = day,
            startSection = sections.first.toString(),
            endSection = sections.last.toString(),
            weeks = normalizeWeeks(timing.groupValues[1]),
            teacher = timing.groupValues[3],
            place = timing.groupValues[4].trim(),
        )
    }

    private fun shortLabelledTiming(value: String): String? {
        val labelled =
            Regex("^[\\p{IsHan}A-Za-z][\\p{IsHan}A-Za-z0-9._-]{0,3}\\s*[:：=]\\s*(.+)$")
                .matchEntire(value.trim()) ?: return null
        val timing = labelled.groupValues[1].trim().trim('(', ')')
        return when {
            weekPattern.matchEntire(timing) != null -> "weeks"
            sectionPattern.find(timing)?.value == timing -> "sections"
            dayValue(timing).isNotBlank() -> "day"
            else -> null
        }
    }

    private fun clusterLines(lines: List<DocumentText>, height: Float): List<List<DocumentText>> {
        val groups = mutableListOf<MutableList<DocumentText>>()
        lines
            .sortedBy { it.top }
            .forEach { line ->
                val group = groups.lastOrNull()
                if (group == null || line.top - group.maxOf { it.bottom } > height * 1.9f)
                    groups += mutableListOf(line)
                else group += line
            }
        return groups
    }

    private fun fallbackColumns(
        lines: List<DocumentText>,
        height: Float,
    ): List<List<DocumentText>> {
        val groups = mutableListOf<MutableList<DocumentText>>()
        val width = lines.maxOf { it.right } - lines.minOf { it.left }
        val tolerance = maxOf(height * 2f, width * 0.035f)
        lines
            .sortedBy { it.left }
            .forEach { line ->
                val group = groups.lastOrNull()
                if (group == null || line.left - group.first().left > tolerance)
                    groups += mutableListOf(line)
                else group += line
            }
        return groups.map { it.sortedBy { item -> item.top } }
    }

    private fun positionedHeaders(line: DocumentText): List<DocumentText> {
        if (headerDay(line.text) != null) return listOf(line)
        val matches = dayPattern.findAll(normalize(line.text)).toList()
        if (matches.size < 2) return emptyList()
        // Uniformly spaced weekday labels can be one OCR line. Their ordered positions form the
        // grid.
        return matches.mapIndexed { i, match ->
            val width = (line.right - line.left) / matches.size
            line.copy(
                text = match.value,
                left = line.left + width * i,
                right = line.left + width * (i + 1),
            )
        }
    }

    private fun quote(value: String): String =
        if (value.any { it in ",\"\r\n" }) "\"${value.replace("\"", "\"\"")}\"" else value

    private fun key(value: String): String = value.lowercase().filter { it.isLetterOrDigit() }

    private fun headerIndex(value: String): Int? =
        aliases.indexOfFirst { key(value) in it }.takeIf { it >= 0 }

    private fun normalize(value: String): String =
        value
            .replace('\u00a0', ' ')
            .replace('（', '(')
            .replace('）', ')')
            .replace('，', ',')
            .replace('－', '-')
            .replace('—', '-')
            .replace('–', '-')
            .replace('～', '~')
            .replace("\r\n", "\n")
            .replace('\r', '\n')

    private fun compact(value: String): String =
        normalize(value)
            .trim()
            .replace(
                Regex(
                    "(?<=\\p{IsHan})\\s+(?=[\\p{IsHan}A-Za-z0-9])|(?<=[A-Za-z0-9])\\s+(?=\\p{IsHan})"
                ),
                "",
            )
            .replace(Regex("(?<=[A-Za-z])\\s*\\n\\s*(?=[0-9])"), "")
            .replace("\n", " ")
            .trim()

    private fun cellMetadata(value: String): String {
        val clean = normalize(value).trim()
        // Structured teacher/place columns already identify the field. Preserve their deliberate
        // spacing; only join words when the cell itself contains wrapped lines.
        return if ('\n' in clean) compact(clean) else clean
    }

    private fun dayValue(value: String): String {
        val clean = compact(value).lowercase()
        clean.toIntOrNull()?.let {
            return if (it in 1..7) it.toString() else ""
        }
        val chinese = clean.removePrefix("星期").removePrefix("礼拜").removePrefix("周")
        val index = listOf("一", "二", "三", "四", "五", "六", "日", "天").indexOf(chinese)
        if (index >= 0) return if (index == 7) "7" else (index + 1).toString()
        return when (clean) {
            "monday",
            "mon" -> "1"
            "tuesday",
            "tue" -> "2"
            "wednesday",
            "wed" -> "3"
            "thursday",
            "thu" -> "4"
            "friday",
            "fri" -> "5"
            "saturday",
            "sat" -> "6"
            "sunday",
            "sun" -> "7"
            else -> if (chinese.toIntOrNull() in 1..7) chinese else ""
        }
    }

    private fun headerDay(value: String): Int? {
        val clean = compact(value.lines().firstOrNull().orEmpty())
        if (clean.toIntOrNull() != null) return null
        dayValue(clean).toIntOrNull()?.let {
            return it
        }
        val first = Regex("^(?:星期|礼拜|周)([一二三四五六日天1-7])(?:\\d{1,2}[/月.-]\\d{1,2}日?)?$").find(clean)
        return first?.groupValues?.get(1)?.let(::dayValue)?.toIntOrNull()
    }

    private fun sectionValue(value: String): String {
        val plain =
            compact(value).removePrefix("第").removeSuffix("节次").removeSuffix("节").removeSuffix(".0")
        return plain.toIntOrNull()?.takeIf { it in 1..24 }?.toString().orEmpty()
    }

    private fun timingText(value: String): String =
        normalize(value)
            .replace(Regex("(?<=\\d)\\s*([-~至])\\s*(?=\\d)"), "$1")
            .replace(Regex("(?<=\\d)\\s+(?=[周节])"), "")

    private fun sectionRange(value: String): IntRange? {
        val clean = timingText(value)
        val match =
            sectionPattern.find(clean)
                ?: Regex("^\\s*(\\d{1,2})[-~至](\\d{1,2})\\s*$").find(clean)
                ?: return null
        val first = match.groupValues[1].toIntOrNull() ?: return null
        val last = match.groupValues[2].toIntOrNull() ?: first
        return (first..last).takeIf { first in 1..24 && last in first..24 }
    }

    private fun rowSection(value: String): IntRange? =
        Regex("第(\\d+)小节-第(\\d+)小节").find(value.replace(" ", ""))?.let {
            it.groupValues[1].toInt()..it.groupValues[2].toInt()
        }
            ?: sectionRange(value)
            ?: sectionValue(value.lines().firstOrNull().orEmpty()).toIntOrNull()?.let { it..it }

    private fun normalizeWeeks(value: String): String {
        var text =
            normalize(value)
                .replace(Regex("\\s+"), "")
                .replace("周次:", "")
                .replace("周数:", "")
                .replace("第", "")
        text =
            text
                .replace(Regex("(?i)weeks?"), "")
                .replace("单周", "单")
                .replace("双周", "双")
                .replace("周", "")
                .replace("(", "")
                .replace(")", "")
                .replace('~', '-')
                .replace("至", "-")
                .replace(';', ',')
                .replace('、', ',')
                .replace('/', ',')
        // A trailing parity marker on an explicit range applies to that range, never an implied
        // semester.
        return runCatching {
                Importers.weeks(text)
                text
            }
            .getOrElse { value.trim() }
    }

    private fun extractWeeks(value: String): String {
        val clean = timingText(value)
        labelledWeeks
            .find(clean)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let {
                return normalizeWeeks(it)
            }
        val found = weekPattern.find(clean) ?: return ""
        var text = found.value
        val after = clean.substring(found.range.last + 1).trimStart()
        Regex("^\\(?([单双])(?:周)?\\)?").find(after)?.let { text += it.groupValues[1] }
        return normalizeWeeks(text)
    }

    private fun isTiming(value: String): Boolean =
        sectionPattern.containsMatchIn(value) ||
            weekPattern.containsMatchIn(value) ||
            labelledWeeks.containsMatchIn(value) ||
            dayPattern.containsMatchIn(value)

    private fun isMetadata(value: String): Boolean =
        fieldLabel.containsMatchIn(value.trim()) ||
            teacherPattern.containsMatchIn(value) ||
            placePattern.containsMatchIn(value) ||
            roomLine.matches(value.trim()) ||
            value.trim().startsWith("学分") ||
            value.trim().startsWith("课程代码") ||
            value.trim().startsWith("班级") ||
            classListRow.matches(value.trim()) ||
            Regex("考核方式|教学班组成|课程学时组成|选课备注").containsMatchIn(value)

    private fun ignored(value: String): Boolean =
        value.trim().let {
            it.isBlank() ||
                it in setOf("无", "-", "空", "午休", "休息", "节次", "时间段", "上午", "下午", "晚上") ||
                Regex("^[\\d\\s/:.\\-]+$").matches(it) ||
                it.startsWith("备注") ||
                it.startsWith("说明") ||
                it.startsWith("实践课：") ||
                Regex("^(实践课程|其他课程)[:：]").containsMatchIn(it) ||
                it.startsWith("打印时间") ||
                it.startsWith("打印日期") ||
                Regex("^[\\s:：]*(实践|理论|实验|上机)\\s*[:：]").containsMatchIn(it)
        }

    private fun metadataValue(value: String): String =
        compact(
            value.substringBefore(
                Regex(
                    "(?:老师|教师|地点|教室|周次|课程代码|teacher|instructor|location|classroom|room|weeks?)\\s*[:：=]",
                    RegexOption.IGNORE_CASE,
                )
            )
        )

    private fun String.substringBefore(regex: Regex): String =
        regex.find(this)?.let { substring(0, it.range.first) } ?: this
}

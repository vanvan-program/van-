// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.domain

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class DocumentImportTest {
    private fun sheet(vararg rows: List<String>): DocumentSheet =
        DocumentSheet(
            "文档测试",
            rows.flatMapIndexed { row, cells ->
                cells.mapIndexed { column, text -> DocumentCell(row, column, text) }
            },
        )

    @Test
    fun standardColumnsAndEnglishAliasesPreserveMetadataAndImportAsCsv() {
        val result =
            DocumentImport.fromSheet(
                sheet(
                    listOf(
                        "Course name",
                        "Weekday",
                        "Start section",
                        "End section",
                        "Instructor",
                        "Location",
                        "Weeks",
                    ),
                    listOf("大数据技术", "Monday", "1.0", "2", "王剑雄", "朝阳校区 A414", "3-4周,6-8周"),
                    listOf("Operating Systems", "周日", "3", "4", "Ms. Li", "Room 306", "1-16周(双)"),
                )
            )
        assertEquals(2, result.rows.size)
        assertEquals("1", result.rows[0].day)
        assertEquals("王剑雄", result.rows[0].teacher)
        assertEquals("朝阳校区 A414", result.rows[0].place)
        assertEquals("3-4,6-8", result.rows[0].weeks)
        assertEquals("7", result.rows[1].day)
        assertEquals((2..16 step 2).toSet(), Importers.weeks(result.rows[1].weeks))
        assertEquals(0, result.unresolvedCount)
        val draft = Importers.csv(result.csv, LocalDate.parse("2026-09-07"))
        assertEquals(2, draft.schedule.sessions.size)
        assertEquals("王剑雄", draft.schedule.sessions.first().teacher)
        assertEquals("朝阳校区 A414", draft.schedule.sessions.first().place)
        assertEquals("Ms. Li", result.rows[1].teacher)
        assertEquals("Room 306", result.rows[1].place)
    }

    @Test
    fun combinedSectionsColumnAndHeaderBelowTitleAreRecognized() {
        val result =
            DocumentImport.fromSheet(
                sheet(
                    listOf("2026秋季个人课表"),
                    listOf("课程", "星期", "节次", "教师", "教室", "周次"),
                    listOf("Java程序设计", "星期三", "第3-4节", "祁爱华", "A314", "1、3、5周"),
                )
            )
        assertEquals(
            DocumentCourseRow("Java程序设计", "3", "3", "4", "祁爱华", "A314", "1,3,5"),
            result.rows.single(),
        )
    }

    @Test
    fun hebiaceStyleWeekGridRestoresSplitWordsAndSeparatedWeekRanges() {
        val result =
            DocumentImport.fromSheet(
                sheet(
                    listOf("节次", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"),
                    listOf(
                        "1-2节",
                        "大数\n据技\n术\n(1-2节)3-4周,6-8周\n地点: 朝阳校区 A414\n教师: 王剑雄",
                        "",
                        "",
                        "",
                        "汇编语言程序设计\n(1-2节)3-14周\n教室: A214\n老师: 杜春梅",
                    ),
                )
            )
        assertEquals(2, result.rows.size)
        val first = result.rows.first()
        assertEquals("大数据技术", first.name)
        assertEquals("1", first.day)
        assertEquals("1", first.startSection)
        assertEquals("2", first.endSection)
        assertEquals("3-4,6-8", first.weeks)
        assertEquals("王剑雄", first.teacher)
        assertEquals("朝阳校区A414", first.place)
        assertEquals("5", result.rows.last().day)
        assertEquals(0, result.unresolvedCount)
    }

    @Test
    fun mergedCellsInferSectionsOnlyFromExplicitRowLabels() {
        val cells =
            listOf(
                DocumentCell(0, 0, "节次"),
                DocumentCell(0, 1, "周一"),
                DocumentCell(0, 2, "周二"),
                DocumentCell(1, 0, "第1节"),
                DocumentCell(2, 0, "第2节"),
                DocumentCell(3, 0, "第3节"),
                DocumentCell(4, 0, "第4节"),
                DocumentCell(1, 1, "操作系统\n1-16周(单)\n教师：李老师\n教室：A214", rowSpan = 2),
                DocumentCell(3, 2, "计算机系统结构\n1-16周", rowSpan = 2),
            )
        val result = DocumentImport.fromSheet(DocumentSheet("合并网格", cells))
        assertEquals(2, result.rows.size)
        assertEquals("1", result.rows.first().startSection)
        assertEquals("2", result.rows.first().endSection)
        assertEquals("3", result.rows.last().startSection)
        assertEquals("4", result.rows.last().endSection)
        assertEquals((1..16 step 2).toSet(), Importers.weeks(result.rows.first().weeks))
    }

    @Test
    fun crossDayMergedCellRequiresConfirmationInsteadOfAssigningFirstDay() {
        val result =
            DocumentImport.fromSheet(
                DocumentSheet(
                    "横跨星期",
                    listOf(
                        DocumentCell(0, 1, "周一"),
                        DocumentCell(0, 2, "周二"),
                        DocumentCell(1, 1, "实践课程\n1-2节\n1-8周", columnSpan = 2),
                    ),
                )
            )
        assertEquals("", result.rows.single().day)
        assertEquals(1, result.unresolvedCount)
        assertTrue(result.warnings.any { it.contains("跨星期") })
    }

    @Test
    fun multipleCoursesInsideOneCellKeepSeparateRows() {
        val result =
            DocumentImport.fromSheet(
                sheet(
                    listOf("节次", "周一", "周二"),
                    listOf(
                        "1-2节",
                        "大数据技术\n(1-2节)1-8周\n教师: 张老师\n地点: A414\n操作系统\n(1-2节)9-16周\n教师: 李老师\n地点: A214",
                    ),
                )
            )
        assertEquals(listOf("大数据技术", "操作系统"), result.rows.map { it.name })
        assertEquals(listOf("1-8", "9-16"), result.rows.map { it.weeks })
        assertEquals(listOf("A414", "A214"), result.rows.map { it.place })
    }

    @Test
    fun missingWeeksAndIncompleteMergeDoNotInventSemesterOrSections() {
        val result =
            DocumentImport.fromSheet(
                DocumentSheet(
                    "缺失数据",
                    listOf(
                        DocumentCell(0, 0, "节次"),
                        DocumentCell(0, 1, "周一"),
                        DocumentCell(0, 2, "周二"),
                        DocumentCell(1, 0, "第1节"),
                        DocumentCell(1, 1, "操作系统", rowSpan = 2),
                    ),
                )
            )
        val row = result.rows.single()
        assertEquals("", row.startSection)
        assertEquals("", row.endSection)
        assertEquals("", row.weeks)
        assertEquals(1, result.unresolvedCount)
        assertTrue(result.warnings.any { it.contains("不会自动填入") })
    }

    @Test
    fun numericDataCellsDoNotBecomeWeekdayHeaders() {
        val result =
            DocumentImport.fromSheet(
                sheet(listOf("1", "2", "3", "4", "5", "6", "7"), listOf("无标题页"))
            )
        assertTrue(result.rows.isEmpty())
        assertTrue(result.warnings.any { it.contains("星期表头") })
    }

    private fun line(
        text: String,
        x: Float,
        y: Float,
        width: Float = 0.11f,
        height: Float = 0.022f,
    ) = DocumentText(text, x, y, x + width, y + height)

    @Test
    fun positionedOcrUsesWeekdayColumnsAndRecognizesMetadata() {
        val lines =
            listOf(
                line("个人课表", 0.3f, 0.05f),
                line("星期一", 0.13f, 0.10f),
                line("星期二", 0.33f, 0.106f),
                line("星期三", 0.53f, 0.10f),
                line("大数据技术", 0.13f, 0.20f),
                line("(1-2节)3-4周,6-8周", 0.13f, 0.23f),
                line("教室:朝阳校区A414", 0.13f, 0.26f),
                line("教师:王剑雄", 0.13f, 0.29f),
                line("操作系统", 0.53f, 0.20f),
                line("3-4节 1-16周(双)", 0.53f, 0.23f),
                line("老师:张老师", 0.53f, 0.26f),
                line("地点:A214", 0.53f, 0.29f),
            )
        val result = DocumentImport.fromPositioned("OCR测试", lines)
        assertEquals(listOf("大数据技术", "操作系统"), result.rows.map { it.name })
        assertEquals(listOf("1", "3"), result.rows.map { it.day })
        assertEquals("王剑雄", result.rows.first().teacher)
        assertEquals("朝阳校区A414", result.rows.first().place)
        assertEquals((2..16 step 2).toSet(), Importers.weeks(result.rows.last().weeks))
        assertEquals(0, result.unresolvedCount)
    }

    @Test
    fun screenshotWithoutHeaderRetainsCourseButRequiresWeekday() {
        val result =
            DocumentImport.fromPositioned(
                "裁切截图",
                listOf(
                    line("大数据技术", 0.15f, 0.3f),
                    line("(1-2节)3-4周,6-8周", 0.15f, 0.33f),
                    line("教师:王剑雄", 0.15f, 0.36f),
                    line("教室:朝阳校区A414", 0.15f, 0.39f),
                ),
            )
        assertEquals("大数据技术", result.rows.single().name)
        assertEquals("", result.rows.single().day)
        assertEquals("3-4,6-8", result.rows.single().weeks)
        assertEquals(1, result.unresolvedCount)
        assertTrue(result.warnings.any { it.contains("无法从图片确认") })
    }

    @Test
    fun oneOcrLineCanContainAllWeekdayLabelsAndDateLinesAreIgnored() {
        val result =
            DocumentImport.fromPositioned(
                "整行标题",
                listOf(
                    line("周一 周二 周三 周四 周五 周六 周日", 0.1f, 0.1f, width = 0.84f),
                    line("10/05", 0.11f, 0.15f),
                    line("操作系统", 0.59f, 0.3f, width = 0.1f),
                    line("(3-4节)1-16周", 0.59f, 0.33f, width = 0.1f),
                ),
            )
        assertEquals(1, result.rows.size)
        assertEquals("5", result.rows.single().day)
        assertEquals("操作系统", result.rows.single().name)
    }

    @Test
    fun severalCroppedColumnsRemainIndependentAndAllNeedWeekday() {
        val result =
            DocumentImport.fromPositioned(
                "裁切多列",
                listOf(
                    line("大数据技术", 0.15f, 0.3f),
                    line("(1-2节)3-4周,6-8周", 0.15f, 0.33f),
                    line("教师:王剑雄", 0.15f, 0.36f),
                    line("操作系统", 0.55f, 0.3f),
                    line("(3-4节)1-16周", 0.55f, 0.33f),
                    line("教师:张老师", 0.55f, 0.36f),
                ),
            )
        assertEquals(listOf("大数据技术", "操作系统"), result.rows.map { it.name })
        assertTrue(result.rows.all { it.day.isBlank() })
        assertEquals(2, result.unresolvedCount)
    }

    @Test
    fun distantGutterCannotSupplySectionsWhileNearbyExplicitRowRangeCan() {
        val result =
            DocumentImport.fromPositioned(
                "节次距离核对",
                listOf(
                    line("周一", 0.15f, 0.1f),
                    line("周二", 0.35f, 0.1f),
                    line("1-2节", 0.01f, 0.23f, width = 0.06f),
                    line("大数据技术", 0.15f, 0.2f),
                    line("1-8周", 0.15f, 0.23f),
                    line("教师:王剑雄", 0.15f, 0.26f),
                    line("操作系统", 0.35f, 0.7f),
                    line("1-16周", 0.35f, 0.73f),
                    line("教师:张老师", 0.35f, 0.76f),
                ),
            )
        assertEquals(2, result.rows.size)
        val near = result.rows.first { it.name == "大数据技术" }
        val far = result.rows.first { it.name == "操作系统" }
        assertEquals("1", near.startSection)
        assertEquals("2", near.endSection)
        assertEquals("", far.startSection)
        assertEquals("", far.endSection)
        assertEquals("2", far.day)
        assertEquals(1, result.unresolvedCount)
    }

    @Test
    fun missingReversedOrUnevenWeekdayHeadersNeverAssignANeighboringDay() {
        val badHeaders =
            listOf(
                listOf(line("周一", 0.1f, 0.1f), line("周三", 0.5f, 0.1f)),
                listOf(line("周二", 0.1f, 0.1f), line("周一", 0.5f, 0.1f)),
                listOf(line("周一", 0.1f, 0.1f), line("周二", 0.2f, 0.1f), line("周三", 0.8f, 0.1f)),
            )
        badHeaders.forEach { header ->
            val result =
                DocumentImport.fromPositioned(
                    "不完整星期表头",
                    header +
                        listOf(
                            line("操作系统", 0.32f, 0.3f),
                            line("(3-4节)1-16周", 0.32f, 0.33f),
                            line("教师:张老师", 0.32f, 0.36f),
                        ),
                )
            assertEquals("操作系统", result.rows.single().name)
            assertEquals("", result.rows.single().day)
            assertEquals("3", result.rows.single().startSection)
            assertEquals("4", result.rows.single().endSection)
            assertEquals(1, result.unresolvedCount)
            assertTrue(result.warnings.any { it.contains("星期表头") && it.contains("无法") })
        }
    }

    @Test
    fun nativeOcrFixtureKeepsLabelledWeekTeacherAndRoomInTheSameCourse() {
        fun text(value: String, baseline: Float, width: Float) =
            DocumentText(value, 96f, baseline - 72f, 96f + width, baseline)
        val result =
            DocumentImport.fromPositioned(
                "本机PNG识别回归",
                listOf(
                    text("课表导入测试", 130f, 430f),
                    text("课程名称:高等效学", 320f, 720f),
                    text("星期一", 440f, 216f),
                    text("第1-2节", 560f, 300f),
                    text("周次:1-16周", 680f, 450f),
                    text("Teacher: 陈明", 800f, 520f),
                    text("Room: 教学楼 A101", 920f, 700f),
                ),
            )
        assertEquals(1, result.rows.size)
        val row = result.rows.single()
        assertEquals("高等效学", row.name)
        assertEquals("1", row.day)
        assertEquals("1", row.startSection)
        assertEquals("2", row.endSection)
        assertEquals("1-16", row.weeks)
        assertEquals("陈明", row.teacher)
        assertEquals("教学楼A101", row.place)
        assertEquals(0, result.unresolvedCount)
        assertTrue(result.rows.none { it.name.contains("周次") })
        val imported = Importers.csv(result.csv, LocalDate.parse("2026-09-07"))
        assertEquals((1..16).toSet(), imported.schedule.sessions.single().weeks)
    }

    @Test
    fun nativePdfOcrMisreadWeekLabelStillFillsTheExistingCourse() {
        fun text(value: String, baseline: Float, width: Float) =
            DocumentText(value, 96f, baseline - 72f, 96f + width, baseline)
        val result =
            DocumentImport.fromPositioned(
                "本机PDF短标签识别回归",
                listOf(
                    text("TRE SAM", 130f, 430f),
                    text("课程名称: 高等效学", 320f, 720f),
                    text("星期一", 440f, 216f),
                    text("第1-2节", 560f, 300f),
                    text("LR: 1-16周", 680f, 450f),
                    text("Teacher: 陈明", 800f, 520f),
                    text("Room: 教学楼 A101", 920f, 700f),
                ),
            )
        assertEquals(
            DocumentCourseRow("高等效学", "1", "1", "2", "陈明", "教学楼A101", "1-16"),
            result.rows.single(),
        )
        assertEquals(0, result.unresolvedCount)
    }

    @Test
    fun shortTimingFieldFixKeepsRealCourseNamesWithSectionsAsSeparateCourses() {
        val result =
            DocumentImport.fromSheet(
                sheet(
                    listOf("节次", "周一", "周二"),
                    listOf(
                        "1-2节",
                        "课程名称:高等数学\n星期一\n第1-2节\nLR:1-16周\nTeacher:陈明\nRoom:A101\n高数:3-4节\n1-8周\nTeacher:李老师\nRoom:A102\n课程名称:大学英语\n5-6节\n9-16周\nTeacher:张老师\nRoom:A103",
                    ),
                )
            )
        assertEquals(listOf("高等数学", "高数", "大学英语"), result.rows.map { it.name })
        assertEquals(listOf("1", "3", "5"), result.rows.map { it.startSection })
        assertEquals(listOf("1-16", "1-8", "9-16"), result.rows.map { it.weeks })
        assertEquals(listOf("陈明", "李老师", "张老师"), result.rows.map { it.teacher })
        assertEquals(0, result.unresolvedCount)
    }

    @Test
    fun labelledWeekListsAndWrappedTimingRangesRemainExplicit() {
        val result =
            DocumentImport.fromSheet(
                sheet(
                    listOf("节次", "周一", "周二"),
                    listOf(
                        "1-2节",
                        "操作系统\n(1 -\n2 节)\n周次：1、3、5\n教师:张老师",
                        "Java程序设计\n3-4节\n第1-16(单)周",
                    ),
                )
            )
        assertEquals(listOf("操作系统", "Java程序设计"), result.rows.map { it.name })
        assertEquals("1", result.rows.first().startSection)
        assertEquals("2", result.rows.first().endSection)
        assertEquals(setOf(1, 3, 5), Importers.weeks(result.rows.first().weeks))
        assertEquals((1..16 step 2).toSet(), Importers.weeks(result.rows.last().weeks))
    }

    @Test
    fun badWeeksAndSectionsRemainUnresolvedUntilUserCorrectsThem() {
        val result =
            DocumentImport.fromSheet(
                sheet(
                    listOf("课程名称", "星期", "开始节数", "结束节数", "老师", "地点", "周数"),
                    listOf("操作系统", "周二", "4", "2", "张老师", "A214", "0-16周"),
                )
            )
        assertEquals("0-16周", result.rows.single().weeks)
        assertEquals(1, result.unresolvedCount)
        assertTrue(
            runCatching { Importers.csv(result.csv, LocalDate.parse("2026-09-07")) }.isFailure
        )
    }

    @Test
    fun csvEscapingRetainsManualEditsWithCommaQuotesAndNewlines() {
        val row = DocumentCourseRow("课程,\"A\"", "1", "1", "2", "老师\n甲", "教学楼,A414", "1,3,5")
        val csv = DocumentImport.toCsv(listOf(row))
        val parsed = Importers.csvRows(csv)
        assertEquals(2, parsed.size)
        assertEquals(
            listOf(
                row.name,
                row.day,
                row.startSection,
                row.endSection,
                row.teacher,
                row.place,
                row.weeks,
            ),
            parsed.last(),
        )
    }

    /** Synthetic geometry mirroring the jwapp campus export: 7 day columns plus a section gutter. */
    private fun campusRowLine(text: String, x: Float, y: Float, width: Float = 100f) =
        DocumentText(text, x, y, x + width, y + 9f)

    @Test
    fun campusPdfTextLayerCellsParseWrappedTeacherPlaceAndWeeks() {
        val lines = mutableListOf<DocumentText>()
        lines += DocumentText("时间段", 60f, 20f, 90f, 30f)
        lines += DocumentText("节次", 60f, 20f, 90f, 30f)
        val dayCenters = listOf(151f, 254.8f, 358.7f, 462.5f, 566.3f, 670.2f, 774f)
        listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日").forEachIndexed { i, day ->
            lines += DocumentText(day, dayCenters[i] - 20f, 20f, dayCenters[i] + 20f, 30f)
        }
        for (section in 1..10) {
            lines += DocumentText(section.toString(), 70f, 55f + (section - 1) * 52f, 80f, 64f + (section - 1) * 52f)
        }
        // Monday morning cell; the metadata tail wraps mid-word, exactly like the export.
        val monday =
            listOf(
                "大数据技术",
                "(1-2节)3-4周,6-8周/校区:朝阳校区/场地:A414/教师:王",
                "剑雄/教学班:(2026-2027-1)-XX03005-01/教学班组成:计",
                "241;计242;计243;计244;计",
                "245/考核方式:考查/选课备",
                "注:/课程学时组成:理论:24,实验:8/周学时:2/总学时",
                ":24/学分:2.0",
            )
        monday.forEachIndexed { k, text -> lines += campusRowLine(text, 105f, 100f + k * 11f) }
        // Monday afternoon cell immediately below: clusters merge, splitBlocks separates courses.
        val mondayPm =
            listOf(
                "计算机系统结构",
                "(3-4节)3-4周,7-11周/校区:朝阳校区/场地:A314/教师:高",
                "丽婷/教学班:(2026-2027-1)-XX03011-01/教学班组成:计241;计242",
                "学分:2.0",
            )
        mondayPm.forEachIndexed { k, text -> lines += campusRowLine(text, 105f, 177f + k * 11f) }
        // Friday cell with single-week parity.
        lines += campusRowLine("Java程序设计", 516f, 100f)
        lines +=
            campusRowLine(
                "(3-4节)3周,7-11周(单)/校区:朝阳校区/场地:A214/教师:祁爱华/教学班:(2026-2027-1)-XX02003-01/学分:3.0",
                516f,
                111f,
            )
        // Footer rows: practice/exam weeks, legend and print time are not day-section courses.
        lines += campusRowLine("实践课程：认识实习李超(共2周)/1-2周; 数据库原理课程设计周丽莉(共1周)/18周;", 60f, 700f, 740f)
        lines += campusRowLine("其他课程：操作系统庞慧(共1周)/13周/无; 大数据技术王剑雄(共1周)/15周/无;", 60f, 711f, 740f)
        lines += campusRowLine("打印时间:2026-10-10", 400f, 722f, 120f)
        lines += campusRowLine("： 实践 : 理论 : 实验 : 上机", 400f, 733f, 120f)

        val result = DocumentImport.fromPositioned("教务PDF", lines)
        assertEquals(3, result.rows.size)
        assertEquals(
            DocumentCourseRow("大数据技术", "1", "1", "2", "王剑雄", "A414", "3-4,6-8"),
            result.rows[0],
        )
        assertEquals(
            DocumentCourseRow("计算机系统结构", "1", "3", "4", "高丽婷", "A314", "3-4,7-11"),
            result.rows[1],
        )
        assertEquals(
            DocumentCourseRow("Java程序设计", "5", "3", "4", "祁爱华", "A214", "3,7-11单"),
            result.rows[2],
        )
        assertTrue(result.rows.none { it.name.contains("实践") || it.name.contains("打印") })
        assertEquals(0, result.unresolvedCount)
        val draft = Importers.csv(result.csv, LocalDate.parse("2026-09-07"))
        assertEquals(3, draft.schedule.sessions.size)
    }
}

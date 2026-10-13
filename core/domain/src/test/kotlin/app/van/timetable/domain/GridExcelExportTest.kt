package app.van.timetable.domain

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class GridExcelExportTest {
    @Test
    fun mergedCellKeepsEveryCourseAndTeachingRunWithItsOwnWeeksAndRoom() {
        val sheet =
            DocumentSheet(
                "学生课表",
                listOf(
                    DocumentCell(0, 0, "节次/星期"),
                    DocumentCell(0, 1, "星期一"),
                    DocumentCell(0, 2, "星期二"),
                    DocumentCell(1, 0, "第9节"),
                    DocumentCell(2, 0, "第10节"),
                    DocumentCell(3, 0, "第11节"),
                    DocumentCell(
                        1,
                        1,
                        """
                        市场调查[120202110] 01[必修课]
                        9-10周 9-11节[实践] 示例老师 6-504
                        1-3周,6-7周,11-16周 9-11节[讲授] 示例老师 6-301
                        2024级示例班
                        市场调查[120202110] 01[必修课]
                        8周 第9节-第9节[讲授] 示例老师 6-504
                        2024级示例班
                        市场调查[120202110] 01[必修课]
                        8周 第10节-第11节[实践] 示例老师 6-504
                        2024级示例班
                        """
                            .trimIndent(),
                        rowSpan = 3,
                    ),
                    DocumentCell(
                        2,
                        2,
                        """
                        红色音乐鉴赏[2026-2027-1-722] 04[选修课]
                        9-16周 第10节-第11节[讲授] 示例老师 4-313
                        商务谈判[120202112] 01[必修课]
                        1-3周,5-8周 第10节-第11节[讲授] 另一老师 6-101
                        2024级示例班
                        """
                            .trimIndent(),
                        rowSpan = 2,
                    ),
                ),
            )
        val draft = DocumentImport.fromSheet(sheet)
        assertEquals(0, draft.unresolvedCount)
        assertEquals(6, draft.rows.size)
        assertEquals(listOf("11", "11", "9", "11", "11", "11"), draft.rows.map { it.endSection })
        assertEquals(
            listOf("6-504", "6-301", "6-504", "6-504", "4-313", "6-101"),
            draft.rows.map { it.place },
        )
        assertEquals("1-3,6-7,11-16", draft.rows[1].weeks)
        assertEquals("红色音乐鉴赏", draft.rows[4].name)
        val schedule = DocumentImport.toSchedule(draft.rows, LocalDate.of(2026, 9, 7))
        assertEquals(3, schedule.courses.size)
        assertEquals(6, schedule.sessions.size)
        assertEquals(11, schedule.routines.first().sections.size)
        assertEquals(2, CourseEngine.resolveDay(schedule, LocalDate.of(2026, 10, 26)).size)
    }

    @Test
    fun explicitEndSectionWinsOverMergedCellHeight() {
        val draft =
            DocumentImport.fromSheet(
                DocumentSheet(
                    "学生课表",
                    listOf(
                        DocumentCell(0, 1, "星期一"),
                        DocumentCell(0, 2, "星期二"),
                        DocumentCell(1, 0, "第6节"),
                        DocumentCell(2, 0, "第7节"),
                        DocumentCell(3, 0, "第8节"),
                        DocumentCell(4, 0, "第9节"),
                        DocumentCell(5, 0, "第10节"),
                        DocumentCell(
                            1,
                            1,
                            "商务谈判[120202112] 01[必修课]\n1-8周 第6节-第7节[讲授] 老师 6-102\n2024级示例班\n商务谈判实训[120202138] 01[必修课]\n9-14周 第6节-第10节[实践] 老师 6-202\n2024级示例班",
                            rowSpan = 5,
                        ),
                    ),
                )
            )
        assertEquals(listOf("7", "10"), draft.rows.map { it.endSection })
        assertEquals(listOf("商务谈判", "商务谈判实训"), draft.rows.map { it.name })
    }
}

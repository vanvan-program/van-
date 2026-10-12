// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.domain

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class WakeUpCompatibilityTest {
    private val monday = LocalDate.parse("2026-09-07")
    private val rows =
        """[{"timeTable":99,"node":1,"startTime":"8:0","endTime":"8:45"},{"timeTable":99,"node":2,"startTime":"8:55","endTime":"9:40"}]"""

    private fun fixture(
        times: String = rows,
        tableRef: String = "\"timeTable\":0,",
        detail: String =
            """{"id":41,"tableId":8,"day":1,"startNode":1,"step":2,"startWeek":1,"endWeek":16,"type":1,"teacher":"张老师","room":"立德楼 A414"}""",
    ) =
        """{"id":7,"name":"夏季作息"}
$times
{"id":8,${tableRef}"nodes":2,"startDate":"2026-9-7","maxWeek":20,"tableName":"WakeUp兼容验证"}
[{"id":41,"tableId":8,"courseName":"导入验证课程","credit":2.5,"note":"本地导入"}]
[$detail]"""

    @Test
    fun exportIdsAreRebuiltWithoutDroppingTimesOrMetadata() {
        val (backup, warnings) = Importers.file("\uFEFF" + fixture().replace("\n", "\r\n"), monday)
        val s = backup.schedules.single()
        assertEquals("WakeUp兼容验证", s.name)
        assertEquals("夏季作息", s.routines.single().name)
        assertEquals("08:00", s.routines.single().sections.first().start)
        assertEquals("09:40", s.routines.single().sections.last().end)
        assertEquals("2.5", s.courses.single().credit)
        assertEquals("张老师", s.sessions.single().teacher)
        assertEquals("立德楼 A414", s.sessions.single().place)
        assertEquals((1..16 step 2).toSet(), s.sessions.single().weeks)
        assertTrue(warnings.any { it.contains("关联ID不同") })
        val copied = Importers.freshCopy(s)
        assertEquals(
            copied,
            Codec.restore(Codec.backup(Backup(schedules = listOf(copied)))).schedules.single(),
        )
    }

    @Test
    fun missingDatabaseLinksDoNotInvalidateAnOtherwiseCompleteBackup() {
        val times = rows.replace("\"timeTable\":99,", "")
        val draft = Importers.wakeup(fixture(times = times, tableRef = ""))
        assertEquals(2, draft.schedule.routines.single().sections.size)
        assertEquals(1, draft.schedule.sessions.size)
    }

    @Test
    fun fullRoutineMayHaveMoreRowsThanTheDisplayedSectionCount() {
        val times =
            rows.dropLast(1) +
                ",{\"timeTable\":99,\"node\":3,\"startTime\":\"10:00\",\"endTime\":\"10:45\"}]"
        assertEquals(
            2,
            Importers.wakeup(fixture(times = times)).schedule.routines.single().sections.size,
        )
    }

    @Test
    fun customClockCourseDoesNotRequireSectionPlaceholders() {
        val detail =
            """{"id":41,"day":2,"ownTime":true,"startNode":0,"step":0,"startTime":"12:5","endTime":"13:0","startWeek":1,"endWeek":8,"type":0}"""
        val n = Importers.wakeup(fixture(detail = detail)).schedule.sessions.single()
        assertEquals(TimeMode.CLOCK, n.mode)
        assertEquals("12:05", n.start)
        assertEquals("13:00", n.end)
    }

    @Test
    fun brokenTimesAndAmbiguousRoutinesStillFail() {
        val variants =
            listOf(
                rows.replace("\"timeTable\":99,\"node\":2", "\"timeTable\":100,\"node\":2"),
                rows.replace("\"node\":2", "\"node\":1"),
                rows.replace("\"startTime\":\"8:55\"", "\"startTime\":\"8:30\""),
                rows.replace("\"startTime\":\"8:0\"", "\"startTime\":\"25:00\""),
            )
        variants.forEach {
            assertTrue(runCatching { Importers.file(fixture(times = it), monday) }.isFailure)
        }
        assertTrue(
            runCatching {
                    Importers.wakeup(
                        fixture()
                            .replace(
                                "\"id\":41,\"tableId\":8,\"day\"",
                                "\"id\":999,\"tableId\":8,\"day\"",
                            )
                    )
                }
                .isFailure
        )
    }
}

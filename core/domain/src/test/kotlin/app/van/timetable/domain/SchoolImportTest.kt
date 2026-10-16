// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.domain

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class SchoolImportTest {
    @Test
    fun visibleFooterPreservesPracticeAndOtherCoursesWithoutSchedulingFakeTimes() {
        val result =
            """{"courses":[{"name":"【调】示例课程庚","day":3,"startSection":9,"endSection":10,"weeks":[4,6,7,8,9,10,11,12,16],"teacher":"甲老师","position":"示例校区 B512"}],
          "extraCoursesText":"实践课程：示例实习乙老师(共2周)/1-2周;示例课程庚课程设计甲老师(共1周)/18周;示例课程丙课程设计丙老师(共1周)/19周;其它课程：示例课程庚甲老师(共4周)/9-12周/无;形势与政策E丁老师(共4周)/1-4周/无;"}"""
        val draft = Importers.school(result, "某学校", LocalDate.parse("2026-09-07"))
        val schedule = draft.schedule
        val extra = schedule.sessions.filter { it.mode == TimeMode.UNTIMED }
        assertEquals(5, schedule.courses.size)
        assertEquals(5, extra.size)
        assertEquals(setOf(1, 2), extra[0].weeks)
        assertEquals("乙老师", extra[0].teacher)
        assertEquals(setOf(18), extra[1].weeks)
        assertEquals(setOf(19), extra[2].weeks)
        assertEquals(setOf(9, 10, 11, 12), extra[3].weeks)
        assertEquals(schedule.sessions.first().courseId, extra[3].courseId)
        assertTrue(extra.all { it.day == 0 && it.place.isBlank() })
        assertTrue(draft.warnings.any { it.contains("已保留5个实践或无固定时间") })
        assertFalse(draft.warnings.any { it.contains("未读取到地点") })
        assertTrue(CourseEngine.resolveDay(schedule, LocalDate.parse("2026-09-07")).isEmpty())
        val copy = Importers.freshCopy(schedule)
        assertEquals(
            copy,
            Codec.restore(Codec.backup(Backup(schedules = listOf(copy)))).schedules.single(),
        )
    }

    @Test
    fun malformedFooterCannotBeDroppedWhileSavingRegularCourses() {
        val base =
            """{"courses":[{"name":"常规课程","day":1,"startSection":1,"endSection":2,"weeks":[1]}],"extraCoursesText":"%s"}"""
        for (footer in listOf("实践课程：无法识别的条目;", "其它课程：课程设计甲老师(共1周)/未知周;", "实践课程：课程设计甲老师(共1周)/0周;")) {
            val failure =
                assertThrows(RuntimeException::class.java) {
                    Importers.school(base.format(footer), "某学校", LocalDate.parse("2026-09-07"))
                }
            assertTrue(
                failure.message.orEmpty().contains("周") ||
                    failure.message.orEmpty().contains("课程条目")
            )
        }
    }

    @Test
    fun selectedTermAndMultipleTeachingSlotsRemainIntactThroughBackup() {
        val result =
            """{"term":"2024-2025学年 · 第2学期","courses":[
              {"name":"课程甲","day":3,"startSection":3,"endSection":4,"weeks":[3,4,6,7,8],"teacher":"甲老师","position":"示例校区 A414"},
              {"name":"课程甲","day":7,"startSection":9,"endSection":10,"weeks":[2,4,6,8],"teacher":"乙老师","position":"公共机房3"}
            ]}"""
        val draft = Importers.school(result, "某学校", LocalDate.parse("2024-02-26"))
        assertEquals(1, draft.schedule.courses.size)
        assertEquals(2, draft.schedule.sessions.size)
        assertEquals(setOf(3, 4, 6, 7, 8), draft.schedule.sessions[0].weeks)
        assertEquals(setOf(2, 4, 6, 8), draft.schedule.sessions[1].weeks)
        assertEquals(listOf("甲老师", "乙老师"), draft.schedule.sessions.map { it.teacher })
        assertEquals(listOf("示例校区 A414", "公共机房3"), draft.schedule.sessions.map { it.place })
        assertEquals(7, draft.schedule.sessions[1].day)
        assertEquals(10, draft.schedule.sessions[1].endSection)
        assertTrue(draft.warnings.any { it.contains("2024-2025学年 · 第2学期") })
        assertFalse(draft.warnings.any { it.contains("未读取到") })
        val copy = Importers.freshCopy(draft.schedule)
        assertNotEquals(draft.schedule.id, copy.id)
        assertEquals(
            copy,
            Codec.restore(Codec.backup(Backup(schedules = listOf(copy)))).schedules.single(),
        )
    }
}

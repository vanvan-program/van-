// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.domain

import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.*
import org.junit.Test

class SuppliedBackupTimelineTest {
    // Derived from the user's 2026-10-12 backup. Course names, teachers, places and identifiers
    // are anonymized; routines, session modes, times, weeks and day adjustments are unchanged.
    private fun suppliedBackup(): Backup =
        Codec.restore(
            javaClass.getResourceAsStream("/supplied-backup-2026-10-12-timing.van.json")!!
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        )

    @Test
    fun suppliedBackupPreservesBothRoutinesThroughRestoreAndSave() {
        val backup = suppliedBackup()
        assertEquals(2, backup.schedules.size)
        val first = backup.schedules.first()
        val second = backup.schedules.last()
        assertEquals(11, first.routines.single().sections.size)
        assertEquals(13, second.routines.single().sections.size)
        assertEquals(Section(1, "08:10", "08:55"), first.routines.single().sections.first())
        assertEquals(Section(11, "21:05", "21:50"), first.routines.single().sections.last())
        assertEquals(Section(2, "08:50", "09:35"), second.routines.single().sections[1])
        assertEquals(Section(13, "20:10", "20:55"), second.routines.single().sections.last())
        assertEquals(backup, Codec.restore(Codec.backup(backup)))

        val monday = CourseEngine.dateAt(first, 7, 1)
        val regular = CourseEngine.resolveDay(first, monday).first { it.session.startSection == 3 }
        // The stale CLOCK defaults stored on a SECTIONS session must never replace its routine.
        assertEquals("08:00", regular.session.start)
        assertEquals(LocalTime.parse("10:30"), regular.start)
        assertEquals(LocalTime.parse("12:10"), regular.end)
    }

    @Test
    fun suppliedCustomCourseAddsOnlyTheTimeItOccupiesInEachBreak() {
        val schedule = suppliedBackup().schedules.first()
        val monday = CourseEngine.dateAt(schedule, 7, 1)
        val courses = (0L..6L).flatMap { CourseEngine.timetableDay(schedule, monday.plusDays(it)) }
        val custom = courses.single { it.session.mode == TimeMode.CLOCK }
        assertEquals(LocalDate.parse("2026-10-16"), custom.date)
        assertEquals(LocalTime.parse("10:20"), custom.start)
        assertEquals(LocalTime.parse("12:00"), custom.end)

        val sections = CourseEngine.routine(schedule, monday).sections
        val timeline = GridTimeline.create(sections, courses)
        val breaks = timeline.bands.filter { it.section == null }
        assertEquals(
            listOf("10:20" to "10:30", "11:15" to "11:25"),
            breaks.map { it.start.toString() to it.end.toString() },
        )
        assertEquals(12.3f, timeline.units, 0.001f)
        assertEquals(2f, timeline.position(custom.start), 0f)
        assertEquals(2.65f, timeline.position(LocalTime.parse("10:30")), 0.001f)
        assertTrue(timeline.position(custom.end) < timeline.position(LocalTime.parse("12:10")))
        assertEquals(sections.map { it.number }, timeline.bands.mapNotNull { it.section })

        val withoutCustom = CourseEngine.dateAt(schedule, 5, 1)
        val ordinaryWeek =
            GridTimeline.create(
                sections,
                (0L..6L).flatMap {
                    CourseEngine.timetableDay(schedule, withoutCustom.plusDays(it))
                },
            )
        assertEquals(11f, ordinaryWeek.units, 0f)
        assertTrue(ordinaryWeek.bands.all { it.section != null })
    }

    @Test
    fun overlappingCustomCoursesMergeWhileEmptyBreaksStayCompressed() {
        val course = Course(name = "课间课程")
        val schedule =
            Schedule(
                week1Monday = "2026-10-12",
                courses = listOf(course),
                sessions =
                    listOf("12:00" to "12:30", "12:20" to "13:00", "13:20" to "13:50")
                        .map { (start, end) ->
                            CourseSession(
                                courseId = course.id,
                                weeks = setOf(1),
                                mode = TimeMode.CLOCK,
                                start = start,
                                end = end,
                            )
                        },
            )
        val timeline =
            GridTimeline.create(
                defaultSections(),
                CourseEngine.resolveDay(schedule, LocalDate.parse(schedule.week1Monday)),
            )
        assertEquals(
            listOf("12:00" to "13:00", "13:20" to "13:50"),
            timeline.bands
                .filter { it.section == null }
                .map { it.start.toString() to it.end.toString() },
        )
        assertEquals(
            timeline.position(LocalTime.parse("11:40")),
            timeline.position(LocalTime.parse("12:00")),
            0f,
        )
        assertEquals(
            timeline.position(LocalTime.parse("13:00")),
            timeline.position(LocalTime.parse("13:20")),
            0f,
        )
        assertEquals(
            timeline.position(LocalTime.parse("13:50")),
            timeline.position(LocalTime.parse("14:30")),
            0f,
        )
        assertTrue(
            timeline.position(LocalTime.parse("12:30")) >
                timeline.position(LocalTime.parse("12:00"))
        )
    }
}

package app.van.timetable.domain

import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.*
import org.junit.Test

class Version020Test {
    private val course = Course(name = "自定义课程", customColor = 0xFF123456)
    private val base = Schedule(week1Monday = "2026-09-07", courses = listOf(course))

    private fun clock(start: String, end: String) =
        CourseSession(
            courseId = course.id,
            day = 1,
            weeks = setOf(1),
            mode = TimeMode.CLOCK,
            start = start,
            end = end,
        )

    @Test
    fun oldBackupsAndCustomColorRoundTrip() {
        val old =
            Codec.decode(Codec.encode(base.copy(courses = listOf(course.copy(customColor = null)))))
        assertNull(old.courses.single().customColor)
        assertEquals(CoursePalette.light[0], CoursePalette.argb(old.courses.single()))
        val s = base.copy(sessions = listOf(clock("12:00", "13:00")))
        val restored = Codec.restore(Codec.backup(Backup(schedules = listOf(s)))).schedules.single()
        assertEquals(0xFF123456L, restored.courses.single().customColor)
        assertEquals(0xFF123456L, CoursePalette.argb(restored.courses.single(), true))
        Validation.requireValid(restored)
        assertTrue(
            Validation.errors(base.copy(courses = listOf(course.copy(customColor = 0x123456))))
                .isNotEmpty()
        )
        assertEquals(0xFFFFFFFFL, CoursePalette.textArgb(0xFF123456))
        assertEquals(0xFF142036L, CoursePalette.textArgb(0xFFFFFFFF))
    }

    @Test
    fun weekDatesUseSemesterAndValidateBothFields() {
        assertEquals(LocalDate.parse("2026-09-07"), CourseEngine.dateAt(base, 1, 1))
        assertEquals(LocalDate.parse("2026-09-20"), CourseEngine.dateAt(base, 2, 7))
        assertEquals(LocalDate.parse("2027-01-24"), CourseEngine.dateAt(base, 20, 7))
        listOf(0 to 1, 21 to 1, 1 to 0, 1 to 8).forEach { (w, d) ->
            assertTrue(runCatching { CourseEngine.dateAt(base, w, d) }.isFailure)
        }
    }

    @Test
    fun weekAdjustmentPreservesOriginalAndRevokeRestoresTarget() {
        val original = CourseEngine.dateAt(base, 1, 1)
        val target = CourseEngine.dateAt(base, 2, 3)
        val s =
            base.copy(
                sessions = listOf(clock("12:00", "13:00")),
                dayOverrides = listOf(DayOverride(target.toString(), original.toString())),
            )
        assertEquals(course.id, CourseEngine.resolveDay(s, original).single().course.id)
        assertEquals(target, CourseEngine.resolveDay(s, target).single().date)
        assertTrue(CourseEngine.resolveDay(s.copy(dayOverrides = emptyList()), target).isEmpty())
    }

    @Test
    fun clockAndMovedCoursesOccupyFixedGridIncludingBreaksAndOutOfHours() {
        val timeline = GridTimeline.create(defaultSections())
        val samples = listOf("06:30", "07:30", "08:00", "08:15", "08:40",
            "11:40", "12:00", "13:00", "14:30", "20:40", "22:00", "23:00")
            .map(LocalTime::parse).map(timeline::position)
        samples.zipWithNext().forEach { (a, b) -> assertTrue(a < b) }
        assertEquals(10, timeline.bands.count { it.section != null })
        assertTrue(timeline.bands.filter { it.section == null }.all { it.units == 20f / 56f })
        assertNotNull(timeline.gapContaining(LocalTime.NOON, LocalTime.of(13, 0)))
        assertEquals(timeline.units, timeline.position(LocalTime.MAX), 0.001f)
    }

    @Test
    fun disjointWeeksStayVisibleWithoutInventingMissingWeeks() {
        assertEquals("第1–3、5、7–8周", formatWeeks(setOf(8, 3, 2, 1, 5, 7)))
        assertEquals("第1、3、5周", formatWeeks(setOf(1, 3, 5)))
    }
}

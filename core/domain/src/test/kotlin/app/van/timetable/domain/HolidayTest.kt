package app.van.timetable.domain

import java.time.*
import org.junit.Assert.*
import org.junit.Test

class HolidayTest {
    private val course = Course(id = "course", name = "正常课程")
    private val table =
        Schedule(
            week1Monday = "2026-09-07",
            courses = listOf(course),
            sessions = (1..7).map { CourseSession(id = "day$it", courseId = course.id, day = it) },
        )
    private val holiday =
        Holiday(id = "holiday", name = "测试假期", from = "2026-09-08", through = "2026-09-10")

    @Test
    fun inclusiveRangePreservesCoursesAndRestoresAfterRemoval() {
        val changed = table.copy(holidays = listOf(holiday))
        for (day in 8..10) assertTrue(
            CourseEngine.resolveDay(changed, LocalDate.of(2026, 9, day)).isEmpty()
        )
        for (day in listOf(7, 11)) assertEquals(
            1,
            CourseEngine.resolveDay(changed, LocalDate.of(2026, 9, day)).size,
        )
        assertEquals(table.sessions, changed.sessions)
        for (day in 8..10) assertEquals(
            CourseEngine.resolveDay(table, LocalDate.of(2026, 9, day)),
            CourseEngine.timetableDay(changed, LocalDate.of(2026, 9, day)),
        )
        val restored = changed.copy(holidays = emptyList())
        assertEquals(
            CourseEngine.resolveDay(table, LocalDate.of(2026, 9, 8)),
            CourseEngine.resolveDay(restored, LocalDate.of(2026, 9, 8)),
        )
    }

    @Test
    fun holidayAlsoSuppressesSingleCoursesDayReplacementAndMovedInSessions() {
        val dated =
            CourseSession(
                id = "exam",
                courseId = course.id,
                date = "2026-09-09",
                mode = TimeMode.CLOCK,
            )
        val changed =
            table.copy(
                holidays = listOf(holiday),
                sessions = table.sessions + dated,
                dayOverrides = listOf(DayOverride("2026-09-08", "2026-09-07")),
                sessionOverrides =
                    listOf(SessionOverride("day5", "2026-09-11", targetDate = "2026-09-09")),
            )
        assertTrue(CourseEngine.resolveDay(changed, LocalDate.of(2026, 9, 8)).isEmpty())
        assertTrue(CourseEngine.resolveDay(changed, LocalDate.of(2026, 9, 9)).isEmpty())
        assertEquals(3, CourseEngine.timetableDay(changed, LocalDate.of(2026, 9, 9)).size)
        assertEquals(
            3,
            CourseEngine.resolveDay(changed.copy(holidays = emptyList()), LocalDate.of(2026, 9, 9))
                .size,
        )
    }

    @Test
    fun remainingAndNextSkipHolidayAndResumeOnFirstSchoolDay() {
        val changed = table.copy(holidays = listOf(holiday))
        val now = Instant.parse("2026-09-08T00:00:00Z")
        assertTrue(CourseEngine.remaining(changed, now).isEmpty())
        assertEquals(LocalDate.of(2026, 9, 11), CourseEngine.next(changed, now)?.date)
    }

    @Test
    fun overlappingAndSingleDayHolidaysUseUnionOfDates() {
        val changed =
            table.copy(
                holidays =
                    listOf(
                        holiday,
                        holiday.copy(id = "second", from = "2026-09-10", through = "2026-09-12"),
                    )
            )
        Validation.requireValid(changed)
        assertTrue(CourseEngine.resolveDay(changed, LocalDate.of(2026, 9, 12)).isEmpty())
        assertEquals(1, CourseEngine.resolveDay(changed, LocalDate.of(2026, 9, 13)).size)
        val one =
            table.copy(holidays = listOf(holiday.copy(from = "2026-09-08", through = "2026-09-08")))
        assertTrue(CourseEngine.resolveDay(one, LocalDate.of(2026, 9, 8)).isEmpty())
        assertEquals(1, CourseEngine.resolveDay(one, LocalDate.of(2026, 9, 9)).size)
    }

    @Test
    fun holidayValidationRejectsBadDatesAndReverseRange() {
        for (bad in
            listOf(
                holiday.copy(from = "bad"),
                holiday.copy(through = "2026-02-30"),
                holiday.copy(from = "2026-09-12"),
                holiday.copy(name = ""),
            )) assertTrue(Validation.errors(table.copy(holidays = listOf(bad))).isNotEmpty())
    }

    @Test
    fun backupRoundTripIncludesHolidayAndOlderSchedulesDefaultToNone() {
        val changed = table.copy(holidays = listOf(holiday))
        assertEquals(
            changed,
            Codec.restore(Codec.backup(Backup(schedules = listOf(changed)))).schedules.single(),
        )
        val old = Codec.json.encodeToJsonElement(Schedule.serializer(), table).toString()
        assertTrue(Codec.decode(old).holidays.isEmpty())
        val legacy = """{"week1Monday":"2026-09-07"}"""
        assertTrue(Codec.decode(legacy).holidays.isEmpty())
    }
}

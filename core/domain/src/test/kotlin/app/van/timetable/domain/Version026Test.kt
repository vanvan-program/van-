package app.van.timetable.domain

import java.time.*
import org.junit.Assert.*
import org.junit.Test

class Version026Test {
    private val monday = LocalDate.parse("2026-10-19")
    private val course = Course(id = "course", name = "测试课程")
    private val morning = CourseSession(id = "morning", courseId = course.id, weeks = setOf(1, 2))
    private val afternoon = morning.copy(id = "afternoon", startSection = 5, endSection = 8)
    private val schedule = Schedule(week1Monday = monday.toString(), courses = listOf(course), sessions = listOf(morning, afternoon))

    @Test fun cancelOnlySelectedBlockAndPreserveOtherWeeksAndBackup() {
        val selected = CourseEngine.resolveDay(schedule, monday).last()
        val cancelled = CourseEngine.cancelOccurrence(schedule, selected)
        Validation.requireValid(cancelled)
        assertEquals(listOf("morning"), CourseEngine.resolveDay(cancelled, monday).map { it.session.id })
        assertEquals(2, CourseEngine.resolveDay(cancelled, monday.plusWeeks(1)).size)
        assertEquals(schedule.sessions, cancelled.sessions)
        val restored = Codec.decode(Codec.encode(cancelled))
        assertEquals(cancelled, restored)
        assertEquals(1, CourseEngine.resolveDay(restored, monday).size)
        assertEquals(1, CourseEngine.cancelOccurrence(cancelled, selected).sessionOverrides.size)
    }

    @Test fun cancellingMovedLessonDoesNotResurrectSourceOrCancelOtherLesson() {
        val target = monday.plusDays(1)
        val moved = schedule.copy(sessionOverrides = listOf(SessionOverride("afternoon", monday.toString(), target.toString(), start = "12:00", end = "13:00", place = "新教室")))
        val selected = CourseEngine.resolveDay(moved, target).single()
        assertEquals(monday.toString(), CourseEngine.originalDate(selected))
        val cancelled = CourseEngine.cancelOccurrence(moved, selected)
        assertTrue(CourseEngine.resolveDay(cancelled, target).isEmpty())
        assertEquals(listOf("morning"), CourseEngine.resolveDay(cancelled, monday).map { it.session.id })
        assertEquals(2, CourseEngine.resolveDay(cancelled, monday.plusWeeks(1)).size)
        assertEquals(1, cancelled.sessionOverrides.size)
        Validation.requireValid(cancelled)
    }

    @Test fun cancelDateReplacedDayAndOnceOnlyClockLesson() {
        val target = monday.plusDays(2)
        val replaced = schedule.copy(dayOverrides = listOf(DayOverride(target.toString(), monday.toString())))
        val cancelled = CourseEngine.cancelOccurrence(replaced, CourseEngine.resolveDay(replaced, target).last())
        assertEquals(1, CourseEngine.resolveDay(cancelled, target).size)
        assertEquals(2, CourseEngine.resolveDay(cancelled, monday).size)
        val once = schedule.copy(sessions = listOf(morning.copy(mode = TimeMode.CLOCK, date = target.toString(), weeks = emptySet(), start = "12:00", end = "13:00")))
        val result = CourseEngine.cancelOccurrence(once, CourseEngine.resolveDay(once, target).single())
        assertTrue(CourseEngine.resolveDay(result, target).isEmpty())
        assertEquals(once.sessions, result.sessions)
    }

    @Test fun fixedGapsScaleWithoutChangingSectionRows() {
        val a = GridTimeline.create(defaultSections(), 20f / 56f)
        val b = GridTimeline.create(defaultSections(), 20f / 36f)
        assertEquals(10, a.bands.count { it.section != null })
        assertTrue(a.bands.filter { it.section != null }.all { it.units == 1f })
        assertEquals(a.bands.count { it.section == null }, b.bands.count { it.section == null })
        assertEquals(20f, a.bands.first().units * 56f, 0.001f)
        assertEquals(20f, b.bands.first().units * 36f, 0.001f)
        assertNotNull(a.gapContaining(LocalTime.of(6, 30), LocalTime.of(7, 30)))
        assertNotNull(a.gapContaining(LocalTime.of(12, 0), LocalTime.of(13, 0)))
        assertNotNull(a.gapContaining(LocalTime.of(22, 0), LocalTime.of(23, 0)))
        assertNull(a.gapContaining(LocalTime.of(8, 15), LocalTime.of(10, 30)))
    }
}

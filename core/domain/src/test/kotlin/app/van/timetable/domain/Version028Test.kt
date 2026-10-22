package app.van.timetable.domain

import java.time.LocalTime
import org.junit.Assert.*
import org.junit.Test

class Version028Test {
    private val course = Course(id = "course", name = "合成课程")
    private val session = CourseSession(id = "lesson", courseId = course.id)
    private val schedule = Schedule(week1Monday = "2026-10-19", courses = listOf(course), sessions = listOf(session))
    private fun clock(start: String, end: String) = session.copy(mode = TimeMode.CLOCK, start = start, end = end)

    @Test fun regularAndContainedClockCoursesCollapseIncludingMultiSectionLessons() {
        assertTrue(GridTimeline.canCollapseGaps(schedule))
        assertTrue(GridTimeline.canCollapseGaps(schedule.copy(sessions = listOf(clock("08:00", "09:40")))))
        assertTrue(GridTimeline.canCollapseGaps(schedule.copy(sessions = listOf(clock("08:15", "10:30")))))
    }

    @Test fun coursesInGapsOrWithBoundariesInGapsKeepTheAxis() {
        for ((start, end) in listOf("06:30" to "07:30", "12:00" to "13:00", "22:00" to "23:00",
            "08:45" to "08:55", "08:40" to "08:50", "08:50" to "09:00")) {
            assertFalse("$start-$end", GridTimeline.canCollapseGaps(schedule.copy(sessions = listOf(clock(start, end)))))
        }
    }

    @Test fun laterWeeksAndHiddenWeekendsStillPreventCollapsing() {
        val later = clock("12:00", "13:00").copy(id = "later", day = 7, weeks = setOf(20))
        assertFalse(GridTimeline.canCollapseGaps(schedule.copy(sessions = listOf(session, later))))
    }

    @Test fun onceOnlyAndMovedLessonsOutsideSemesterAreIncluded() {
        val once = clock("12:00", "13:00").copy(date = "2027-10-19")
        assertFalse(GridTimeline.canCollapseGaps(schedule.copy(sessions = listOf(once))))
        val moved = SessionOverride(session.id, "2026-10-19", "2027-10-19", start = "12:00", end = "13:00")
        assertFalse(GridTimeline.canCollapseGaps(schedule.copy(sessionOverrides = listOf(moved))))
    }

    @Test fun holidayPreviewsAndChangedTimesKeepTheirSpace() {
        val changed = SessionOverride(session.id, "2026-10-19", start = "12:00", end = "13:00")
        assertFalse(GridTimeline.canCollapseGaps(schedule.copy(sessionOverrides = listOf(changed),
            holidays = listOf(Holiday(from = "2026-10-19", through = "2026-10-19")))))
    }

    @Test fun dateBasedRoutinesUseTheSameSharedAxisAsTheGrid() {
        val late = TimeRoutine(id = "late", sections = defaultSections().map {
            it.copy(start = LocalTime.parse(it.start).plusHours(3).toString(), end = LocalTime.parse(it.end).plusHours(3).toString())
        })
        assertFalse(GridTimeline.canCollapseGaps(schedule.copy(routines = schedule.routines + late,
            routineRules = listOf(RoutineRule(late.id, "2026-10-20", "2026-10-25")),
            sessions = listOf(session.copy(day = 2)))))
    }

    @Test fun collapsedTimelineJoinsNeighborsButRetainsEmptySectionRows() {
        val timeline = GridTimeline.create(defaultSections(), 0f)
        assertEquals(10f, timeline.units, 0f)
        assertEquals(timeline.position(LocalTime.parse("09:40")), timeline.position(LocalTime.parse("10:00")), 0f)
        assertEquals(1f, timeline.position(LocalTime.parse("14:30")) - timeline.position(LocalTime.parse("10:55")), 0f)
        assertTrue(timeline.bands.filter { it.section != null }.all { it.units == 1f })
    }

    @Test fun inactiveCoursesInDateBasedRoutinesAlsoKeepTheirSpace() {
        val late = TimeRoutine(id = "late", sections = defaultSections().map {
            it.copy(start = LocalTime.parse(it.start).plusHours(3).toString(), end = LocalTime.parse(it.end).plusHours(3).toString())
        })
        val s = schedule.copy(routines = schedule.routines + late,
            routineRules = listOf(RoutineRule(late.id, "2026-10-20", "2026-10-25")),
            sessions = listOf(session.copy(day = 2, weeks = setOf(20))))
        assertFalse(GridTimeline.canCollapseGaps(s))
    }

    @Test fun legacyPreferencesDefaultToAutoAndExplicitChoicesSurviveFullBackups() {
        assertNull(Codec.json.decodeFromString(Preferences.serializer(), "{\"theme\":\"dark\"}").showCourseGaps)
        for (choice in listOf(null, true, false)) {
            val backup = Backup(schedules = listOf(schedule), preferences = Preferences(showCourseGaps = choice))
            assertEquals(backup, Codec.restore(Codec.backup(backup)))
        }
    }
}

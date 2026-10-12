package app.van.timetable.domain

import java.time.*
import org.junit.Assert.*
import org.junit.Test

class EngineTest {
    @Test
    fun olderPreferencesDefaultToFittingTheWholeWeek() {
        val old = Codec.json.decodeFromString<Preferences>("""{"theme":"dark","rowHeight":68}""")
        assertTrue(old.fitWeek)
        assertEquals("dark", old.theme)
        val backup = Backup(schedules = listOf(table()), preferences = old.copy(fitWeek = false))
        assertFalse(Codec.restore(Codec.backup(backup)).preferences!!.fitWeek)
    }

    @Test
    fun backupRejectsBrokenDisplayPreferences() {
        val text =
            Codec.backup(
                Backup(schedules = listOf(table()), preferences = Preferences(fontScale = 100f))
            )
        assertTrue(runCatching { Codec.restore(text) }.isFailure)
    }

    private val c = Course(id = "c", name = "操作系统")
    private val session = CourseSession(id = "s", courseId = "c", day = 1, weeks = setOf(1, 3, 5))

    private fun table() =
        Schedule(week1Monday = "2026-09-07", courses = listOf(c), sessions = listOf(session))

    @Test
    fun disjointWeeksAndSemesterBoundaries() {
        val s = table()
        assertEquals(1, CourseEngine.resolveDay(s, LocalDate.parse("2026-09-07")).size)
        assertTrue(CourseEngine.resolveDay(s, LocalDate.parse("2026-09-14")).isEmpty())
        assertEquals(0, CourseEngine.week(s, LocalDate.parse("2026-09-06")))
        assertEquals(2, CourseEngine.week(s, LocalDate.parse("2026-09-14")))
    }

    @Test
    fun exactlyEndedRemoved() {
        val s = table()
        assertEquals(1, CourseEngine.remaining(s, Instant.parse("2026-09-07T01:39:59Z")).size)
        assertTrue(CourseEngine.remaining(s, Instant.parse("2026-09-07T01:40:00Z")).isEmpty())
    }

    @Test
    fun nextRespectsPriorityAmongOngoingConflicts() {
        val preferred = Course(id = "p", name = "优先课程")
        val s =
            table()
                .copy(
                    courses = listOf(c, preferred),
                    sessions =
                        listOf(
                            session,
                            session.copy(id = "preferred", courseId = preferred.id, priority = 2),
                        ),
                )
        assertEquals(
            preferred.id,
            CourseEngine.next(s, Instant.parse("2026-09-07T00:30:00Z"))?.course?.id,
        )
        assertEquals(
            preferred.id,
            CourseEngine.next(s, Instant.parse("2026-09-06T23:00:00Z"))?.course?.id,
        )
    }

    @Test
    fun moveOccursOnce() {
        val s =
            table()
                .copy(sessionOverrides = listOf(SessionOverride("s", "2026-09-07", "2026-09-08")))
        assertTrue(CourseEngine.resolveDay(s, LocalDate.parse("2026-09-07")).isEmpty())
        assertEquals(1, CourseEngine.resolveDay(s, LocalDate.parse("2026-09-08")).size)
    }

    @Test
    fun daySwapDoesNotRecursivelyCopy() {
        val s =
            table()
                .copy(
                    dayOverrides =
                        listOf(
                            DayOverride("2026-09-08", "2026-09-07"),
                            DayOverride("2026-09-09", "2026-09-08"),
                        )
                )
        assertEquals(1, CourseEngine.resolveDay(s, LocalDate.parse("2026-09-08")).size)
        assertTrue(CourseEngine.resolveDay(s, LocalDate.parse("2026-09-09")).isEmpty())
    }

    @Test
    fun cancellationAndAdjustment() {
        val s =
            table()
                .copy(
                    sessionOverrides = listOf(SessionOverride("s", "2026-09-07", cancelled = true))
                )
        assertTrue(CourseEngine.resolveDay(s, LocalDate.parse("2026-09-07")).isEmpty())
    }

    @Test
    fun clockTimeUnaffectedByRoutine() {
        val s =
            table()
                .copy(
                    sessions =
                        listOf(session.copy(mode = TimeMode.CLOCK, start = "12:00", end = "13:00"))
                )
        assertEquals(
            LocalTime.NOON,
            CourseEngine.resolveDay(s, LocalDate.parse("2026-09-07")).single().start,
        )
    }

    @Test
    fun routineChangesByDate() {
        val r =
            TimeRoutine(
                id = "summer",
                sections =
                    defaultSections().map {
                        it.copy(
                            start = LocalTime.parse(it.start).plusMinutes(10).toString(),
                            end = LocalTime.parse(it.end).plusMinutes(10).toString(),
                        )
                    },
            )
        val s =
            table()
                .copy(
                    routines = table().routines + r,
                    routineRules = listOf(RoutineRule("summer", "2026-09-07", "2026-10-01")),
                )
        assertEquals(
            LocalTime.of(8, 10),
            CourseEngine.resolveDay(s, LocalDate.parse("2026-09-07")).single().start,
        )
    }

    @Test
    fun touchingIntervalsDoNotConflict() {
        val s =
            table()
                .copy(
                    sessions =
                        listOf(
                            session.copy(mode = TimeMode.CLOCK, start = "08:00", end = "09:00"),
                            session.copy(
                                id = "s2",
                                mode = TimeMode.CLOCK,
                                start = "09:00",
                                end = "10:00",
                            ),
                        )
                )
        assertTrue(
            CourseEngine.conflicts(CourseEngine.resolveDay(s, LocalDate.parse("2026-09-07")))
                .isEmpty()
        )
    }

    @Test
    fun transitiveConflictsGrouped() {
        val s =
            table()
                .copy(
                    sessions =
                        listOf(
                            session.copy(mode = TimeMode.CLOCK, start = "08:00", end = "09:00"),
                            session.copy(
                                id = "b",
                                mode = TimeMode.CLOCK,
                                start = "08:30",
                                end = "10:00",
                            ),
                            session.copy(
                                id = "d",
                                mode = TimeMode.CLOCK,
                                start = "09:30",
                                end = "11:00",
                            ),
                        )
                )
        assertEquals(
            3,
            CourseEngine.conflicts(CourseEngine.resolveDay(s, LocalDate.parse("2026-09-07")))
                .single()
                .size,
        )
    }

    @Test
    fun nextAcrossEmptyWeeks() {
        assertEquals(
            LocalDate.parse("2026-09-21"),
            CourseEngine.next(table(), Instant.parse("2026-09-07T03:00:00Z"))!!.date,
        )
    }

    @Test
    fun weekExpressions() {
        assertEquals(setOf(1, 2, 3, 4, 5, 7, 9, 11), Importers.weeks("1-5、7-11单"))
        assertEquals(setOf(2, 4, 6), Importers.weeks("1-6(双)"))
    }

    @Test
    fun csvQuotedFields() {
        val d =
            Importers.csv(
                "课程名称,星期,开始节数,结束节数,老师,地点,周数\r\n\"数据库,原理\",1,1,2,无,\"楼A\n101\",1-20",
                LocalDate.parse("2026-09-07"),
            )
        assertEquals("数据库,原理", d.schedule.courses.single().name)
        assertEquals(20, d.schedule.maxWeeks)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidCsvFailsAtomically() {
        Importers.csv(
            "课程名称,星期,开始节数,结束节数,老师,地点,周数\n数学,1,1,2,无,无,1-20\n坏行",
            LocalDate.parse("2026-09-07"),
        )
    }

    @Test
    fun backupRoundTrip() {
        val s = table()
        assertEquals(
            s,
            Codec.restore(Codec.backup(Backup(schedules = listOf(s)))).schedules.single(),
        )
    }

    @Test
    fun copyRemapsEveryReference() {
        val s =
            table()
                .copy(
                    sessionOverrides = listOf(SessionOverride("s", "2026-09-07", cancelled = true))
                )
        val copy = Importers.freshCopy(s)
        Validation.requireValid(copy)
        assertNotEquals(s.id, copy.id)
        assertNotEquals("s", copy.sessions.single().id)
        assertEquals(copy.sessions.single().id, copy.sessionOverrides.single().sessionId)
    }

    @Test
    fun wakeupStepAndSharedCourse() {
        val text =
            """{"id":1,"name":"作息"}
[{"timeTable":1,"node":1,"startTime":"08:00","endTime":"08:45"},{"timeTable":1,"node":2,"startTime":"08:55","endTime":"09:40"}]
{"id":2,"timeTable":1,"startDate":"2026-9-7","maxWeek":20,"nodes":2,"tableName":"测试"}
[{"id":5,"tableId":2,"courseName":"数学"}]
[{"id":5,"day":1,"startNode":1,"step":2,"startWeek":1,"endWeek":8,"type":1},{"id":5,"day":1,"startNode":1,"step":2,"startWeek":11,"endWeek":16,"type":0}]"""
        val s = Importers.wakeup(text).schedule
        assertEquals(2, s.sessions.single().endSection)
        assertEquals(setOf(1, 3, 5, 7, 11, 12, 13, 14, 15, 16), s.sessions.single().weeks)
    }
}

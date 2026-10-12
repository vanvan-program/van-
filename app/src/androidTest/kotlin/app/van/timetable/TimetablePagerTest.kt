package app.van.timetable

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.van.timetable.domain.*
import java.io.File
import java.time.*
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimetablePagerTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val store
        get() = (ui.activity.application as VanApplication).store

    private var schedule: Schedule? = null
    private var preferences: Preferences? = null
    private val today = LocalDate.now(ZoneId.of("Asia/Shanghai"))

    private fun seed(
        firstMonday: LocalDate = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    ): Schedule {
        val courses = (1..3).map { Course(name = "分页第${it}周课程") }
        val s =
            Schedule(
                name = "课表分页动画验证",
                week1Monday = firstMonday.toString(),
                maxWeeks = 3,
                courses = courses,
                sessions =
                    courses.mapIndexed { index, course ->
                        CourseSession(courseId = course.id, day = 3, weeks = setOf(index + 1))
                    },
            )
        schedule = s
        runBlocking {
            preferences = store.prefs()
            store.save(s)
            store.updatePreferences {
                it.copy(
                    activeScheduleId = s.id,
                    theme = "light",
                    fontScale = 1f,
                    showWeekend = true,
                    showInactive = false,
                    fitWeek = true,
                    reminders = false,
                )
            }
        }
        ui.waitUntil(10000) { ui.onAllNodesWithText(s.name).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("第1周 ▾").assertExists()
        return s
    }

    @After
    fun cleanup() {
        ui.mainClock.autoAdvance = true
        runBlocking {
            schedule?.let { store.delete(it.id) }
            preferences?.let { p -> store.updatePreferences { p } }
        }
    }

    private fun chooseWeek(week: Int) {
        ui.onNodeWithTag("week-selector").performClick()
        ui.onNodeWithText("第${week}周").performClick()
        ui.onNodeWithText("第${week}周 ▾").assertExists()
    }

    private fun screenshot(name: String) {
        ui.waitForIdle()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            .takeScreenshot(File(ui.activity.getExternalFilesDir(null), name))
    }

    @Test
    fun draggingMovesContentBeforeReleaseAndShortSlowDragReturnsToSameWeek() {
        val s = seed()
        val occurrence = CourseEngine.timetableDay(s, CourseEngine.dateAt(s, 1, 3)).single()
        val card = ui.onNodeWithTag("grid-course-${occurrence.id}")
        val before = card.fetchSemanticsNode().boundsInRoot.left
        val viewport = ui.onNodeWithTag("week-viewport")
        val width = viewport.fetchSemanticsNode().boundsInRoot.width
        screenshot("pager-before.png")
        ui.mainClock.autoAdvance = false
        viewport.performTouchInput {
            down(Offset(width * 0.8f, height * 0.65f))
            advanceEventTime(300)
            moveBy(Offset(-width * 0.28f, 0f))
        }
        ui.mainClock.advanceTimeBy(64)
        ui.waitForIdle()
        val during = card.fetchSemanticsNode().boundsInRoot.left
        assertTrue(
            "Content must follow the finger before release: $before -> $during",
            during < before - width * 0.15f,
        )
        ui.onNodeWithText("第1周 ▾").assertExists()
        screenshot("pager-during-drag.png")
        viewport.performTouchInput {
            advanceEventTime(300)
            up()
        }
        ui.mainClock.autoAdvance = true
        ui.waitForIdle()
        ui.onNodeWithText("第1周 ▾").assertExists()
        assertEquals(before, card.fetchSemanticsNode().boundsInRoot.left, 2f)
    }

    @Test
    fun firstAndLastWeekAreBoundedAndJumpTodayAnimatesToCurrentWeek() {
        seed()
        ui.onNodeWithTag("week-viewport").performTouchInput { swipeRight() }
        ui.onNodeWithText("第1周 ▾").assertExists()
        chooseWeek(3)
        ui.onNodeWithTag("week-viewport").performTouchInput { swipeLeft() }
        ui.onNodeWithText("第3周 ▾").assertExists()
        ui.onNodeWithTag("jump-today").performClick()
        ui.onNodeWithText("第1周 ▾").assertExists()
        ui.onNodeWithContentDescription("课表菜单").performClick()
        ui.onNodeWithText("下一周").performClick()
        ui.onNodeWithText("第2周 ▾").assertExists()
        screenshot("pager-week-two.png")
    }

    @Test
    fun courseClickAfterPagingUsesThatPagesDateAndRecreationKeepsWeek() {
        val s = seed()
        ui.onNodeWithTag("week-viewport").performTouchInput { swipeLeft() }
        ui.onNodeWithText("第2周 ▾").assertExists()
        ui.activityRule.scenario.recreate()
        ui.onNodeWithText("第2周 ▾").assertExists()
        val occurrence = CourseEngine.timetableDay(s, CourseEngine.dateAt(s, 2, 3)).single()
        ui.onNodeWithTag("grid-course-${occurrence.id}").performClick()
        ui.onNodeWithText("分页第2周课程").assertIsDisplayed()
        ui.onNodeWithText("调整 ${occurrence.date} ${occurrence.start} 的课程").assertExists()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        ui.onNodeWithText("第2周 ▾").assertExists()
        ui.onNodeWithTag("week-viewport").performTouchInput { swipeRight() }
        ui.onNodeWithText("第1周 ▾").assertExists()
    }

    @Test
    fun openingBeforeSemesterStillShowsActualTodayInDailyMode() {
        seed(today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(2))
        ui.onNodeWithText("学期尚未开始").assertExists()
        ui.onNodeWithContentDescription("课表菜单").performClick()
        ui.onNodeWithText("切换为日列表").performClick()
        val title = "$today ${app.van.timetable.ui.weekdays[today.dayOfWeek.value - 1]}"
        ui.onNodeWithText(title).assertExists()
        ui.onNodeWithTag("day-list").performTouchInput { swipeLeft() }
        ui.onNodeWithTag("jump-today").performClick()
        ui.onNodeWithText(title).assertExists()
        ui.onNodeWithContentDescription("课表菜单").performClick()
        ui.onNodeWithText("切换为周课表").performClick()
        ui.activityRule.scenario.recreate()
        ui.onNodeWithText("第1周 ▾").assertExists()
        ui.onNodeWithContentDescription("课表菜单").performClick()
        ui.onNodeWithText("切换为日列表").performClick()
        ui.onNodeWithText(title).assertExists()
    }

    @Test
    fun dailyPagerCrossesTermBoundaryAndButtonsAndRecreationKeepDate() {
        val s = seed()
        chooseWeek(3)
        ui.onNodeWithTag("week-day-3-6").performClick()
        val sunday = CourseEngine.dateAt(s, 3, 7)
        fun assertDate(date: LocalDate) {
            ui.onNodeWithText("$date ${app.van.timetable.ui.weekdays[date.dayOfWeek.value - 1]}")
                .assertExists()
        }
        assertDate(sunday)
        ui.onNodeWithTag("day-list").performTouchInput { swipeLeft() }
        assertDate(sunday.plusDays(1))
        ui.activityRule.scenario.recreate()
        assertDate(sunday.plusDays(1))
        ui.onNodeWithText("后一天").performClick()
        assertDate(sunday.plusDays(2))
        ui.onNodeWithText("前一天").performClick()
        assertDate(sunday.plusDays(1))
        screenshot("pager-day-outside-term.png")
        ui.onNodeWithTag("jump-today").performClick()
        assertDate(today)
        ui.onNodeWithContentDescription("课表菜单").performClick()
        ui.onNodeWithText("切换为周课表").performClick()
        ui.onNodeWithText("第1周 ▾").assertExists()
    }
}

package app.van.timetable

import android.content.Intent
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
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
class Version028UiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val store get() = (ui.activity.application as VanApplication).store
    private var previous: Backup? = null
    private val today = LocalDate.now(ZoneId.of("Asia/Shanghai"))
    private val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    private val course = Course(id = "course028", name = "合成测试课程", color = 2)
    private val first = CourseSession(id = "first028", courseId = course.id, day = today.dayOfWeek.value)
    private val second = first.copy(id = "second028", startSection = 3, endSection = 4)

    @Before fun preserve() { previous = runBlocking { Backup(schedules = store.all(), preferences = store.prefs()) } }
    @After fun restore() { previous?.let { runBlocking { store.restore(it) } } }

    private fun seed(extra: List<CourseSession> = emptyList(), prefs: Preferences = Preferences()): Schedule {
        val s = Schedule(name = "0.2.8 自动间隙验证", week1Monday = monday.toString(), courses = listOf(course), sessions = listOf(first, second) + extra)
        runBlocking {
            store.save(s)
            store.updatePreferences { prefs.copy(activeScheduleId = s.id) }
        }
        instrumentation.runOnMainSync {
            ui.activity.startActivity(Intent(ui.activity, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("openTimetable", true).putExtra("scheduleId", s.id).putExtra("date", today.toString()))
        }
        ui.waitUntil(10000) { ui.onAllNodesWithText(s.name).fetchSemanticsNodes().isNotEmpty() }
        ui.waitForIdle()
        return s
    }

    private fun section(number: Int, week: Int = 1) =
        ui.onNode(hasTestTag("section-$number") and hasAnyAncestor(hasTestTag("week-grid-$week"))).fetchSemanticsNode().boundsInRoot
    private fun card(id: String, date: LocalDate = today) = ui.onNodeWithTag("grid-course-$id@$date").fetchSemanticsNode().boundsInRoot
    private fun screenshot(name: String) {
        ui.waitForIdle()
        UiDevice.getInstance(instrumentation).takeScreenshot(File(ui.activity.getExternalFilesDir(null), name))
    }

    private fun assertRounded(id: String, date: LocalDate = today) {
        val pixels = ui.onNodeWithTag("grid-course-$id@$date").captureToImage().toPixelMap()
        // The corner must show the page behind the rounded card, while the
        // middle of its top edge must show the card fill. Solid blocks fail.
        assertNotEquals(pixels[0, 0].toArgb(), pixels[pixels.width / 2, 0].toArgb())
    }

    @Test fun defaultsMatch025InsetsAndRoundedShapeInBothThemes() {
        val adjacentDay = if (today.dayOfWeek.value == 7) today.minusDays(1) else today.plusDays(1)
        val neighbor = first.copy(id = "neighbor028", day = adjacentDay.dayOfWeek.value)
        seed(listOf(neighbor))
        for (theme in listOf("light", "dark")) {
            runBlocking { store.updatePreferences { it.copy(theme = theme) } }
            ui.waitForIdle()
            val a = card(first.id)
            val b = card(second.id)
            val dp = ui.activity.resources.displayMetrics.density
            assertEquals(4f * dp, b.top - a.bottom, 1f)
            val adjacent = card(neighbor.id, adjacentDay)
            if (adjacentDay > today) assertEquals(4f * dp, adjacent.left - a.right, 1f)
            else assertEquals(4f * dp, a.left - adjacent.right, 1f)
            assertEquals(section(1).top + 2f * dp, a.top, 1f)
            assertEquals(section(2).bottom - 2f * dp, a.bottom, 1f)
            assertRounded(first.id)
            assertRounded(second.id)
            assertRounded(neighbor.id, adjacentDay)
            assertEquals(section(2).bottom, section(3).top, 1f)
            assertEquals(section(4).bottom, section(5).top, 1f)
            assertEquals(section(5).height, section(6).top - section(5).top, 1f)
            screenshot("028-auto-$theme.png")
        }
        ui.onNodeWithTag("grid-course-${second.id}@$today").performClick()
        ui.onNodeWithText(course.name).assertExists()
    }

    @Test fun laterWeekGapLessonKeepsSpacingWhenPagingAndRemainsVisible() {
        val lunch = first.copy(id = "lunch028", weeks = setOf(2), mode = TimeMode.CLOCK, start = "12:00", end = "13:00")
        seed(listOf(lunch))
        val before = section(3)
        val dp = ui.activity.resources.displayMetrics.density
        assertEquals(20f * dp, section(3).top - section(2).bottom, 1f)
        assertEquals(22f * dp, card(second.id).top - card(first.id).bottom, 1f)
        assertRounded(first.id)
        ui.onNodeWithTag("week-viewport").performTouchInput { swipeLeft() }
        ui.onNodeWithText("第2周 ▾").assertExists()
        assertEquals(before.top, section(3, 2).top, 1f)
        ui.onNodeWithTag("grid-course-${lunch.id}@${today.plusWeeks(1)}").performScrollTo().assertIsDisplayed()
        assertRounded(lunch.id, today.plusWeeks(1))
        screenshot("028-gap-lesson.png")
        ui.onNodeWithTag("grid-course-${lunch.id}@${today.plusWeeks(1)}").performClick()
        ui.onNodeWithText("12:00–13:00", substring = false).assertExists()
    }

    @Test fun manualSettingAndReturnToAutoSurviveActivityRecreationAndBackup() {
        val s = seed()
        ui.onNodeWithText("我的").performClick()
        ui.onNodeWithText("外观设置").performScrollTo().performClick()
        ui.onNode(isToggleable() and hasAnyAncestor(hasTestTag("course-gap-setting")))
            .performScrollTo().performClick()
        ui.waitUntil(10000) { runBlocking { store.prefs().showCourseGaps == true } }
        ui.activityRule.scenario.recreate()
        ui.onNodeWithText("恢复自动判断").performScrollTo().performClick()
        ui.waitUntil(10000) { runBlocking { store.prefs().showCourseGaps == null } }
        runBlocking {
            val restored = Codec.restore(store.backup())
            assertNull(restored.preferences!!.showCourseGaps)
            assertEquals(s, restored.schedules.first { it.id == s.id })
        }
        UiDevice.getInstance(instrumentation).pressBack()
        ui.onNodeWithText("课表", substring = false).performClick()
        assertEquals(4f * ui.activity.resources.displayMetrics.density, card(second.id).top - card(first.id).bottom, 1f)
        assertRounded(first.id)
    }

    @Test fun oldManualFalseStillPreservesRoundedCardsAndOutOfRoutineLessons() {
        val lunch = first.copy(id = "lunch028", mode = TimeMode.CLOCK, start = "12:00", end = "13:00")
        seed(listOf(lunch), Preferences(showCourseGaps = false))
        assertTrue(section(3).top - section(2).bottom > 10f)
        ui.onNodeWithTag("grid-course-${lunch.id}@$today").performScrollTo().assertIsDisplayed()
        assertTrue(card(lunch.id).height > 10f)
        assertRounded(first.id)
        assertRounded(lunch.id)
        screenshot("028-manual-with-gap-lesson.png")
    }
}

package app.van.timetable

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.van.timetable.data.Demo
import app.van.timetable.domain.*
import java.io.File
import java.time.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RequestedFeaturesTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private fun screenshot(name: String) {
        ui.waitForIdle()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            .takeScreenshot(File(ui.activity.getExternalFilesDir(null), name))
    }

    private fun seed(): Schedule {
        val s = Demo.schedule()
        runBlocking {
            val store = (ui.activity.application as VanApplication).store
            store.save(s)
            store.updatePreferences {
                it.copy(
                    activeScheduleId = s.id,
                    theme = "light",
                    fontScale = 1f,
                    fitWeek = true,
                    reminders = false,
                )
            }
        }
        ui.waitUntil(10000) { ui.onAllNodesWithText(s.name).fetchSemanticsNodes().isNotEmpty() }
        return s
    }

    @Test
    fun swipingChangesWeekAndDayAndShowsWeekday() {
        val s = seed()
        val before = LocalDate.now(ZoneId.of(s.zone))
        val currentWeek = CourseEngine.week(s, before)
        ui.onNodeWithText("第${currentWeek}周 ▾").assertExists()
        ui.onNodeWithTag("week-viewport").performTouchInput { swipeLeft() }
        ui.onNodeWithText("第${currentWeek + 1}周 ▾").assertExists()
        ui.onNodeWithTag("week-viewport").performTouchInput { swipeRight() }
        ui.onNodeWithText("第${currentWeek}周 ▾").assertExists()
        ui.onNodeWithContentDescription("课表菜单").performClick()
        ui.onNodeWithText("切换为日列表").performClick()
        ui.onNodeWithText("$before ${app.van.timetable.ui.weekdays[before.dayOfWeek.value - 1]}")
            .assertExists()
        screenshot("feature-day-list.png")
        ui.onNodeWithTag("day-list").performTouchInput { swipeLeft() }
        val after = before.plusDays(1)
        ui.onNodeWithText("$after ${app.van.timetable.ui.weekdays[after.dayOfWeek.value - 1]}")
            .assertExists()
        ui.onNodeWithTag("day-list").performTouchInput { swipeRight() }
        ui.onNodeWithText("$before ${app.van.timetable.ui.weekdays[before.dayOfWeek.value - 1]}")
            .assertExists()
    }

    @Test
    fun enlargedGridScrollsBeforeSwitchingWeeks() {
        val s = seed()
        val store = (ui.activity.application as VanApplication).store
        val currentWeek = CourseEngine.week(s, LocalDate.now(ZoneId.of(s.zone)))
        runBlocking { store.updatePreferences { it.copy(fontScale = 1.6f) } }
        ui.waitForIdle()
        ui.onNodeWithTag("week-viewport").performTouchInput { swipeLeft() }
        ui.onNodeWithText("第${currentWeek}周 ▾").assertExists()
        ui.onNodeWithTag("week-day-$currentWeek-6").assertIsDisplayed()
        ui.onNodeWithTag("week-viewport").performTouchInput { swipeLeft() }
        ui.onNodeWithText("第${currentWeek + 1}周 ▾").assertExists()
        ui.onNodeWithTag("week-viewport").performTouchInput { swipeRight() }
        ui.onNodeWithText("第${currentWeek + 1}周 ▾").assertExists()
        ui.onNodeWithTag("week-day-${currentWeek + 1}-0").assertIsDisplayed()
        ui.onNodeWithTag("week-viewport").performTouchInput { swipeRight() }
        ui.onNodeWithText("第${currentWeek}周 ▾").assertExists()
        runBlocking { store.updatePreferences { it.copy(fontScale = 1f) } }
    }

    @Test
    fun quickEventsKeepParentAndExpandedPaletteSurvivesSaving() {
        val s = seed()
        ui.onNodeWithText("课程", useUnmergedTree = true).performClick()
        ui.onNodeWithText("大数据技术").performClick()
        ui.onNodeWithText("添加实验课").performScrollTo().performClick()
        ui.onNodeWithContentDescription("课程颜色12").performScrollTo().performClick()
        screenshot("feature-colors.png")
        ui.onNodeWithText("保存课程").performScrollTo().performClick()
        val store = (ui.activity.application as VanApplication).store
        ui.waitUntil(10000) {
            runBlocking { store.all().first { it.id == s.id }.courses.any { it.kind == "实验课" } }
        }
        ui.onNodeWithText("添加期末考试").performScrollTo().performClick()
        ui.onNodeWithText("保存课程").performScrollTo().performClick()
        ui.waitUntil(10000) {
            runBlocking { store.all().first { it.id == s.id }.courses.any { it.kind == "期末考试" } }
        }
        val saved = runBlocking { store.all().first { it.id == s.id } }
        val parent = saved.courses.first { it.name == "大数据技术" }
        val lab = saved.courses.first { it.kind == "实验课" }
        val exam = saved.courses.first { it.kind == "期末考试" }
        assertEquals(parent.id, lab.parentCourseId)
        assertEquals(parent.id, exam.parentCourseId)
        assertEquals(11, lab.color)
        assertEquals(TimeMode.CLOCK, saved.sessions.first { it.courseId == exam.id }.mode)
        assertNotNull(saved.sessions.first { it.courseId == exam.id }.date)
        Validation.requireValid(Codec.decode(Codec.encode(saved)))
    }
}

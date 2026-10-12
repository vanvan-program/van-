package app.van.timetable

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.van.timetable.data.Demo
import app.van.timetable.domain.*
import java.io.File
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HolidayUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val store
        get() = (ui.activity.application as VanApplication).store

    private fun seed(): Schedule {
        val s = Demo.schedule()
        runBlocking {
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

    private fun dateNode(date: LocalDate) =
        ui.onNode(
            hasText(
                date.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.getDefault()))
            ),
            useUnmergedTree = true,
        )

    private fun chooseDate(tag: String, date: LocalDate) {
        ui.onNodeWithTag(tag).performScrollTo().performClick()
        ui.onNodeWithTag("calendar-picker").assertIsDisplayed()
        dateNode(date).performClick()
        ui.onNodeWithText("确定日期").performClick()
    }

    private fun screenshot(name: String) {
        ui.waitForIdle()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            .takeScreenshot(File(ui.activity.getExternalFilesDir(null), name))
    }

    @Test
    fun holidayCalendarShowsGrayCourseCardsAndRevokeRestoresOriginalCourses() {
        val s = seed()
        val today = LocalDate.now(ZoneId.of(s.zone))
        val from = today.withDayOfMonth(1)
        val through = today.withDayOfMonth(today.lengthOfMonth())
        ui.onNodeWithText("我的", useUnmergedTree = true).performClick()
        ui.onNodeWithText("节假日设置").performScrollTo().performClick()
        ui.onNode(hasSetTextAction() and hasText("假期名称")).performTextReplacement("测试连休")
        chooseDate("holiday-from-date", from)
        chooseDate("holiday-through-date", through)
        ui.onNodeWithText("添加假期").performScrollTo().performClick()
        ui.waitUntil(10000) {
            runBlocking { store.all().first { it.id == s.id }.holidays.size == 1 }
        }
        val saved = runBlocking { store.all().first { it.id == s.id } }
        assertEquals(from.toString(), saved.holidays.single().from)
        assertEquals(through.toString(), saved.holidays.single().through)
        assertEquals(s.sessions, saved.sessions)
        assertTrue(CourseEngine.resolveDay(saved, today).isEmpty())
        screenshot("holiday-settings.png")
        ui.onNodeWithContentDescription("返回").performClick()
        ui.onNodeWithText("课表", useUnmergedTree = true).performClick()
        val occurrence = CourseEngine.resolveDay(s, today).first()
        val card = ui.onNodeWithTag("holiday-course-${occurrence.id}")
        card.assertIsDisplayed()
        card.assertTextContains("[放假]${occurrence.course.name}")
        ui.onNodeWithTag("holiday-day-$today").assertDoesNotExist()
        fun assertGray(expected: Int) {
            val pixels = card.captureToImage().toPixelMap()
            assertEquals(expected, pixels[pixels.width / 2, 3].toArgb())
            assertTrue(
                card.fetchSemanticsNode().boundsInRoot.height <
                    ui.onNodeWithTag("week-viewport").fetchSemanticsNode().boundsInRoot.height / 2
            )
        }
        assertGray(0xFFD4D7DE.toInt())
        // Holiday previews remain visible even when ordinary non-week courses are hidden.
        runBlocking { store.updatePreferences { it.copy(showInactive = false) } }
        card.assertIsDisplayed()
        screenshot("holiday-courses-light.png")
        runBlocking { store.updatePreferences { it.copy(theme = "dark") } }
        ui.waitForIdle()
        card.assertIsDisplayed()
        assertGray(0xFF344155.toInt())
        screenshot("holiday-courses-dark.png")
        for (dark in listOf(false, true)) {
            val bytes =
                app.van.timetable.system.Files.image(
                    saved,
                    CourseEngine.week(saved, today),
                    dark,
                    false,
                )
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            assertEquals(
                if (dark) 0xFF344155.toInt() else 0xFFD4D7DE.toInt(),
                bitmap.getPixel((today.dayOfWeek.value - 1) * 200 + 100, 188),
            )
            bitmap.recycle()
        }
        ui.onNodeWithContentDescription("课表菜单").performClick()
        ui.onNodeWithText("切换为日列表").performClick()
        ui.onNodeWithText("放假期间，今日无课").assertIsDisplayed()
        ui.onNodeWithText("[放假]${occurrence.course.name}").assertIsDisplayed()
        ui.onNodeWithText("正在上课").assertDoesNotExist()
        screenshot("holiday-courses-day-list.png")
        ui.onNodeWithText("我的", useUnmergedTree = true).performClick()
        ui.onNodeWithText("节假日设置").performScrollTo().performClick()
        ui.onNodeWithText("撤销假期").performScrollTo().performClick()
        ui.onNodeWithText("确认").performClick()
        ui.waitUntil(10000) {
            runBlocking { store.all().first { it.id == s.id }.holidays.isEmpty() }
        }
        assertEquals(s.sessions, runBlocking { store.all().first { it.id == s.id }.sessions })
        ui.onNodeWithContentDescription("返回").performClick()
        ui.onNodeWithText("课表", useUnmergedTree = true).performClick()
        ui.onNodeWithText("放假期间，今日无课").assertDoesNotExist()
        val expected = CourseEngine.resolveDay(s, today).first().course.name
        ui.onNodeWithText(expected).assertIsDisplayed()
    }

    @Test
    fun semesterCalendarAllowsMondaysAndPreservesChosenDateAfterRecreation() {
        val s = seed()
        val original = LocalDate.parse(s.week1Monday)
        val firstMonday =
            original
                .withDayOfMonth(1)
                .with(java.time.temporal.TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))
        val target = if (firstMonday == original) firstMonday.plusWeeks(1) else firstMonday
        ui.onNodeWithText("我的", useUnmergedTree = true).performClick()
        ui.onNodeWithText("学期设置").performScrollTo().performClick()
        ui.onNodeWithTag("semester-start-date").performClick()
        dateNode(target.plusDays(1)).assertIsNotEnabled()
        screenshot("semester-calendar.png")
        dateNode(target).performClick()
        ui.onNodeWithText("确定日期").performClick()
        ui.activityRule.scenario.recreate()
        ui.onNodeWithText("$target 周一").assertIsDisplayed()
        ui.onNodeWithText("保存学期设置").performScrollTo().performClick()
        ui.onNodeWithText("确认").performClick()
        ui.waitUntil(10000) {
            runBlocking { store.all().first { it.id == s.id }.week1Monday == target.toString() }
        }
        val saved = runBlocking { store.all().first { it.id == s.id } }
        assertEquals(s.sessions, saved.sessions)
        assertEquals(s.routines, saved.routines)
        ui.onNodeWithText("课表", useUnmergedTree = true).performClick()
        val currentWeek = CourseEngine.week(saved, LocalDate.now(ZoneId.of(saved.zone)))
        ui.onNodeWithText("第${currentWeek}周 ▾").assertIsDisplayed()
    }
}

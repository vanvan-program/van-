package app.van.timetable

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import app.van.timetable.domain.*
import java.io.File
import java.time.*
import java.time.temporal.TemporalAdjusters
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Version026UiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device get() = UiDevice.getInstance(instrumentation)
    private val store get() = (ui.activity.application as VanApplication).store
    private var previous: Backup? = null
    private val today = LocalDate.now(ZoneId.of("Asia/Shanghai"))
    private val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    private val c = Course(id = "course026", name = "数字图像处理 · 实验课", color = 2)
    private val n = CourseSession(id = "morning026", courseId = c.id, day = today.dayOfWeek.value)

    @Before fun preserve() { previous = runBlocking { Backup(schedules = store.all(), preferences = store.prefs()) } }
    @After fun restore() { previous?.let { runBlocking { store.restore(it) } } }

    private fun seed(s: Schedule = Schedule(name = "0.2.6 验证课表", week1Monday = monday.toString(), courses = listOf(c), sessions = listOf(n)), prefs: Preferences = Preferences()): Schedule {
        runBlocking {
            store.save(s)
            store.updatePreferences { prefs.copy(activeScheduleId = s.id, reminders = false) }
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

    private fun screenshot(name: String) {
        ui.waitForIdle()
        device.waitForIdle(500)
        assertTrue(device.takeScreenshot(File(ui.activity.getExternalFilesDir(null), name)))
    }
    private fun read(id: String) = runBlocking { store.all().first { it.id == id } }
    private fun waitSaved(condition: () -> Boolean) = ui.waitUntil(15000, condition)

    @Test fun fixedGapsDoNotMoveSectionsAcrossWeeksAndLabelSwitch() {
        val custom = Course(id = "clock026", name = "午休自定义课程")
        val clock = CourseSession(id = "clockslot026", courseId = custom.id, day = today.dayOfWeek.value, weeks = setOf(1), mode = TimeMode.CLOCK, start = "12:00", end = "13:00")
        val s = seed(Schedule(name = "固定窄缝验证", week1Monday = monday.toString(), courses = listOf(c, custom), sessions = listOf(n, clock)))
        val before = ui.onNode(hasTestTag("section-3") and hasAnyAncestor(hasTestTag("week-grid-1"))).fetchSemanticsNode().boundsInRoot
        ui.onNodeWithTag("grid-course-${clock.id}@$today").performScrollTo().assertExists()
        screenshot("026-light-grid.png")
        ui.onNodeWithTag("week-viewport").performTouchInput { swipeLeft() }
        ui.onNodeWithText("第2周 ▾").assertExists()
        val after = ui.onNode(hasTestTag("section-3") and hasAnyAncestor(hasTestTag("week-grid-2"))).fetchSemanticsNode().boundsInRoot
        assertEquals(before.top, after.top, 1f)
        assertEquals(before.height, after.height, 1f)
        runBlocking { store.updatePreferences { it.copy(showCustomTimeLabels = false, theme = "dark", fontScale = 1.3f, showWeekend = false) } }
        ui.waitForIdle()
        screenshot("026-dark-large-grid.png")
        assertEquals(2, read(s.id).sessions.size)
    }

    @Test fun calendarClearAndWheelSelectionSaveAndSurviveRecreation() {
        val s = seed()
        ui.onNodeWithTag("grid-course-${n.id}@$today").performClick()
        ui.onNodeWithText("编辑整门课程").performScrollTo().performClick()
        ui.onNodeWithText("选择日期").performScrollTo().performClick()
        ui.onNodeWithTag("calendar-picker").assertExists()
        screenshot("026-calendar.png")
        ui.onNode(hasText(today.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.getDefault())), substring = true), useUnmergedTree = true).performClick()
        ui.onNodeWithText("确定日期").performClick()
        ui.onNodeWithTag("clear-once-date").performScrollTo().assertExists()
        ui.activityRule.scenario.recreate()
        ui.onNodeWithTag("clear-once-date").performScrollTo().performClick()
        ui.onNodeWithTag("start-section").performScrollTo().performClick()
        ui.onNodeWithText("选择上课节次").assertExists()
        ui.onNodeWithTag("start-section-wheel").performTouchInput { swipeUp(durationMillis = 400) }
        ui.onNodeWithTag("end-section-wheel").performTouchInput { swipeUp(durationMillis = 400) }
        ui.waitForIdle()
        screenshot("026-section-wheels.png")
        ui.onNodeWithText("确定节次").performClick()
        ui.onNodeWithTag("start-section").performClick()
        ui.onNodeWithText("取消").performClick()
        ui.onNodeWithText("保存课程").performScrollTo().performClick()
        waitSaved { read(s.id).sessions.single().startSection != 1 }
        val saved = read(s.id).sessions.single()
        assertNull(saved.date)
        assertTrue(saved.weeks.isNotEmpty())
        assertTrue(saved.endSection >= saved.startSection)
        assertTrue(saved.endSection <= s.routines.minOf { it.sections.size })
    }

    @Test fun deleteOnlySelectedBlockWithConfirmationAndKeepOtherTimes() {
        val afternoon = n.copy(id = "afternoon026", startSection = 5, endSection = 8)
        val s = seed(Schedule(name = "单次删除验证", week1Monday = monday.toString(), courses = listOf(c), sessions = listOf(n, afternoon)))
        ui.onNodeWithTag("grid-course-${afternoon.id}@$today").performScrollTo().performClick()
        ui.onAllNodesWithText("仅删除本日本节").assertCountEquals(1)
        ui.onNodeWithText("仅删除本日本节").performScrollTo().performClick()
        ui.onNodeWithText("确认").assertExists()
        screenshot("026-delete-confirm.png")
        ui.onNodeWithText("取消").performClick()
        assertEquals(0, read(s.id).sessionOverrides.size)
        ui.onNodeWithText("仅删除本日本节").performClick()
        ui.onNodeWithText("确认").performClick()
        waitSaved { read(s.id).sessionOverrides.size == 1 }
        val saved = read(s.id)
        assertEquals(listOf(n.id), CourseEngine.resolveDay(saved, today).map { it.session.id })
        assertEquals(2, CourseEngine.resolveDay(saved, today.plusWeeks(1)).size)
        ui.onNodeWithTag("week-selector").assertExists()
    }

    @Test fun movedClockLessonDeletesAtItsOriginalDate() {
        val source = today.minusDays(1)
        val clock = n.copy(mode = TimeMode.CLOCK, date = source.toString(), weeks = emptySet(), start = "12:00", end = "13:00")
        val s = seed(Schedule(name = "调入课程删除验证", week1Monday = monday.toString(), courses = listOf(c), sessions = listOf(clock), sessionOverrides = listOf(SessionOverride(clock.id, source.toString(), today.toString()))))
        ui.onNodeWithTag("grid-course-${clock.id}@$source").performScrollTo().performClick()
        ui.onNodeWithText("仅删除本日本节").performScrollTo().performClick()
        ui.onNodeWithText("确认").performClick()
        waitSaved { read(s.id).sessionOverrides.single().cancelled }
        val saved = read(s.id)
        assertTrue(CourseEngine.resolveDay(saved, today).isEmpty())
        assertTrue(CourseEngine.resolveDay(saved, source).isEmpty())
        assertEquals(source.toString(), saved.sessionOverrides.single().originalDate)
    }

    @Test fun holidayPreviewHasNoDeleteAndHistoryIsAvailableOffline() {
        seed(Schedule(name = "假期与历史验证", week1Monday = monday.toString(), courses = listOf(c), sessions = listOf(n), holidays = listOf(Holiday(from = today.toString(), through = today.toString()))))
        ui.onNodeWithTag("holiday-course-${n.id}@$today").performClick()
        ui.onNodeWithText("仅删除本日本节").assertDoesNotExist()
        device.pressBack()
        ui.onNodeWithText("我的").performClick()
        ui.onNodeWithText("帮助与关于").performScrollTo().performClick()
        ui.onNodeWithText("历史版本更新").performScrollTo().performClick()
        ui.onNodeWithTag("changelog-list").assertExists()
        ui.onNodeWithText("0.2.6 紧凑课表与课程操作").assertExists()
        screenshot("026-changelog.png")
        ui.onNodeWithTag("changelog-list").performScrollToNode(hasText("0.1.2 教务网址输入与备份兼容"))
        ui.onNodeWithText("0.1.2 教务网址输入与备份兼容").performClick()
        ui.onNodeWithText("教务入口改为用户输入网址", substring = true).assertExists()
    }

    @Test fun gapCardsBeforeLunchAndAfterRoutineRemainInsideTheirBandsAndClickable() {
        val slots = listOf("06:30" to "07:30", "12:00" to "13:00", "13:15" to "13:45", "22:00" to "23:00", "08:15" to "10:15")
            .mapIndexed { index, (start, end) -> n.copy(id = "gap$index", mode = TimeMode.CLOCK, start = start, end = end) }
        seed(Schedule(name = "作息外与跨课间验证", week1Monday = monday.toString(), courses = listOf(c), sessions = slots))
        val lunch = ui.onNodeWithTag("grid-course-gap1@$today").performScrollTo().fetchSemanticsNode().boundsInRoot
        val later = ui.onNodeWithTag("grid-course-gap2@$today").fetchSemanticsNode().boundsInRoot
        assertTrue(lunch.right <= later.left + 1f)
        assertTrue(lunch.height <= 21f * ui.activity.resources.displayMetrics.density)
        screenshot("026-gap-cards.png")
        for (slot in listOf(slots.first(), slots.last(), slots[3])) {
            ui.onNodeWithTag("grid-course-${slot.id}@$today").performScrollTo().performClick()
            ui.onNodeWithTag("delete-occurrence-${slot.id}@$today").performScrollTo().assertExists()
            ui.onAllNodesWithText("仅删除本日本节").assertCountEquals(1)
            device.pressBack()
        }
    }
}

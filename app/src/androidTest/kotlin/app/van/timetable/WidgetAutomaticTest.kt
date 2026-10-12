package app.van.timetable

import android.app.AlarmManager
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.widget.ListView
import android.widget.TextView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import app.van.timetable.domain.*
import app.van.timetable.widget.*
import java.io.File
import java.time.*
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetAutomaticTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val context
        get() = instrumentation.targetContext

    private val store
        get() = (context.applicationContext as VanApplication).store

    private fun bind(scenario: ActivityScenario<WidgetPreviewActivity>): Int {
        instrumentation.uiAutomation.adoptShellPermissionIdentity(
            "android.permission.BIND_APPWIDGET"
        )
        var id = 0
        try {
            scenario.onActivity {
                assertTrue(it.bind())
                id = it.widgetId
            }
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
        return id
    }

    private fun waitForCount(
        scenario: ActivityScenario<WidgetPreviewActivity>,
        count: Int,
        timeout: Long = 10000,
    ) {
        val deadline = System.currentTimeMillis() + timeout
        var actual = -1
        while (System.currentTimeMillis() < deadline && actual != count) {
            Thread.sleep(100)
            scenario.onActivity {
                actual = it.widgetView.findViewById<ListView>(R.id.today_list)?.adapter?.count ?: -1
            }
        }
        assertEquals("未点击更新，小组件应自动同步", count, actual)
    }

    @Test
    fun holidayChangesSynchronizeWithoutRefreshAndRealAlarmRemovesEndedCourse() {
        assertTrue(
            "本项时间触发验证需要闹钟与提醒授权",
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms(),
        )
        val zone = ZoneId.of("Asia/Shanghai")
        val today = LocalDate.now(zone)
        val before = runBlocking { store.prefs() }
        val empty =
            Schedule(
                name = "自动刷新验证",
                week1Monday =
                    today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString(),
            )
        runBlocking {
            store.save(empty)
            store.updatePreferences {
                it.copy(
                    activeScheduleId = empty.id,
                    theme = "light",
                    reminders = false,
                    statusNotification = false,
                )
            }
        }
        val scenario = ActivityScenario.launch(WidgetPreviewActivity::class.java)
        try {
            val id = bind(scenario)
            assertEquals(
                1800000L,
                AppWidgetManager.getInstance(context)
                    .getAppWidgetInfo(id)
                    .updatePeriodMillis
                    .toLong(),
            )
            waitForCount(scenario, 0)
            val now = LocalTime.now(zone).withNano(0)
            org.junit.Assume.assumeTrue(now < LocalTime.of(23, 59, 30) && now > LocalTime.of(0, 1))
            val course = Course(name = "自动结束课程")
            val populated =
                empty.copy(
                    courses = listOf(course),
                    sessions =
                        listOf(
                            CourseSession(
                                courseId = course.id,
                                date = today.toString(),
                                mode = TimeMode.CLOCK,
                                start = now.minusSeconds(20).toString(),
                                end = now.plusSeconds(25).toString(),
                            )
                        ),
                )
            runBlocking { store.save(populated) }
            waitForCount(scenario, 1)
            val holiday =
                Holiday(name = "即时放假", from = today.toString(), through = today.toString())
            runBlocking { store.save(populated.copy(holidays = listOf(holiday))) }
            waitForCount(scenario, 0)
            scenario.onActivity {
                assertEquals(
                    "假期无课",
                    it.widgetView.findViewById<TextView>(R.id.today_empty).text.toString(),
                )
            }
            runBlocking { store.save(populated) }
            waitForCount(scenario, 1)
            // No Widgets.update, Scheduler.reconcile, refresh click or data mutation after this
            // point.
            waitForCount(scenario, 0, 35000)
            scenario.onActivity {
                assertEquals(
                    "今日课程已结束",
                    it.widgetView.findViewById<TextView>(R.id.today_empty).text.toString(),
                )
            }
            UiDevice.getInstance(instrumentation)
                .takeScreenshot(
                    File(context.getExternalFilesDir(null), "widget-automatic-ended.png")
                )
        } finally {
            scenario.close()
            runBlocking {
                store.delete(empty.id)
                store.updatePreferences { before }
            }
        }
    }

    @Test
    fun realWidgetCourseClickOpensBoundTimetableAndReusedActivityReturnsFromOtherTab() {
        val zone = ZoneId.of("Asia/Shanghai")
        val today = LocalDate.now(zone)
        val course = Course(name = "组件点击验证课程")
        val schedule =
            Schedule(
                name = "组件绑定验证表",
                week1Monday =
                    today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString(),
                courses = listOf(course),
                sessions =
                    listOf(
                        CourseSession(
                            courseId = course.id,
                            date = today.toString(),
                            mode = TimeMode.CLOCK,
                            start = "00:01",
                            end = "23:59",
                        )
                    ),
            )
        val before = runBlocking { store.prefs() }
        runBlocking { store.save(schedule) }
        val scenario = ActivityScenario.launch(WidgetPreviewActivity::class.java)
        var id = 0
        try {
            id = bind(scenario)
            context
                .getSharedPreferences("widgets", 0)
                .edit()
                .putString("schedule_$id", schedule.id)
                .commit()
            runBlocking { Widgets.update(context, id) }
            waitForCount(scenario, 1)
            val list =
                UiDevice.getInstance(instrumentation)
                    .findObject(UiSelector().resourceId("app.van.timetable:id/today_list"))
            assertTrue(
                list.getChild(UiSelector().resourceId("app.van.timetable:id/item_root")).click()
            )
            ui.waitUntil(10000) {
                ui.onAllNodesWithTag("week-viewport").fetchSemanticsNodes().isNotEmpty()
            }
            ui.onNodeWithText(schedule.name).assertIsDisplayed()
            ui.onNodeWithText("编辑整门课程").assertDoesNotExist()
            UiDevice.getInstance(instrumentation)
                .takeScreenshot(
                    File(context.getExternalFilesDir(null), "widget-opens-timetable.png")
                )
            ui.onNodeWithText("我的", useUnmergedTree = true).performClick()
            instrumentation.runOnMainSync {
                context.startActivity(
                    Intent(context, MainActivity::class.java)
                        .putExtra("openTimetable", true)
                        .putExtra("scheduleId", schedule.id)
                        .putExtra("date", today.plusWeeks(1).toString())
                        .addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                        )
                )
            }
            ui.waitUntil(10000) {
                ui.onAllNodesWithTag("week-viewport").fetchSemanticsNodes().isNotEmpty()
            }
            ui.onNodeWithText("第2周 ▾").assertIsDisplayed()
            ui.onNodeWithText(schedule.name).assertIsDisplayed()
            assertEquals(
                "打开绑定表不改变App启用的课表",
                before.activeScheduleId,
                runBlocking { store.prefs().activeScheduleId },
            )
        } finally {
            scenario.close()
            context.getSharedPreferences("widgets", 0).edit().remove("schedule_$id").apply()
            runBlocking {
                store.delete(schedule.id)
                store.updatePreferences { before }
            }
        }
    }
}

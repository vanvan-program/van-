package app.van.timetable

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.widget.ListView
import android.widget.RemoteViews
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
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Version020WidgetTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val context
        get() = instrumentation.targetContext

    private val store
        get() = (context.applicationContext as VanApplication).store

    private val device
        get() = UiDevice.getInstance(instrumentation)

    private fun launchApp(s: Schedule) {
        instrumentation.runOnMainSync {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .putExtra("openTimetable", true)
                    .putExtra("scheduleId", s.id)
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
    }

    private fun returnToWidget() {
        context.startActivity(
            Intent(context, WidgetPreviewActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        )
        assertTrue(
            device
                .findObject(UiSelector().resourceId("app.van.timetable:id/widget_root"))
                .waitForExists(10000)
        )
    }

    private fun waitUpdated(scenario: ActivityScenario<WidgetPreviewActivity>, marker: String) {
        var text = marker
        ui.waitUntil(10000) {
            scenario.onActivity {
                text = it.widgetView.findViewById<TextView>(R.id.widget_updated).text.toString()
            }
            text != marker
        }
    }

    private fun stale(id: Int, marker: String) {
        AppWidgetManager.getInstance(context)
            .partiallyUpdateAppWidget(
                id,
                RemoteViews(context.packageName, R.layout.widget_dual).apply {
                    setTextViewText(R.id.widget_updated, marker)
                },
            )
    }

    private fun bind(scenario: ActivityScenario<WidgetPreviewActivity>, provider: Class<*>): Int {
        instrumentation.uiAutomation.adoptShellPermissionIdentity(
            "android.permission.BIND_APPWIDGET"
        )
        var id = 0
        try {
            scenario.onActivity {
                assertTrue(it.bind(provider))
                id = it.widgetId
            }
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
        return id
    }

    @Test
    fun emptyAreasOfAllWidgetTypesOpenBoundTable() {
        val before = runBlocking { store.prefs() }
        val today = LocalDate.now(ZoneId.of("Asia/Shanghai"))
        val s =
            Schedule(
                name = "空白点击绑定表",
                week1Monday =
                    today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString(),
            )
        runBlocking { store.save(s) }
        try {
            for (provider in
                listOf(DualWidget::class.java, TodayWidget::class.java, NextWidget::class.java)) {
                val ids =
                    if (provider == NextWidget::class.java) listOf("widget_root")
                    else if (provider == TodayWidget::class.java)
                        listOf("today_empty", "widget_root")
                    else listOf("today_empty", "tomorrow_empty", "widget_root")
                for (resource in ids) {
                    val scenario = ActivityScenario.launch(WidgetPreviewActivity::class.java)
                    val id = bind(scenario, provider)
                    context
                        .getSharedPreferences("widgets", 0)
                        .edit()
                        .putString("schedule_$id", s.id)
                        .commit()
                    runBlocking { Widgets.update(context, id) }
                    try {
                        val node =
                            device.findObject(
                                UiSelector().resourceId("app.van.timetable:id/$resource")
                            )
                        assertTrue(node.waitForExists(10000))
                        val bounds = node.bounds
                        // The root padding is outside all children; empty-view bottom is
                        // course-free.
                        val x = if (resource == "widget_root") bounds.left + 2 else bounds.centerX()
                        assertTrue(device.click(x, bounds.bottom - 6))
                        ui.waitUntil(10000) {
                            ui.onAllNodesWithTag("week-viewport").fetchSemanticsNodes().isNotEmpty()
                        }
                        ui.onNodeWithText(s.name).assertIsDisplayed()
                        assertEquals(
                            before.activeScheduleId,
                            runBlocking { store.prefs().activeScheduleId },
                        )
                    } finally {
                        scenario.close()
                        context
                            .getSharedPreferences("widgets", 0)
                            .edit()
                            .remove("schedule_$id")
                            .commit()
                    }
                }
            }
        } finally {
            runBlocking {
                store.delete(s.id)
                store.updatePreferences { before }
            }
        }
    }

    @Test
    fun blankBelowCoursesOpensAppAndAppAndCourseEntryRefreshWithoutDataChanges() {
        val before = runBlocking { store.prefs() }
        val today = LocalDate.now(ZoneId.of("Asia/Shanghai"))
        val c = Course(name = "组件空白与刷新课程", customColor = 0xFF123456)
        val s =
            Schedule(
                name = "点击刷新绑定表",
                week1Monday =
                    today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString(),
                courses = listOf(c),
                sessions =
                    listOf(
                        CourseSession(
                            courseId = c.id,
                            date = today.toString(),
                            mode = TimeMode.CLOCK,
                            start = "00:01",
                            end = "23:59",
                        )
                    ),
            )
        runBlocking { store.save(s) }
        val scenario = ActivityScenario.launch(WidgetPreviewActivity::class.java)
        val id = bind(scenario, DualWidget::class.java)
        try {
            context
                .getSharedPreferences("widgets", 0)
                .edit()
                .putString("schedule_$id", s.id)
                .commit()
            runBlocking { Widgets.update(context, id) }
            var count = -1
            ui.waitUntil(10000) {
                scenario.onActivity {
                    count =
                        it.widgetView.findViewById<ListView>(R.id.today_list).adapter?.count ?: -1
                }
                count == 1
            }
            val content =
                device
                    .findObject(UiSelector().resourceId("app.van.timetable:id/today_content"))
                    .bounds
            val list =
                device.findObject(UiSelector().resourceId("app.van.timetable:id/today_list")).bounds
            assertTrue("单节课程下面需留出可点击的空白区域", list.bottom < content.bottom - 10)
            device.takeScreenshot(File(context.getExternalFilesDir(null), "020-widget-blank.png"))
            stale(id, "进入前旧标记")
            var marker = ""
            ui.waitUntil(5000) {
                scenario.onActivity {
                    marker =
                        it.widgetView.findViewById<TextView>(R.id.widget_updated).text.toString()
                }
                marker == "进入前旧标记"
            }
            assertTrue(device.click(content.centerX(), (list.bottom + content.bottom) / 2))
            ui.waitUntil(10000) {
                ui.onAllNodesWithTag("week-viewport").fetchSemanticsNodes().isNotEmpty()
            }
            ui.onNodeWithText(s.name).assertIsDisplayed()
            waitUpdated(scenario, "进入前旧标记")
            // The app is already resumed: only entering Detail can replace this marker.
            stale(id, "课程页前旧标记")
            ui.waitUntil(5000) {
                scenario.onActivity {
                    marker =
                        it.widgetView.findViewById<TextView>(R.id.widget_updated).text.toString()
                }
                marker == "课程页前旧标记"
            }
            ui.onNodeWithTag("grid-course-${s.sessions.single().id}@$today")
                .performScrollTo()
                .performClick()
            ui.onNodeWithText("编辑整门课程").assertExists()
            waitUpdated(scenario, "课程页前旧标记")
            assertEquals(s, runBlocking { store.all().first { it.id == s.id } })
        } finally {
            scenario.close()
            context.getSharedPreferences("widgets", 0).edit().remove("schedule_$id").commit()
            runBlocking {
                store.delete(s.id)
                store.updatePreferences { before }
            }
        }
    }
}

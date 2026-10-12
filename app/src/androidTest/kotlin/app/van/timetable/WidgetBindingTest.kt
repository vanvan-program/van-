package app.van.timetable

import android.widget.ListView
import android.widget.TextView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.van.timetable.domain.*
import app.van.timetable.widget.*
import java.io.File
import java.time.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetBindingTest {
    @get:Rule val ui = createEmptyComposeRule()

    @Test
    fun twoWidgetsKeepDifferentSchedulesAfterActiveTableChangesAndDeletion() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as VanApplication
        val today = LocalDate.now(ZoneId.of("Asia/Shanghai"))
        fun table(name: String, count: Int): Schedule {
            val courses = (1..count).map { Course(name = "$name 课程$it", color = 11) }
            return Schedule(
                name = name,
                week1Monday =
                    today
                        .with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                        .toString(),
                courses = courses,
                sessions =
                    courses.map {
                        CourseSession(courseId = it.id, date = today.plusDays(1).toString())
                    },
            )
        }
        val a = table("课表甲", 2)
        val b = table("课表乙", 3)
        runBlocking {
            app.store
                .all()
                .filter { it.name == a.name || it.name == b.name }
                .forEach { app.store.delete(it.id) }
            app.store.save(a)
            app.store.save(b)
            app.store.updatePreferences { it.copy(activeScheduleId = a.id) }
        }
        val scenario = ActivityScenario.launch(WidgetPreviewActivity::class.java)
        var first = 0
        var second = 0
        val prefs = context.getSharedPreferences("widgets", 0)
        try {
            instrumentation.uiAutomation.adoptShellPermissionIdentity(
                "android.permission.BIND_APPWIDGET"
            )
            scenario.onActivity {
                check(it.bind())
                first = it.widgetId
                second = it.addWidget()
            }
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            prefs
                .edit()
                .putString("schedule_$first", a.id)
                .putString("schedule_$second", b.id)
                .commit()
            runBlocking {
                Widgets.update(context, first)
                Widgets.update(context, second)
            }
            repeat(50) {
                Thread.sleep(150)
                var ready = false
                scenario.onActivity {
                    ready =
                        it.widgetView.findViewById<ListView>(R.id.tomorrow_list).adapter?.count ==
                            2 &&
                            it.additional
                                .getValue(second)
                                .findViewById<ListView>(R.id.tomorrow_list)
                                .adapter
                                ?.count == 3
                }
                if (ready) return@repeat
            }
            scenario.onActivity {
                assertEquals(
                    2,
                    it.widgetView.findViewById<ListView>(R.id.tomorrow_list).adapter.count,
                )
                assertEquals(
                    3,
                    it.additional
                        .getValue(second)
                        .findViewById<ListView>(R.id.tomorrow_list)
                        .adapter
                        .count,
                )
                assertTrue(
                    it.widgetView.findViewById<TextView>(R.id.widget_name).text.contains("课表甲")
                )
                assertEquals(
                    13f,
                    it.widgetView.findViewById<TextView>(R.id.today_title).textSize /
                        it.resources.displayMetrics.scaledDensity,
                    .1f,
                )
            }
            UiDevice.getInstance(instrumentation)
                .takeScreenshot(File(context.getExternalFilesDir(null), "widget-bindings.png"))
            scenario.onActivity {
                it.widgetView.findViewById<TextView>(R.id.widget_updated).performClick()
            }
            ui.waitUntil(10000) { ui.onAllNodesWithText("保存设置").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("课表乙").performScrollTo().performClick()
            ui.onNodeWithText("浅色").performScrollTo().performClick()
            UiDevice.getInstance(instrumentation)
                .takeScreenshot(File(context.getExternalFilesDir(null), "widget-settings.png"))
            ui.onNodeWithText("保存设置").performScrollTo().performClick()
            ui.waitUntil(10000) {
                prefs.getString("schedule_$first", "") == b.id
            }
            runBlocking { assertEquals(b.id, Widgets.schedule(context, first)?.id) }
            assertEquals(b.id, prefs.getString("schedule_$second", ""))
            prefs.edit().putString("schedule_$first", a.id).commit()
            runBlocking {
                app.store.updatePreferences { it.copy(activeScheduleId = b.id) }
                assertEquals(a.id, Widgets.schedule(context, first)?.id)
                assertEquals(b.id, Widgets.schedule(context, second)?.id)
                app.store.delete(a.id)
                assertNull(Widgets.schedule(context, first))
                assertEquals(b.id, Widgets.schedule(context, second)?.id)
            }
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            scenario.close()
            prefs.edit().remove("schedule_$first").remove("schedule_$second").commit()
            runBlocking {
                app.store.delete(a.id)
                app.store.delete(b.id)
            }
        }
    }
}

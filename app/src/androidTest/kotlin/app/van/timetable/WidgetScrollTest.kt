// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable

import android.widget.ListView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import app.van.timetable.domain.*
import app.van.timetable.widget.*
import java.io.File
import java.time.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetScrollTest {
    @Test
    fun twoNativeListsScrollIndependentlyAndEndedCourseIsAbsent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as VanApplication
        val zone = ZoneId.of("Asia/Shanghai")
        val today = LocalDate.now(zone)
        val time = LocalTime.now(zone).withSecond(0).withNano(0)
        org.junit.Assume.assumeTrue(
            "避免测试数据跨越午夜",
            time < LocalTime.of(23, 40) && time > LocalTime.of(0, 10),
        )
        val courses =
            (0..10).map { i -> Course(name = if (i == 0) "已结束不应出现" else "测试课程$i", color = i % 5) }
        val sessions = courses.flatMapIndexed { i, c ->
            listOf(
                CourseSession(
                    courseId = c.id,
                    date = today.toString(),
                    mode = TimeMode.CLOCK,
                    start =
                        (if (i == 0) time.minusMinutes(8) else time.plusMinutes(i.toLong()))
                            .toString(),
                    end =
                        (if (i == 0) time.minusMinutes(3) else time.plusMinutes(i + 2L)).toString(),
                ),
                CourseSession(
                    courseId = c.id,
                    date = today.plusDays(1).toString(),
                    mode = TimeMode.CLOCK,
                    start = "08:00",
                    end = "09:00",
                ),
            )
        }
        val schedule =
            Schedule(
                name = "滑动验证课表",
                week1Monday =
                    today
                        .with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                        .toString(),
                courses = courses,
                sessions = sessions,
            )
        runBlocking {
            app.store.save(schedule)
            app.store.updatePreferences { it.copy(activeScheduleId = schedule.id) }
        }
        val scenario = ActivityScenario.launch(WidgetPreviewActivity::class.java)
        try {
            instrumentation.uiAutomation.adoptShellPermissionIdentity(
                "android.permission.BIND_APPWIDGET"
            )
            var bound = false
            var id = 0
            scenario.onActivity {
                bound = it.bind()
                id = it.widgetId
            }
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            assertTrue("测试宿主需成功绑定系统小组件", bound)
            runBlocking { Widgets.update(instrumentation.targetContext, id) }
            var todayList: ListView? = null
            var tomorrowList: ListView? = null
            repeat(40) {
                if (todayList?.adapter?.count == 10 && tomorrowList?.adapter?.count == 11)
                    return@repeat
                Thread.sleep(200)
                scenario.onActivity {
                    todayList = it.widgetView.findViewById(R.id.today_list)
                    tomorrowList = it.widgetView.findViewById(R.id.tomorrow_list)
                }
            }
            scenario.onActivity { activity ->
                val left = activity.widgetView.findViewById<ListView>(R.id.today_list)
                val right = activity.widgetView.findViewById<ListView>(R.id.tomorrow_list)
                assertEquals(10, left.adapter.count)
                assertEquals(11, right.adapter.count)
                assertNotEquals(left.id, right.id)
            }
            val device = UiDevice.getInstance(instrumentation)
            val leftObject =
                device.findObject(UiSelector().resourceId("app.van.timetable:id/today_list"))
            assertTrue("左侧课程列表存在", leftObject.exists())
            assertTrue("左侧接收向上滑动", leftObject.swipeUp(20))
            instrumentation.waitForIdleSync()
            Thread.sleep(400)
            scenario.onActivity { activity ->
                assertTrue(
                    "真实滑动改变左侧位置",
                    activity.widgetView
                        .findViewById<ListView>(R.id.today_list)
                        .firstVisiblePosition > 0,
                )
                assertEquals(
                    "右侧位置不随左侧移动",
                    0,
                    activity.widgetView
                        .findViewById<ListView>(R.id.tomorrow_list)
                        .firstVisiblePosition,
                )
            }
            val rightObject =
                device.findObject(UiSelector().resourceId("app.van.timetable:id/tomorrow_list"))
            assertTrue("右侧接收向上滑动", rightObject.swipeUp(20))
            instrumentation.waitForIdleSync()
            Thread.sleep(400)
            scenario.onActivity { activity ->
                assertTrue(
                    activity.widgetView
                        .findViewById<ListView>(R.id.tomorrow_list)
                        .firstVisiblePosition > 0
                )
            }
            device.takeScreenshot(
                File(instrumentation.targetContext.getExternalFilesDir(null), "widget-scroll.png")
            )
            scenario.onActivity { activity ->
                activity.widgetView.findViewById<ListView>(R.id.tomorrow_list).setSelection(0)
                activity.widgetView.findViewById<ListView>(R.id.today_list).setSelection(9)
            }
            instrumentation.waitForIdleSync()
            Thread.sleep(300)
            scenario.onActivity { activity ->
                val left = activity.widgetView.findViewById<ListView>(R.id.today_list)
                val right = activity.widgetView.findViewById<ListView>(R.id.tomorrow_list)
                assertTrue(left.firstVisiblePosition > 0)
                assertEquals(0, right.firstVisiblePosition)
                right.setSelection(10)
            }
            instrumentation.waitForIdleSync()
            Thread.sleep(300)
            scenario.onActivity { activity ->
                assertTrue(
                    activity.widgetView
                        .findViewById<ListView>(R.id.tomorrow_list)
                        .firstVisiblePosition > 0
                )
            }
            runBlocking {
                app.store.updatePreferences { it.copy(theme = "dark") }
                Widgets.update(instrumentation.targetContext, id)
            }
            var darkApplied = false
            repeat(20) {
                if (!darkApplied) {
                    Thread.sleep(100)
                    scenario.onActivity { activity ->
                        val background =
                            activity.widgetView
                                .findViewById<android.view.View>(R.id.widget_root)
                                .background as android.graphics.drawable.GradientDrawable
                        darkApplied =
                            background.color?.defaultColor ==
                                android.graphics.Color.parseColor("#202020")
                    }
                }
            }
            assertTrue("小组件切换深色背景", darkApplied)
            device.takeScreenshot(
                File(instrumentation.targetContext.getExternalFilesDir(null), "widget-dark.png")
            )
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            scenario.close()
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable

import android.graphics.Rect
import android.widget.ListView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.van.timetable.domain.*
import app.van.timetable.widget.*
import java.io.File
import java.time.*
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetCompactTest {
    @Test
    fun fourByTwoShowsTwoCompleteCoursesInEachColumnIncludingHostPadding() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val store = (context.applicationContext as VanApplication).store
        val zone = ZoneId.of("Asia/Shanghai")
        val today = LocalDate.now(zone)
        val time = LocalTime.now(zone).withSecond(0).withNano(0)
        val remainingMinutes = Duration.between(time, LocalTime.MAX).toMinutes()
        org.junit.Assume.assumeTrue("避免测试数据跨越午夜", remainingMinutes > 10)
        val step = ((remainingMinutes - 2) / 6).coerceIn(2, 20)
        val courses =
            listOf("高等数学", "大学物理", "计算机程序设计实验", "大学英语").mapIndexed { i, name ->
                Course(name = name, color = listOf(0, 5, 8, 10)[i])
            }
        val schedule =
            Schedule(
                name = "4×2显示验证",
                week1Monday =
                    today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString(),
                courses = courses,
                sessions =
                    courses.flatMapIndexed { i, course ->
                        listOf(
                            CourseSession(
                                courseId = course.id,
                                date = today.toString(),
                                mode = TimeMode.CLOCK,
                                start = time.plusMinutes(1 + step * i).toString(),
                                end = time.plusMinutes(1 + step * (i + 1)).toString(),
                                place = "A${201 + i}",
                            ),
                            CourseSession(
                                courseId = course.id,
                                date = today.plusDays(1).toString(),
                                mode = TimeMode.CLOCK,
                                start = LocalTime.of(8 + i * 2, 0).toString(),
                                end = LocalTime.of(9 + i * 2, 40).toString(),
                                place = "B${301 + i}",
                            ),
                        )
                    },
            )
        val before = runBlocking { store.prefs() }
        runBlocking { store.save(schedule) }
        val scenario = ActivityScenario.launch(WidgetPreviewActivity::class.java)
        var id = 0
        val measurements = StringBuilder()
        try {
            instrumentation.uiAutomation.adoptShellPermissionIdentity(
                "android.permission.BIND_APPWIDGET"
            )
            scenario.onActivity {
                assertTrue(it.bind())
                id = it.widgetId
            }
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            val info = android.appwidget.AppWidgetManager.getInstance(context).getAppWidgetInfo(id)
            assertEquals("默认宽度应为四格", 4, info.targetCellWidth)
            assertEquals("默认高度应为两格", 2, info.targetCellHeight)
            assertTrue(
                "兼容旧桌面的最小高度不应要求三格",
                info.minHeight <= 110 * context.resources.displayMetrics.density,
            )
            context
                .getSharedPreferences("widgets", 0)
                .edit()
                .putString("schedule_$id", schedule.id)
                .commit()
            for (theme in listOf("light", "dark")) {
                runBlocking { store.updatePreferences { it.copy(theme = theme) } }
                // Grow again after tiny mode: a reused host must reset font sizes and padding.
                for (height in listOf(170, 130, 110, 200, 170, 130)) {
                    scenario.onActivity { it.resize(height) }
                    runBlocking { Widgets.update(context, id) }
                    var ready = false
                    val deadline = System.currentTimeMillis() + 10000
                    while (!ready && System.currentTimeMillis() < deadline) {
                        instrumentation.waitForIdleSync()
                        Thread.sleep(100)
                        scenario.onActivity { activity ->
                            ready =
                                listOf(R.id.today_list, R.id.tomorrow_list).all { listId ->
                                    val list = activity.widgetView.findViewById<ListView>(listId)
                                    list?.adapter?.count == 4 &&
                                        list.childCount >= 2 &&
                                        list.getChildAt(1).height > 0 &&
                                        list.getChildAt(1).bottom <= list.height &&
                                        !activity.widgetView.isLayoutRequested
                                }
                        }
                    }
                    UiDevice.getInstance(instrumentation)
                        .takeScreenshot(
                            File(context.getExternalFilesDir(null), "widget-4x2-$height-$theme.png")
                        )
                    scenario.onActivity { activity ->
                        measurements.appendLine(
                            "$theme host=${activity.widgetView.width}×${activity.widgetView.height}px, fontScale=${activity.resources.configuration.fontScale}"
                        )
                        for (listId in listOf(R.id.today_list, R.id.tomorrow_list)) {
                            val list = activity.widgetView.findViewById<ListView>(listId)
                            assertNotNull("课程列表已加载", list)
                            measurements.appendLine(
                                "${activity.resources.getResourceEntryName(listId)} height=${list.height}px children=${list.childCount}"
                            )
                            (0 until list.childCount).forEach { index ->
                                val child = list.getChildAt(index)
                                measurements.appendLine(
                                    "measured-row$index=${child.top}..${child.bottom}px, height=${child.height}"
                                )
                            }
                            assertEquals(4, list.adapter.count)
                            assertEquals(0, list.firstVisiblePosition)
                            assertTrue("$theme/$height dp第二节课必须完整显示：$measurements", ready)
                            for (index in 0..1) {
                                val row = list.getChildAt(index)
                                assertTrue(row.top >= 0 && row.bottom <= list.height)
                                val visible = Rect()
                                assertTrue(row.getGlobalVisibleRect(visible))
                                assertEquals("课程卡片没有被宿主裁剪", row.height, visible.height())
                                measurements.appendLine("row$index=${row.top}..${row.bottom}px")
                                for (textId in
                                    listOf(R.id.item_name, R.id.item_time, R.id.item_place)) {
                                    val text = row.findViewById<TextView>(textId)
                                    assertTrue(text.text.isNotBlank())
                                    assertTrue(text.width > 0 && text.getGlobalVisibleRect(visible))
                                    assertEquals("姓名、时间和地点的文字行完整显示", text.height, visible.height())
                                    assertEquals(1, text.layout.lineCount)
                                }
                                val clock = row.findViewById<TextView>(R.id.item_time)
                                assertEquals("时间段不截断", 0, clock.layout.getEllipsisCount(0))
                                assertTrue(clock.layout.getLineWidth(0) <= clock.width)
                            }
                        }
                    }
                    UiDevice.getInstance(instrumentation)
                        .takeScreenshot(
                            File(context.getExternalFilesDir(null), "widget-4x2-$height-$theme.png")
                        )
                }
            }
            File(context.getExternalFilesDir(null), "widget-4x2-measurements.txt")
                .writeText(measurements.toString())
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            scenario.close()
            context.getSharedPreferences("widgets", 0).edit().remove("schedule_$id").apply()
            runBlocking {
                store.delete(schedule.id)
                store.updatePreferences { before }
            }
        }
    }
}

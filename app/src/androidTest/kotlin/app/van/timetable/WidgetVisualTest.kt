package app.van.timetable

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.ListView
import android.widget.TextView
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
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetVisualTest {
    @Test
    fun referenceLayoutHasReadableHierarchyAndThemeAndTeacherAtFourByTwo() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val store = (context.applicationContext as VanApplication).store
        val today = LocalDate.now(ZoneId.of("Asia/Shanghai"))
        val courses =
            listOf("市场调查【讲授】", "形式与政策（五）", "习近平新时代中国特色社会主义思想", "门店销售与服务", "推销学").mapIndexed {
                index,
                name ->
                Course(
                    name = name,
                    customColor =
                        listOf(0xFF3385EDL, 0xFFDA69CBL, 0xFFFA793FL, 0xFFE6BD63L, 0xFF6CC399L)[
                            index],
                )
            }
        val s =
            Schedule(
                name = "宝三上",
                week1Monday =
                    today
                        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                        .minusWeeks(5)
                        .toString(),
                courses = courses,
                sessions =
                    courses.mapIndexed { index, c ->
                        CourseSession(
                            courseId = c.id,
                            mode = TimeMode.CLOCK,
                            day = (if (index == 0) today else today.plusDays(1)).dayOfWeek.value,
                            weeks =
                                setOf(
                                    CourseEngine.week(
                                        Schedule(
                                            week1Monday =
                                                today
                                                    .with(
                                                        TemporalAdjusters.previousOrSame(
                                                            DayOfWeek.MONDAY
                                                        )
                                                    )
                                                    .minusWeeks(5)
                                                    .toString()
                                        ),
                                        if (index == 0) today else today.plusDays(1),
                                    )
                                ),
                            start =
                                if (index == 0) "00:00"
                                else listOf("08:00", "09:50", "14:00", "15:50")[index - 1],
                            end =
                                if (index == 0) "23:59"
                                else listOf("09:35", "12:15", "15:35", "17:25")[index - 1],
                            place = if (index == 0) "6-301" else "6-203",
                            teacher = if (index == 0) "甲老师" else "乙老师",
                        )
                    },
            )
        val preferences = runBlocking { store.prefs() }
        runBlocking { store.save(s) }
        val scenario = ActivityScenario.launch(WidgetPreviewActivity::class.java)
        var id = 0
        try {
            instrumentation.uiAutomation.adoptShellPermissionIdentity(
                "android.permission.BIND_APPWIDGET"
            )
            scenario.onActivity {
                assertTrue(it.bind())
                id = it.widgetId
                it.resize(170)
            }
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            context
                .getSharedPreferences("widgets", 0)
                .edit()
                .putString("schedule_$id", s.id)
                .commit()
            for (theme in listOf("light", "dark")) {
                context
                    .getSharedPreferences("widgets", 0)
                    .edit()
                    .putString("theme_$id", theme)
                    .commit()
                runBlocking { Widgets.update(context, id) }
                var ready = false
                val deadline = System.currentTimeMillis() + 10000
                while (!ready && System.currentTimeMillis() < deadline) {
                    instrumentation.waitForIdleSync()
                    Thread.sleep(100)
                    scenario.onActivity {
                        val root = it.widgetView.findViewById<View>(R.id.widget_root)
                        val list = it.widgetView.findViewById<ListView>(R.id.tomorrow_list)
                        ready =
                            list?.adapter?.count == 4 &&
                                list.childCount >= 2 &&
                                (root.background as? GradientDrawable)?.color?.defaultColor ==
                                    Color.parseColor(if (theme == "light") "#F0EFF5" else "#202020")
                    }
                }
                assertTrue("真实小组件加载完成", ready)
                var bounds = IntArray(4)
                scenario.onActivity {
                    val root = it.widgetView.findViewById<View>(R.id.widget_root)
                    assertEquals(
                        "宝三上",
                        root.findViewById<TextView>(R.id.widget_name).text.toString(),
                    )
                    assertTrue(root.findViewById<TextView>(R.id.widget_date).text.contains("第6周"))
                    val list = root.findViewById<ListView>(R.id.tomorrow_list)
                    val row = list.getChildAt(0)
                    val name = row.findViewById<TextView>(R.id.item_name)
                    val place = row.findViewById<TextView>(R.id.item_place)
                    val time = row.findViewById<TextView>(R.id.item_time)
                    assertTrue(name.top < place.top && place.top < time.top)
                    assertFalse("课程名常规字重", name.typeface.isBold)
                    assertEquals("6-203  乙老师", place.text.toString())
                    assertEquals("08:00–09:35", time.text.toString())
                    assertEquals("时间不截断", 0, time.layout.getEllipsisCount(0))
                    assertTrue(time.layout.getLineWidth(0) <= time.width)
                    assertTrue(
                        "前两节课完整显示：row0=${row.height}, row1=${list.getChildAt(1).height}, bottom=${list.getChildAt(1).bottom}, viewport=${list.height}, name=${name.height}, place=${place.height}, time=${time.height}",
                        list.getChildAt(1).bottom <= list.height,
                    )
                    assertEquals(
                        Color.parseColor(if (theme == "light") "#252635" else "#ECECF3"),
                        name.currentTextColor,
                    )
                    val bar =
                        row.findViewById<View>(R.id.item_bar).background
                            as android.graphics.drawable.ColorDrawable
                    assertEquals(0xFFDA69CB.toInt(), bar.color)
                    val location = IntArray(2)
                    root.getLocationOnScreen(location)
                    bounds = intArrayOf(location[0], location[1], root.width, root.height)
                }
                // Crop pixels from the actual display screenshot, preserving the real widget.
                val screen = instrumentation.uiAutomation.takeScreenshot()
                val crop = Bitmap.createBitmap(screen, bounds[0], bounds[1], bounds[2], bounds[3])
                File(context.getExternalFilesDir(null), "022-widget-4x2-$theme.png")
                    .outputStream()
                    .use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
                crop.recycle()
                screen.recycle()
            }
            scenario.onActivity { it.resize(110) }
            runBlocking { Widgets.update(context, id) }
            var compactReady = false
            val deadline = System.currentTimeMillis() + 10000
            while (!compactReady && System.currentTimeMillis() < deadline) {
                instrumentation.waitForIdleSync()
                Thread.sleep(100)
                scenario.onActivity {
                    compactReady =
                        it.widgetView.findViewById<View>(R.id.widget_updated).visibility ==
                            View.GONE
                }
            }
            assertTrue("最矮尺寸优先保留课程区域", compactReady)
            scenario.onActivity {
                it.widgetView.findViewById<View>(R.id.widget_name).performClick()
            }
            val device = UiDevice.getInstance(instrumentation)
            assertTrue(
                "隐藏更新行后课表名仍能打开设置",
                device.findObject(UiSelector().text("桌面小组件")).waitForExists(10000),
            )
            device.pressBack()
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            scenario.close()
            context
                .getSharedPreferences("widgets", 0)
                .edit()
                .remove("schedule_$id")
                .remove("theme_$id")
                .commit()
            runBlocking {
                store.delete(s.id)
                store.updatePreferences { preferences }
            }
        }
    }
}

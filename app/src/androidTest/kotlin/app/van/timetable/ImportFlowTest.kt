// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.van.timetable.data.Demo
import app.van.timetable.domain.*
import app.van.timetable.school.schools
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImportFlowTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun seed(): Schedule {
        val s = Demo.schedule()
        runBlocking {
            val store = (ui.activity.application as VanApplication).store
            store.save(s)
            store.updatePreferences {
                it.copy(activeScheduleId = s.id, theme = "light", fontScale = 1f)
            }
        }
        ui.waitUntil(10000) { ui.onAllNodesWithText(s.name).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithContentDescription("导入课表").performClick()
        return s
    }

    @Test
    fun addressEntryHasNoSchoolListAndKeepsInputOnRecreation() {
        seed()
        ui.onNodeWithText("从教务系统获取").performClick()
        val store = (ui.activity.application as VanApplication).store
        for (theme in listOf("light", "dark")) {
            runBlocking { store.updatePreferences { it.copy(theme = theme) } }
            ui.waitForIdle()
            schools.forEach { ui.onNodeWithText(it.name).assertDoesNotExist() }
            ui.onNodeWithText("选择导入文件").assertIsDisplayed()
            UiDevice.getInstance(instrumentation)
                .takeScreenshot(
                    File(ui.activity.getExternalFilesDir(null), "import-entry-$theme.png")
                )
        }
        ui.onNode(hasSetTextAction() and hasText("教务系统地址"))
            .performTextReplacement("file:///sdcard/table")
        ui.onNodeWithText("打开教务系统").performClick()
        ui.onNodeWithText("请输入有效的 http 或 https 教务系统地址").assertIsDisplayed()
        val address = "jwx.hebiace.edu.cn/student?semester=2026#/table"
        ui.onNode(hasSetTextAction() and hasText("教务系统地址")).performTextReplacement(address)
        ui.activityRule.scenario.recreate()
        ui.onNode(hasSetTextAction() and hasText("教务系统地址")).assertTextContains(address)
        val launched = AtomicReference<Intent>()
        val monitor =
            object : Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                    if (intent.component?.className == "app.van.timetable.school.SchoolActivity") {
                        launched.set(intent)
                        return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                    }
                    return null
                }
            }
        instrumentation.addMonitor(monitor)
        try {
            ui.onNodeWithText("打开教务系统").performClick()
            ui.waitUntil(10000) { launched.get() != null }
            assertEquals("https://$address", launched.get().getStringExtra("url"))
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test
    fun hebiaceWebResultPreviewAndSavePreserveTheOldSchedule() {
        val result = HebiaceAdapterTest().currentPageFixtureResult()
        val old = seed()
        val store = (ui.activity.application as VanApplication).store
        val before = runBlocking { store.all().associateBy { it.id } }
        val previousPreferences = runBlocking { store.prefs() }
        val school = schools.first { it.id == "hebiace" }
        val monitor =
            object : Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? =
                    if (intent.component?.className == "app.van.timetable.school.SchoolActivity") {
                        assertEquals(school.url, intent.getStringExtra("url"))
                        Instrumentation.ActivityResult(
                            Activity.RESULT_OK,
                            Intent().putExtra("result", result).putExtra("schoolName", school.name),
                        )
                    } else null
            }
        instrumentation.addMonitor(monitor)
        try {
            ui.onNodeWithText("从教务系统获取").performClick()
            ui.onNode(hasSetTextAction() and hasText("教务系统地址")).performTextReplacement(school.url)
            ui.onNodeWithText("打开教务系统").performClick()
            ui.waitUntil(10000) { ui.onAllNodesWithText("导入预览").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("已读取 12 门课程 / 35 个时段").assertIsDisplayed()
            for (theme in listOf("light", "dark")) {
                runBlocking { store.updatePreferences { it.copy(theme = theme) } }
                ui.waitForIdle()
                ui.onNodeWithText("教务学期：2026-2027学年 · 第1学期", substring = true)
                    .performScrollTo()
                    .assertIsDisplayed()
                UiDevice.getInstance(instrumentation)
                    .takeScreenshot(
                        File(
                            ui.activity.getExternalFilesDir(null),
                            "hebiace-current-term-$theme.png",
                        )
                    )
                ui.onAllNodesWithText("老师：甲老师 · 地点：朝阳校区 A414")
                    .onFirst()
                    .performScrollTo()
                    .assertIsDisplayed()
                ui.onAllNodesWithText("第3、4、6、7、8周").onFirst().performScrollTo().assertIsDisplayed()
                ui.onAllNodesWithText("老师：甲老师 · 地点：朝阳校区 A414").onFirst().assertIsDisplayed()
                UiDevice.getInstance(instrumentation)
                    .takeScreenshot(
                        File(
                            ui.activity.getExternalFilesDir(null),
                            "hebiace-current-preview-$theme.png",
                        )
                    )
                ui.onNodeWithText("老师：壬老师 · 地点：未安排").performScrollTo().assertIsDisplayed()
                ui.onNodeWithText("第1、2周").performScrollTo().assertIsDisplayed()
                ui.onNodeWithText("认识实习").assertIsDisplayed()
                ui.onAllNodesWithText("无固定上课时间").assertCountEquals(9)
                ui.onNodeWithText("整周 08:00–09:40").assertDoesNotExist()
                UiDevice.getInstance(instrumentation)
                    .takeScreenshot(
                        File(
                            ui.activity.getExternalFilesDir(null),
                            "hebiace-current-practice-$theme.png",
                        )
                    )
            }
            ui.onNode(isToggleable()).performScrollTo().assertIsOff()
            ui.onNodeWithText("保存为新课表").performScrollTo().performClick()
            ui.waitUntil(10000) {
                runBlocking { store.all().any { it.school == school.name && it.id !in before } }
            }
            runBlocking {
                val all = store.all()
                val saved = all.single { it.school == school.name && it.id !in before }
                assertEquals(old.id, store.prefs().activeScheduleId)
                before.forEach { (id, schedule) ->
                    assertEquals(schedule, all.first { it.id == id })
                }
                assertEquals(12, saved.courses.size)
                assertEquals(26, saved.sessions.count { it.mode == TimeMode.SECTIONS })
                assertEquals(9, saved.sessions.count { it.mode == TimeMode.UNTIMED })
                val slot = saved.sessions.first()
                assertEquals("大数据技术", saved.courses.first().name)
                assertEquals("甲老师", slot.teacher)
                assertEquals("朝阳校区 A414", slot.place)
                assertEquals(setOf(3, 4, 6, 7, 8), slot.weeks)
                assertEquals(1, slot.day)
                assertEquals(1, slot.startSection)
                assertEquals(2, slot.endSection)
                val practice = saved.courses.single { it.name == "认识实习" }
                val practiceSlot = saved.sessions.single { it.courseId == practice.id }
                assertEquals(TimeMode.UNTIMED, practiceSlot.mode)
                assertEquals(setOf(1, 2), practiceSlot.weeks)
                assertEquals("壬老师", practiceSlot.teacher)
                assertEquals(
                    saved,
                    Codec.restore(store.backup()).schedules.first { it.id == saved.id },
                )
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            runBlocking {
                store
                    .all()
                    .filter { it.school == school.name && it.id !in before }
                    .forEach { store.delete(it.id) }
                store.updatePreferences { previousPreferences }
            }
        }
    }

    @Test
    fun wakeupFileSelectionPreviewSaveAndActivationPreserveTheOldSchedule() {
        val old = seed()
        val store = (ui.activity.application as VanApplication).store
        val before = runBlocking { store.all().associateBy { it.id } }
        val context = ui.activity
        val file = File(context.cacheDir, "exports/wakeup-flow.wakeup_schedule")
        file.parentFile!!.mkdirs()
        file.writeText(
            """{"id":7,"name":"验证作息"}
[{"timeTable":99,"node":1,"startTime":"8:0","endTime":"8:45"},{"timeTable":99,"node":2,"startTime":"8:55","endTime":"9:40"}]
{"id":8,"timeTable":0,"nodes":2,"startDate":"2026-9-7","maxWeek":20,"tableName":"WakeUp界面验证"}
[{"id":41,"tableId":8,"courseName":"备份导入验证"}]
[{"id":41,"day":1,"startNode":1,"step":2,"startWeek":1,"endWeek":20,"type":0,"teacher":"张老师","room":"立德楼 A414"}]"""
        )
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val monitor =
            object : Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? =
                    if (intent.action == Intent.ACTION_OPEN_DOCUMENT)
                        Instrumentation.ActivityResult(
                            Activity.RESULT_OK,
                            Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        )
                    else null
            }
        instrumentation.addMonitor(monitor)
        try {
            // Choose a local URI through the same activity-result callback as the system picker.
            ui.onNodeWithText("从教务系统获取").performClick()
            ui.onNodeWithText("选择导入文件").performScrollTo().performClick()
            ui.waitUntil(10000) { ui.onAllNodesWithText("导入预览").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("老师：张老师 · 地点：立德楼 A414").performScrollTo().assertIsDisplayed()
            ui.onNodeWithText("作息关联ID不一致").assertDoesNotExist()
            ui.onNode(isToggleable()).performScrollTo().assertIsOff().performClick().assertIsOn()
            ui.onNodeWithText("保存为新课表").performScrollTo().performClick()
            // Prior runs may leave another schedule with the same name. Identify this import by
            // its new ID and wait for both Room save and the subsequent DataStore activation.
            ui.waitUntil(10000) {
                runBlocking {
                    val saved =
                        store.all().singleOrNull {
                            it.name == "WakeUp界面验证" && it.id !in before
                        }
                    saved != null && store.prefs().activeScheduleId == saved.id
                }
            }
            runBlocking {
                val all = store.all()
                val saved = all.single { it.name == "WakeUp界面验证" && it.id !in before }
                assertEquals(saved.id, store.prefs().activeScheduleId)
                assertEquals(old, all.first { it.id == old.id })
                before.forEach { (id, schedule) ->
                    assertEquals(
                        "Import must preserve existing schedule $id",
                        schedule,
                        all.first { it.id == id },
                    )
                }
                assertEquals("张老师", saved.sessions.single().teacher)
                assertEquals("立德楼 A414", saved.sessions.single().place)
                assertEquals("08:00", saved.routines.single().sections.first().start)
                assertEquals(20, saved.sessions.single().weeks.size)
                assertEquals(
                    saved,
                    Codec.restore(store.backup()).schedules.first { it.id == saved.id },
                )
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            file.delete()
        }
    }
}

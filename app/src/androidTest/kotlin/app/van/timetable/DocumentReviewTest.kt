// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.van.timetable.data.Demo
import app.van.timetable.domain.*
import app.van.timetable.system.ExcelReader
import java.io.File
import jxl.Workbook
import jxl.write.Label
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentReviewTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val switch =
        isToggleable() and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)

    private fun reviewNode(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        ui.onNode(hasScrollToNodeAction()).performScrollToNode(matcher)
        return ui.onNode(matcher).performScrollTo()
    }

    private fun reviewText(text: String) = reviewNode(hasText(text))

    @Test
    fun excelPickerReviewEditAndSaveSurviveRecreationAndKeepTheActiveSchedule() {
        val old = Demo.schedule()
        val store = (ui.activity.application as VanApplication).store
        runBlocking {
            store.save(old)
            store.updatePreferences {
                it.copy(activeScheduleId = old.id, theme = "light", fontScale = 1f)
            }
        }
        ui.waitUntil(10_000) { ui.onAllNodesWithText(old.name).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithContentDescription("导入课表").performClick()

        val name = "Excel界面验证-${System.nanoTime()}"
        val file = File(ui.activity.cacheDir, "exports/$name.xls")
        file.parentFile!!.mkdirs()
        // Original seven-column BIFF workbook generated locally, without student data.
        val workbook = Workbook.createWorkbook(file)
        try {
            val sheet = workbook.createSheet("个人课表", 0)
            val table =
                listOf(
                    listOf("课程名称", "星期", "开始节数", "结束节数", "老师", "地点", "周数"),
                    listOf("示例课程甲", "1", "1", "2", "张老师", "示例楼 A414", "3-4、6-8"),
                    listOf("示例课程乙", "7", "3", "4", "李老师", "示例楼 A214", "1-16"),
                )
            table.forEachIndexed { row, cells ->
                cells.forEachIndexed { column, text -> sheet.addCell(Label(column, row, text)) }
            }
            val other = workbook.createSheet("其他课表", 1)
            listOf(
                    table.first(),
                    listOf("示例课程丙", "2", "5", "6", "王老师", "示例楼 A314", "1-16"),
                )
                .forEachIndexed { row, cells ->
                    cells.forEachIndexed { column, text -> other.addCell(Label(column, row, text)) }
                }
            workbook.write()
        } finally {
            workbook.close()
        }
        // Keep exact source metadata checks in addition to the rendered and persisted values.
        val rawSheet = ExcelReader.read(file.readBytes()).first()
        assertEquals("张老师", rawSheet.cells.single { it.row == 1 && it.column == 4 }.text)
        assertEquals("示例楼 A414", rawSheet.cells.single { it.row == 1 && it.column == 5 }.text)
        val draft = DocumentImport.fromSheet(rawSheet)
        assertEquals("张老师", draft.rows.first().teacher)
        assertEquals("示例楼 A414", draft.rows.first().place)
        val context = ui.activity
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val monitor =
            object : Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? =
                    if (intent.action == Intent.ACTION_OPEN_DOCUMENT) {
                        Instrumentation.ActivityResult(
                            Activity.RESULT_OK,
                            Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        )
                    } else null
            }
        instrumentation.addMonitor(monitor)
        try {
            ui.onNodeWithText("选择本地课表文件").performScrollTo().performClick()
            ui.waitUntil(15_000) {
                ui.onAllNodesWithText("识别结果").fetchSemanticsNodes().isNotEmpty()
            }
            ui.waitUntil(10_000) {
                ui.onAllNodes(
                        SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
                    )
                    .fetchSemanticsNodes()
                    .isEmpty()
            }
            reviewText("核对识别结果").assertIsDisplayed()
            reviewText("示例课程甲").assertIsDisplayed()
            reviewText("张老师 · 示例楼 A414").assertIsDisplayed()
            reviewText("示例课程乙").assertIsDisplayed()
            reviewText("李老师 · 示例楼 A214").assertIsDisplayed()
            reviewText("继续核对学期与作息").assertIsNotEnabled()
            for (theme in listOf("light", "dark")) {
                runBlocking { store.updatePreferences { it.copy(theme = theme) } }
                reviewText("核对识别结果").assertIsDisplayed()
                ui.waitForIdle()
                assertTrue(
                    UiDevice.getInstance(instrumentation)
                        .takeScreenshot(
                            File(
                                ui.activity.getExternalFilesDir(null),
                                "document-review-$theme.png",
                            )
                        )
                )
            }

            reviewText("示例课程甲").performClick()
            reviewNode(hasSetTextAction() and hasText("地点")).performTextReplacement("示例楼 A416")
            ui.activityRule.scenario.recreate()
            reviewNode(hasSetTextAction() and hasText("地点")).assertTextContains("示例楼 A416")
            reviewNode(hasSetTextAction() and hasText("老师")).assertTextContains("张老师")
            reviewNode(hasText("示例课程甲") and !hasSetTextAction()).performClick()
            reviewText("张老师 · 示例楼 A416").assertIsDisplayed()
            // Each worksheet keeps its own edits when switching away and back.
            reviewText("其他课表").performClick()
            reviewText("示例课程丙").performClick()
            reviewNode(hasSetTextAction() and hasText("地点")).performTextReplacement("其他楼 B316")
            reviewNode(hasText("示例课程丙") and !hasSetTextAction()).performClick()
            reviewText("个人课表").performClick()
            reviewText("张老师 · 示例楼 A416").assertIsDisplayed()
            ui.onNodeWithText("示例课程丙").assertDoesNotExist()
            reviewText("其他课表").performClick()
            reviewText("王老师 · 其他楼 B316").assertIsDisplayed()
            reviewText("个人课表").performClick()
            reviewText("张老师 · 示例楼 A416").assertIsDisplayed()
            // A freshly selected file must not inherit the prior file's selected sheet or edits.
            reviewText("其他课表").performClick()
            ui.onNodeWithContentDescription("返回").performClick()
            ui.onNodeWithText("选择本地课表文件").performScrollTo().performClick()
            ui.waitUntil(15_000) {
                ui.onAllNodesWithText("识别结果").fetchSemanticsNodes().isNotEmpty()
            }
            reviewText("张老师 · 示例楼 A414").assertIsDisplayed()
            ui.onNodeWithText("示例课程丙").assertDoesNotExist()
            reviewText("示例课程甲").performClick()
            reviewNode(hasSetTextAction() and hasText("地点")).performTextReplacement("示例楼 A416")
            reviewNode(hasText("示例课程甲") and !hasSetTextAction()).performClick()
            reviewNode(switch).assertIsOff().performClick()
            reviewText("继续核对学期与作息").performClick()
            ui.waitUntil(10_000) {
                ui.onAllNodesWithText("导入预览").fetchSemanticsNodes().isNotEmpty()
            }
            ui.onNodeWithText("老师：张老师 · 地点：示例楼 A416").performScrollTo().assertIsDisplayed()
            ui.onNode(switch).performScrollTo().assertIsOff()
            ui.onNodeWithText("保存为新课表").performScrollTo().performClick()
            ui.waitUntil(10_000) { runBlocking { store.all().any { it.name == name } } }
            runBlocking {
                val saved = store.all().first { it.name == name }
                assertEquals(old.id, store.prefs().activeScheduleId)
                assertEquals(old, store.all().first { it.id == old.id })
                assertEquals(2, saved.courses.size)
                val course = saved.courses.single { it.name == "示例课程甲" }
                val session = saved.sessions.single { it.courseId == course.id }
                assertEquals("张老师", session.teacher)
                assertEquals("示例楼 A416", session.place)
                assertEquals(1, session.day)
                assertEquals(1, session.startSection)
                assertEquals(2, session.endSection)
                assertEquals(setOf(3, 4, 6, 7, 8), session.weeks)
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

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
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Optional real-file UI checks. Supply sample-0.pdf .. sample-2.pdf in externalFilesDir. */
@RunWith(AndroidJUnit4::class)
class LocalPdfFlowTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val switch =
        isToggleable() and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)

    private fun review(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        ui.onNode(hasScrollToNodeAction()).performScrollToNode(matcher)
        return ui.onNode(matcher).performScrollTo()
    }

    @Test fun firstRealPdfPreservesBlankWeekCoursesThroughReviewAndSave() = verify(0)

    @Test fun secondRealPdfPreservesMetadataAndOtherCoursesThroughReviewAndSave() = verify(1)

    @Test fun thirdRealPdfPreservesMixedParityAndPracticeThroughReviewAndSave() = verify(2)

    private fun verify(index: Int) {
        val activity = ui.activity
        val source = File(activity.getExternalFilesDir(null), "sample-$index.pdf")
        assumeTrue(source.exists())
        val copy = File(activity.cacheDir, "exports/pdf-check-$index.pdf")
        copy.parentFile!!.mkdirs()
        source.copyTo(copy, overwrite = true)
        val store = (activity.application as VanApplication).store
        val previous = runBlocking { store.prefs() }
        val old = Importers.freshCopy(Demo.schedule(), "PDF校验原课表")
        runBlocking {
            store.save(old)
            store.updatePreferences {
                it.copy(activeScheduleId = old.id, theme = "light", fontScale = 1f)
            }
        }
        val before = runBlocking { store.all().associateBy { it.id } }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", copy)
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
            ui.waitUntil(10_000) {
                ui.onAllNodesWithText(old.name).fetchSemanticsNodes().isNotEmpty()
            }
            ui.onNodeWithContentDescription("导入课表").performClick()
            ui.onNodeWithText("选择本地课表文件").performScrollTo().performClick()
            ui.waitUntil(30_000) {
                ui.onAllNodesWithText("识别结果").fetchSemanticsNodes().isNotEmpty()
            }
            val expectedRows = listOf(25, 20, 35)[index]
            review(hasText("PDF · ${expectedRows}个课程时段", substring = true)).assertIsDisplayed()
            assertTrue(
                UiDevice.getInstance(instrumentation)
                    .takeScreenshot(
                        File(activity.getExternalFilesDir(null), "pdf-check-$index-review.png")
                    )
            )
            val criticalWeek =
                listOf(
                    "星期1 · 7–8节 · 1-15单",
                    "星期4 · 9–10节 · 2-4,6-10",
                    "星期3 · 9–10节 · 4-6双,7-12,16",
                )[index]
            review(hasText(criticalWeek)).assertIsDisplayed()
            val pendingName = listOf("IT企业实习", "网络程序设计实训", "数据库原理课程设计")[index]
            review(hasText(pendingName)).assertIsDisplayed()
            review(switch).assertIsOff().performClick()
            review(hasText("继续核对学期与作息")).assertIsEnabled().performClick()
            ui.waitUntil(10_000) {
                ui.onAllNodesWithText("导入预览").fetchSemanticsNodes().isNotEmpty()
            }
            val courses = listOf(16, 8, 12)[index]
            val sessions = listOf(21, 20, 35)[index]
            ui.onNodeWithText("已读取 $courses 门课程 / $sessions 个时段").assertIsDisplayed()
            ui.onNodeWithText(pendingName).performScrollTo().assertIsDisplayed()
            ui.onAllNodesWithText("无固定上课时间").assertCountEquals(listOf(1, 7, 9)[index])
            ui.onNodeWithText("整周 08:00–09:40").assertDoesNotExist()
            assertTrue(
                UiDevice.getInstance(instrumentation)
                    .takeScreenshot(
                        File(activity.getExternalFilesDir(null), "pdf-check-$index-preview.png")
                    )
            )
            ui.onNode(hasSetTextAction() and hasText("第1周周一 YYYY-MM-DD"))
                .performScrollTo()
                .performTextReplacement("2026-08-31")
            ui.onNode(switch).performScrollTo().assertIsOff()
            ui.onNodeWithText("保存为新课表").performScrollTo().performClick()
            ui.waitUntil(10_000) { runBlocking { store.all().any { it.id !in before } } }
            runBlocking {
                val all = store.all()
                val saved = all.single { it.id !in before }
                assertEquals(old.id, store.prefs().activeScheduleId)
                before.forEach { (id, value) -> assertEquals(value, all.first { it.id == id }) }
                assertEquals(courses, saved.courses.size)
                assertEquals(
                    listOf(20, 13, 26)[index],
                    saved.sessions.count { it.mode == TimeMode.SECTIONS },
                )
                assertEquals(
                    listOf(1, 7, 9)[index],
                    saved.sessions.count { it.mode == TimeMode.UNTIMED },
                )
                assertEquals("2026-08-31", saved.week1Monday)
                assertEquals(
                    saved,
                    Codec.restore(store.backup()).schedules.first { it.id == saved.id },
                )
                when (index) {
                    0 -> {
                        val probability = saved.courses.single { it.name == "概率论与数理统计" }
                        assertEquals(
                            (1..15 step 2).toSet(),
                            saved.sessions
                                .single {
                                    it.courseId == probability.id &&
                                        it.day == 1 &&
                                        it.startSection == 7
                                }
                                .weeks,
                        )
                        for (name in listOf("思想政治理论实践课", "科技论文写作与科研创新实践", "操作系统课程设计", "IT企业实习")) {
                            val course = saved.courses.single { it.name == name }
                            assertTrue(course.note.contains("老师："))
                            assertTrue(saved.sessions.none { it.courseId == course.id })
                        }
                        assertEquals(12, saved.routines.first().sections.size)
                    }
                    1 -> {
                        val image = saved.courses.single { it.name == "数字图像处理" }
                        val slot = saved.sessions.single { it.courseId == image.id && it.day == 4 }
                        assertEquals("郭晓峰", slot.teacher)
                        assertEquals("信息科技大楼北-301", slot.place)
                        assertEquals(setOf(2, 3, 4, 6, 7, 8, 9, 10), slot.weeks)
                    }
                    2 -> {
                        val database = saved.courses.single { it.name == "数据库原理" }
                        val slot =
                            saved.sessions.single {
                                it.courseId == database.id && it.day == 3 && it.startSection == 9
                            }
                        assertEquals(setOf(4, 6, 7, 8, 9, 10, 11, 12, 16), slot.weeks)
                        assertEquals("B512", slot.place)
                        val practice = saved.courses.single { it.name == "数据库原理课程设计" }
                        assertEquals(
                            setOf(18),
                            saved.sessions.single { it.courseId == practice.id }.weeks,
                        )
                    }
                }
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            copy.delete()
            runBlocking {
                store.all().filter { it.id !in before }.forEach { store.delete(it.id) }
                store.delete(old.id)
                store.updatePreferences { previous }
            }
        }
    }
}

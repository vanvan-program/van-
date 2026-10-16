// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.van.timetable.data.*
import app.van.timetable.domain.*
import app.van.timetable.widget.Widgets
import java.io.File
import java.time.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalSmokeTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private fun seed(): Schedule {
        val s = Demo.schedule()
        runBlocking {
            val store = (ui.activity.application as VanApplication).store
            store.save(s)
            store.updatePreferences { it.copy(activeScheduleId = s.id, theme = "light") }
        }
        ui.waitUntil(10000) { ui.onAllNodesWithText(s.name).fetchSemanticsNodes().isNotEmpty() }
        return s
    }

    @Test
    fun wholeWeekAndEveningCourseAreVisibleInBothThemes() {
        val demo = Demo.schedule()
        val evening = Course(name = "晚间验证课程", color = 2)
        val s =
            demo.copy(
                courses = demo.courses + evening,
                sessions =
                    demo.sessions +
                        CourseSession(
                            courseId = evening.id,
                            day = 7,
                            startSection = 9,
                            endSection = 10,
                            teacher = "验证老师",
                            place = "示例楼 B512",
                        ),
            )
        val store = (ui.activity.application as VanApplication).store
        runBlocking {
            store.save(s)
            store.updatePreferences {
                it.copy(
                    activeScheduleId = s.id,
                    theme = "light",
                    fontScale = 1f,
                    fitWeek = true,
                    rowHeight = 56,
                )
            }
        }
        ui.waitUntil(10000) { ui.onAllNodesWithText("晚间验证课程").fetchSemanticsNodes().isNotEmpty() }
        for (theme in listOf("light", "dark")) {
            runBlocking { store.updatePreferences { it.copy(theme = theme) } }
            ui.waitForIdle()
            val viewport = ui.onNodeWithTag("week-viewport").fetchSemanticsNode().boundsInRoot
            for (number in 1..10) {
                ui.onNodeWithTag("section-$number").assertIsDisplayed()
                val row = ui.onNodeWithTag("section-$number").fetchSemanticsNode().boundsInRoot
                assertTrue(
                    "Section $number extends below viewport",
                    row.bottom <= viewport.bottom + 1f,
                )
            }
            ui.onNodeWithText("晚间验证课程").assertIsDisplayed()
            ui.onNodeWithText("示例楼 B512", useUnmergedTree = true).assertIsDisplayed()
            ui.onNodeWithText("日", useUnmergedTree = true).assertIsDisplayed()
            ui.onNodeWithText("20:40", useUnmergedTree = true).assertIsDisplayed()
            val card = ui.onNodeWithText("晚间验证课程").fetchSemanticsNode().boundsInRoot
            assertTrue(card.bottom <= viewport.bottom + 1f)
            val bar =
                ui.onNodeWithTag("next-class").fetchSemanticsNode().layoutInfo.coordinates.size
            assertTrue(
                "Next-class bar exceeds 32dp",
                bar.height / ui.activity.resources.displayMetrics.density <= 32f,
            )
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                .takeScreenshot(File(ui.activity.getExternalFilesDir(null), "compact-$theme.png"))
        }
    }

    @Test
    fun navigateAndChangeTheme() {
        seed()
        ui.onNodeWithText("课程", useUnmergedTree = true).performClick()
        ui.onNodeWithText("搜索课程、老师、地点").assertExists()
        ui.onNodeWithText("我的", useUnmergedTree = true).performClick()
        ui.onNodeWithText("外观设置").performClick()
        ui.onNodeWithText("深色").performClick()
        ui.waitUntil(10000) {
            runBlocking {
                (ui.activity.application as VanApplication).store.prefs().theme == "dark"
            }
        }
        ui.waitForIdle()
        ui.onNodeWithText("深色").assertIsSelected()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        UiDevice.getInstance(instrumentation)
            .takeScreenshot(File(ui.activity.getExternalFilesDir(null), "dark-settings.png"))
        ui.onNodeWithContentDescription("返回").performClick()
        ui.onNodeWithText("课表", useUnmergedTree = true).performClick()
        ui.waitForIdle()
        UiDevice.getInstance(instrumentation)
            .takeScreenshot(File(ui.activity.getExternalFilesDir(null), "dark-timetable.png"))
        ui.onNodeWithText("我的", useUnmergedTree = true).performClick()
        ui.onNodeWithText("提醒与小米手环").performScrollTo().performClick()
        ui.onNodeWithText("课前提醒").assertExists()
    }

    @Test
    fun editCourseAndKeepSlotsAfterRecreation() {
        val s = seed()
        ui.onNodeWithText("课程", useUnmergedTree = true).performClick()
        ui.onNodeWithText("示例课程甲").performClick()
        ui.onNodeWithText("编辑整门课程").performScrollTo().performClick()
        ui.onNode(hasSetTextAction() and hasText("课程名称")).performTextReplacement("编辑验证课程")
        ui.activityRule.scenario.recreate()
        ui.onNode(hasSetTextAction() and hasText("课程名称")).assertTextContains("编辑验证课程")
        ui.onNodeWithText("保存课程").performScrollTo().performClick()
        ui.waitUntil(10000) {
            runBlocking {
                (ui.activity.application as VanApplication)
                    .store
                    .all()
                    .first { it.id == s.id }
                    .courses
                    .any { it.name == "编辑验证课程" }
            }
        }
        runBlocking {
            val actual =
                (ui.activity.application as VanApplication).store.all().first { it.id == s.id }
            assertEquals(s.sessions.sortedBy { it.id }, actual.sessions.sortedBy { it.id })
        }
    }

    @Test
    fun persistenceAndRestoreRoundTrip() {
        val s = seed()
        val store = (ui.activity.application as VanApplication).store
        runBlocking {
            val backup = Codec.restore(store.backup())
            store.save(s.copy(name = "修改后的课表"))
            store.restore(backup)
            assertEquals(s, store.all().first { it.id == s.id })
            assertTrue(ui.activity.getFileStreamPath("before-restore.van.json").exists())
        }
    }

    @Test
    fun widgetItemUsesRealNativeViews() {
        val s = seed()
        val date = LocalDate.parse(s.week1Monday)
        val item = CourseEngine.resolveDay(s, date).first()
        val view = Widgets.item(ui.activity, item, Instant.now(), ZoneId.of(s.zone))
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val actual = view.apply(ui.activity, android.widget.FrameLayout(ui.activity))
            assertEquals(
                item.course.name,
                actual.findViewById<android.widget.TextView>(R.id.item_name).text.toString(),
            )
            assertTrue(
                actual
                    .findViewById<android.widget.TextView>(R.id.item_time)
                    .text
                    .contains(item.start.toString())
            )
        }
    }

    @Test
    fun invalidDataNeverReplacesExisting() {
        val s = seed()
        val store = (ui.activity.application as VanApplication).store
        runBlocking {
            val before = store.all()
            val bad = s.copy(sessions = s.sessions.map { it.copy(weeks = setOf(999)) })
            assertTrue(runCatching { store.save(bad) }.isFailure)
            assertEquals(before, store.all())
        }
    }
}

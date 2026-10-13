// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.van.timetable.domain.*
import app.van.timetable.system.Files
import app.van.timetable.ui.Timetable
import app.van.timetable.ui.VanTheme
import app.van.timetable.ui.weekdays
import java.io.File
import java.time.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Version023UiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val store get() = (ui.activity.application as VanApplication).store
    private var previousBackup: Backup? = null
    private val fixedNow = Instant.parse("2026-10-13T04:00:00Z")
    private val fixedToday = LocalDate.of(2026, 10, 13)
    private val appearance = Preferences(theme = "light", showInactive = false)

    @After
    fun cleanup() {
        previousBackup?.let { backup -> runBlocking { store.restore(backup) } }
    }

    private fun render(schedule: Schedule, preferences: Preferences = appearance) {
        ui.runOnUiThread {
            ui.activity.setContent {
                VanTheme(preferences) { Timetable(schedule, preferences, fixedNow, {}) }
            }
        }
        ui.waitForIdle()
    }

    private fun chooseWeek(week: Int) {
        ui.onNodeWithTag("week-selector").performClick()
        ui.onNodeWithText("第${week}周").performClick()
        ui.onNodeWithText("第${week}周 ▾").assertExists()
    }

    private fun switchMode(text: String) {
        ui.onNodeWithContentDescription("课表菜单").performClick()
        ui.onNodeWithText(text).performClick()
    }

    private fun weeklySchedule() = Schedule(
        name = "0.2.3 返回与布局验证",
        week1Monday = "2026-10-12",
        maxWeeks = 3,
    )

    private fun screenshot(name: String) {
        ui.waitForIdle()
        UiDevice.getInstance(instrumentation)
            .takeScreenshot(File(ui.activity.getExternalFilesDir(null), name))
    }

    @Test
    fun separateRightEdgeButtonReturnsToCurrentWeekAndToday() {
        render(weeklySchedule())
        ui.onNodeWithTag("jump-today").assertDoesNotExist()
        chooseWeek(3)
        val button = ui.onNodeWithTag("jump-today").assertIsDisplayed()
        button.assert(hasContentDescription("回到本周"))
        val buttonBounds = button.fetchSemanticsNode().boundsInRoot
        val viewport = ui.onNodeWithTag("week-viewport").fetchSemanticsNode().boundsInRoot
        assertTrue("Return button should sit along the right edge", buttonBounds.left > viewport.center.x)
        assertTrue("Return button should be beside the grid", buttonBounds.top > viewport.top)
        screenshot("023-return-current-week.png")
        button.performClick()
        ui.onNodeWithText("第1周 ▾").assertExists()
        ui.onNodeWithTag("jump-today").assertDoesNotExist()

        switchMode("切换为日列表")
        ui.onNodeWithText("$fixedToday ${weekdays[fixedToday.dayOfWeek.value - 1]}").assertExists()
        ui.onNodeWithTag("jump-today").assertDoesNotExist()
        ui.onNodeWithText("后一天").performClick()
        ui.onNodeWithTag("jump-today")
            .assertIsDisplayed()
            .assert(hasContentDescription("回到今日"))
            .performClick()
        ui.onNodeWithText("$fixedToday ${weekdays[fixedToday.dayOfWeek.value - 1]}").assertExists()
        ui.onNodeWithTag("jump-today").assertDoesNotExist()
    }

    @Test
    fun adjustedWeekNotesKeepViewportAndSectionPositionsStable() {
        val schedule = weeklySchedule().copy(
            dayOverrides = listOf(
                DayOverride("2026-10-19", "2026-10-12"),
                DayOverride("2026-10-21", cancelled = true),
                DayOverride("2026-10-26", "2026-10-13"),
            ),
            holidays = listOf(Holiday(name = "多条调课说明布局验证", from = "2026-10-19", through = "2026-10-20")),
        )
        render(schedule)
        val viewport = ui.onNodeWithTag("week-viewport").fetchSemanticsNode().boundsInRoot
        val notes = ui.onNodeWithTag("week-notes").fetchSemanticsNode().boundsInRoot
        val firstSection = section(1, 1).fetchSemanticsNode().boundsInRoot
        val lastSection = section(1, 10).fetchSemanticsNode().boundsInRoot
        assertTrue("Empty notes region must reserve space", notes.height > 0f)
        for (week in listOf(2, 3, 1)) {
            chooseWeek(week)
            assertBoundsEqual(viewport, ui.onNodeWithTag("week-viewport").fetchSemanticsNode().boundsInRoot)
            assertBoundsEqual(notes, ui.onNodeWithTag("week-notes").fetchSemanticsNode().boundsInRoot)
            assertBoundsEqual(firstSection, section(week, 1).fetchSemanticsNode().boundsInRoot)
            assertBoundsEqual(lastSection, section(week, 10).fetchSemanticsNode().boundsInRoot)
            if (week == 2) screenshot("023-notes-fixed-grid.png")
        }
    }

    private fun section(week: Int, number: Int) = ui.onNode(
        hasTestTag("section-$number") and hasAnyAncestor(hasTestTag("week-grid-$week")),
        useUnmergedTree = true,
    )

    private fun assertBoundsEqual(expected: Rect, actual: Rect) {
        assertEquals("Left edge", expected.left, actual.left, 1f)
        assertEquals("Top edge", expected.top, actual.top, 1f)
        assertEquals("Width", expected.width, actual.width, 1f)
        assertEquals("Height", expected.height, actual.height, 1f)
    }

    private fun originalBytes(): ByteArray {
        // Keep the user's private original outside source control and APK assets. The runner
        // provides this unmodified file using adb push before executing this test.
        val path = InstrumentationRegistry.getArguments().getString("originalBackupPath")
            ?: "/sdcard/Download/van-user-original.van.json"
        require(path.matches(Regex("^/[A-Za-z0-9_./-]+$"))) { "Original backup path must be an absolute ASCII path" }
        val bytes = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("cat $path")
        ).use { it.readBytes() }
        assertTrue("Push the user's original van backup to $path before running Version023UiTest", bytes.isNotEmpty())
        return bytes
    }

    @Test
    fun originalVanFilePreviewSaveRecreationAndCompleteRestoreKeepCustomTimes() {
        previousBackup = runBlocking { Backup(schedules = store.all(), preferences = store.prefs()) }
        val bytes = originalBytes()
        val original = Files.parse(Files.decode(bytes)).first
        assertEquals("Both schedules from the original file must be parsed", 2, original.schedules.size)
        val expected = original.schedules.single { it.id == original.preferences!!.activeScheduleId }
        assertEquals("三上", expected.name)
        assertEquals(9, expected.courses.size)
        assertEquals(21, expected.sessions.size)

        val old = Schedule(name = "原文件导入前的旧课表", week1Monday = "2026-10-12")
        runBlocking {
            store.save(old)
            store.updatePreferences { appearance.copy(activeScheduleId = old.id) }
        }
        ui.waitUntil(10000) { ui.onAllNodesWithText(old.name).fetchSemanticsNodes().isNotEmpty() }
        val beforeImport = runBlocking { store.all().associateBy { it.id } }
        val file = File(ui.activity.cacheDir, "exports/user-original.van.json")
        file.parentFile!!.mkdirs()
        file.writeBytes(bytes)
        assertArrayEquals(bytes, file.readBytes())
        val uri = FileProvider.getUriForFile(ui.activity, "${ui.activity.packageName}.files", file)
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? =
                if (intent.action == Intent.ACTION_OPEN_DOCUMENT) Instrumentation.ActivityResult(
                    Activity.RESULT_OK,
                    Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                ) else null
        }
        instrumentation.addMonitor(monitor)
        try {
            openOriginalPreview()
            ui.onNodeWithText("已读取 9 门课程 / 21 个时段").assertIsDisplayed()
            ui.onNode(hasSetTextAction() and hasText("作息（节次,开始时间,结束时间）"))
                .performScrollTo()
                .assertTextContains("1,08:10,08:55", substring = true)
                .assertTextContains("3,10:30,11:15", substring = true)
                .assertTextContains("4,11:25,12:10", substring = true)
            ui.onNodeWithText("周五 10:20–12:00").performScrollTo().assertIsDisplayed()
            ui.onNode(isToggleable()).performScrollTo().assertIsOff().performClick()
            ui.onNodeWithText("保存为新课表").performScrollTo().performClick()
            ui.waitUntil(10000) {
                runBlocking { store.active()?.let { it.name == expected.name && it.id !in beforeImport } == true }
            }
            val saved = runBlocking { store.active()!! }
            assertEquals(normalized(expected), normalized(saved))
            runBlocking {
                val all = store.all().associateBy { it.id }
                beforeImport.forEach { (id, value) -> assertEquals("Preserve prior schedule $id", value, all[id]) }
                assertEquals(saved, Codec.restore(store.backup()).schedules.single { it.id == saved.id })
            }
            returnToTimetableAfterImport()
            chooseWeek(2)
            assertOriginalGridTimes(saved, 2)
            screenshot("023-original-imported-custom-times.png")
            ui.activityRule.scenario.recreate()
            ui.onNodeWithText("第2周 ▾").assertExists()
            assertOriginalGridTimes(saved, 2)

            openOriginalPreview()
            ui.onNodeWithText("恢复完整备份 · 替换全部数据").performScrollTo().performClick()
            ui.onNodeWithText("确认").performClick()
            ui.waitUntil(10000) {
                runBlocking {
                    store.all().associateBy { it.id } == original.schedules.associateBy { it.id } &&
                        store.prefs() == original.preferences
                }
            }
            returnToTimetableAfterImport()
            chooseWeek(2)
            assertOriginalGridTimes(expected, 2)
            ui.activityRule.scenario.recreate()
            assertOriginalGridTimes(expected, 2)
            runBlocking { assertEquals(original, Codec.restore(store.backup())) }
            screenshot("023-original-restored-custom-times.png")
        } finally {
            instrumentation.removeMonitor(monitor)
            file.delete()
        }
    }

    private fun openOriginalPreview() {
        ui.onNodeWithContentDescription("导入课表").performClick()
        ui.onNodeWithText("选择本地课表文件").performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithText("导入预览").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun returnToTimetableAfterImport() {
        // Saving an import intentionally opens schedule management, where the new
        // schedule can be reviewed. Return through its real navigation control.
        ui.waitUntil(10000) { ui.onAllNodesWithText("课表管理").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithContentDescription("返回").performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithTag("week-selector").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun assertOriginalGridTimes(schedule: Schedule, week: Int) {
        schedule.routines.first().sections.forEach { value ->
            section(week, value.number).assert(hasAnyDescendant(hasText(value.start)))
                .assert(hasAnyDescendant(hasText(value.end)))
        }
        val custom = CourseEngine.timetableDay(schedule, CourseEngine.dateAt(schedule, week, 5))
            .single { it.session.mode == TimeMode.CLOCK }
        assertEquals(LocalTime.of(10, 20), custom.start)
        assertEquals(LocalTime.NOON, custom.end)
        val band = ui.onNode(
            hasTestTag("clock-band-10:20-10:30") and hasAnyAncestor(hasTestTag("week-grid-$week")),
            useUnmergedTree = true,
        )
        band.assert(hasAnyDescendant(hasText("10:20"))).assert(hasAnyDescendant(hasText("10:30")))
        ui.onNode(
            hasTestTag("clock-band-09:50-10:30") and hasAnyAncestor(hasTestTag("week-grid-$week")),
            useUnmergedTree = true,
        ).assertDoesNotExist()
        ui.onNodeWithTag("grid-course-${custom.id}").assertExists()
        section(week, 1).performScrollTo().assertIsDisplayed()
    }

    private fun normalized(schedule: Schedule): Schedule {
        val courseIds = schedule.courses.mapIndexed { index, value -> value.id to "course-$index" }.toMap()
        val sessionIds = schedule.sessions.mapIndexed { index, value -> value.id to "session-$index" }.toMap()
        val routineIds = schedule.routines.mapIndexed { index, value -> value.id to "routine-$index" }.toMap()
        return schedule.copy(
            id = "schedule",
            importedAt = 0,
            courses = schedule.courses.map { it.copy(id = courseIds.getValue(it.id)) },
            sessions = schedule.sessions.map { it.copy(id = sessionIds.getValue(it.id), courseId = courseIds.getValue(it.courseId)) },
            routines = schedule.routines.map { it.copy(id = routineIds.getValue(it.id)) },
            routineRules = schedule.routineRules.map { it.copy(routineId = routineIds.getValue(it.routineId)) },
            sessionOverrides = schedule.sessionOverrides.map { it.copy(sessionId = sessionIds.getValue(it.sessionId)) },
        )
    }
}

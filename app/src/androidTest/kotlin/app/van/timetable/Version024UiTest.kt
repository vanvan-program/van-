package app.van.timetable

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.van.timetable.domain.*
import app.van.timetable.system.ExcelReader
import java.io.File
import java.time.*
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Version024UiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device
        get() = UiDevice.getInstance(instrumentation)

    private val store
        get() = (instrumentation.targetContext.applicationContext as VanApplication).store

    private var previous: Backup? = null
    private val today = LocalDate.now(ZoneId.of("Asia/Shanghai"))

    @Before
    fun preserve() {
        previous = runBlocking { Backup(schedules = store.all(), preferences = store.prefs()) }
    }

    @After
    fun cleanup() {
        ui.mainClock.autoAdvance = true
        previous?.let { runBlocking { store.restore(it) } }
    }

    private fun seed(): Schedule {
        val course = Course(name = "入口与连续滑动验证")
        val schedule =
            Schedule(
                name = "0.2.4 回归课表",
                week1Monday =
                    today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString(),
                maxWeeks = 20,
                courses = listOf(course),
                sessions =
                    (1..7).map {
                        CourseSession(courseId = course.id, day = it, weeks = (1..20).toSet())
                    },
            )
        runBlocking {
            store.save(schedule)
            store.updatePreferences {
                it.copy(
                    activeScheduleId = schedule.id,
                    fontScale = 1f,
                    showInactive = false,
                    reminders = false,
                )
            }
        }
        ui.waitUntil(10000) {
            ui.onAllNodesWithText(schedule.name).fetchSemanticsNodes().isNotEmpty()
        }
        return schedule
    }

    private fun launch(schedule: Schedule, course: String? = null) {
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .setAction(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("scheduleId", schedule.id)
                    .putExtra("courseId", course)
                    .putExtra("date", today.toString())
                    .putExtra("openTimetable", course == null)
            )
        }
        ui.waitForIdle()
    }

    private fun screenshot(name: String) {
        assertTrue(device.takeScreenshot(File(ui.activity.getExternalFilesDir(null), name)))
    }

    @Test
    fun warmedDenseTimetablePagingCapturesFrameStats() {
        val base = seed()
        val courses = (1..35).map { Course(name = "连续滑动性能课程$it", color = it % CoursePalette.size) }
        val schedule =
            base.copy(
                courses = courses,
                sessions =
                    courses.mapIndexed { index, course ->
                        CourseSession(
                            courseId = course.id,
                            day = index / 5 + 1,
                            startSection = index % 5 * 2 + 1,
                            endSection = index % 5 * 2 + 2,
                            weeks = (1..20).toSet(),
                        )
                    },
            )
        runBlocking {
            store.save(schedule)
            store.updatePreferences {
                it.copy(
                    fontScale = 1f,
                    theme = "light",
                    showWeekend = true,
                    fitWeek = true,
                    showInactive = false,
                )
            }
        }
        ui.onNodeWithText("第1周 ▾").assertExists()
        val bounds = ui.onNodeWithTag("week-viewport").fetchSemanticsNode().boundsInRoot
        val y = (bounds.top + bounds.height * 0.65f).toInt()
        val left = (bounds.left + bounds.width * 0.25f).toInt()
        val right = (bounds.left + bounds.width * 0.8f).toInt()
        fun pair() {
            device.swipe(right, y, left, y, 60)
            ui.onNodeWithText("第2周 ▾").assertExists()
            device.swipe(left, y, right, y, 60)
            ui.onNodeWithText("第1周 ▾").assertExists()
        }
        repeat(4) { pair() }
        val version =
            instrumentation.targetContext.packageManager
                .getPackageInfo("app.van.timetable", 0)
                .versionName
        repeat(3) { run ->
            device.executeShellCommand("dumpsys gfxinfo app.van.timetable reset")
            repeat(8) { pair() }
            File(
                    instrumentation.targetContext.getExternalFilesDir(null),
                    "pager-$version-run${run + 1}.txt",
                )
                .writeText(
                    device.executeShellCommand("dumpsys gfxinfo app.van.timetable framestats")
                )
        }
    }

    @Test
    fun recreatedCourseEntryDoesNotPushDuplicateDetailsAndRepeatedWidgetEntryClearsHistory() {
        val schedule = seed()
        launch(schedule, schedule.courses.single().id)
        ui.onNodeWithText("调整 $today 08:00 的课程").assertExists()
        repeat(3) {
            ui.activityRule.scenario.recreate()
            ui.onNodeWithText("调整 $today 08:00 的课程").assertExists()
        }
        device.pressBack()
        ui.onNodeWithTag("week-selector").assertExists()
        // Repeated external entries reuse the activity and reset the app's own history.
        repeat(5) {
            ui.onNodeWithContentDescription("添加课程").performClick()
            device.pressHome()
            launch(schedule)
            ui.onNodeWithTag("week-selector").assertExists()
        }
        screenshot("024-widget-entry.png")
        device.pressBack()
        assertTrue(device.wait(Until.gone(By.pkg("app.van.timetable")), 5000))
        // Return the tracked activity to the foreground before ActivityScenario closes it.
        launch(schedule)
        ui.onNodeWithTag("week-selector").assertExists()
    }

    @Test
    fun rapidAlternatingSwipesModeChangesAndLargeGridStayResponsive() {
        seed()
        repeat(20) {
            ui.onNodeWithTag("week-viewport").performTouchInput { swipeLeft(durationMillis = 120) }
            ui.onNodeWithText("第2周 ▾").assertExists()
            ui.onNodeWithTag("week-viewport").performTouchInput { swipeRight(durationMillis = 120) }
            ui.onNodeWithText("第1周 ▾").assertExists()
        }
        repeat(4) {
            ui.onNodeWithContentDescription("课表菜单").performClick()
            ui.onNodeWithText("切换为日列表").performClick()
            ui.onNodeWithTag("day-list").performTouchInput { swipeLeft(durationMillis = 120) }
            ui.onNodeWithText("前一天").performClick()
            ui.onNodeWithContentDescription("课表菜单").performClick()
            ui.onNodeWithText("切换为周课表").performClick()
            ui.onNodeWithText("第1周 ▾").assertExists()
        }
        runBlocking { store.updatePreferences { it.copy(fontScale = 1.5f) } }
        repeat(6) { ui.onNodeWithTag("week-viewport").performTouchInput { swipeLeft() } }
        // Restore width, then paging must still work after edge panning and flings.
        runBlocking { store.updatePreferences { it.copy(fontScale = 1f) } }
        ui.onNodeWithTag("week-selector").performClick()
        ui.onNodeWithText("第1周").performClick()
        ui.onNodeWithTag("week-viewport").performTouchInput { swipeLeft() }
        ui.onNodeWithText("第2周 ▾").assertExists()
        screenshot("024-pager-stress.png")
    }

    @Test
    fun originalExcelReviewPreviewSaveRecreateAndBackupRestorePreserveAll26Runs() {
        seed()
        val bytes =
            ParcelFileDescriptor.AutoCloseInputStream(
                    instrumentation.uiAutomation.executeShellCommand(
                        "cat /sdcard/Download/van-original.xlsx"
                    )
                )
                .use { it.readBytes() }
        assertTrue("Provide the original workbook with adb push", bytes.size > 5000)
        val draft = DocumentImport.fromSheet(ExcelReader.read(bytes).single())
        assertEquals(0, draft.unresolvedCount)
        assertEquals(26, draft.rows.size)
        val expected =
            DocumentImport.toSchedule(
                draft.rows,
                today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),
            )
        assertEquals(11, expected.courses.size)
        assertEquals(26, expected.sessions.size)
        assertTrue(draft.rows.all { it.teacher.isNotBlank() && it.place.isNotBlank() })
        assertEquals(listOf("7", "10"), draft.rows.filter { it.day == "4" }.map { it.endSection })
        val survey = draft.rows.filter { it.name == "市场调查" && it.day == "1" }
        assertEquals(listOf("9-10", "1-3,6-7,11-16", "8", "8"), survey.map { it.weeks })
        assertEquals(listOf("6-504", "6-301", "6-504", "6-504"), survey.map { it.place })
        assertEquals(listOf("11", "11", "9", "11"), survey.map { it.endSection })
        val file = File(ui.activity.cacheDir, "exports/original.xlsx")
        file.parentFile!!.mkdirs()
        file.writeBytes(bytes)
        assertArrayEquals(bytes, file.readBytes())
        val uri = FileProvider.getUriForFile(ui.activity, "${ui.activity.packageName}.files", file)
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
            ui.onNodeWithContentDescription("导入课表").performClick()
            ui.onNodeWithText("选择本地课表文件").performClick()
            ui.waitUntil(15000) {
                ui.onAllNodesWithText("核对识别结果").fetchSemanticsNodes().isNotEmpty()
            }
            ui.onNodeWithText("形势与政策(五)").assertExists()
            ui.onNode(hasScrollToNodeAction()).performScrollToNode(isToggleable())
            ui.onNode(isToggleable()).performClick()
            ui.onNodeWithText("继续核对学期与作息").performScrollTo().performClick()
            ui.onNodeWithText("已读取 11 门课程 / 26 个时段").assertExists()
            ui.onNode(hasSetTextAction() and hasText("新课表名称"))
                .performScrollTo()
                .performTextReplacement("原始Excel兼容验证")
            ui.onNode(hasSetTextAction() and hasText("第1周周一 YYYY-MM-DD"))
                .performScrollTo()
                .performTextReplacement(expected.week1Monday)
            ui.onNode(isToggleable()).performScrollTo().assertIsOff().performClick()
            ui.onNodeWithText("保存为新课表").performScrollTo().performClick()
            ui.waitUntil(10000) { runBlocking { store.active()?.name == "原始Excel兼容验证" } }
            val saved = runBlocking { store.active()!! }
            fun runs(schedule: Schedule) =
                schedule.sessions.map { session ->
                    listOf(
                        schedule.courses.single { it.id == session.courseId }.name,
                        session.day,
                        session.startSection,
                        session.endSection,
                        session.weeks,
                        session.teacher,
                        session.place,
                    )
                }
            assertEquals(runs(expected), runs(saved))
            ui.onNodeWithContentDescription("返回").performClick()
            ui.onNodeWithTag("week-selector").assertExists()
            screenshot("024-original-excel.png")
            ui.activityRule.scenario.recreate()
            assertEquals(saved, runBlocking { store.active() })
            val backup = runBlocking { Codec.restore(store.backup()) }
            runBlocking {
                store.delete(saved.id)
                store.restore(backup)
            }
            ui.activityRule.scenario.recreate()
            assertEquals(runs(expected), runs(runBlocking { store.active()!! }))
            assertEquals(12, saved.routines.first().sections.size)
        } finally {
            instrumentation.removeMonitor(monitor)
            file.delete()
        }
    }
}

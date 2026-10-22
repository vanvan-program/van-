package app.van.timetable

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.van.timetable.domain.*
import java.io.File
import java.time.*
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CourseNotesTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val store get() = (ui.activity.application as VanApplication).store
    private var previous: Backup? = null
    private val today = LocalDate.now(ZoneId.of("Asia/Shanghai"))
    private val note = "分数分布：平时 30%，期末 70%\n点名情况：每节课点名\n作业：周五提交"
    private lateinit var seeded: Schedule

    @Before fun seed() {
        val c = Course(id = "notes-course", name = "课程备注验证")
        val other = Course(id = "other-course", name = "其他课程", note = "保留其他备注")
        val session = CourseSession(id = "notes-session", courseId = c.id, day = today.dayOfWeek.value)
        seeded = Schedule(name = "备注合成课表", week1Monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString(),
            courses = listOf(c, other), sessions = listOf(session, session.copy(id = "later", startSection = 5, endSection = 6)))
        runBlocking {
            previous = Backup(schedules = store.all(), preferences = store.prefs())
            store.save(seeded)
            store.updatePreferences { it.copy(activeScheduleId = seeded.id, reminders = false, showWeekend = true) }
        }
        instrumentation.runOnMainSync {
            ui.activity.startActivity(Intent(ui.activity, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("openTimetable", true).putExtra("scheduleId", seeded.id).putExtra("date", today.toString()))
        }
        ui.waitUntil(10000) { ui.onAllNodesWithText(seeded.name).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("grid-course-notes-session@$today").performClick()
        ui.onNodeWithTag("edit-course-note").performScrollTo().performClick()
    }

    @After fun restore() { previous?.let { runBlocking { store.restore(it) } } }
    private fun saved() = runBlocking { store.all().first { it.id == seeded.id } }
    private fun save(text: String) {
        ui.onNodeWithTag("course-note-input").performTextReplacement(text)
        ui.onNodeWithTag("save-course-note").performClick()
        ui.waitUntil(15000) { saved().courses.first().note == text }
        ui.waitUntil(10000) { ui.onAllNodesWithTag("save-course-note").fetchSemanticsNodes().isEmpty() }
    }

    @Test fun multilineNoteSurvivesRecreationSaveAndBackupWithoutChangingSessions() {
        ui.onNodeWithTag("course-note-input").performTextReplacement(note)
        ui.activityRule.scenario.recreate()
        ui.onNodeWithTag("course-note-input").assertTextContains(note)
        ui.onNodeWithTag("save-course-note").performClick()
        ui.waitUntil(15000) { saved().courses.first().note == note }
        ui.onNodeWithTag("course-note-content").assertTextEquals(note)
        assertEquals(seeded.copy(courses = seeded.courses.map { if (it.id == "notes-course") it.copy(note = note) else it }), saved())
        val backup = Backup(schedules = listOf(saved()), preferences = runBlocking { store.prefs() })
        val restored = Codec.restore(Codec.backup(backup))
        runBlocking { store.restore(restored) }
        ui.activityRule.scenario.recreate()
        ui.onNodeWithTag("course-note-content").assertTextEquals(note)
        ui.waitForIdle()
        assertTrue(UiDevice.getInstance(instrumentation).takeScreenshot(File(ui.activity.getExternalFilesDir(null), "029-course-note.png")))
    }

    @Test fun cancelKeepsSavedNoteAndBlankSaveClearsIt() {
        save(note)
        ui.onNodeWithTag("edit-course-note").performScrollTo().performClick()
        ui.onNodeWithTag("course-note-input").performTextReplacement("不保存的草稿")
        ui.onNodeWithText("取消").performClick()
        assertEquals(note, saved().courses.first().note)
        ui.onNodeWithTag("edit-course-note").performClick()
        ui.onNodeWithTag("course-note-input").assertTextContains(note)
        save("")
        ui.onNodeWithText("添加课程备注").assertExists()
        ui.onNodeWithTag("course-note-content").assertDoesNotExist()
        assertEquals(seeded, saved())
    }

    @Test fun fullCourseEditorSharesTheSameNote() {
        save(note)
        ui.onNodeWithText("编辑整门课程").performScrollTo().performClick()
        ui.onNodeWithTag("course-note-input").performScrollTo().assertTextContains(note)
        val changed = "分数分布：作业 20%，考试 80%\n点名情况：偶尔抽查"
        ui.onNodeWithTag("course-note-input").performTextReplacement(changed)
        ui.onNodeWithText("保存课程").performScrollTo().performClick()
        ui.waitUntil(15000) { saved().courses.first { it.id == "notes-course" }.note == changed }
        ui.onNodeWithTag("course-note-content").assertTextEquals(changed)
    }

    @Test fun singleButtonShowsAllCourseNotesAndAllowsEditingWithoutListFilters() {
        ui.onNodeWithText("取消").performClick()
        ui.onNodeWithContentDescription("返回").performClick()
        ui.onNodeWithText("课程").performClick()
        ui.onAllNodesWithTag("all-course-notes-button").assertCountEquals(1)
        ui.onAllNodesWithText("课程备注").assertCountEquals(1)
        ui.onNodeWithText("搜索课程、老师、地点").performTextInput("课程备注验证")
        ui.waitForIdle()
        assertTrue(UiDevice.getInstance(instrumentation).takeScreenshot(File(ui.activity.getExternalFilesDir(null), "029-notes-entry.png")))
        ui.onNodeWithTag("all-course-notes-button").performClick()
        ui.onNodeWithTag("all-course-notes").assertExists()
        ui.onNodeWithText("暂无备注").assertExists()
        ui.onNodeWithTag("summary-note-content-other-course").performScrollTo().assertTextEquals("保留其他备注")
        assertTrue(ui.onNodeWithTag("course-note-summary-other-course").fetchSemanticsNode().boundsInRoot.top <
            ui.onNodeWithTag("course-note-summary-notes-course").fetchSemanticsNode().boundsInRoot.top)
        assertTrue(UiDevice.getInstance(instrumentation).takeScreenshot(File(ui.activity.getExternalFilesDir(null), "029-notes-sorted.png")))
        ui.onNodeWithTag("edit-summary-note-notes-course").performScrollTo().performClick()
        ui.onNodeWithTag("course-note-input").performTextReplacement(note)
        ui.activityRule.scenario.recreate()
        ui.onNodeWithTag("course-note-input").assertTextContains(note)
        save(note)
        ui.onNodeWithTag("summary-note-content-notes-course").assertTextEquals(note)
        assertTrue(ui.onNodeWithTag("course-note-summary-notes-course").fetchSemanticsNode().boundsInRoot.top <
            ui.onNodeWithTag("course-note-summary-other-course").fetchSemanticsNode().boundsInRoot.top)
        ui.onNodeWithText("课程详情").assertDoesNotExist()
        ui.onNodeWithTag("edit-summary-note-other-course").performScrollTo().performClick()
        ui.onNodeWithTag("course-note-input").assertTextContains("保留其他备注")
        ui.onNodeWithTag("course-note-input").performTextReplacement("取消的另一门课草稿")
        ui.onNodeWithText("取消").performClick()
        assertEquals("保留其他备注", saved().courses.first { it.id == "other-course" }.note)
        ui.onNodeWithTag("summary-note-content-notes-course").performScrollTo().assertTextEquals(note)
        ui.waitForIdle()
        assertTrue(UiDevice.getInstance(instrumentation).takeScreenshot(File(ui.activity.getExternalFilesDir(null), "029-all-course-notes.png")))
        ui.onNodeWithTag("edit-summary-note-notes-course").performScrollTo().performClick()
        save("")
        assertTrue(ui.onNodeWithTag("course-note-summary-other-course").fetchSemanticsNode().boundsInRoot.top <
            ui.onNodeWithTag("course-note-summary-notes-course").fetchSemanticsNode().boundsInRoot.top)
        ui.onNodeWithTag("edit-summary-note-notes-course").performScrollTo().performClick()
        save(note)
        ui.onNodeWithContentDescription("返回").performClick()
        ui.onNode(hasText("课程备注验证") and hasClickAction() and !hasSetTextAction()).performClick()
        ui.onNodeWithText("课程详情").assertExists()
        ui.onNodeWithTag("course-note-content").assertTextEquals(note)
        ui.onNodeWithTag("save-course-note").assertDoesNotExist()
    }
}

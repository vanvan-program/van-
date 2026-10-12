package app.van.timetable

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.van.timetable.domain.*
import app.van.timetable.system.Files
import java.io.File
import java.time.*
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Version020UiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val store
        get() = (ui.activity.application as VanApplication).store

    private var schedule: Schedule? = null
    private var preferences: Preferences? = null
    private val today = LocalDate.now(ZoneId.of("Asia/Shanghai"))

    private fun seed(
        courses: List<Course> = emptyList(),
        sessions: List<CourseSession> = emptyList(),
    ): Schedule {
        val s =
            Schedule(
                name = "0.2.0功能验证",
                week1Monday =
                    today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString(),
                courses = courses,
                sessions = sessions,
            )
        schedule = s
        runBlocking {
            preferences = store.prefs()
            store.save(s)
            store.updatePreferences {
                it.copy(
                    activeScheduleId = s.id,
                    theme = "light",
                    showWeekend = true,
                    showInactive = false,
                    fitWeek = true,
                    fontScale = 1f,
                    reminders = false,
                )
            }
        }
        ui.waitUntil(10000) { ui.onAllNodesWithText(s.name).fetchSemanticsNodes().isNotEmpty() }
        return s
    }

    private fun saved() = runBlocking { store.all().first { it.id == schedule!!.id } }

    private fun screenshot(name: String) {
        ui.waitForIdle()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            .takeScreenshot(File(ui.activity.getExternalFilesDir(null), name))
    }

    @After
    fun cleanup() {
        runBlocking {
            schedule?.let { store.delete(it.id) }
            preferences?.let { p -> store.updatePreferences { p } }
        }
    }

    @Test
    fun consecutiveAddsRejectBlankAndNeverOverwriteLastCourseThenRemainEditable() {
        seed()
        ui.onNodeWithContentDescription("添加课程").performClick()
        ui.onNodeWithText("保存课程").performScrollTo().performClick()
        ui.onNodeWithText("课程名称不能为空，请填写名称").assertIsDisplayed()
        assertTrue(saved().courses.isEmpty())
        ui.onNodeWithText("课程名称").performScrollTo().performTextReplacement("第一门课程")
        ui.onNodeWithText("保存课程").performScrollTo().performClick()
        ui.waitUntil(10000) { saved().courses.size == 1 }
        val first = saved()
        ui.onNodeWithContentDescription("添加课程").performClick()
        ui.onNodeWithText("课程名称")
            .performScrollTo()
            .assert(
                SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(""))
            )
        ui.onNodeWithText("保存课程").performScrollTo().performClick()
        assertEquals(first, saved())
        ui.onNodeWithText("课程名称").performScrollTo().performTextReplacement("第二门课程")
        ui.onNodeWithText("保存课程").performScrollTo().performClick()
        ui.waitUntil(10000) { saved().courses.size == 2 }
        assertEquals(
            first.courses.single(),
            saved().courses.first { it.id == first.courses.single().id },
        )
        ui.onNodeWithText("课程", useUnmergedTree = true).performClick()
        ui.onNodeWithText("第二门课程").performClick()
        ui.onNodeWithText("编辑整门课程").performScrollTo().performClick()
        ui.onNodeWithText("课程名称").performTextReplacement("第二门已修改")
        ui.onNodeWithText("保存课程").performScrollTo().performClick()
        ui.waitUntil(10000) { saved().courses.any { it.name == "第二门已修改" } }
        ui.onNodeWithText("编辑整门课程").performScrollTo().performClick()
        ui.onNodeWithText("第二门已修改").assertExists()
    }

    @Test
    fun colorPickerSavesCustomColorAndBackupAndQuickEventKeepIt() {
        seed()
        ui.onNodeWithContentDescription("添加课程").performClick()
        ui.onNodeWithText("课程名称").performTextReplacement("调色盘课程")
        ui.onNodeWithText("打开调色盘").performScrollTo().performClick()
        ui.onNodeWithTag("color-plane").performTouchInput { click(center) }
        ui.onNodeWithTag("color-hex").performTextReplacement("#123456")
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        screenshot("020-color-picker.png")
        ui.onNodeWithText("使用此颜色").performClick()
        ui.activityRule.scenario.recreate()
        ui.onNodeWithText("保存课程").performScrollTo().performClick()
        ui.waitUntil(10000) { saved().courses.isNotEmpty() }
        assertEquals(0xFF123456L, saved().courses.single().customColor)
        assertEquals(0xFF123456L, Codec.decode(Codec.encode(saved())).courses.single().customColor)
        val image = Files.image(saved(), 1, false, false)
        assertTrue(image.isNotEmpty())
        ui.onNodeWithText("课程", useUnmergedTree = true).performClick()
        ui.onNodeWithText("调色盘课程").performClick()
        screenshot("020-custom-color-detail.png")
        ui.onNodeWithText("添加实验课").performScrollTo().performClick()
        ui.onNodeWithText("保存课程").performScrollTo().performClick()
        ui.waitUntil(10000) { saved().courses.size == 2 }
        assertEquals(0xFF123456L, saved().courses.first { it.parentCourseId != null }.customColor)
    }

    @Test
    fun weekAdjustmentSavesResolvedDatesAndCourseListShowsDisjointWeeks() {
        val c = Course(name = "间断周课程")
        val s =
            seed(listOf(c), listOf(CourseSession(courseId = c.id, day = 1, weeks = setOf(1, 3, 5))))
        ui.onNodeWithText("课程", useUnmergedTree = true).performClick()
        ui.onNodeWithText("周次：第1、3、5周").assertIsDisplayed()
        screenshot("020-course-weeks.png")
        ui.onNodeWithText("我的", useUnmergedTree = true).performClick()
        ui.onNodeWithText("调课管理").performScrollTo().performClick()
        ui.onNodeWithText("按周次和星期").performClick()
        ui.onNodeWithTag("目标-week").performTextReplacement("2")
        ui.onNodeWithTag("目标-day").performTextReplacement("3")
        ui.onNodeWithTag("来源-week").performTextReplacement("1")
        ui.onNodeWithTag("来源-day").performTextReplacement("1")
        ui.onNodeWithText("保存整日安排").performScrollTo().performClick()
        ui.waitUntil(10000) { saved().dayOverrides.isNotEmpty() }
        val source = CourseEngine.dateAt(s, 1, 1)
        val target = CourseEngine.dateAt(s, 2, 3)
        assertEquals(
            DayOverride(target.toString(), source.toString()),
            saved().dayOverrides.single(),
        )
        assertEquals(1, CourseEngine.resolveDay(saved(), target).size)
        assertEquals(1, CourseEngine.resolveDay(saved(), source).size)
        screenshot("020-week-adjustment.png")
        ui.onNodeWithTag("目标-week").performScrollTo().performTextReplacement("21")
        ui.onNodeWithText("保存整日安排").performScrollTo().performClick()
        ui.onNodeWithText("周次必须在1–20之间").assertIsDisplayed()
        assertEquals(1, saved().dayOverrides.size)
    }

    @Test
    fun customTimeAndMovedCourseAreInsideGridInBothThemesAndTodayJumpWorks() {
        val names = listOf("早晨课程", "午间课程", "深夜课程", "移动课程")
        val cs = names.map { Course(name = it, customColor = 0xFF234567) }
        val sessions =
            listOf(
                CourseSession(
                    courseId = cs[0].id,
                    day = 1,
                    weeks = setOf(1),
                    mode = TimeMode.CLOCK,
                    start = "06:30",
                    end = "07:30",
                ),
                CourseSession(
                    courseId = cs[1].id,
                    day = 1,
                    weeks = setOf(1),
                    mode = TimeMode.CLOCK,
                    start = "12:00",
                    end = "13:00",
                ),
                CourseSession(
                    courseId = cs[2].id,
                    day = 1,
                    weeks = setOf(1),
                    mode = TimeMode.CLOCK,
                    start = "22:00",
                    end = "23:00",
                ),
                CourseSession(courseId = cs[3].id, day = 2, weeks = setOf(1)),
            )
        val s = seed(cs, sessions)
        val moved =
            s.copy(
                sessionOverrides =
                    listOf(
                        SessionOverride(
                            sessions[3].id,
                            CourseEngine.dateAt(s, 1, 2).toString(),
                            start = "12:15",
                            end = "13:15",
                        )
                    )
            )
        runBlocking { store.save(moved) }
        ui.waitForIdle()
        for (theme in listOf("light", "dark")) {
            runBlocking { store.updatePreferences { it.copy(theme = theme) } }
            ui.waitForIdle()
            sessions.take(3).forEach {
                ui.onNodeWithTag("grid-course-${it.id}@${s.week1Monday}")
                    .assertExists()
                    .assert(hasAnyAncestor(hasTestTag("week-viewport")))
                    .performScrollTo()
                    .assertIsDisplayed()
            }
            ui.onNodeWithTag("grid-course-${sessions[3].id}@${CourseEngine.dateAt(s, 1, 2)}")
                .assertExists()
            ui.onNodeWithText("自定义时间与调课").assertDoesNotExist()
            screenshot("020-grid-$theme.png")
            ui.onNodeWithTag("grid-course-${sessions[0].id}@${s.week1Monday}").performScrollTo()
        }
        ui.onNodeWithTag("week-viewport").performTouchInput { swipeLeft() }
        ui.onNodeWithText("第2周 ▾").assertExists()
        ui.onNodeWithTag("jump-today").performClick()
        ui.onNodeWithText("第1周 ▾").assertExists()
        ui.onNodeWithContentDescription("课表菜单").performClick()
        ui.onNodeWithText("切换为日列表").performClick()
        ui.onNodeWithText("后一天").performClick()
        ui.onNodeWithTag("jump-today").performClick()
        ui.onNodeWithText("$today ${app.van.timetable.ui.weekdays[today.dayOfWeek.value - 1]}")
            .assertExists()
    }
}

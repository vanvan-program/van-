// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.van.timetable.domain.*
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SchoolDomTest {
    // Original synthetic fixtures: no student data or copied school HTML.
    private fun extract(body: String): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val script = context.assets.open("adapters/dom.js").bufferedReader().use { it.readText() }
        val done = CountDownLatch(1)
        var result: String? = null
        lateinit var web: WebView
        instrumentation.runOnMainSync {
            web = WebView(context)
            web.settings.javaScriptEnabled = true
            web.settings.blockNetworkLoads = true
            web.webViewClient =
                object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        view.evaluateJavascript(script) {
                            view.evaluateJavascript("JSON.stringify(window.__vanImportResult)") {
                                value ->
                                result = Codec.json.parseToJsonElement(value).jsonPrimitive.content
                                done.countDown()
                            }
                        }
                    }
                }
            web.loadDataWithBaseURL(
                "https://portal.example.edu.cn/",
                "<html><body>$body</body></html>",
                "text/html",
                "UTF-8",
                null,
            )
        }
        try {
            assertTrue("WebView extraction timed out", done.await(15, TimeUnit.SECONDS))
            return requireNotNull(result)
        } finally {
            instrumentation.runOnMainSync { web.destroy() }
        }
    }

    @Test
    fun tooltipSiblingAndLegacyMetadataSurviveSaveAndBackup() {
        val result =
            extract(
                """
                <table><tr>
                  <td id="1-1"><div class="timetable_con">
                    <p class="title"><font>示例课程甲</font></p>
                    <p><span data-original-title="教师"></span>张老师</p>
                    <p><span data-original-title="上课地点"></span>示例楼 A414</p>
                    <p><span data-original-title="节/周"></span>(1-2节)1-16周(单)</p>
                  </div></td>
                  <td id="2-3"><div class="timetable_con">
                    <p class="title">示例课程乙</p>
                    <span title="教师"></span><b>李老师</b><br>
                    <span title="教室"></span><span>示例楼 A214</span><br>
                    (3-4节)2-16(双)周
                  </div></td>
                  <td id="3-5"><div class="timetable_con">
                    <p class="title">示例课程丙</p>
                    <p><span data-bs-original-title="任课教师"></span>王老师</p>
                    <p><span data-bs-original-title="场地"></span>示例机房3</p>
                    <p>(5-6节)1-8、11-16周</p>
                  </div></td>
                  <td id="4-1"><div class="kbcontent">
                    示例课程丁<br><font title="老师">赵老师</font><br>
                    <font title="教室">示例楼 306</font><br>[0102节]1-16(周)
                  </div></td>
                  <td id="5-7"><div class="timetable_con">
                    <p class="title">示例课程庚</p><p>教师：刘老师</p><p>地点：示例楼 B512</p>
                    <p>(7-8节)1-16周</p>
                  </div></td>
                </tr></table>
                """
                    .trimIndent()
            )
        val draft = Importers.school(result, "某学校", LocalDate.parse("2026-09-07"))
        assertEquals(5, draft.schedule.sessions.size)
        assertEquals(
            listOf("张老师", "李老师", "王老师", "赵老师", "刘老师"),
            draft.schedule.sessions.map { it.teacher },
        )
        assertEquals(
            listOf("示例楼 A414", "示例楼 A214", "示例机房3", "示例楼 306", "示例楼 B512"),
            draft.schedule.sessions.map { it.place },
        )
        assertEquals((1..16 step 2).toSet(), draft.schedule.sessions[0].weeks)
        assertEquals((2..16 step 2).toSet(), draft.schedule.sessions[1].weeks)
        assertEquals((1..8).toSet() + (11..16).toSet(), draft.schedule.sessions[2].weeks)
        assertEquals(1, draft.schedule.sessions[3].startSection)
        assertEquals(2, draft.schedule.sessions[3].endSection)
        assertFalse(draft.warnings.any { it.contains("未读取到") })
        val store =
            (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
                    as VanApplication)
                .store
        runBlocking {
            val saved = Importers.freshCopy(draft.schedule)
            store.save(saved)
            assertEquals(saved.sessions, store.all().first { it.id == saved.id }.sessions)
            assertEquals(saved, Codec.restore(store.backup()).schedules.first { it.id == saved.id })
            store.delete(saved.id)
        }
    }

    @Test
    fun missingMetadataIsReportedAndMalformedCoursesAreRejectedAtomically() {
        val valid =
            "<td id='1-1'><div class='timetable_con'><p class='title'>数学</p><p>(1-2节)1-16周</p></div></td>"
        val draft =
            Importers.school(
                extract("<table><tr>$valid</tr></table>"),
                "某学校",
                LocalDate.parse("2026-09-07"),
            )
        assertTrue(draft.schedule.sessions.single().teacher.isBlank())
        assertTrue(draft.warnings.any { it.contains("未读取到老师") && it.contains("未读取到地点") })
        val bad =
            "<td id='2-3'><div class='timetable_con'><p class='title'>坏数据</p><p>没有周次与节次</p></div></td>"
        val result = extract("<table><tr>$valid$bad</tr></table>")
        assertTrue(
            runCatching { Importers.school(result, "某学校", LocalDate.parse("2026-09-07")) }
                .isFailure
        )
    }

    @Test
    fun narrowColumnsRepeatedWeekSuffixAndSpannedHeadersAreReadWithoutVisualWrapping() {
        // An original fixture shaped like the reported table, not copied school HTML.
        val result =
            extract(
                """
                <style>
                  table {table-layout:fixed; width:240px}
                  td {width:15px; word-break:break-all; white-space:normal}
                  .timetable_con {width:12px; overflow-wrap:anywhere}
                </style>
                <table>
                  <tr><th colspan="10">2026-2027学年个人课表</th></tr>
                  <tr>
                    <th colspan="2" rowspan="2">节次</th>
                    <th rowspan="2">星期一</th><th rowspan="2">星期二</th>
                    <th rowspan="2">星期三</th><th rowspan="2">星期四</th>
                    <th rowspan="2">星期五</th><th rowspan="2">星期六</th>
                    <th colspan="2" rowspan="2">星期日</th>
                  </tr>
                  <tr></tr>
                  <tr>
                    <td rowspan="2">上午</td><td>1</td>
                    <td><div class="timetable_con">
                      <p class="title"><font>示例课程甲</font></p>
                      <p><span data-original-title="节/周"></span>(<span>1</span>-<span>2</span>节)<span>3</span>-<span>4</span>周,<span>6</span>-<span>8</span>周</p>
                      <p><span class="glyphicon glyphicon-map-marker"></span>示例校区 A414</p>
                      <p><span class="glyphicon glyphicon-user"></span>测试甲老师</p>
                    </div></td>
                    <td></td>
                    <td><div class="timetable_con">
                      <p class="title">示例课程丁</p><p>(1-2节)3-14周</p>
                      <p><span data-original-title="上课地点"></span>示例校区 A214</p>
                      <p><span data-original-title="教师"></span>测试乙老师</p>
                    </div></td>
                    <td></td><td></td><td></td>
                    <td colspan="2"><div class="timetable_con">
                      <p class="title">示例课程甲</p><p>(1-2节)3周,6周</p>
                      <p>教师：测试甲老师</p><p>地点：示例校区 A414</p>
                    </div></td>
                  </tr>
                  <tr>
                    <td>2</td><td></td>
                    <td><div class="timetable_con">
                      <p class="title">示例课程乙</p><p>(3-4节)2-16周(双)</p>
                      <p>教师：测试丙老师</p><p>地点：示例校区 A214</p>
                    </div></td>
                    <td></td><td></td><td></td><td></td><td colspan="2"></td>
                  </tr>
                </table>
                """
                    .trimIndent()
            )
        val draft = Importers.school(result, "某学校", LocalDate.parse("2026-09-07"))
        val sessions = draft.schedule.sessions
        assertEquals(listOf(1, 3, 7, 2), sessions.map { it.day })
        assertEquals(setOf(3, 4, 6, 7, 8), sessions[0].weeks)
        assertEquals((3..14).toSet(), sessions[1].weeks)
        assertEquals(setOf(3, 6), sessions[2].weeks)
        assertEquals((2..16 step 2).toSet(), sessions[3].weeks)
        assertEquals(listOf(1, 1, 1, 3), sessions.map { it.startSection })
        assertEquals(listOf(2, 2, 2, 4), sessions.map { it.endSection })
        assertEquals("测试甲老师", sessions[0].teacher)
        assertEquals("示例校区 A414", sessions[0].place)
        assertFalse(draft.warnings.any { it.contains("未读取到") })
    }

    @Test
    fun unidentifiedOrAmbiguousWeekdaysAndMalformedWeekRangesAreRejectedWithShortSummary() {
        val withoutHeader =
            extract(
                "<table><tr><td>节次</td><td><div class='timetable_con'><p class='title'>不能猜星期</p><p>(1-2节)1-16周</p></div></td></tr></table>"
            )
        assertTrue(withoutHeader.contains("缺少可确认的星期表头"))
        assertFalse(withoutHeader.contains("\"courses\""))
        val spanningDays =
            extract(
                "<table><tr><th>节次</th><th>星期一</th><th>星期二</th></tr><tr><td>1</td><td colspan='2'><div class='timetable_con'><p class='title'>跨星期不明确</p><p>(1-2节)1-16周</p></div></td></tr></table>"
            )
        assertTrue(spanningDays.contains("缺少可确认的星期表头"))
        val manyBadRows =
            (1..20).joinToString("") {
                "<tr><td id='1-1'><div class='timetable_con'><p class='title'>坏课程$it</p><p>(1-2节)1-16周,未知周</p></div></td></tr>"
            }
        val bad = extract("<table>$manyBadRows</table>")
        assertTrue(bad.contains("共20项"))
        assertTrue(bad.contains("坏课程1："))
        assertFalse(bad.contains("坏课程3："))
        assertFalse(bad.contains("\"courses\""))
        assertTrue("Failure summary must not fill the screen", bad.length < 240)
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.van.timetable.domain.*
import app.van.timetable.school.schoolAdapterAssets
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ZhengfangAdapterTest {
    private val url =
        "https://portal.example.edu.cn/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151&layout=default"
    private val termInputs =
        """<select id="xnm"><option value="2024">2024-2025学年</option></select>
        <select id="xqm"><option value="12">第2学期</option><option value="3">第1学期</option></select>"""
    private val row =
        """{"kcmc":"接口课程","xqj":"3","jc":"3-4节","zcd":"3-4周,6-8周","xm":"测试甲老师","cdmc":"示例校区 A414"}"""
    private val dom =
        """<table><tr><td id="1-1"><div class="timetable_con"><p class="title">网页课程</p>
        <p>(1-2节)1-16周(单)</p><p><span class="glyphicon-user"></span>测试乙老师</p>
        <p><span class="glyphicon-map-marker"></span>示例校区 A214</p></div></td></tr></table>"""
    private val legacyArrays =
        """Array.prototype.filter=function(predicate){
          const result=[];for(let i=0;i<this.length;i++)if(predicate(i,this[i],this))result.push(this[i]);return result;
        };
        Array.prototype.some=function(predicate){
          for(let i=0;i<this.length;i++)if(predicate(i,this[i],this))return true;return false;
        };
        Array.prototype.every=function(){return true;};"""

    // Original fixtures and mock transport; never contacts the school or uses student data.
    private fun extract(body: String, setup: String = ""): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val script =
            schoolAdapterAssets(url).joinToString("\n") { adapter ->
                context.assets.open("adapters/$adapter.js").bufferedReader().use { it.readText() }
            }
        val done = CountDownLatch(1)
        val handler = Handler(Looper.getMainLooper())
        var result: String? = null
        lateinit var web: WebView
        instrumentation.runOnMainSync {
            web = WebView(context)
            web.settings.javaScriptEnabled = true
            web.settings.blockNetworkLoads = true
            web.webViewClient =
                object : WebViewClient() {
                    override fun onPageFinished(view: WebView, pageUrl: String) {
                        view.evaluateJavascript("$setup\n$script") {
                            val poll =
                                object : Runnable {
                                    override fun run() {
                                        view.evaluateJavascript(
                                            "JSON.stringify(window.__vanImportResult || null)"
                                        ) { value ->
                                            val parsed =
                                                Codec.json
                                                    .parseToJsonElement(value)
                                                    .jsonPrimitive
                                                    .content
                                            if (parsed == "null") handler.postDelayed(this, 50)
                                            else {
                                                result = parsed
                                                done.countDown()
                                            }
                                        }
                                    }
                                }
                            handler.post(poll)
                        }
                    }
                }
            web.loadDataWithBaseURL(
                url,
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
            instrumentation.runOnMainSync {
                handler.removeCallbacksAndMessages(null)
                web.destroy()
            }
        }
    }

    private fun response(json: String, before: String = ""): String =
        """window.fetch = async (url, options) => {
          $before
          return {ok:true,status:200,redirected:false,text:async()=>JSON.stringify($json)};
        };"""

    private fun draft(result: String) =
        Importers.school(result, "某学校", LocalDate.parse("2024-02-26"))

    internal fun fixtureResult(): String =
        extract(termInputs, legacyArrays + response("""{"kbList":[$row],"sjkList":[]}"""))

    internal fun currentPageFixtureResult(): String =
        extract(
            ZhengfangPageFixture.html,
            legacyArrays + "window.fetch=async()=>{throw Error('Grid must be read locally')};",
        )

    @Test
    fun queriedGridExcludesHiddenListAndPreservesAllFooterEntries() {
        val result = currentPageFixtureResult()
        val imported = Importers.school(result, "某学校", LocalDate.parse("2026-09-07"))
        val schedule = imported.schedule
        val timed = schedule.sessions.filter { it.mode == TimeMode.SECTIONS }
        val untimed = schedule.sessions.filter { it.mode == TimeMode.UNTIMED }
        assertEquals(26, timed.size)
        assertEquals(9, untimed.size)
        assertEquals(12, schedule.courses.size)
        assertTrue(timed.all { it.teacher.isNotBlank() && it.place.startsWith("示例校区") })
        assertTrue(imported.warnings.any { it.contains("2026-2027学年 · 第1学期") })
        assertTrue(imported.warnings.any { it.contains("已保留9个实践或无固定时间") })
        assertEquals(setOf(3, 4, 6, 7, 8), timed.first().weeks)
        assertEquals(setOf(3, 7, 9, 11), timed.single { it.day == 2 && it.startSection == 3 }.weeks)
        assertEquals(
            setOf(4, 6, 7, 8, 9, 10, 11, 12, 16),
            timed.single { it.day == 3 && it.startSection == 9 }.weeks,
        )
        assertEquals(2, timed.count { it.day == 7 && it.startSection == 3 })
        assertEquals(setOf(1, 2), untimed.first().weeks)
        assertEquals(setOf(18), untimed[1].weeks)
        assertEquals(setOf(19), untimed[2].weeks)
        assertTrue(untimed.all { it.day == 0 && it.teacher.isNotBlank() && it.place.isBlank() })
        val copy = Importers.freshCopy(schedule)
        assertEquals(
            copy,
            Codec.restore(Codec.backup(Backup(schedules = listOf(copy)))).schedules.single(),
        )
    }

    @Test
    fun selectedTermWithoutQueryingNeverImportsTheOldGrid() {
        val result =
            extract(
                ZhengfangPageFixture.html,
                legacyArrays + "document.getElementById('xnm').value='2025';",
            )
        assertTrue(result.contains("所选学期与当前表格不一致"))
        assertFalse(result.contains("\"courses\""))
    }

    @Test
    fun schoolArrayOverridesPreserveApiAndDomAndNeverDropPractice() {
        val api = draft(fixtureResult())
        assertTrue(api.warnings.any { it.contains("2024-2025学年 · 第2学期") })
        assertEquals("测试甲老师", api.schedule.sessions.single().teacher)
        val table =
            dom.replace("<table><tr>", "<table><tr><th>周一</th><th>周二</th></tr><tr>")
                .replace(" id=\"1-1\"", "")
        val fallback =
            draft(
                extract(
                    termInputs + table,
                    legacyArrays + "window.fetch=async()=>{throw Error('Unavailable')};",
                )
            )
        assertEquals(1, fallback.schedule.sessions.single().day)
        assertEquals("测试乙老师", fallback.schedule.sessions.single().teacher)
        assertEquals("示例校区 A214", fallback.schedule.sessions.single().place)
        assertTrue(fallback.warnings.any { it.contains("2024-2025学年 · 第2学期") })
        val practice =
            extract(
                termInputs + dom,
                legacyArrays + response("""{"kbList":[$row],"sjkList":[{"qtkcgs":"第17周课程设计"}]}"""),
            )
        assertTrue(practice.contains("实践或时间未定课程"))
        assertFalse(practice.contains("\"courses\""))
        assertTrue(extract("<input id='yhm'><input id='mm'>", legacyArrays).contains("请先登录"))
    }

    @Test
    fun selectedTermApiMetadataAndWeekSegmentsSurviveSaveAndBackup() {
        val second =
            """{"kcmc":"单双周课程","xqj":7,"jc":"9-10","zcd":"2-16(双)周","jsxm":"测试丙老师","cdmc":"公共机房3"}"""
        val third = """{"kcmc":"间断课程","xqj":"2","jc":"1","zcd":"1-3、7、11-15周(单)"}"""
        val checks =
            """if (url !== 'https://portal.example.edu.cn/kbcx/xskbcx_cxXsKb.html?gnmkdm=N2151'
              || options.method !== 'POST' || options.credentials !== 'same-origin'
              || options.headers['X-Requested-With'] !== 'XMLHttpRequest'
              || options.body !== 'xnm=2024&xqm=12') throw Error('Wrong request');"""
        val imported =
            draft(
                extract(
                    termInputs,
                    response("""{"kbList":[$row,$second,$third],"sjkList":[]}""", checks),
                )
            )
        val sessions = imported.schedule.sessions
        assertEquals(3, sessions.size)
        assertEquals(listOf(3, 7, 2), sessions.map { it.day })
        assertEquals(setOf(3, 4, 6, 7, 8), sessions[0].weeks)
        assertEquals((2..16 step 2).toSet(), sessions[1].weeks)
        assertEquals(setOf(1, 3, 7, 11, 13, 15), sessions[2].weeks)
        assertEquals(listOf("测试甲老师", "测试丙老师", ""), sessions.map { it.teacher })
        assertEquals("示例校区 A414", sessions[0].place)
        assertEquals(9, sessions[1].startSection)
        assertEquals(10, sessions[1].endSection)
        assertTrue(imported.warnings.any { it.contains("2024-2025学年 · 第2学期") })
        assertTrue(imported.warnings.any { it.contains("未读取到老师") })
        val store =
            (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
                    as VanApplication)
                .store
        runBlocking {
            val saved = Importers.freshCopy(imported.schedule)
            try {
                store.save(saved)
                assertEquals(saved.sessions, store.all().first { it.id == saved.id }.sessions)
                assertEquals(
                    saved,
                    Codec.restore(store.backup()).schedules.first { it.id == saved.id },
                )
            } finally {
                store.delete(saved.id)
            }
        }
    }

    @Test
    fun malformedApiRowsNeverFallBackToPartialVisibleCourses() {
        for (bad in
            listOf(
                row.replace("3-4周,6-8周", "1-16周,未知周"),
                row.replace("3-4节", "3,5节"),
                row.replace("\"3\"", "\"8\""),
                row.replace("3-4周,6-8周", "0-16周"),
            )) {
            val result = extract(termInputs + dom, response("""{"kbList":[$row,$bad]}"""))
            assertTrue(result.contains("未保存课程"))
            assertFalse(result.contains("\"courses\""))
        }
    }

    @Test
    fun unavailableApiFallsBackToRenderedDomWithTermAndMetadata() {
        val imported =
            draft(
                extract(
                    termInputs + dom,
                    "window.fetch=async()=>{throw Error('Network unavailable')};",
                )
            )
        assertEquals("网页课程", imported.schedule.courses.single().name)
        assertEquals("测试乙老师", imported.schedule.sessions.single().teacher)
        assertEquals("示例校区 A214", imported.schedule.sessions.single().place)
        assertTrue(imported.warnings.any { it.contains("第2学期") })
        val legacy =
            draft(
                extract(
                    dom,
                    "window.fetch=()=>{throw Error('Must not fetch without a selected term')};",
                )
            )
        assertEquals(1, legacy.schedule.sessions.size)
    }

    @Test
    fun expiredLoginNeverImportsStaleVisibleCourses() {
        val loginResponse =
            """window.fetch=async()=>({ok:true,status:200,redirected:false,text:async()=>'<input id="yhm"><input id="mm">'});"""
        val redirected =
            """window.fetch=async()=>({ok:true,status:200,redirected:true,url:'https://portal.example.edu.cn/xtgl/login_slogin.html'});"""
        val denied = "window.fetch=async()=>({ok:false,status:403,redirected:false});"
        for (setup in listOf(loginResponse, redirected, denied)) {
            val result = extract(termInputs + dom, setup)
            assertTrue(result.contains("登录已失效"))
            assertFalse(result.contains("\"courses\""))
        }
        assertTrue(extract("<input id='yhm'><input id='mm'>").contains("请先登录"))
    }

    @Test
    fun changedTermAndEmptyTermAreRejectedWithoutGuessing() {
        val changed =
            extract(
                termInputs + dom,
                response("""{"kbList":[$row]}""", "document.getElementById('xqm').value='3';"),
            )
        assertTrue(changed.contains("学期已改变"))
        assertFalse(changed.contains("\"courses\""))
        val empty =
            extract(
                termInputs.replace("value=\"2024\"", "value=\"\""),
                response("""{"kbList":[$row]}"""),
            )
        assertTrue(empty.contains("选择有效的学年"))
    }

    @Test
    fun practiceCoursesAndEmptyApiCannotBeSilentlyDropped() {
        val practice =
            extract(
                termInputs + dom,
                response("""{"kbList":[$row],"sjkList":[{"qtkcgs":"课程设计：第17周，时间未定"}]}"""),
            )
        assertTrue(practice.contains("实践或时间未定课程"))
        assertFalse(practice.contains("\"courses\""))
        val empty = extract(termInputs + dom, response("""{"kbList":[],"sjkList":[]}"""))
        assertTrue(empty.contains("所选学期没有返回课程"))
        assertFalse(empty.contains("\"courses\""))
    }

    @Test
    fun sameOriginIframeTimetableUsesItsOwnSelectedTermAndTransport() {
        val frameBody = kotlinx.serialization.json.JsonPrimitive(termInputs).toString()
        val setup =
            """const frame=document.querySelector('iframe');
          frame.contentDocument.open();frame.contentDocument.write($frameBody);frame.contentDocument.close();
          frame.contentWindow.fetch=async(url,options)=>({ok:true,status:200,redirected:false,text:async()=>JSON.stringify({kbList:[$row]})});
        """
        val imported = draft(extract("<iframe src='about:blank'></iframe>", setup))
        assertEquals("接口课程", imported.schedule.courses.single().name)
        assertTrue(imported.warnings.any { it.contains("第2学期") })
    }
}

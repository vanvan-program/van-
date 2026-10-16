// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable

/** Reconstructed from the observed 2026-2027 term: no student identity or teaching-class IDs. */
internal object ZhengfangPageFixture {
    private val slots =
        """
        1-1|示例课程甲|3-4周,6-8周|甲老师|A414
        2-1|示例课程乙|3-4周,7-13周|乙老师|A214
        3-1|示例课程甲|3-4周,6-8周|甲老师|A414
        4-1|示例课程丙|3-4周,6-14周|丙老师|A314
        5-1|示例课程丁|3-14周|丁老师|A214
        6-1|示例课程乙|6周|乙老师|A214
        7-1|示例课程甲|3周,6周|甲老师|A414
        1-3|示例课程戊|3-4周,7-11周|戊老师|A314
        2-3|示例课程丙|3周,7-11周(单)|丙老师|A214
        3-3|示例课程乙|3-4周,6-12周|乙老师|A214
        4-3|示例课程丙|6-13周|丙老师|示例机房3
        6-3|示例课程丙|6周|丙老师|A214
        7-3|示例课程戊|3周|戊老师|A314
        7-3|示例课程乙|6周|乙老师|A214
        4-5|示例课程丁|3-14周|丁老师|A214
        1-7|示例课程己|3-4周,7-15周|己老师|B304
        3-7|示例课程己|3-4周,6-14周|己老师|A407
        4-7|示例课程戊|3-10周|戊老师|A214
        5-7|【调】示例课程庚|4-12周,16周|庚老师|示例教室306
        7-7|示例课程己|3周|己老师|B304
        7-7|示例课程己|6周|己老师|A407
        1-9|示例课程辛|3-4周,7-9周|辛老师|B412
        3-9|【调】示例课程庚|4-6周(双),7-12周,16周|庚老师|B512
        4-9|示例课程辛|3-8周|辛老师|A512
        7-9|示例课程辛|3周|辛老师|B412
        7-9|【调】示例课程庚|6周|庚老师|B512
        """
            .trimIndent()
            .lines()
            .map { it.split('|') }

    private val footer =
        """<div class="timetable_title"><span>实践课程：</span><span>示例实习壬老师(共2周)/1-2周;示例课程庚课程设计庚老师(共1周)/18周;示例课程丙课程设计丙老师(共1周)/19周;</span><br><span>其它课程：</span><span>示例课程乙乙老师(共1周)/13周/无;示例课程甲甲老师(共1周)/15周/无;示例课程庚庚老师(共4周)/9-12周/无;形势与政策E癸老师(共4周)/1-4周/无;示例课程丁丁老师(共1周)/15周/无;示例课程辛辛老师(共1周)/8周/无;</span><br></div>"""

    val html: String = buildString {
        append(
            """<select id="xnm"><option value="2026">2026-2027</option><option value="2025">2025-2026</option></select><select id="xqm"><option value="3">1</option><option value="12">2</option></select><table id="kbgrid_table_0"><tr><td colspan="9"><h6>2026-2027学年第1学期</h6></td></tr>"""
        )
        for ((cell, entries) in slots.groupBy { it[0] }) {
            append("<tr><td id=\"$cell\">")
            for (entry in entries) {
                val section = cell.substringAfter('-').toInt()
                append(
                    """<div class="timetable_con text-left"><span class="title"><font color="blue">${entry[1]}</font></span><p><span title="节/周"><font><span class="glyphicon glyphicon-time"></span></font></span><font> ($section-${section + 1}节)${entry[2]}</font></p><p><span title="上课地点"><font><span class="glyphicon glyphicon-map-marker"></span></font></span><font> 示例校区  ${entry[4]}</font></p><font> </font><p><span title="教师 "><font><span class="glyphicon glyphicon-user"></span></font></span><font> ${entry[3]}</font></p></div>"""
                )
            }
            append("</td></tr>")
        }
        append("<tr><td colspan=\"9\">$footer</td></tr></table>")
        // The actual page keeps this alternate representation mounted even in grid mode.
        append("<table id=\"kblist_table\" style=\"display:none\">")
        for (entry in slots) append(
            """<tr><td><div class="timetable_con"><span class="title">${entry[1]}</span><p>周数：${entry[2]}<span class="glyphicon-user"></span>教师：${entry[3]}</p></div></td></tr>"""
        )
        append("<tr><td>$footer</td></tr></table>")
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.van.timetable.system.ExcelReader
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import jxl.Workbook
import jxl.write.DateFormat
import jxl.write.DateTime
import jxl.write.Label
import jxl.write.Number
import jxl.write.WritableCellFormat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExcelReaderTest {
    // Original, synthetic workbooks; no student data or copied school files.
    private fun xlsx(
        first: String =
            """
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <sheetData><row r="1">
                <c r="A1" t="s"><v>0</v></c>
                <c r="B1" t="inlineStr"><is><t>星期一</t></is></c>
              </row><row r="2">
                <c r="A2"><v>1</v></c>
                <c r="B2" t="s"><v>1</v></c>
                <c r="D2" t="inlineStr"><is><r><t>教师：</t></r><r><t>张老师</t></r></is></c>
                <c r="E2" t="str"><f>"立德楼 A414"</f><v>立德楼 A414</v></c>
              </row><row r="3"><c r="A3"><v>2</v></c></row></sheetData>
              <mergeCells count="1"><mergeCell ref="B2:C3"/></mergeCells>
            </worksheet>
            """
                .trimIndent(),
        relation: String = "worksheets/course.xml",
        mode: String = "",
    ): ByteArray {
        val entries =
            linkedMapOf(
                "[Content_Types].xml" to
                    """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""",
                "xl/workbook.xml" to
                    """
                    <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                      <sheets><sheet name="个人课表" sheetId="9" r:id="rId9"/><sheet name="备注" sheetId="1" r:id="rId1"/></sheets>
                    </workbook>
                    """
                        .trimIndent(),
                "xl/_rels/workbook.xml.rels" to
                    """
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="/xl/worksheets/notes.xml"/>
                  <Relationship Id="rId9" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="$relation" $mode/>
                </Relationships>
            """
                        .trimIndent(),
                "xl/sharedStrings.xml" to
                    """
                    <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                      <si><t>节次</t></si>
                      <si><r><t>大数据技术</t></r><r><t xml:space="preserve">&#10;教师：张老师&#10;地点：立德楼 A414&#10;(1-2节)3-4周,6-8周</t></r><rPh sb="0" eb="2"><t>ignored phonetic</t></rPh></si>
                    </sst>
                    """
                        .trimIndent(),
                "xl/styles.xml" to
                    """
                    <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                      <cellXfs count="3"><xf numFmtId="0"/><xf numFmtId="20"/><xf numFmtId="14"/></cellXfs>
                    </styleSheet>
                    """
                        .trimIndent(),
                "xl/worksheets/course.xml" to first,
                "xl/worksheets/notes.xml" to
                    """
                    <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>请核对学期与开学日期</t></is></c></row></sheetData></worksheet>
                    """
                        .trimIndent(),
            )
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    @Test
    fun modernExcelPreservesSheetOrderRichStringsAndMergedAnchor() {
        val sheets = ExcelReader.read(xlsx())
        assertEquals(listOf("个人课表", "备注"), sheets.map { it.name })
        val cells = sheets.first().cells
        val course = cells.single { it.row == 1 && it.column == 1 }
        assertEquals("大数据技术\n教师：张老师\n地点：立德楼 A414\n(1-2节)3-4周,6-8周", course.text)
        assertEquals(2, course.rowSpan)
        assertEquals(2, course.columnSpan)
        assertFalse(cells.any { it.row == 2 && it.column == 1 })
        assertEquals("教师：张老师", cells.single { it.column == 3 }.text)
        assertEquals("立德楼 A414", cells.single { it.column == 4 }.text)
        assertEquals("请核对学期与开学日期", sheets[1].cells.single().text)
    }

    @Test
    fun modernExcelReadsCachedValuesAndFormatsNumericTimesAndDates() {
        val sheet =
            """
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData><row r="1">
              <c r="A1" s="1"><v>0.3333333333333333</v></c>
              <c r="B1" s="1"><v>0.4027777777777778</v></c>
              <c r="C1" s="2"><v>60</v></c>
              <c r="D1"><f>1+2</f><v>3</v></c>
              <c r="E1" t="b"><v>1</v></c>
            </row></sheetData></worksheet>
            """
                .trimIndent()
        assertEquals(
            listOf("08:00", "09:40", "1900-02-28", "3", "TRUE"),
            ExcelReader.read(xlsx(sheet)).first().cells.map { it.text },
        )
    }

    @Test
    fun legacyBinaryExcelReadsChineseMetadataMultipleSheetsAndMergesOnAndroid() {
        val output = ByteArrayOutputStream()
        val workbook = Workbook.createWorkbook(output)
        try {
            val sheet = workbook.createSheet("个人课表", 0)
            sheet.addCell(Label(0, 0, "节次"))
            sheet.addCell(Label(1, 0, "星期一"))
            sheet.addCell(Label(0, 1, "1"))
            sheet.addCell(Label(0, 2, "2"))
            sheet.addCell(Label(1, 1, "操作系统\n教师：李老师\n地点：立德楼 A214\n(1-2节)1-16周"))
            sheet.mergeCells(1, 1, 1, 2)
            // JXL NumberFormat accepts numeric formats only: it replaces "hh:mm" with
            // "#.###" before export. DateFormat creates an actual Excel time cell.
            sheet.addCell(Number(2, 1, 8.0 / 24, WritableCellFormat(DateFormat("hh:mm"))))
            sheet.addCell(
                Number(3, 1, (9.0 * 60 + 40) / 1440, WritableCellFormat(DateFormat("hh:mm")))
            )
            sheet.addCell(
                DateTime(
                    4,
                    1,
                    Date.from(Instant.parse("2026-09-07T00:00:00Z")),
                    WritableCellFormat(DateFormat("yyyy-MM-dd")),
                    DateTime.GMT,
                )
            )
            // A regular decimal with no date/time format must remain a decimal.
            sheet.addCell(Number(5, 1, 8.0 / 24))
            workbook.createSheet("备注", 1).addCell(Label(0, 0, "个人课表"))
            workbook.write()
        } finally {
            workbook.close()
        }
        val sheets = ExcelReader.read(output.toByteArray())
        assertEquals(listOf("个人课表", "备注"), sheets.map { it.name })
        val course = sheets.first().cells.single { it.row == 1 && it.column == 1 }
        assertTrue(course.text.contains("教师：李老师\n地点：立德楼 A214"))
        assertEquals(2, course.rowSpan)
        assertEquals(1, course.columnSpan)
        assertEquals("08:00", sheets.first().cells.single { it.column == 2 }.text)
        assertEquals("09:40", sheets.first().cells.single { it.column == 3 }.text)
        assertEquals("2026-09-07", sheets.first().cells.single { it.column == 4 }.text)
        assertEquals("0.333", sheets.first().cells.single { it.column == 5 }.text)
        assertFalse(sheets.first().cells.any { it.row == 2 && it.column == 1 })
    }

    @Test
    fun invalidReferencesExternalLinksAndDocumentTypesAreRejected() {
        val oversized =
            """<worksheet><sheetData><row r="1"><c r="A4097" t="inlineStr"><is><t>越界</t></is></c></row></sheetData></worksheet>"""
        assertTrue(runCatching { ExcelReader.read(xlsx(oversized)) }.isFailure)
        assertTrue(
            runCatching {
                    ExcelReader.read(
                        xlsx(
                            relation = "https://example.com/sheet.xml",
                            mode = "TargetMode=\"External\"",
                        )
                    )
                }
                .isFailure
        )
        val doctype =
            """<!DOCTYPE worksheet [<!ENTITY course "不允许的实体">]><worksheet><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>&course;</t></is></c></row></sheetData></worksheet>"""
        assertTrue(runCatching { ExcelReader.read(xlsx(doctype)) }.isFailure)
        assertTrue(runCatching { ExcelReader.read("not an Excel file".toByteArray()) }.isFailure)
    }
}

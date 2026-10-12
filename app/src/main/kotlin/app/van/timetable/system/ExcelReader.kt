// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.system

import android.util.Xml
import app.van.timetable.domain.DocumentCell
import app.van.timetable.domain.DocumentSheet
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import java.util.zip.ZipInputStream
import jxl.Cell
import jxl.DateCell
import jxl.NumberCell
import jxl.Workbook
import jxl.WorkbookSettings
import kotlin.math.floor
import kotlin.math.roundToLong
import org.xmlpull.v1.XmlPullParser

/**
 * Local Excel reader. It reads cached cell values and never evaluates formulas or external links.
 */
object ExcelReader {
    private const val MAX_INPUT = 20 * 1024 * 1024
    private const val MAX_UNZIPPED = 32 * 1024 * 1024
    private const val MAX_XML = 8 * 1024 * 1024
    private const val MAX_ROWS = 4096
    private const val MAX_COLUMNS = 128
    private const val MAX_CELLS = 50_000
    private const val MAX_SHEETS = 24
    private const val MAX_TEXT = 40_000
    private val ole =
        byteArrayOf(
            0xD0.toByte(),
            0xCF.toByte(),
            0x11,
            0xE0.toByte(),
            0xA1.toByte(),
            0xB1.toByte(),
            0x1A,
            0xE1.toByte(),
        )

    fun read(bytes: ByteArray): List<DocumentSheet> {
        require(bytes.isNotEmpty() && bytes.size <= MAX_INPUT) { "Excel 文件为空或超过 20 MB" }
        val sheets =
            when {
                bytes.size >= 8 && bytes.take(8).toByteArray().contentEquals(ole) -> xls(bytes)
                bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() ->
                    xlsx(bytes)
                else -> error("无法识别 Excel 文件，请选择 .xlsx 或 .xls 文件")
            }
        require(sheets.any { it.cells.isNotEmpty() }) { "Excel 文件中没有可读取的单元格" }
        require(sheets.sumOf { it.cells.size } <= MAX_CELLS) { "Excel 单元格过多，请只保留课表工作表" }
        return sheets
    }

    private data class Merge(
        val row: Int,
        val column: Int,
        val throughRow: Int,
        val throughColumn: Int,
    ) {
        fun covers(cell: DocumentCell) =
            cell.row in row..throughRow && cell.column in column..throughColumn
    }

    private fun merged(cells: List<DocumentCell>, ranges: List<Merge>): List<DocumentCell> {
        require(ranges.size <= 512) { "合并单元格过多，请只保留课表工作表" }
        return cells.mapNotNull { cell ->
            val range = ranges.firstOrNull { it.covers(cell) }
            when {
                range == null -> cell
                cell.row == range.row && cell.column == range.column ->
                    cell.copy(
                        rowSpan = range.throughRow - range.row + 1,
                        columnSpan = range.throughColumn - range.column + 1,
                    )
                else -> null
            }
        }
    }

    private fun xls(bytes: ByteArray): List<DocumentSheet> {
        val settings =
            WorkbookSettings().apply {
                setDrawingsDisabled(true)
                setNamesDisabled(true)
                setIgnoreBlanks(true)
                setGCDisabled(true)
                setInitialFileSize(1024 * 1024)
                setArrayGrowSize(1024 * 1024)
                setLocale(Locale.CHINA)
            }
        val workbook =
            try {
                Workbook.getWorkbook(ByteArrayInputStream(bytes), settings)
            } catch (e: Exception) {
                throw IllegalArgumentException("无法读取 .xls 文件，请检查文件是否损坏或加密", e)
            }
        try {
            require(workbook.numberOfSheets in 1..MAX_SHEETS) { "Excel 工作表过多，请只保留课表工作表" }
            var count = 0
            return (0 until workbook.numberOfSheets).map { index ->
                val sheet = workbook.getSheet(index)
                require(sheet.rows <= MAX_ROWS && sheet.columns <= MAX_COLUMNS) {
                    "Excel 行列过多，请只保留课表范围"
                }
                val cells = buildList {
                    for (row in 0 until sheet.rows) {
                        for (cell in sheet.getRow(row)) {
                            val text = clean(xlsText(cell))
                            if (text.isNotEmpty()) {
                                require(++count <= MAX_CELLS) { "Excel 单元格过多，请只保留课表工作表" }
                                add(DocumentCell(row, cell.column, text))
                            }
                        }
                    }
                }
                val ranges =
                    sheet.mergedCells.map { range ->
                        val first = range.topLeft
                        val last = range.bottomRight
                        merge(first.row, first.column, last.row, last.column)
                    }
                DocumentSheet(sheet.name, merged(cells, ranges))
            }
        } finally {
            workbook.close()
        }
    }

    private fun xlsText(cell: Cell): String {
        val format = cell.cellFormat?.format?.formatString
        return when (cell) {
            is NumberCell -> {
                // JXL may expose an Excel time format as a NumberCell and return a rounded
                // fraction from getContents(). Use its exact cached value and Excel format.
                val raw = cell.value.toString()
                val displayed = formattedNumber(raw, format, false)
                if (displayed == raw) cell.contents else displayed
            }
            is DateCell -> {
                // JXL resolves the workbook's 1900/1904 date mode into a UTC Date.
                val date = cell.date.toInstant().atOffset(ZoneOffset.UTC)
                val clock = "%02d:%02d".format(Locale.ROOT, date.hour, date.minute)
                val pattern =
                    format
                        .orEmpty()
                        .lowercase(Locale.ROOT)
                        .replace(Regex("\"[^\"]*\"|\\[[^]]*]|\\\\."), "")
                val hasDate = 'y' in pattern || 'd' in pattern
                val hasTime = 'h' in pattern || 's' in pattern
                when {
                    cell.isTime || (!hasDate && hasTime) -> clock
                    hasTime -> "${date.toLocalDate()} $clock"
                    else -> date.toLocalDate().toString()
                }
            }
            else -> cell.contents
        }
    }

    private data class SheetRef(val name: String, val relationship: String)

    private data class Manifest(val sheets: List<SheetRef>, val date1904: Boolean)

    private fun xlsx(bytes: ByteArray): List<DocumentSheet> {
        val entries = unzip(bytes)
        val workbook = entries["xl/workbook.xml"] ?: error("Excel 缺少工作簿信息，文件可能损坏或加密")
        val manifest = manifest(workbook)
        val relations =
            entries["xl/_rels/workbook.xml.rels"]?.let(::relationships) ?: error("Excel 缺少工作表关联信息")
        val shared = entries["xl/sharedStrings.xml"]?.let(::strings).orEmpty()
        val formats = entries["xl/styles.xml"]?.let(::formats).orEmpty()
        var totalCells = 0
        return manifest.sheets.map { sheet ->
            val path = relations[sheet.relationship] ?: error("Excel 工作表关联无效：${sheet.name}")
            val xml = entries[path] ?: error("Excel 缺少工作表：${sheet.name}")
            val cells = cells(xml, shared, formats, manifest.date1904)
            totalCells += cells.size
            require(totalCells <= MAX_CELLS) { "Excel 单元格过多，请只保留课表工作表" }
            DocumentSheet(sheet.name, cells)
        }
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        var total = 0
        var entryCount = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++entryCount <= 500) { "Excel 内容过多，请只保留课表工作表" }
                val name = entry.name.replace('\\', '/')
                require(!name.startsWith('/') && name.split('/').none { it == ".." }) {
                    "Excel 文件路径无效"
                }
                val keep =
                    name.startsWith("xl/") && (name.endsWith(".xml") || name.endsWith(".rels"))
                val output = if (keep) ByteArrayOutputStream() else null
                val buffer = ByteArray(8192)
                var size = 0
                while (true) {
                    val read = zip.read(buffer)
                    if (read < 0) break
                    total += read
                    size += read
                    require(total <= MAX_UNZIPPED && (!keep || size <= MAX_XML)) {
                        "Excel 解压内容过大，请只保留课表工作表"
                    }
                    output?.write(buffer, 0, read)
                }
                if (output != null) {
                    require(name !in result) { "Excel 文件包含重复路径" }
                    result[name] = output.toByteArray()
                }
                zip.closeEntry()
            }
        }
        return result
    }

    private inline fun parse(bytes: ByteArray, visit: (XmlPullParser, Int) -> Unit) {
        val parser =
            Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                setInput(ByteArrayInputStream(bytes), null)
            }
        var event = parser.eventType
        var tokens = 0
        while (event != XmlPullParser.END_DOCUMENT) {
            require(++tokens <= 1_000_000) { "Excel XML 内容过于复杂" }
            require(event != XmlPullParser.DOCDECL) { "Excel XML 不支持文档类型声明" }
            visit(parser, event)
            event = parser.nextToken()
        }
    }

    private fun XmlPullParser.attr(name: String): String? =
        (0 until attributeCount)
            .firstOrNull { getAttributeName(it) == name }
            ?.let { getAttributeValue(it) }

    private fun manifest(bytes: ByteArray): Manifest {
        val sheets = mutableListOf<SheetRef>()
        var date1904 = false
        parse(bytes) { parser, event ->
            if (event == XmlPullParser.START_TAG)
                when (parser.name) {
                    "workbookPr" -> date1904 = parser.attr("date1904") in listOf("1", "true")
                    "sheet" -> {
                        val name =
                            parser.attr("name").orEmpty().ifBlank { "工作表 ${sheets.size + 1}" }
                        val relationship = parser.attr("id") ?: error("Excel 工作表缺少关联信息")
                        sheets += SheetRef(clean(name), relationship)
                        require(sheets.size <= MAX_SHEETS) { "Excel 工作表过多，请只保留课表工作表" }
                    }
                }
        }
        require(sheets.isNotEmpty()) { "Excel 没有工作表" }
        return Manifest(sheets, date1904)
    }

    private fun relationships(bytes: ByteArray): Map<String, String> {
        val result = mutableMapOf<String, String>()
        parse(bytes) { parser, event ->
            if (
                event == XmlPullParser.START_TAG &&
                    parser.name == "Relationship" &&
                    parser.attr("Type").orEmpty().endsWith("/worksheet")
            ) {
                require(parser.attr("TargetMode") != "External") { "Excel 工作表不能引用外部文件" }
                val id = parser.attr("Id") ?: error("Excel 工作表关联无效")
                val target = parser.attr("Target") ?: error("Excel 工作表路径无效")
                require(':' !in target && '\\' !in target) { "Excel 工作表路径无效" }
                val segments = mutableListOf<String>()
                val base = if (target.startsWith('/')) target.drop(1) else "xl/$target"
                for (part in base.split('/')) {
                    when (part) {
                        "",
                        "." -> Unit
                        ".." -> {
                            require(segments.isNotEmpty()) { "Excel 工作表路径无效" }
                            segments.removeAt(segments.lastIndex)
                        }
                        else -> segments += part
                    }
                }
                val path = segments.joinToString("/")
                require(path.startsWith("xl/") && path.endsWith(".xml") && id !in result) {
                    "Excel 工作表关联无效"
                }
                result[id] = path
            }
        }
        return result
    }

    private fun strings(bytes: ByteArray): List<String> {
        val result = mutableListOf<String>()
        var current: StringBuilder? = null
        var inText = false
        var phonetic = 0
        parse(bytes) { parser, event ->
            when (event) {
                XmlPullParser.START_TAG ->
                    when (parser.name) {
                        "si" -> current = StringBuilder()
                        "rPh" -> phonetic++
                        "t" -> inText = phonetic == 0
                    }
                XmlPullParser.END_TAG ->
                    when (parser.name) {
                        "si" -> {
                            result += clean(current?.toString().orEmpty())
                            current = null
                            require(result.size <= 100_000) { "Excel 文本过多" }
                        }
                        "rPh" -> phonetic--
                        "t" -> inText = false
                    }
                XmlPullParser.TEXT,
                XmlPullParser.CDSECT,
                XmlPullParser.ENTITY_REF ->
                    if (inText) {
                        current?.append(parser.text.orEmpty())
                        require((current?.length ?: 0) <= MAX_TEXT) { "Excel 单元格文字过长" }
                    }
            }
        }
        return result
    }

    private fun formats(bytes: ByteArray): List<String> {
        val codes =
            mutableMapOf<Int, String>(
                14 to "yyyy-mm-dd",
                15 to "yyyy-mm-dd",
                16 to "yyyy-mm-dd",
                17 to "yyyy-mm-dd",
                18 to "hh:mm",
                19 to "hh:mm:ss",
                20 to "hh:mm",
                21 to "hh:mm:ss",
                22 to "yyyy-mm-dd hh:mm",
                45 to "mm:ss",
                46 to "[h]:mm:ss",
                47 to "mm:ss",
            )
        val ids = mutableListOf<Int>()
        var inStyles = false
        parse(bytes) { parser, event ->
            if (event == XmlPullParser.START_TAG)
                when (parser.name) {
                    "numFmt" ->
                        parser.attr("numFmtId")?.toIntOrNull()?.let {
                            codes[it] = parser.attr("formatCode").orEmpty()
                        }
                    "cellXfs" -> inStyles = true
                    "xf" -> if (inStyles) ids += parser.attr("numFmtId")?.toIntOrNull() ?: 0
                }
            if (event == XmlPullParser.END_TAG && parser.name == "cellXfs") inStyles = false
        }
        return ids.map { codes[it].orEmpty() }
    }

    private fun formattedNumber(value: String, format: String?, date1904: Boolean): String {
        if (format.isNullOrBlank()) return value
        val number = value.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: return value
        val pattern = format.lowercase(Locale.ROOT).replace(Regex("\"[^\"]*\"|\\[[^]]*]|\\\\."), "")
        val date = 'y' in pattern || 'd' in pattern
        val time = 'h' in pattern || 's' in pattern
        if (!date && !time) return value
        val seconds = ((number - floor(number)) * 86400).roundToLong().mod(86400)
        val clock = "%02d:%02d".format(Locale.ROOT, seconds / 3600, (seconds % 3600) / 60)
        if (!date) return clock
        if (number > 200_000) return value
        val day =
            if (date1904) LocalDate.of(1904, 1, 1).plusDays(floor(number).toLong())
            else {
                val serial = floor(number).toLong()
                LocalDate.of(1899, 12, 31).plusDays(serial - if (serial >= 60) 1 else 0)
            }
        return if (time) "$day $clock" else day.toString()
    }

    private fun cells(
        bytes: ByteArray,
        shared: List<String>,
        formats: List<String>,
        date1904: Boolean,
    ): List<DocumentCell> {
        val result = mutableListOf<DocumentCell>()
        val ranges = mutableListOf<Merge>()
        val positions = mutableSetOf<Pair<Int, Int>>()
        var row = -1
        var nextColumn = 0
        var current: Pair<Int, Int>? = null
        var kind = ""
        var style = -1
        var inValue = false
        var inText = false
        var phonetic = 0
        val value = StringBuilder()
        val inline = StringBuilder()
        parse(bytes) { parser, event ->
            when (event) {
                XmlPullParser.START_TAG ->
                    when (parser.name) {
                        "row" -> {
                            row = parser.attr("r")?.toIntOrNull()?.minus(1) ?: (row + 1)
                            require(row in 0 until MAX_ROWS) { "Excel 行数过多，请只保留课表范围" }
                            nextColumn = 0
                        }
                        "c" -> {
                            current = parser.attr("r")?.let(::position) ?: (row to nextColumn)
                            require(
                                current!!.first in 0 until MAX_ROWS &&
                                    current!!.second in 0 until MAX_COLUMNS
                            ) {
                                "Excel 行列过多，请只保留课表范围"
                            }
                            require(positions.add(current!!)) { "Excel 包含重复单元格位置" }
                            require(positions.size <= MAX_CELLS) { "Excel 单元格过多，请只保留课表工作表" }
                            nextColumn = current!!.second + 1
                            kind = parser.attr("t").orEmpty()
                            style = parser.attr("s")?.toIntOrNull() ?: -1
                            value.clear()
                            inline.clear()
                            phonetic = 0
                        }
                        "v" -> inValue = current != null
                        "rPh" -> if (current != null) phonetic++
                        "t" -> inText = current != null && phonetic == 0
                        "mergeCell" -> {
                            val range = parser.attr("ref")?.split(':').orEmpty()
                            require(range.size == 2) { "Excel 合并单元格范围无效" }
                            val first = position(range[0])
                            val last = position(range[1])
                            ranges += merge(first.first, first.second, last.first, last.second)
                            require(ranges.size <= 512) { "合并单元格过多，请只保留课表工作表" }
                        }
                    }
                XmlPullParser.END_TAG ->
                    when (parser.name) {
                        "v" -> inValue = false
                        "t" -> inText = false
                        "rPh" -> if (current != null) phonetic--
                        "c" -> {
                            val raw =
                                when (kind) {
                                    "s" ->
                                        shared.getOrNull(
                                            value.toString().trim().toIntOrNull() ?: -1
                                        ) ?: error("Excel 共享文本关联无效")
                                    "inlineStr" -> inline.toString()
                                    "b" -> if (value.toString() == "1") "TRUE" else "FALSE"
                                    "str",
                                    "e",
                                    "d" -> value.toString()
                                    else ->
                                        formattedNumber(
                                            value.toString(),
                                            formats.getOrNull(style),
                                            date1904,
                                        )
                                }
                            val text = clean(raw)
                            if (text.isNotEmpty())
                                current?.let { result += DocumentCell(it.first, it.second, text) }
                            current = null
                            inValue = false
                            inText = false
                        }
                    }
                XmlPullParser.TEXT,
                XmlPullParser.CDSECT,
                XmlPullParser.ENTITY_REF -> {
                    if (inValue) value.append(parser.text.orEmpty())
                    if (inText) inline.append(parser.text.orEmpty())
                    require(value.length <= MAX_TEXT && inline.length <= MAX_TEXT) {
                        "Excel 单元格文字过长"
                    }
                }
            }
        }
        return merged(result, ranges)
    }

    private fun position(reference: String): Pair<Int, Int> {
        val match =
            Regex("\\$?([A-Za-z]{1,3})\\$?([1-9][0-9]*)").matchEntire(reference)
                ?: error("Excel 单元格坐标无效")
        val column =
            match.groupValues[1].uppercase(Locale.ROOT).fold(0) { number, letter ->
                number * 26 + (letter - 'A' + 1)
            } - 1
        val row = match.groupValues[2].toIntOrNull()?.minus(1) ?: error("Excel 行数过多")
        require(row in 0 until MAX_ROWS && column in 0 until MAX_COLUMNS) { "Excel 行列过多，请只保留课表范围" }
        return row to column
    }

    private fun merge(row: Int, column: Int, throughRow: Int, throughColumn: Int): Merge {
        require(
            row in 0 until MAX_ROWS &&
                throughRow in row until MAX_ROWS &&
                column in 0 until MAX_COLUMNS &&
                throughColumn in column until MAX_COLUMNS
        ) {
            "Excel 合并单元格范围无效"
        }
        return Merge(row, column, throughRow, throughColumn)
    }

    private fun clean(text: String): String {
        require(text.length <= MAX_TEXT) { "Excel 单元格文字过长" }
        return text.replace("\r\n", "\n").replace('\r', '\n').trim()
    }
}

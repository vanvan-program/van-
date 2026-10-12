// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.domain

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Minimal read-only PDF text-layer extractor for machine-exported timetable PDFs.
 *
 * Academic-system exports commonly draw text with a non-embedded CID font (STSong-Light /
 * UniGB-UCS2-H in this project's sample). Renderers show such pages fine, but Android exposes no
 * text extraction API, so the app had to OCR rendered bitmaps and inherit recognition errors.
 * Reading the content stream directly recovers exact strings and geometry instead.
 *
 * Deliberate scope: unencrypted files, classic objects (no object streams, no xref dependency —
 * objects are scanned), Flate or uncompressed streams, Type0 fonts whose CMap is UCS2/UTF-16 style,
 * and single-byte simple fonts. Anything outside this scope yields little or no text and the caller
 * falls back to OCR.
 */
object PdfTextExtractor {
    private const val MAX_STREAM_BYTES = 64 * 1024 * 1024
    private const val MAX_LINES = 20_000

    data class Extraction(val lines: List<DocumentText>, val cjkCount: Int, val letterCount: Int)

    fun extract(bytes: ByteArray): Extraction {
        val objects = parseObjects(String(bytes, Charsets.ISO_8859_1))
        val catalog = objects.values.firstOrNull { compactDict(it.dict).contains("/Type/Catalog") }
        val pagesRef = catalog?.let { reference(it.dict, "/Pages") }
        val root =
            pagesRef?.let { objects[it] }
                ?: objects.values.firstOrNull { compactDict(it.dict).contains("/Type/Pages") }
                ?: return Extraction(emptyList(), 0, 0)
        val collected = mutableListOf<PageInfo>()
        collectPages(objects, root, Template(), collected, 0)
        val lines = mutableListOf<DocumentText>()
        var cjk = 0
        var letters = 0
        var offset = 0f
        var columnHeaders = emptyList<Run>()
        require(collected.size <= 12) { "PDF最多支持12页，请单独导出课表页面" }
        for ((pageIndex, page) in collected.withIndex()) {
            val contents = page.contentRefs.orEmpty().mapNotNull { objects[it]?.let(::streamOf) }
            if (contents.isEmpty()) continue
            val runs = mutableListOf<Run>()
            interpret(concat(contents), page, objects, runs)
            val grouped = groupLines(runs, page.displayWidth, referenceHeaders = columnHeaders)
            val observed = grouped.filter { Regex("^星期[一二三四五六日天]$").matches(it.text.trim()) }
            if (observed.size >= 5) columnHeaders = observed
            for (line in grouped) {
                if (lines.size >= MAX_LINES) return Extraction(lines, cjk, letters)
                lines +=
                    DocumentText(
                        line.text,
                        line.left,
                        line.top + offset,
                        line.right,
                        line.bottom + offset,
                        pageIndex,
                    )
                for (ch in line.text) {
                    when {
                        isCjk(ch) -> cjk++
                        ch.isLetterOrDigit() -> letters++
                    }
                }
            }
            offset += page.displayHeight * 1.5f
        }
        return Extraction(lines, cjk, letters)
    }

    fun hasUsableText(extraction: Extraction): Boolean =
        extraction.lines.size >= 2 && extraction.cjkCount + extraction.letterCount >= 30

    // ---- object layer ----

    private class PdfObj(val dict: String, val stream: ByteArray?)

    private open class Template(
        val mediaBox: FloatArray? = null,
        val rotate: Int? = null,
        val resources: String? = null,
    )

    private class PageInfo : Template {
        constructor(
            mediaBox: FloatArray?,
            rotate: Int?,
            resources: String?,
        ) : super(mediaBox, rotate, resources)

        var contentRefs: List<Int> = emptyList()
        var rotation = 0
        var boxX = 0f
        var boxY = 0f
        var displayWidth = 612f
        var displayHeight = 792f
    }

    private fun parseObjects(data: String): Map<Int, PdfObj> {
        val map = HashMap<Int, PdfObj>()
        val header = Regex("(\\d+)\\s+\\d+\\s+obj\\b")
        var idx = 0
        while (idx < data.length) {
            val match = header.find(data, idx) ?: break
            val num = match.groupValues[1].toInt()
            val bodyStart = match.range.last + 1
            val endObj = data.indexOf("endobj", bodyStart)
            if (endObj < 0) break
            val streamIdx = streamStart(data, bodyStart, endObj)
            if (streamIdx < 0) {
                map[num] = PdfObj(data.substring(bodyStart, endObj), null)
                idx = endObj + 6
            } else {
                val dict = data.substring(bodyStart, streamIdx)
                var s = streamIdx + "stream".length
                if (s < data.length && data[s] == '\r') s++
                if (s < data.length && data[s] == '\n') s++
                val endStream = data.indexOf("endstream", s)
                val stop = if (endStream < 0) data.length else endStream
                map[num] = PdfObj(dict, latin1(data.substring(s, stop)))
                idx = stop + "endstream".length
            }
        }
        return map
    }

    /** The stream keyword must directly follow the dictionary's closing >>. */
    private fun streamStart(data: String, from: Int, until: Int): Int {
        var idx = data.indexOf("stream", from)
        while (idx in from until until) {
            var back = idx - 1
            while (back >= from && (data[back] == '\n' || data[back] == '\r')) back--
            if (back >= from && data[back] == '>') return idx
            idx = data.indexOf("stream", idx + 6)
        }
        return -1
    }

    private fun streamOf(obj: PdfObj): ByteArray? {
        val raw = obj.stream ?: return obj.dict.toByteArray(Charsets.ISO_8859_1)
        val dict = compactDict(obj.dict)
        return when {
            "/FlateDecode" in dict -> inflate(raw)
            "/Filter" !in dict -> raw
            else -> null
        }
    }

    private fun inflate(raw: ByteArray): ByteArray? {
        for (rawMode in booleanArrayOf(false, true)) {
            val inflater = Inflater(rawMode)
            inflater.setInput(raw)
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            try {
                while (!inflater.finished() && output.size() <= MAX_STREAM_BYTES) {
                    val count = inflater.inflate(buffer)
                    if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                    output.write(buffer, 0, count)
                }
                if (output.size() > 0) return output.toByteArray()
            } catch (_: DataFormatException) {
                // fall through to the next mode
            } finally {
                inflater.end()
            }
        }
        return null
    }

    private fun collectPages(
        objects: Map<Int, PdfObj>,
        node: PdfObj,
        inherited: Template,
        out: MutableList<PageInfo>,
        depth: Int,
    ) {
        if (depth > 32 || out.size > 128) return
        val dict = node.dict
        val compact = compactDict(dict)
        val mediaBox = numberArray(dict, "/MediaBox") ?: inherited.mediaBox
        val rotate = keyInt(dict, "/Rotate") ?: inherited.rotate
        val resources = resourcesText(dict) ?: inherited.resources
        if (!compact.contains("/Type/Page") || compact.contains("/Type/Pages")) {
            for (kid in arrayReferences(dict, "/Kids")) {
                objects[kid]?.let {
                    collectPages(objects, it, Template(mediaBox, rotate, resources), out, depth + 1)
                }
            }
            return
        }
        val page = PageInfo(mediaBox, rotate, resources)
        page.contentRefs = arrayReferences(dict, "/Contents")
        val box = page.mediaBox
        val width = if (box != null && box.size >= 4) box[2] - box[0] else 612f
        val height = if (box != null && box.size >= 4) box[3] - box[1] else 792f
        val rotation = (((page.rotate ?: 0) % 360) + 360) % 360
        page.rotation = rotation
        page.boxX = box?.getOrNull(0) ?: 0f
        page.boxY = box?.getOrNull(1) ?: 0f
        if (rotation == 90 || rotation == 270) {
            page.displayWidth = height
            page.displayHeight = width
        } else {
            page.displayWidth = width
            page.displayHeight = height
        }
        out += page
    }

    // ---- dictionary helpers (tolerant; machine exports write plain ASCII keys) ----

    private fun compactDict(dict: String): String = dict.filter { !it.isWhitespace() }

    private fun reference(dict: String, key: String): Int? =
        Regex(Regex.escape(key) + "\\s+(\\d+)\\s+\\d+\\s+R")
            .find(dict)
            ?.groupValues
            ?.get(1)
            ?.toInt()

    private fun leadingRef(text: String): Int? =
        Regex("^\\s*(\\d+)\\s+\\d+\\s+R").find(text)?.groupValues?.get(1)?.toInt()

    private fun arrayReferences(dict: String, key: String): List<Int> {
        directArray(dict, key)?.let { body ->
            return Regex("(\\d+)\\s+\\d+\\s+R")
                .findAll(body)
                .mapNotNull { it.groupValues[1].toInt() }
                .toList()
        }
        return reference(dict, key)?.let { listOf(it) } ?: emptyList()
    }

    private fun directArray(dict: String, key: String): String? {
        val at = indexOfKey(dict, key) ?: return null
        var i = at + key.length
        while (i < dict.length && dict[i].isWhitespace()) i++
        if (i >= dict.length || dict[i] != '[') return null
        var depth = 0
        while (i < dict.length) {
            when (dict[i]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return dict.substring(at + key.length, i + 1)
                }
            }
            i++
        }
        return null
    }

    private fun keyInt(dict: String, key: String): Int? =
        Regex(Regex.escape(key) + "\\s+(-?\\d+)").find(dict)?.groupValues?.get(1)?.toInt()

    private fun numberArray(dict: String, key: String): FloatArray? {
        val body = directArray(dict, key) ?: return null
        val numbers = Regex("-?\\d+(?:\\.\\d+)?").findAll(body).map { it.value.toFloat() }.toList()
        return if (numbers.size >= 4) numbers.toFloatArray() else null
    }

    /** /Key << ... >> or /Key N 0 R; returns the raw value text. */
    private fun dictValue(dict: String, key: String): String? {
        val at = indexOfKey(dict, key) ?: return null
        var i = at + key.length
        while (i < dict.length && dict[i].isWhitespace()) i++
        if (i >= dict.length) return null
        if (dict[i] == '<' && i + 1 < dict.length && dict[i + 1] == '<') {
            var depth = 0
            val start = i
            while (i < dict.length) {
                if (dict[i] == '<' && i + 1 < dict.length && dict[i + 1] == '<') {
                    depth++
                    i += 2
                } else if (dict[i] == '>' && i + 1 < dict.length && dict[i + 1] == '>') {
                    depth--
                    i += 2
                    if (depth == 0) return dict.substring(start, i)
                } else i++
            }
            return null
        }
        return Regex("^\\d+\\s+\\d+\\s+R").find(dict.substring(i))?.value
    }

    /** /Resources may be inline or an indirect reference; return its dictionary text. */
    private fun resourcesText(dict: String, objects: Map<Int, PdfObj>? = null): String? {
        val value = dictValue(dict, "/Resources") ?: return null
        return if (value.startsWith("<<")) value
        else objects?.let { leadingRef(value)?.let(it::get) }?.dict ?: value
    }

    private fun indexOfKey(dict: String, key: String): Int? {
        var from = 0
        while (true) {
            val at = dict.indexOf(key, from)
            if (at < 0) return null
            val before = dict.getOrNull(at - 1)
            val after = dict.getOrNull(at + key.length)
            // '/' itself ends the previous token, so "0 R/Resources" is key "/Resources";
            // only an escaped slash inside a longer name can precede the key.
            val boundaryBefore = before == null || before != '/'
            val boundaryAfter = after == null || after in " \t\r\n\u000C" || after in "/<>[](){}%"
            if (boundaryBefore && boundaryAfter) return at
            from = at + 1
        }
    }

    // ---- content stream interpretation ----

    private data class Mat(
        val a: Float,
        val b: Float,
        val c: Float,
        val d: Float,
        val e: Float,
        val f: Float,
    ) {
        operator fun times(o: Mat) =
            Mat(
                a * o.a + b * o.c,
                a * o.b + b * o.d,
                c * o.a + d * o.c,
                c * o.b + d * o.d,
                e * o.a + f * o.c + o.e,
                e * o.b + f * o.d + o.f,
            )

        companion object {
            val ID = Mat(1f, 0f, 0f, 1f, 0f, 0f)
        }
    }

    private class Run(
        val text: String,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    )

    private enum class FontKind {
        UTF16,
        LATIN,
        UNSUPPORTED,
    }

    private class NameToken(val value: String)

    private fun fontKind(fontDict: String?): FontKind {
        if (fontDict == null) return FontKind.LATIN
        val compact = compactDict(fontDict)
        if ("/Type0" !in compact && "/Subtype/Type0" !in compact) return FontKind.LATIN
        val encoding = keyName(fontDict, "/Encoding") ?: return FontKind.UNSUPPORTED
        return when {
            encoding.contains("UCS2", true) ||
                encoding.contains("UTF16", true) ||
                encoding.equals("Identity-H", true) ||
                encoding.equals("Identity-V", true) -> FontKind.UTF16
            else -> FontKind.UNSUPPORTED
        }
    }

    private fun keyName(dict: String, key: String): String? =
        Regex(Regex.escape(key) + "\\s*/([A-Za-z0-9+\\-_,.]+)").find(dict)?.groupValues?.get(1)

    /** Resolves /Resources << /Font << /F1 5 0 R >> >> across indirect references. */
    private data class PdfFont(val kind: FontKind, val unicode: Map<Int, String> = emptyMap())

    private fun readFont(dict: String?, objects: Map<Int, PdfObj>): PdfFont {
        val kind = fontKind(dict)
        val cmap =
            dict
                ?.let { reference(it, "/ToUnicode") }
                ?.let { objects[it] }
                ?.let(::streamOf)
                ?.let { String(it, Charsets.ISO_8859_1) }
                ?: return PdfFont(
                    if (dict != null && keyName(dict, "/Encoding")?.startsWith("Identity-") == true)
                        FontKind.UNSUPPORTED
                    else kind
                )
        val mapping = mutableMapOf<Int, String>()
        fun decoded(hex: String): String {
            val data = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            return String(data, Charsets.UTF_16BE)
        }
        Regex("beginbfchar([\\s\\S]*?)endbfchar").findAll(cmap).forEach { block ->
            Regex("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>").findAll(block.groupValues[1]).forEach {
                mapping[it.groupValues[1].toInt(16)] = decoded(it.groupValues[2])
            }
        }
        Regex("beginbfrange([\\s\\S]*?)endbfrange").findAll(cmap).forEach { block ->
            Regex("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>\\s*(<([0-9A-Fa-f]+)>|\\[([^]]*)])")
                .findAll(block.groupValues[1])
                .forEach { range ->
                    val first = range.groupValues[1].toInt(16)
                    val last = range.groupValues[2].toInt(16)
                    require(last - first in 0..65535) { "PDF字体映射过大" }
                    if (range.groupValues[4].isNotBlank()) {
                        val base = range.groupValues[4].toLong(16)
                        val length = range.groupValues[4].length
                        for (code in first..last) mapping[code] =
                            decoded((base + code - first).toString(16).padStart(length, '0'))
                    } else {
                        Regex("<([0-9A-Fa-f]+)>")
                            .findAll(range.groupValues[5])
                            .take(last - first + 1)
                            .forEachIndexed { i, value ->
                                mapping[first + i] = decoded(value.groupValues[1])
                            }
                    }
                }
        }
        return PdfFont(if (mapping.isEmpty()) FontKind.UNSUPPORTED else FontKind.UTF16, mapping)
    }

    private fun pageFonts(page: PageInfo, objects: Map<Int, PdfObj>): Map<String, PdfFont> {
        val result = HashMap<String, PdfFont>()
        var resources = page.resources ?: return result
        leadingRef(resources)?.let { ref -> resources = objects[ref]?.dict ?: return result }
        val fontValue = dictValue(resources, "/Font") ?: return result
        val fontDictText =
            leadingRef(fontValue)?.let { objects[it]?.dict }
                ?: fontValue.takeIf { it.startsWith("<<") }
                ?: return result
        var i = 0
        while (i < fontDictText.length) {
            val slash = fontDictText.indexOf('/', i)
            if (slash < 0) break
            var j = slash + 1
            while (j < fontDictText.length && fontDictText[j] !in " \t\r\n/<>[]()") j++
            val name = fontDictText.substring(slash + 1, j)
            var k = j
            while (k < fontDictText.length && fontDictText[k].isWhitespace()) k++
            val refMatch = Regex("(\\d+)\\s+\\d+\\s+R").find(fontDictText, k)
            when {
                k < fontDictText.length && fontDictText[k].isDigit() && refMatch != null -> {
                    result[name] = readFont(objects[refMatch.groupValues[1].toInt()]?.dict, objects)
                    i = refMatch.range.last + 1
                }
                k < fontDictText.length && fontDictText[k] == '<' -> {
                    var depth = 0
                    var end = k
                    while (end < fontDictText.length) {
                        when (fontDictText[end]) {
                            '<' -> depth++
                            '>' -> {
                                depth--
                                if (depth == 0) break
                            }
                        }
                        end++
                    }
                    result[name] = readFont(fontDictText.substring(k, end + 1), objects)
                    i = end + 1
                }
                else -> i = j
            }
        }
        return result
    }

    private fun interpret(
        content: String,
        page: PageInfo,
        objects: Map<Int, PdfObj>,
        out: MutableList<Run>,
    ) {
        val fonts = pageFonts(page, objects)
        val rotation = page.rotation
        val boxX = page.boxX
        val boxY = page.boxY
        val mediaWidth =
            if (rotation == 90 || rotation == 270) page.displayHeight else page.displayWidth
        val mediaHeight =
            if (rotation == 90 || rotation == 270) page.displayWidth else page.displayHeight
        var ctm = Mat.ID
        val stack = ArrayDeque<Mat>()
        var tm = Mat.ID
        var leading = 0f
        var fontSize = 0f
        var kind = FontKind.LATIN
        var unicode = emptyMap<Int, String>()
        var charScale = 100f // Tz is a percentage; 100 keeps glyph advances at full size
        var charSpacing = 0f
        var unsupportedSeen = false
        val operands = ArrayList<Any>()

        fun number(depth: Int): Float =
            (operands.getOrNull(operands.size - 1 - depth) as? Float) ?: 0f

        fun lastString(): ByteArray? {
            for (k in operands.indices.reversed()) {
                val value = operands[k]
                if (value is ByteArray) return value
            }
            return null
        }

        fun show(bytes: ByteArray?) {
            if (bytes == null) return
            val text =
                when (kind) {
                    FontKind.UTF16 -> {
                        val data = if (bytes.size % 2 == 1) bytes.copyOf(bytes.size - 1) else bytes
                        if (unicode.isEmpty()) String(data, Charsets.UTF_16BE)
                        else
                            data.toList().chunked(2).joinToString("") {
                                unicode[((it[0].toInt() and 255) shl 8) or (it[1].toInt() and 255)]
                                    ?: "�"
                            }
                    }
                    FontKind.LATIN -> latinText(bytes)
                    FontKind.UNSUPPORTED -> {
                        unsupportedSeen = true
                        return
                    }
                }
            if (text.isEmpty() || fontSize <= 0f) return
            val trm = tm * ctm
            var ems = 0f
            for (ch in text) ems += if (ch.code >= 0x2E80) 1f else 0.5f
            val widthText =
                ems * fontSize * charScale / 100f + charSpacing * (text.length - 1).coerceAtLeast(0)
            val dx = trm.a * widthText
            val dy = trm.b * widthText
            val start = display(trm.e, trm.f, rotation, boxX, boxY, mediaWidth, mediaHeight)
            val end = display(trm.e + dx, trm.f + dy, rotation, boxX, boxY, mediaWidth, mediaHeight)
            val sizeDisp = hypot(trm.a, trm.b) * fontSize
            val left = minOf(start[0], end[0])
            val right = maxOf(start[0], end[0])
            if (right - left <= 0.01f || sizeDisp <= 0.01f) return
            val baseTop = page.displayHeight - maxOf(start[1], end[1])
            out += Run(text, left, baseTop - sizeDisp * 0.8f, right, baseTop + sizeDisp * 0.2f)
            if (out.size > MAX_LINES * 4) throw IllegalStateException("页面文字过多")
        }

        var i = 0
        val n = content.length
        while (i < n) {
            val ch = content[i]
            when {
                ch.isWhitespace() -> i++
                ch == '(' -> {
                    val (bytes, next) = literalString(content, i)
                    operands += bytes
                    i = next
                }
                ch == '<' && i + 1 < n && content[i + 1] == '<' -> {
                    var depth = 0
                    while (i < n) {
                        if (content[i] == '<' && i + 1 < n && content[i + 1] == '<') {
                            depth++
                            i += 2
                        } else if (content[i] == '>' && i + 1 < n && content[i + 1] == '>') {
                            depth--
                            i += 2
                            if (depth == 0) break
                        } else i++
                    }
                }
                ch == '<' -> {
                    val (bytes, next) = hexString(content, i)
                    operands += bytes
                    i = next
                }
                ch == '[' -> {
                    val (array, next) = arrayToken(content, i)
                    operands.add(array)
                    i = next
                }
                ch == ']' || ch == '{' || ch == '}' -> i++
                ch == '/' -> {
                    var j = i + 1
                    while (j < n && content[j] !in " \t\r\n/[]<>()") j++
                    operands += NameToken(content.substring(i + 1, j))
                    i = j
                }
                ch == '-' || ch == '+' || ch == '.' || ch.isDigit() -> {
                    val match = Regex("[-+]?\\d*\\.?\\d+").find(content, i) ?: return
                    operands += match.value.toFloat()
                    i = match.range.last + 1
                }
                ch == 'B' && i + 1 < n && content[i + 1] == 'I' -> {
                    val stop = content.indexOf("EI", i + 2)
                    i = if (stop < 0) n else stop + 2
                }
                content[i].isLetter() || ch == '*' || ch == '\'' || ch == '"' -> {
                    var j = i
                    while (
                        j < n &&
                            (content[j].isLetter() ||
                                content[j] == '*' ||
                                content[j] == '\'' ||
                                content[j] == '"')
                    ) j++
                    val op = content.substring(i, j)
                    i = j
                    when (op) {
                        "q" -> {
                            stack.addLast(ctm)
                            if (stack.size > 256) stack.removeFirst()
                        }
                        "Q" -> ctm = stack.removeLastOrNull() ?: Mat.ID
                        "cm" -> {
                            if (operands.size >= 6)
                                ctm =
                                    Mat(
                                        number(5),
                                        number(4),
                                        number(3),
                                        number(2),
                                        number(1),
                                        number(0),
                                    ) * ctm
                        }
                        "BT" -> tm = Mat.ID
                        "Tf" -> {
                            if (operands.size >= 2) {
                                fontSize = number(0)
                                val name = (operands[operands.size - 2] as? NameToken)?.value
                                kind = fonts[name]?.kind ?: FontKind.LATIN
                                unicode = fonts[name]?.unicode.orEmpty()
                            }
                        }
                        "Td" -> tm = Mat(1f, 0f, 0f, 1f, number(1), number(0)) * tm
                        "TD" -> {
                            leading = -number(0)
                            tm = Mat(1f, 0f, 0f, 1f, number(1), number(0)) * tm
                        }
                        "Tm" -> {
                            if (operands.size >= 6)
                                tm =
                                    Mat(
                                        number(5),
                                        number(4),
                                        number(3),
                                        number(2),
                                        number(1),
                                        number(0),
                                    )
                        }
                        "TL" -> leading = number(0)
                        "T*" -> tm = Mat(1f, 0f, 0f, 1f, 0f, -leading) * tm
                        "Tj" -> show(lastString())
                        "'" -> {
                            tm = Mat(1f, 0f, 0f, 1f, 0f, -leading) * tm
                            show(lastString())
                        }
                        "\"" -> {
                            tm = Mat(1f, 0f, 0f, 1f, 0f, -leading) * tm
                            show(lastString())
                        }
                        "TJ" -> {
                            val array = operands.lastOrNull() as? List<*>
                            if (array != null) {
                                val merged = ByteArrayOutputStream()
                                for (element in array) {
                                    if (element is ByteArray) merged.write(element)
                                }
                                show(merged.toByteArray())
                            }
                        }
                        "Tc" -> charSpacing = number(0)
                        "Tz" -> charScale = number(0)
                        else -> Unit
                    }
                    operands.clear()
                }
                else -> i++
            }
        }
        // Unsupported fonts yield no usable text, allowing the caller to fall back to OCR.
    }

    private fun display(
        x: Float,
        y: Float,
        rotation: Int,
        boxX: Float,
        boxY: Float,
        mediaWidth: Float,
        mediaHeight: Float,
    ): FloatArray {
        val px = x - boxX
        val py = y - boxY
        return when (rotation) {
            90 -> floatArrayOf(py, mediaWidth - px)
            180 -> floatArrayOf(mediaWidth - px, mediaHeight - py)
            270 -> floatArrayOf(mediaHeight - py, px)
            else -> floatArrayOf(px, py)
        }
    }

    private fun hypot(a: Float, b: Float): Float = kotlin.math.sqrt(a * a + b * b)

    private fun literalString(content: String, start: Int): Pair<ByteArray, Int> {
        val out = ByteArrayOutputStream()
        var i = start + 1
        var depth = 1
        while (i < content.length && depth > 0) {
            val ch = content[i]
            when {
                ch == '\\' && i + 1 < content.length -> {
                    val next = content[i + 1]
                    when {
                        next == 'n' -> {
                            out.write('\n'.code)
                            i += 2
                        }
                        next == 'r' -> {
                            out.write('\r'.code)
                            i += 2
                        }
                        next == 't' -> {
                            out.write('\t'.code)
                            i += 2
                        }
                        next == 'b' -> {
                            out.write(8)
                            i += 2
                        }
                        next == 'f' -> {
                            out.write(12)
                            i += 2
                        }
                        next in '0'..'7' -> {
                            var value = 0
                            var digits = 0
                            while (
                                i + 1 < content.length && digits < 3 && content[i + 1] in '0'..'7'
                            ) {
                                value = value * 8 + (content[i + 1] - '0')
                                digits++
                                i++
                            }
                            i++
                            out.write(value and 0xFF)
                        }
                        else -> {
                            if (next != '\n' && next != '\r') out.write(next.code)
                            if (next == '\r' && content.getOrNull(i + 2) == '\n') i++
                            i += 2
                        }
                    }
                }
                ch == '(' -> {
                    depth++
                    out.write(ch.code)
                    i++
                }
                ch == ')' -> {
                    depth--
                    if (depth > 0) out.write(ch.code)
                    i++
                }
                else -> {
                    out.write(ch.code and 0xFF)
                    i++
                }
            }
        }
        return out.toByteArray() to i
    }

    private fun hexString(content: String, start: Int): Pair<ByteArray, Int> {
        val digits = StringBuilder()
        var i = start + 1
        while (i < content.length && content[i] != '>') {
            val ch = content[i]
            if (ch.isDigit() || ch in 'a'..'f' || ch in 'A'..'F') digits.append(ch)
            i++
        }
        if (digits.length % 2 == 1) digits.append('0')
        val out = ByteArray(digits.length / 2)
        for (k in out.indices) {
            out[k] =
                ((Character.digit(digits[k * 2], 16) shl 4) or
                        Character.digit(digits[k * 2 + 1], 16))
                    .toByte()
        }
        return out to (i + 1)
    }

    private fun arrayToken(content: String, start: Int): Pair<List<Any>, Int> {
        val out = ArrayList<Any>()
        var i = start + 1
        while (i < content.length) {
            val ch = content[i]
            when {
                ch.isWhitespace() -> i++
                ch == ']' -> return out to (i + 1)
                ch == '(' -> {
                    val (bytes, next) = literalString(content, i)
                    out += bytes
                    i = next
                }
                ch == '<' && i + 1 < content.length && content[i + 1] != '<' -> {
                    val (bytes, next) = hexString(content, i)
                    out += bytes
                    i = next
                }
                ch == '-' || ch == '+' || ch == '.' || ch.isDigit() -> {
                    val match = Regex("[-+]?\\d*\\.?\\d+").find(content, i) ?: return out to (i + 1)
                    out += match.value.toFloat()
                    i = match.range.last + 1
                }
                else -> i++
            }
        }
        return out to i
    }

    private fun latin1(text: String): ByteArray = text.toByteArray(Charsets.ISO_8859_1)

    /** WinAnsi-style single-byte text. */
    private fun latinText(bytes: ByteArray): String =
        try {
            String(bytes, charset("windows-1252"))
        } catch (_: Exception) {
            String(bytes, Charsets.ISO_8859_1)
        }

    private fun concat(parts: List<ByteArray>): String {
        val raw = ByteArray(parts.sumOf { it.size })
        var at = 0
        for (part in parts) {
            part.copyInto(raw, at)
            at += part.size
        }
        return String(raw, Charsets.ISO_8859_1)
    }

    // ---- line assembly ----

    private fun groupLines(
        runs: List<Run>,
        displayWidth: Float,
        separateColumns: Boolean = true,
        referenceHeaders: List<Run> = emptyList(),
    ): List<Run> {
        if (separateColumns) {
            val preliminary = groupLines(runs, displayWidth, false)
            val observed = preliminary.filter { Regex("^星期[一二三四五六日天]$").matches(it.text.trim()) }
            val headers =
                (if (observed.size >= 5) observed
                    else
                        referenceHeaders.map {
                            Run(it.text, it.left, -100f, it.right, -90f)
                        })
                    .sortedBy { it.left }
            if (headers.size >= 5) {
                val centers = headers.map { (it.left + it.right) / 2 }
                val width = centers.zipWithNext { a, b -> b - a }.sorted().let { it[it.size / 2] }
                val edge = centers.first() - width / 2 - 4f
                return runs
                    .groupBy {
                        if (it.top < headers.minOf { h -> h.top } - 1f) -2
                        else if (it.left < edge) -1
                        else ((it.left - edge) / width).toInt().coerceAtMost(6)
                    }
                    .values
                    .flatMap { groupLines(it, displayWidth, false) }
                    .sortedWith(compareBy({ it.top }, { it.left }))
            }
        }
        val sorted = runs.sortedWith(compareBy({ it.top }, { it.left }))
        val rows = mutableListOf<MutableList<Run>>()
        for (run in sorted) {
            val row = rows.lastOrNull()
            val useRow =
                row != null &&
                    kotlin.math.abs(
                        (run.top + run.bottom) / 2f -
                            (row.maxOf { it.top } + row.minOf { it.bottom }) / 2f
                    ) <= 0.6f * (run.bottom - run.top).coerceAtLeast(0.5f)
            if (useRow) row?.add(run) else rows += mutableListOf(run)
        }
        val lines = mutableListOf<Run>()
        for (row in rows) {
            for (run in row.sortedBy { it.left }) {
                val last = lines.lastOrNull()
                val height = (run.bottom - run.top).coerceAtLeast(0.5f)
                val gapMax = maxOf(1.5f, minOf(0.6f * height, displayWidth * 0.02f))
                if (
                    last != null &&
                        run.left - last.right <= gapMax &&
                        ((run.top + run.bottom) / 2f).isSameRow(last)
                ) {
                    lines[lines.size - 1] =
                        Run(
                            last.text + run.text,
                            last.left,
                            minOf(last.top, run.top),
                            run.right,
                            maxOf(last.bottom, run.bottom),
                        )
                } else lines += run
            }
        }
        return lines
    }

    private fun Float.isSameRow(other: Run): Boolean =
        kotlin.math.abs(this - (other.top + other.bottom) / 2f) <=
            0.6f * (other.bottom - other.top).coerceAtLeast(0.5f)

    private fun isCjk(ch: Char): Boolean =
        (ch.code in 0x2E80..0x9FFF) || (ch.code in 0x3400..0x4DBF) || (ch.code in 0xF900..0xFAFF)
}

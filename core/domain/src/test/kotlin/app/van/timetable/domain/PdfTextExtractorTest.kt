// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.domain

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import org.junit.Assert.*
import org.junit.Test

class PdfTextExtractorTest {
    private fun flate(text: String): ByteArray {
        val deflater = Deflater()
        deflater.setInput(text.toByteArray(Charsets.ISO_8859_1))
        deflater.finish()
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (!deflater.finished()) {
            val count = deflater.deflate(buffer)
            output.write(buffer, 0, count)
        }
        deflater.end()
        return output.toByteArray()
    }

    /** Literal string with UTF-16BE bytes written as octal escapes, as Type0 writers do. */
    private fun han(text: String): String {
        val bytes = text.toByteArray(Charsets.UTF_16BE)
        return bytes.joinToString("") { "\\%03o".format(it.toInt() and 0xFF) }
    }

    private fun pdf(objects: List<Triple<Int, String, ByteArray?>>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("%PDF-1.4\n".toByteArray(Charsets.ISO_8859_1))
        for ((id, dict, stream) in objects) {
            out.write("$id 0 obj\n".toByteArray(Charsets.ISO_8859_1))
            if (stream == null) {
                out.write("$dict\nendobj\n".toByteArray(Charsets.ISO_8859_1))
            } else {
                out.write(
                    (dict.removeSuffix(">>") + "/Length ${stream.size}>>\nstream\n").toByteArray(
                        Charsets.ISO_8859_1
                    )
                )
                out.write(stream)
                out.write("\nendstream\nendobj\n".toByteArray(Charsets.ISO_8859_1))
            }
        }
        out.write("trailer\n<</Root 1 0 R>>\n%%EOF\n".toByteArray(Charsets.ISO_8859_1))
        return out.toByteArray()
    }

    @Test
    fun rotatedCampusExportYieldsExactTextInDisplayOrder() {
        val content =
            "0 1 -1 0 595 0 cm\n" +
                "q\nBT\n/F1 9.5 Tf\n16 570 Td\n(${han("示例课程甲")}) Tj\n0 -11.5 Td\n" +
                "(${han("(1-2节)3-4周,6-8周/校区:示例校区/场地:A414/教师:己")}) Tj\nET\nQ\n" +
                "16 500 810 44 re\nS\n"
        val bytes =
            pdf(
                listOf(
                    Triple(1, "<</Type/Catalog/Pages 2 0 R>>", null as ByteArray?),
                    Triple(
                        2,
                        "<</Type/Pages/MediaBox [0 0 595 842]/Rotate 90/Resources <</Font <</F1 5 0 R>>>>/Kids [3 0 R]/Count 1>>",
                        null,
                    ),
                    Triple(3, "<</Type/Page/Parent 2 0 R/Contents 4 0 R>>", null),
                    Triple(4, "<</Filter/FlateDecode>>", flate(content)),
                    Triple(
                        5,
                        "<</Type/Font/Subtype/Type0/BaseFont/STSong-Light/Encoding/UniGB-UCS2-H>>",
                        null,
                    ),
                )
            )
        val extraction = PdfTextExtractor.extract(bytes)
        assertEquals(2, extraction.lines.size)
        assertTrue(extraction.cjkCount >= 19)
        val name = extraction.lines[0]
        val detail = extraction.lines[1]
        assertEquals("示例课程甲", name.text)
        assertEquals("(1-2节)3-4周,6-8周/校区:示例校区/场地:A414/教师:己", detail.text)
        assertEquals(16f, name.left, 0.6f)
        // MediaBox is portrait 595x842 with /Rotate 90, so display space is 842x595 top-down.
        assertEquals((595f - 570f) - 0.8f * 9.5f, name.top, 0.8f)
        assertEquals(5f * 9.5f, name.right - name.left, 1.2f)
        assertTrue(detail.top > name.top)
        assertEquals(16f, detail.left, 0.6f)
        // This two-line fragment is shorter than the full-document text quality threshold.
        assertFalse(PdfTextExtractor.hasUsableText(extraction))
    }

    @Test
    fun simpleLatinFontTjArrayAndEscapesDecode() {
        val content =
            "BT\n/F2 10 Tf\n100 700 Td\n[(Hel) -20 (lo \\(x\\))] TJ\n0 -12 Td\n(W\\\\2) Tj\nET\n"
        val bytes =
            pdf(
                listOf(
                    Triple(1, "<</Type/Catalog/Pages 2 0 R>>", null as ByteArray?),
                    Triple(2, "<</Type/Pages/MediaBox [0 0 612 792]/Kids [3 0 R]/Count 1>>", null),
                    Triple(
                        3,
                        "<</Type/Page/Parent 2 0 R/Contents 4 0 R/Resources <</Font <</F2 5 0 R>>>>>>",
                        null,
                    ),
                    Triple(4, "<</Filter/FlateDecode>>", flate(content)),
                    Triple(5, "<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>", null),
                )
            )
        val extraction = PdfTextExtractor.extract(bytes)
        assertEquals(listOf("Hello (x)", "W\\2"), extraction.lines.map { it.text })
        assertEquals((792f - 700f) - 0.8f * 10f, extraction.lines[0].top, 0.8f)
        assertEquals(100f, extraction.lines[0].left, 0.6f)
    }

    @Test
    fun scannedOrUnsupportedPdfReportsNoUsableText() {
        val empty =
            PdfTextExtractor.extract(
                pdf(
                    listOf(
                        Triple(1, "<</Type/Catalog/Pages 2 0 R>>", null as ByteArray?),
                        Triple(
                            2,
                            "<</Type/Pages/MediaBox [0 0 612 792]/Kids [3 0 R]/Count 1>>",
                            null,
                        ),
                        Triple(3, "<</Type/Page/Parent 2 0 R/Contents 4 0 R>>", null),
                        Triple(4, "<</Filter/FlateDecode>>", flate("16 500 200 40 re\nS\n")),
                    )
                )
            )
        assertEquals(0, empty.lines.size)
        assertFalse(PdfTextExtractor.hasUsableText(empty))

        val blocked =
            PdfTextExtractor.extract(
                pdf(
                    listOf(
                        Triple(1, "<</Type/Catalog/Pages 2 0 R>>", null as ByteArray?),
                        Triple(
                            2,
                            "<</Type/Pages/MediaBox [0 0 612 792]/Kids [3 0 R]/Count 1>>",
                            null,
                        ),
                        Triple(
                            3,
                            "<</Type/Page/Parent 2 0 R/Contents 4 0 R/Resources <</Font <</F1 5 0 R>>>>>>",
                            null,
                        ),
                        Triple(
                            4,
                            "<</Filter/FlateDecode>>",
                            flate("BT\n/F1 10 Tf\n10 700 Td\n(A) Tj\nET\n"),
                        ),
                        Triple(
                            5,
                            "<</Type/Font/Subtype/Type0/BaseFont/SimSun/Encoding/GB-EUC-H>>",
                            null,
                        ),
                    )
                )
            )
        assertEquals(0, blocked.lines.size)
        assertFalse(PdfTextExtractor.hasUsableText(blocked))
    }
}

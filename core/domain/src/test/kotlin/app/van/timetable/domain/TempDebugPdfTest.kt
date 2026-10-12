// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.domain

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import org.junit.Test

class TempDebugPdfTest {
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

    private fun pdf(objects: List<Triple<Int, String, ByteArray?>>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("%PDF-1.4\n".toByteArray(Charsets.ISO_8859_1))
        for ((id, dict, stream) in objects) {
            out.write("$id 0 obj\n".toByteArray(Charsets.ISO_8859_1))
            if (stream == null) {
                out.write("$dict\nendobj\n".toByteArray(Charsets.ISO_8859_1))
            } else {
                out.write((dict.removeSuffix(">>") + "/Length ${stream.size}>>\nstream\n").toByteArray(Charsets.ISO_8859_1))
                out.write(stream)
                out.write("\nendstream\nendobj\n".toByteArray(Charsets.ISO_8859_1))
            }
        }
        out.write("trailer\n<</Root 1 0 R>>\n%%EOF\n".toByteArray(Charsets.ISO_8859_1))
        return out.toByteArray()
    }

    @Test
    fun debug() {
        val bytes =
            pdf(
                listOf(
                    Triple(1, "<</Type/Catalog/Pages 2 0 R>>", null as ByteArray?),
                    Triple(2, "<</Type/Pages/MediaBox [0 0 612 792]/Kids [3 0 R]/Count 1>>", null),
                    Triple(3, "<</Type/Page/Parent 2 0 R/Contents 4 0 R/Resources <</Font <</F1 5 0 R>>>>>>", null),
                    Triple(4, "<</Filter/FlateDecode>>", flate("BT\n/F1 10 Tf\n10 700 Td\n(A) Tj\nET\n")),
                    Triple(5, "<</Type/Font/Subtype/Type0/BaseFont/SimSun/Encoding/GB-EUC-H>>", null),
                )
            )
        val result = PdfTextExtractor.extract(bytes)
        println("BLOCKED=" + result)
        val latin =
            pdf(
                listOf(
                    Triple(1, "<</Type/Catalog/Pages 2 0 R>>", null as ByteArray?),
                    Triple(2, "<</Type/Pages/MediaBox [0 0 612 792]/Kids [3 0 R]/Count 1>>", null),
                    Triple(3, "<</Type/Page/Parent 2 0 R/Contents 4 0 R/Resources <</Font <</F2 5 0 R>>>>>>", null),
                    Triple(4, "<</Filter/FlateDecode>>", flate("BT\n/F2 10 Tf\n100 700 Td\n[(Hel) -20 (lo \\(x\\))] TJ\n0 -12 Td\n(W\\\\2) Tj\nET\n")),
                    Triple(5, "<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>", null),
                )
            )
        println("LATIN=" + PdfTextExtractor.extract(latin).lines)
    }
}

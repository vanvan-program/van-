package app.van.timetable.domain

import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Optional local comparison; private source PDFs are never bundled with the app. */
class LocalPdfCompatibilityTest {
    @Test
    fun suppliedPdfs() {
        val paths = System.getenv("VAN_PDF_SAMPLES")?.split("|").orEmpty()
        assumeTrue(paths.size == 3 && paths.all { File(it).exists() })
        paths.forEachIndexed { i, path ->
            val extraction = PdfTextExtractor.extract(File(path).readBytes())
            val result = DocumentImport.fromPdf("Sample $i", extraction.lines)
            File(System.getenv("VAN_PDF_RESULTS"), "sample-$i.txt")
                .writeText(
                    extraction.lines.joinToString("\n") {
                        "${it.page} ${it.left},${it.top}: ${it.text}"
                    } +
                        "\nRESULT\n" +
                        result.rows.joinToString("\n") +
                        "\n" +
                        result.warnings.joinToString("\n")
                )
            check(PdfTextExtractor.hasUsableText(extraction))
            check(result.rows.isNotEmpty())
            val expectedTimed = listOf(20, 13, 26)[i]
            val expectedUntimed = listOf(5, 7, 9)[i]
            check(result.rows.count { !it.untimed } == expectedTimed)
            check(result.rows.count { it.untimed } == expectedUntimed)
            check(result.unresolvedCount == 0)
            val schedule =
                DocumentImport.toSchedule(result.rows, java.time.LocalDate.of(2026, 8, 31))
            Validation.requireValid(Codec.decode(Codec.encode(schedule)))
            check(schedule.sessions.count { it.mode == TimeMode.SECTIONS } == expectedTimed)
            if (i == 0) {
                check(
                    result.rows.any {
                        it.name == "概率论与数理统计" &&
                            it.startSection == "7" &&
                            it.endSection == "8" &&
                            it.weeks == "1-15单"
                    }
                )
                check(schedule.courses.any { it.name == "IT企业实习" })
            } else if (i == 1) {
                check(
                    result.rows.any {
                        it.name == "数字图像处理" &&
                            it.day == "4" &&
                            it.teacher == "郭晓峰" &&
                            it.place == "信息科技大楼北-301"
                    }
                )
            } else {
                check(
                    result.rows.any {
                        it.name == "数据库原理" &&
                            it.day == "3" &&
                            it.weeks == "4-6双,7-12,16" &&
                            it.place == "B512"
                    }
                )
                check(
                    result.rows.any {
                        it.name == "数据库原理课程设计" && it.teacher == "周丽莉" && it.weeks == "18"
                    }
                )
            }
        }
    }
}

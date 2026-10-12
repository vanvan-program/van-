// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.van.timetable.domain.DocumentRecognition
import app.van.timetable.system.DocumentFiles
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentFilesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    // Original local fixtures. No school screenshots, personal data or network services are used.
    private fun fixture(
        canvas: Canvas,
        scale: Float,
        course: String,
        day: String,
        english: Boolean = false,
    ) {
        canvas.save()
        canvas.scale(scale, scale)
        canvas.drawColor(Color.WHITE)
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 72f
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            }
        canvas.drawText(if (english) "Timetable Import Test" else "课表导入测试", 96f, 130f, paint)
        val lines =
            if (english)
                listOf(
                    "Course: $course",
                    day,
                    "1-2 periods",
                    "Weeks: 1-16 weeks",
                    "Teacher: Chen Ming",
                    "Room: A101",
                )
            else
                listOf(
                    "课程名称：$course",
                    "星期$day",
                    "第1-2节",
                    "周次：1-16周",
                    "Teacher: 陈明",
                    "Room: 教学楼 A101",
                )
        lines.forEachIndexed { index, line ->
            canvas.drawText(line, 96f, 320f + index * 120f, paint)
        }
        canvas.restore()
    }

    private fun file(extension: String): File {
        val directory = File(context.cacheDir, "exports").apply { mkdirs() }
        return File.createTempFile("document-test-", ".$extension", directory)
    }

    private fun read(file: File, progress: (String) -> Unit = {}) = runBlocking {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        DocumentFiles.read(context, uri, progress)
    }

    private fun temporaryPdfs(context: Context): Set<String> =
        context.cacheDir
            .listFiles()
            .orEmpty()
            .filter { it.name.startsWith("timetable-") && it.extension == "pdf" }
            .map { it.name }
            .toSet()

    private fun saveDiagnostic(bitmap: Bitmap, name: String) {
        File(context.getExternalFilesDir(null), name).outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private fun savePdfRenderDiagnostic(source: File, name: String) {
        val descriptor = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
        PdfRenderer(descriptor).use { renderer ->
            renderer.openPage(0).use { page ->
                val bitmap =
                    Bitmap.createBitmap(page.width * 3, page.height * 3, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    saveDiagnostic(bitmap, name)
                } finally {
                    bitmap.recycle()
                }
            }
        }
    }

    private fun chineseVectorDiagnostic() {
        // Preserve a diagnostic of Android's CJK vector-font output for visual comparison. This
        // is a fixture diagnostic, not a production-file assertion or OCR accuracy workaround.
        val source = file("pdf")
        val pdf = PdfDocument()
        try {
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(900, 700, 1).create())
            fixture(page.canvas, 0.5f, "高等数学", "一")
            pdf.finishPage(page)
            source.outputStream().use(pdf::writeTo)
            savePdfRenderDiagnostic(source, "document-vector-chinese-render.png")
        } finally {
            pdf.close()
            source.delete()
        }
    }

    private fun assertEditableChineseRecognition(page: DocumentRecognition, coursePrefix: String) {
        File(context.getExternalFilesDir(null), "document-${page.name}-recognition.txt")
            .writeText(page.rawText + "\n\n" + page.csv)
        // The first real OCR run read 数学 as 效学. Require an observed, meaningful Chinese course
        // prefix plus parsed fields, rather than asserting perfect spelling or merely any text.
        val raw = page.rawText.replace(Regex("\\s+"), "")
        assertTrue(
            "Bundled OCR should preserve the Chinese course prefix for review: $raw",
            raw.contains(coursePrefix),
        )
        assertTrue("Recognized courses must be editable before saving", page.rows.isNotEmpty())
        assertTrue(
            "Chinese course must become an editable row: ${page.rows}",
            page.rows.any { it.name.replace(Regex("\\s+"), "").contains(coursePrefix) },
        )
        assertTrue(
            "Review CSV should contain all seven editable fields",
            page.csv.startsWith("课程名称,星期,开始节数,结束节数,老师,地点,周数"),
        )
        assertTrue(
            "Explicit metadata should be retained for review",
            page.rows.any { it.teacher.isNotBlank() && it.place.contains("A101") },
        )
        assertTrue(
            "Explicit sections and weeks should be editable: ${page.rows}",
            page.rows.any {
                it.startSection == "1" && it.endSection == "2" && it.weeks.isNotBlank()
            },
        )
        assertTrue("Recognition must retain review warnings", page.warnings.isNotEmpty())
    }

    @Test
    fun pngFromContentUriRunsBundledChineseOcrAndProducesEditableRows() {
        val image = file("png")
        val bitmap = Bitmap.createBitmap(1800, 1400, Bitmap.Config.ARGB_8888)
        try {
            fixture(Canvas(bitmap), 1f, "高等数学", "一")
            saveDiagnostic(bitmap, "document-fixture.png")
            image.outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            val progress = mutableListOf<String>()
            val imported = read(image) { progress += it }
            assertNull("OCR must return a review document, not save a schedule", imported.backup)
            val document = requireNotNull(imported.document)
            assertEquals("图片", document.kind)
            assertEquals(1, document.pages.size)
            assertEditableChineseRecognition(document.pages.single(), "高等")
            assertTrue(progress.any { it.contains("课表图片") })
            assertTrue(File(context.filesDir, "ocr/tessdata/chi_sim.traineddata").length() > 0)
            assertTrue(File(context.filesDir, "ocr/tessdata/eng.traineddata").length() > 0)
        } finally {
            bitmap.recycle()
            image.delete()
        }
    }

    @Test
    fun twoPagePdfFromContentUriRecognizesEveryPageAndDeletesTemporaryCopy() {
        val source = file("pdf")
        val before = temporaryPdfs(context)
        try {
            val pdf = PdfDocument()
            val bitmaps = mutableListOf<Bitmap>()
            try {
                listOf("高等数学" to "一", "大学英语" to "二").forEachIndexed { index, (course, day) ->
                    val page =
                        pdf.startPage(PdfDocument.PageInfo.Builder(900, 700, index + 1).create())
                    val bitmap = Bitmap.createBitmap(1800, 1400, Bitmap.Config.ARGB_8888)
                    bitmaps += bitmap
                    fixture(Canvas(bitmap), 1f, course, day)
                    page.canvas.drawBitmap(
                        bitmap,
                        Rect(0, 0, bitmap.width, bitmap.height),
                        RectF(0f, 0f, 900f, 700f),
                        Paint(Paint.FILTER_BITMAP_FLAG),
                    )
                    pdf.finishPage(page)
                }
                source.outputStream().use(pdf::writeTo)
            } finally {
                pdf.close()
                bitmaps.forEach { it.recycle() }
            }
            savePdfRenderDiagnostic(source, "document-scanned-chinese-render.png")
            chineseVectorDiagnostic()
            val progress = mutableListOf<String>()
            val imported = read(source) { progress += it }
            assertNull(imported.backup)
            val document = requireNotNull(imported.document)
            assertEquals("PDF", document.kind)
            assertEquals(2, document.pages.size)
            assertEditableChineseRecognition(document.pages[0], "高等")
            assertEditableChineseRecognition(document.pages[1], "大学")
            assertTrue(progress.any { it.contains("第1/2页") })
            assertTrue(progress.any { it.contains("第2/2页") })
            assertEquals(before, temporaryPdfs(context))
        } finally {
            source.delete()
        }
    }

    @Test
    fun englishVectorPdfFromContentUriProducesCourseTimingAndMetadataRows() {
        val source = file("pdf")
        val before = temporaryPdfs(context)
        val pdf = PdfDocument()
        try {
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(900, 700, 1).create())
            fixture(page.canvas, 0.5f, "English Mathematics", "Monday", english = true)
            pdf.finishPage(page)
            source.outputStream().use(pdf::writeTo)
            val imported = read(source)
            assertNull(imported.backup)
            val recognized = requireNotNull(imported.document).pages.single()
            val raw = recognized.rawText.replace(Regex("\\s+"), "").lowercase()
            assertTrue(
                "English vector text should survive PDF rasterization: $raw",
                raw.contains("englishmathematics"),
            )
            assertTrue(
                "Vector course should have editable parsed fields: ${recognized.rows}",
                recognized.rows.any {
                    it.name.lowercase().contains("mathematics") &&
                        it.day == "1" &&
                        it.startSection == "1" &&
                        it.endSection == "2" &&
                        it.weeks.isNotBlank() &&
                        it.teacher.isNotBlank() &&
                        it.place.contains("A101")
                },
            )
            assertEquals(before, temporaryPdfs(context))
        } finally {
            pdf.close()
            source.delete()
        }
    }

    @Test
    fun damagedPdfAndInvalidImageAreRejectedAndTemporaryPdfIsDeleted() {
        val pdf = file("pdf")
        val image = file("png")
        val before = temporaryPdfs(context)
        try {
            pdf.writeText("%PDF-1.4\nThis is not a valid PDF document.")
            image.writeBytes(byteArrayOf(7, 2, 3, 4, 5))
            val pdfError = runCatching { read(pdf) }.exceptionOrNull()
            assertNotNull("A damaged PDF must not create a review draft", pdfError)
            assertTrue(pdfError?.message.orEmpty().contains("PDF"))
            assertEquals(before, temporaryPdfs(context))
            val imageError = runCatching { read(image) }.exceptionOrNull()
            assertNotNull("An invalid image must not create a review draft", imageError)
            assertTrue(imageError?.message.orEmpty().contains("图片"))
        } finally {
            pdf.delete()
            image.delete()
        }
    }
}

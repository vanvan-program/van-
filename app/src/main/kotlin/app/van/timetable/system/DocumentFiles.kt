// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.system

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import app.van.timetable.domain.*
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class DocumentSource(
    val name: String,
    val kind: String,
    val pages: List<DocumentRecognition>,
    val id: String = newId(),
)

data class ImportedFile(
    val backup: Backup? = null,
    val warnings: List<String> = emptyList(),
    val document: DocumentSource? = null,
)

/** All document content stays on this device; the bundled OCR engine makes no network calls. */
object DocumentFiles {
    suspend fun read(context: Context, uri: Uri, progress: (String) -> Unit = {}): ImportedFile {
        val name =
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "导入课表"
        val bytes = Files.readBytes(context, uri, 25 * 1024 * 1024)
        require(bytes.isNotEmpty()) { "文件为空" }
        val mime = context.contentResolver.getType(uri).orEmpty()
        val extension = name.substringAfterLast('.', "").lowercase()
        val pdf = bytes.take(5).toByteArray().contentEquals("%PDF-".toByteArray())
        val excel =
            bytes.size >= 8 &&
                (bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte() ||
                    bytes
                        .take(8)
                        .toByteArray()
                        .contentEquals(
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
                        ))
        return when {
            pdf || extension == "pdf" || mime == "application/pdf" -> {
                progress("正在识别PDF")
                ImportedFile(
                    document = DocumentSource(name, "PDF", pdf(context, name, bytes, progress))
                )
            }
            excel || extension in setOf("xlsx", "xls") -> {
                progress("正在读取Excel工作表")
                val sheets = ExcelReader.read(bytes)
                require(sheets.isNotEmpty()) { "Excel没有可读取的工作表" }
                ImportedFile(
                    document = DocumentSource(name, "Excel", sheets.map(DocumentImport::fromSheet))
                )
            }
            mime.startsWith("image/") ||
                extension in setOf("png", "jpg", "jpeg", "webp", "bmp", "heic", "heif") -> {
                progress("正在识别课表图片")
                val bitmap = decodeImage(bytes)
                val result =
                    try {
                        Ocr(context).use { it.recognize("课表图片", bitmap) }
                    } finally {
                        bitmap.recycle()
                    }
                ImportedFile(document = DocumentSource(name, "图片", listOf(result)))
            }
            else -> {
                val (backup, warnings) = Files.parse(Files.decode(bytes))
                ImportedFile(backup, warnings)
            }
        }
    }

    private suspend fun pdf(
        context: Context,
        name: String,
        bytes: ByteArray,
        progress: (String) -> Unit,
    ): List<DocumentRecognition> {
        // Academic-system exports carry an exact text layer; prefer it over OCR. Scanned PDFs
        // fall through to the rendered-bitmap path below.
        progress("正在读取PDF文字层")
        val extracted = runCatching { PdfTextExtractor.extract(bytes) }.getOrNull()
        if (extracted != null && PdfTextExtractor.hasUsableText(extracted)) {
            return listOf(DocumentImport.fromPdf(name, extracted.lines))
        }
        val file = File.createTempFile("timetable-", ".pdf", context.cacheDir)
        try {
            file.writeBytes(bytes)
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer =
                try {
                    PdfRenderer(descriptor)
                } catch (e: Exception) {
                    descriptor.close()
                    error("无法读取PDF，请检查文件是否损坏或受密码保护")
                }
            renderer.use {
                require(it.pageCount in 1..12) { "PDF最多支持12页，请单独导出课表页面" }
                return Ocr(context).use { ocr ->
                    (0 until it.pageCount).map { index ->
                        currentCoroutineContext().ensureActive()
                        progress("正在识别PDF第${index + 1}/${it.pageCount}页")
                        it.openPage(index).use { page ->
                            val scale =
                                minOf(
                                    2f,
                                    2800f / maxOf(page.width, page.height),
                                    kotlin.math.sqrt(
                                        6_000_000f / (page.width.toFloat() * page.height)
                                    ),
                                )
                            val bitmap =
                                Bitmap.createBitmap(
                                    (page.width * scale).toInt().coerceAtLeast(1),
                                    (page.height * scale).toInt().coerceAtLeast(1),
                                    Bitmap.Config.ARGB_8888,
                                )
                            try {
                                bitmap.eraseColor(Color.WHITE)
                                page.render(
                                    bitmap,
                                    null,
                                    null,
                                    PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                                )
                                ocr.recognize("第${index + 1}页", bitmap)
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    }
                }
            }
        } finally {
            file.delete()
        }
    }

    internal fun decodeImage(bytes: ByteArray): Bitmap {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        require(
            options.outWidth > 0 &&
                options.outHeight > 0 &&
                options.outWidth.toLong() * options.outHeight <= 100_000_000
        ) {
            "图片无法读取或尺寸过大"
        }
        options.inJustDecodeBounds = false
        options.inSampleSize = 1
        options.inPreferredConfig = Bitmap.Config.ARGB_8888
        while (
            maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 3200 ||
                options.outWidth.toLong() * options.outHeight /
                    options.inSampleSize /
                    options.inSampleSize > 6_000_000
        ) options.inSampleSize *= 2
        var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: error("图片无法读取")
        val orientation =
            runCatching {
                    ExifInterface(ByteArrayInputStream(bytes))
                        .getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
                }
                .getOrDefault(1)
        val matrix =
            Matrix().apply {
                when (orientation) {
                    2 -> setScale(-1f, 1f)
                    3 -> setRotate(180f)
                    4 -> setScale(1f, -1f)
                    5 -> {
                        setRotate(90f)
                        postScale(-1f, 1f)
                    }
                    6 -> setRotate(90f)
                    7 -> {
                        setRotate(-90f)
                        postScale(-1f, 1f)
                    }
                    8 -> setRotate(-90f)
                }
            }
        if (!matrix.isIdentity) {
            val rotated =
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated !== bitmap) bitmap.recycle()
            bitmap = rotated
        }
        // Flatten transparency so black glyphs on a transparent screenshot stay readable.
        val white = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(white).apply {
            drawColor(Color.WHITE)
            drawBitmap(bitmap, 0f, 0f, null)
        }
        bitmap.recycle()
        return white
    }

    private class Ocr(context: Context) : AutoCloseable {
        private val tess = TessBaseAPI()

        init {
            try {
                val root = File(context.filesDir, "ocr")
                val directory = File(root, "tessdata").apply { mkdirs() }
                for (language in listOf("chi_sim", "eng")) {
                    val target = File(directory, "$language.traineddata")
                    if (!target.exists()) {
                        val pending = File(directory, "$language.pending")
                        try {
                            context.assets.open("tessdata/$language.traineddata").use { input ->
                                pending.outputStream().use(input::copyTo)
                            }
                            check(pending.renameTo(target)) { "无法准备本机识别模型" }
                        } finally {
                            pending.delete()
                        }
                    }
                }
                check(tess.init(root.absolutePath, "chi_sim+eng", TessBaseAPI.OEM_LSTM_ONLY)) {
                    "无法初始化本机识别模型"
                }
                tess.pageSegMode = TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT
            } catch (e: Throwable) {
                tess.recycle()
                throw e
            }
        }

        fun recognize(name: String, bitmap: Bitmap): DocumentRecognition {
            val original = recognizeAtSize(name, bitmap)
            // Very large glyphs can reduce LSTM accuracy. Compare a smaller local render using
            // the engine's confidence rather than assuming one PDF/image resolution is best.
            if (maxOf(bitmap.width, bitmap.height) <= 2200) return original.first
            val smaller =
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * 2 / 3).coerceAtLeast(1),
                    (bitmap.height * 2 / 3).coerceAtLeast(1),
                    true,
                )
            return try {
                val alternative = recognizeAtSize(name, smaller)
                val hasCourses = alternative.first.rows.isNotEmpty()
                if (
                    hasCourses &&
                        (original.first.rows.isEmpty() || alternative.second > original.second)
                )
                    alternative.first
                else original.first
            } finally {
                smaller.recycle()
            }
        }

        private fun recognizeAtSize(name: String, bitmap: Bitmap): Pair<DocumentRecognition, Int> {
            tess.setImage(bitmap)
            tess.getUTF8Text()
            val confidence = tess.meanConfidence()
            val lines = mutableListOf<DocumentText>()
            val iterator = tess.resultIterator
            if (iterator != null) {
                try {
                    iterator.begin()
                    do {
                        val level = TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE
                        val text = iterator.getUTF8Text(level)?.trim().orEmpty()
                        val box = iterator.getBoundingRect(level)
                        if (text.isNotBlank())
                            lines +=
                                DocumentText(
                                    text,
                                    box.left.toFloat() / bitmap.width,
                                    box.top.toFloat() / bitmap.height,
                                    box.right.toFloat() / bitmap.width,
                                    box.bottom.toFloat() / bitmap.height,
                                )
                        require(lines.size <= 10000) { "页面文字过多，请仅保留课表区域" }
                    } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE))
                } finally {
                    iterator.delete()
                }
            }
            tess.clear()
            return DocumentImport.fromPositioned(name, lines) to confidence
        }

        override fun close() {
            tess.recycle()
        }
    }
}

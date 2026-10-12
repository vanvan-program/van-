// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.system

import android.content.*
import android.graphics.*
import android.net.Uri
import androidx.core.content.FileProvider
import app.van.timetable.domain.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.time.LocalDate

object Files {
    fun read(context: Context, uri: Uri): String {
        return decode(readBytes(context, uri, 10 * 1024 * 1024))
    }

    fun readBytes(context: Context, uri: Uri, limit: Int): ByteArray {
        val stream = context.contentResolver.openInputStream(uri) ?: error("无法打开文件")
        return stream.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(out.size() + count <= limit) { "文件超过${limit / 1024 / 1024}MB限制" }
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
    }

    fun decode(bytes: ByteArray): String =
        runCatching {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            }
            .getOrElse {
                Charset.forName("GB18030")
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            }

    fun parse(text: String): Pair<Backup, List<String>> {
        return Importers.file(text, app.van.timetable.ui.mondayNow())
    }

    fun shareData(context: Context, s: Schedule) {
        share(
            context,
            "${safeName(s.name)}.van.json",
            Codec.backup(Backup(scope = "single", schedules = listOf(s))).toByteArray(),
            "application/json",
        )
    }

    fun share(context: Context, name: String, bytes: ByteArray, mime: String) {
        val folder = File(context.cacheDir, "exports").apply { mkdirs() }
        val f = File(folder, name)
        f.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND)
                    .setType(mime)
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                "分享课表",
            )
        )
    }

    private fun safeName(name: String) =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(60).ifBlank { "课表" }

    fun image(s: Schedule, week: Int, dark: Boolean, teachers: Boolean): ByteArray {
        val monday = LocalDate.parse(s.week1Monday).plusWeeks((week - 1).toLong())
        val cols = (0..6).map { d -> CourseEngine.timetableDay(s, monday.plusDays(d.toLong())) }
        val maxRows = maxOf(1, cols.maxOf { it.size })
        val width = 1400
        val height = 200 + maxRows * 220
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.parseColor(if (dark) "#111A2A" else "#F8F9FE"))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.parseColor(if (dark) "#EFF3FA" else "#18233A")
        paint.textSize = 42f
        canvas.drawText("${s.name} · 第${week}周", 32f, 60f, paint)
        paint.textSize = 22f
        canvas.drawText("${monday} 起 · van课表", 32f, 102f, paint)
        cols.forEachIndexed { d, items ->
            paint.color = Color.parseColor(if (dark) "#EFF3FA" else "#18233A")
            paint.textSize = 26f
            canvas.drawText(
                "${app.van.timetable.ui.weekdays[d]} ${monday.plusDays(d.toLong()).dayOfMonth}",
                d * 200f + 14,
                154f,
                paint,
            )
            items.forEachIndexed { i, o ->
                val holiday = CourseEngine.holiday(s, o.date) != null
                val x = d * 200f + 8
                val y = 180f + i * 220
                paint.color =
                    if (holiday) Color.parseColor(if (dark) "#344155" else "#D4D7DE")
                    else CoursePalette.argb(o.course, dark).toInt()
                canvas.drawRoundRect(RectF(x, y, x + 184, y + 208), 14f, 14f, paint)
                paint.color =
                    Color.parseColor(
                        if (holiday) {
                            if (dark) "#C1CBDA" else "#55565A"
                        } else if (
                            CoursePalette.textArgb(CoursePalette.argb(o.course, dark)) == 0xFFFFFFFF
                        )
                            "#FFFFFF"
                        else "#142036"
                    )
                paint.textSize = 24f
                val title =
                    (if (holiday) "[放假]" else "") + (if (o.adjusted) "【调】" else "") + o.course.name
                val lines = wrap(title, paint, 164f)
                lines.take(3).forEachIndexed { j, line ->
                    canvas.drawText(line, x + 10, y + 34 + j * 30, paint)
                }
                paint.textSize = 20f
                wrap(o.place, paint, 164f).take(2).forEachIndexed { j, line ->
                    canvas.drawText(line, x + 10, y + 130 + j * 24, paint)
                }
                canvas.drawText("${o.start}–${o.end}", x + 10, y + 188, paint)
                if (teachers && o.session.teacher.isNotBlank()) {
                    paint.textSize = 16f
                    canvas.drawText(o.session.teacher.take(10), x + 10, y + 164, paint)
                }
            }
        }
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private fun wrap(text: String, paint: Paint, width: Float): List<String> {
        val lines = mutableListOf<String>()
        var line = ""
        text.forEach { c ->
            if (line.isNotEmpty() && paint.measureText(line + c) > width) {
                lines += line
                line = ""
            }
            line += c
        }
        if (line.isNotEmpty()) lines += line
        return lines
    }
}

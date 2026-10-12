// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun CourseColorPicker(initial: Long, onDismiss: () -> Unit, onSelect: (Long) -> Unit) {
    val hsv =
        remember(initial) {
            FloatArray(3).also { android.graphics.Color.colorToHSV(initial.toInt(), it) }
        }
    var hue by rememberSaveable { mutableFloatStateOf(hsv[0]) }
    var saturation by rememberSaveable { mutableFloatStateOf(hsv[1]) }
    var brightness by rememberSaveable { mutableFloatStateOf(hsv[2]) }
    var hex by rememberSaveable { mutableStateOf("#%06X".format(initial and 0xFFFFFF)) }
    var error by remember { mutableStateOf("") }
    fun argb() =
        android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, brightness)).toLong() and
            0xFFFFFFFF
    fun syncHex() {
        hex = "#%06X".format(argb() and 0xFFFFFF)
        error = ""
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("调色盘") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Canvas(
                    Modifier.fillMaxWidth().height(150.dp).testTag("color-plane").pointerInput(
                        hue
                    ) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            fun pick(position: Offset) {
                                saturation = (position.x / size.width).coerceIn(0f, 1f)
                                brightness = (1 - position.y / size.height).coerceIn(0f, 1f)
                                syncHex()
                            }
                            pick(down.position)
                            down.consume()
                            do {
                                val event = awaitPointerEvent()
                                event.changes.forEach {
                                    if (it.pressed) pick(it.position)
                                    it.consume()
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    }
                ) {
                    drawRect(Color.hsv(hue, 1f, 1f))
                    drawRect(Brush.horizontalGradient(listOf(Color.White, Color.Transparent)))
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                    val point = Offset(saturation * size.width, (1 - brightness) * size.height)
                    drawCircle(Color.Black, 7.dp.toPx(), point)
                    drawCircle(Color.White, 5.dp.toPx(), point)
                }
                Text("色相")
                Slider(
                    hue,
                    {
                        hue = it
                        syncHex()
                    },
                    valueRange = 0f..359.9f,
                    modifier = Modifier.semantics { contentDescription = "色相" },
                )
                Text("饱和度")
                Slider(
                    saturation,
                    {
                        saturation = it
                        syncHex()
                    },
                    modifier = Modifier.semantics { contentDescription = "饱和度" },
                )
                Text("明亮度")
                Slider(
                    brightness,
                    {
                        brightness = it
                        syncHex()
                    },
                    modifier = Modifier.semantics { contentDescription = "明亮度" },
                )
                Field(
                    hex,
                    {
                        hex = it
                        error = ""
                        val value = it.removePrefix("#")
                        if (value.matches(Regex("[0-9a-fA-F]{6}"))) {
                            val parsed = FloatArray(3)
                            android.graphics.Color.colorToHSV(
                                (0xFF000000 or value.toLong(16)).toInt(),
                                parsed,
                            )
                            hue = parsed[0]
                            saturation = parsed[1]
                            brightness = parsed[2]
                        }
                    },
                    "颜色代码 #RRGGBB",
                    Modifier.testTag("color-hex"),
                )
                val preview = Color(argb())
                Text(
                    "课程颜色预览",
                    color = courseTextColor(preview),
                    modifier =
                        Modifier.fillMaxWidth()
                            .background(preview, RoundedCornerShape(8.dp))
                            .padding(12.dp),
                )
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val value = hex.removePrefix("#")
                    if (value.matches(Regex("[0-9a-fA-F]{6}")))
                        onSelect(0xFF000000 or value.toLong(16))
                    else error = "请输入六位颜色代码，例如 #3874F6"
                }
            ) {
                Text("使用此颜色")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.ui

import android.widget.NumberPicker
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/** The two wheels edit a temporary range; only confirmation updates the course draft. */
@Composable
fun SectionRangeField(first: Int, last: Int, maximum: Int, onChange: (Int, Int) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    var start by rememberSaveable { mutableIntStateOf(first.coerceIn(1, maximum)) }
    var end by rememberSaveable { mutableIntStateOf(last.coerceIn(start, maximum)) }
    fun show() {
        start = first.coerceIn(1, maximum)
        end = last.coerceIn(start, maximum)
        open = true
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = ::show, modifier = Modifier.weight(1f).testTag("start-section")) {
            Text("开始节：$first")
        }
        OutlinedButton(onClick = ::show, modifier = Modifier.weight(1f).testTag("end-section")) {
            Text("结束节：$last")
        }
    }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("选择上课节次") },
        text = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Column(Modifier.weight(1f)) {
                    Text("开始节")
                    SectionWheel(start, 1, maximum, "开始节滚轮") {
                        start = it
                        if (end < it) end = it
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text("结束节")
                    SectionWheel(end, start, maximum, "结束节滚轮") { end = it }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onChange(start, end); open = false }) { Text("确定节次") } },
        dismissButton = { TextButton(onClick = { open = false }) { Text("取消") } },
    )
}

@Composable
private fun SectionWheel(value: Int, minimum: Int, maximum: Int, label: String, onChange: (Int) -> Unit) {
    val callback by rememberUpdatedState(onChange)
    val textColor = MaterialTheme.colorScheme.onSurface
    AndroidView(
        modifier = Modifier.fillMaxWidth().height(160.dp).testTag(if (label == "开始节滚轮") "start-section-wheel" else "end-section-wheel"),
        factory = { context -> NumberPicker(context).apply {
            wrapSelectorWheel = false
            descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
            setOnValueChangedListener { _, _, number -> callback(number) }
        } },
        update = { picker ->
            // Reset the lower bound before reducing maxValue to keep native bounds valid.
            picker.minValue = 1
            picker.maxValue = maximum
            picker.minValue = minimum
            picker.value = value.coerceIn(minimum, maximum)
            picker.contentDescription = label
            if (android.os.Build.VERSION.SDK_INT >= 29) picker.textColor = android.graphics.Color.argb(
                255, (textColor.red * 255).toInt(), (textColor.green * 255).toInt(), (textColor.blue * 255).toInt())
        },
    )
}

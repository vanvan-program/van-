// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun CourseNoteField(value: String, onChange: (String) -> Unit, enabled: Boolean = true) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text("课程备注") },
        placeholder = { Text("分数分布：平时 30%，期末 70%\n点名情况：每节课点名／偶尔抽查／不点名") },
        supportingText = { Text("可自由记录分数分布、点名情况、作业要求等。") },
        modifier = Modifier.fillMaxWidth().testTag("course-note-input"),
        enabled = enabled,
        minLines = 3,
        maxLines = 8,
        shape = RoundedCornerShape(14.dp),
    )
}

@Composable
internal fun CourseNoteDialog(
    value: String,
    onChange: (String) -> Unit,
    saving: Boolean,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("课程备注") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                CourseNoteField(value, onChange, enabled = !saving)
                Muted("留空保存可清除备注；同一课程的所有上课时段共用。")
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = onSave,
                modifier = Modifier.testTag("save-course-note"),
            ) { Text(if (saving) "保存中…" else "保存备注") }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("取消") } },
    )
}

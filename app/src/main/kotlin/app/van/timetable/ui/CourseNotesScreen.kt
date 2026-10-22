// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.van.timetable.domain.Schedule

@Composable
internal fun CourseNotesScreen(s: Schedule, vm: VanViewModel) {
    var editingId by rememberSaveable(s.id) { mutableStateOf<String?>(null) }
    var draft by rememberSaveable(s.id) { mutableStateOf("") }
    val saving by vm.busy.collectAsStateWithLifecycle()
    val courses = remember(s.courses) { s.courses.sortedBy { it.note.isBlank() } }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Muted("${s.name} · 共 ${s.courses.size} 门课程，${s.courses.count { it.note.isNotBlank() }} 门有备注")
        LazyColumn(
            modifier = Modifier.weight(1f).testTag("all-course-notes"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            if (s.courses.isEmpty()) item { Muted("还没有课程，添加课程后可在这里查看备注。") }
            items(courses, key = { it.id }) { course ->
                Panel(Modifier.testTag("course-note-summary-${course.id}")) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(course.name, modifier = Modifier.weight(1f).padding(top = 12.dp), fontWeight = FontWeight.SemiBold)
                        TextButton(
                            onClick = { draft = course.note; editingId = course.id },
                            enabled = !saving,
                            modifier = Modifier.testTag("edit-summary-note-${course.id}"),
                        ) { Text(if (course.note.isBlank()) "添加备注" else "编辑") }
                    }
                    if (course.note.isBlank()) Muted("暂无备注")
                    else SelectionContainer {
                        Text(course.note, modifier = Modifier.testTag("summary-note-content-${course.id}"))
                    }
                }
            }
        }
    }
    s.courses.firstOrNull { it.id == editingId }?.let { course ->
        CourseNoteDialog(
            value = draft,
            onChange = { draft = it },
            saving = saving,
            onSave = { vm.courseNote(s.id, course.id, draft) { editingId = null } },
            onDismiss = { editingId = null },
        )
    }
}

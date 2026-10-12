// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.van.timetable.domain.*
import java.time.*

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = LocalVan.current.surface,
    ) {
        Column(
            Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
fun Muted(text: String) {
    Text(text, color = LocalVan.current.muted, fontSize = 13.sp)
}

@Composable
fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    multi: Boolean = false,
) {
    OutlinedTextField(
        value,
        onChange,
        label = { Text(label) },
        modifier = modifier.fillMaxWidth(),
        singleLine = !multi,
        shape = RoundedCornerShape(14.dp),
    )
}

@Composable
fun Toggle(title: String, value: Boolean, onChange: (Boolean) -> Unit, description: String = "") {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (description.isNotEmpty()) Muted(description)
        }
        Switch(value, onChange)
    }
}

@Composable
fun Action(title: String, description: String = "", onClick: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = LocalVan.current.surface,
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium)
                if (description.isNotEmpty()) Muted(description)
            }
            Text("›", fontSize = 24.sp, color = LocalVan.current.blue)
        }
    }
}

@Composable
fun Confirm(title: String, text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("确认") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun CourseRow(
    o: Occurrence,
    now: Instant,
    onClick: () -> Unit,
    zone: ZoneId = ZoneId.of("Asia/Shanghai"),
    conflict: Boolean = false,
    inactive: Boolean = false,
) {
    val c = LocalVan.current
    val current = !inactive && now >= o.startsAt(zone) && now < o.endsAt(zone)
    Surface(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = if (inactive) c.inactive else c.surface,
        contentColor = if (inactive) c.inactiveText else c.text,
    ) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.width(5.dp)
                    .height(66.dp)
                    .background(
                        if (inactive) c.inactiveText else c.courseColor(o.course),
                        RoundedCornerShape(3.dp),
                    )
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row {
                    Text(
                        (if (inactive) "[放假]" else "") +
                            (if (o.adjusted) "【调】" else "") +
                            o.course.name,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    if (current) Text("正在上课", color = c.blue, fontSize = 12.sp)
                }
                Muted(o.place.ifBlank { "地点未填写" })
                Text("${o.start}–${o.end}" + (if (conflict) " · 时间冲突" else ""), fontSize = 14.sp)
                if (o.session.teacher.isNotBlank()) Muted(o.session.teacher)
            }
        }
    }
}

fun mondayNow(): LocalDate =
    LocalDate.now(ZoneId.of("Asia/Shanghai"))
        .with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

val weekdays = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

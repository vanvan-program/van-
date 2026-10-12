// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.time.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarDateField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    onlyMondays: Boolean = false,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val date = runCatching { LocalDate.parse(value) }.getOrNull()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick = { open = true }, modifier = modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.DateRange, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(date?.let { "$it ${weekdays[it.dayOfWeek.value - 1]}" } ?: "选择日期")
        }
    }
    if (open) {
        val selectable =
            remember(onlyMondays) {
                object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                        !onlyMondays ||
                            Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).dayOfWeek ==
                                DayOfWeek.MONDAY
                }
            }
        val picker =
            rememberDatePickerState(
                initialSelectedDateMillis =
                    date?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
                selectableDates = selectable,
            )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(
                    enabled = picker.selectedDateMillis != null,
                    onClick = {
                        picker.selectedDateMillis?.let {
                            onChange(
                                Instant.ofEpochMilli(it)
                                    .atZone(ZoneOffset.UTC)
                                    .toLocalDate()
                                    .toString()
                            )
                        }
                        open = false
                    },
                ) {
                    Text("确定日期")
                }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("取消") } },
        ) {
            DatePicker(
                state = picker,
                showModeToggle = false,
                title = { Text(if (onlyMondays) "请选择第1周的周一" else label, Modifier.padding(24.dp)) },
                modifier = Modifier.testTag("calendar-picker"),
            )
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.van.timetable.domain.*
import java.time.*

@Composable
fun HolidayScreen(s: Schedule, vm: VanViewModel) {
    val today = LocalDate.now(ZoneId.of(s.zone)).toString()
    var editing by rememberSaveable(s.id) { mutableStateOf<String?>(null) }
    var name by rememberSaveable(s.id) { mutableStateOf("假期") }
    var from by rememberSaveable(s.id) { mutableStateOf(today) }
    var through by rememberSaveable(s.id) { mutableStateOf(today) }
    var error by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<Holiday?>(null) }
    fun reset() {
        editing = null
        name = "假期"
        from = today
        through = today
        error = ""
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Muted("设置当前课表的放假日期，包含开始和结束两天。期间的课程在课表中以灰色卡片保留，标记为放假；小组件和提醒会跳过这些课程。撤销假期即可恢复原安排。")
        Field(name, { name = it }, "假期名称")
        CalendarDateField("放假开始日期", from, { from = it }, Modifier.testTag("holiday-from-date"))
        CalendarDateField(
            "放假结束日期",
            through,
            { through = it },
            Modifier.testTag("holiday-through-date"),
        )
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        Button(
            onClick = {
                runCatching {
                        val holiday = Holiday(editing ?: newId(), name.trim(), from, through)
                        val changed =
                            s.copy(holidays = s.holidays.filterNot { it.id == editing } + holiday)
                        Validation.requireValid(changed)
                        vm.save(changed, after = { reset() })
                    }
                    .onFailure { error = it.message.orEmpty() }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (editing == null) "添加假期" else "保存假期")
        }
        if (editing != null) TextButton(onClick = { reset() }) { Text("取消编辑") }
        Text("已设置假期", style = MaterialTheme.typography.titleMedium)
        if (s.holidays.isEmpty()) Muted("尚未设置假期")
        s.holidays
            .sortedBy { it.from }
            .forEach { holiday ->
                Panel {
                    Text(holiday.name, style = MaterialTheme.typography.titleMedium)
                    Muted("${holiday.from} 至 ${holiday.through}")
                    Row {
                        TextButton(
                            onClick = {
                                editing = holiday.id
                                name = holiday.name
                                from = holiday.from
                                through = holiday.through
                                error = ""
                            }
                        ) {
                            Text("编辑")
                        }
                        TextButton(onClick = { deleting = holiday }) { Text("撤销假期") }
                    }
                }
            }
    }
    deleting?.let { holiday ->
        Confirm("撤销假期", "撤销“${holiday.name}”后，恢复这些日期的原课程安排。", { deleting = null }) {
            vm.save(s.copy(holidays = s.holidays.filterNot { it.id == holiday.id }))
            if (editing == holiday.id) reset()
            deleting = null
        }
    }
}

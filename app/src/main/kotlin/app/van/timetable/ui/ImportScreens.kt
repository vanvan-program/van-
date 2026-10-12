// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.van.timetable.domain.*
import app.van.timetable.school.schoolAddress
import java.time.LocalDate

@Composable
fun ImportEntry(selectFile: () -> Unit, nav: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("先检查，再保存。", style = MaterialTheme.typography.headlineMedium)
        Action("选择本地课表文件", "PDF、Excel、课表图片、WakeUp、CSV和van课表备份", selectFile)
        Action("从教务系统获取", "用户在学校网页中自行登录") { nav("school") }
        Panel {
            Text("支持的信息", fontWeight = FontWeight.Medium)
            Text(
                "PDF、Excel与图片：先核对识别结果，再确认学期和作息。Excel支持.xlsx和.xls。\nWakeUp备份：课程、周次、作息和学期。\nCSV：课程、节次、周次、老师和地点，需要确认开学日期与作息。\nvan课表：支持完整备份与单份课表。"
            )
            Muted("导入默认保存为新课表，不覆盖旧表，也不自动复制旧表备注和调课。")
        }
    }
}

@Composable
fun SchoolEntry(p: Preferences, vm: VanViewModel, open: (String) -> Unit, selectFile: () -> Unit) {
    var address by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("导入自己的课表", style = MaterialTheme.typography.headlineSmall)
        Field(
            address,
            {
                address = it
                error = ""
            },
            "教务系统地址",
        )
        Muted("输入或粘贴教务系统网址，登录后进入个人课表页面获取课程。")
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        Button(
            onClick = {
                runCatching { schoolAddress(address) }
                    .onSuccess(open)
                    .onFailure { error = it.message.orEmpty() }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("打开教务系统")
        }
        Toggle(
            "保留教务登录会话",
            p.keepLogin,
            { v -> vm.prefs { it.copy(keepLogin = v) } },
            "默认清除登录状态；App不保存账号密码",
        )
        HorizontalDivider()
        Action("选择导入文件", "PDF、Excel、课表图片、WakeUp、CSV和van课表备份", selectFile)
    }
}

@Composable
fun ImportPreview(
    vm: VanViewModel,
    backup: Backup,
    warnings: List<String>,
    back: () -> Unit,
    finished: () -> Unit,
) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val s = backup.schedules[selected.coerceIn(backup.schedules.indices)]
    var name by rememberSaveable(s.id) { mutableStateOf(s.name) }
    var monday by rememberSaveable(s.id) { mutableStateOf(s.week1Monday) }
    var weeks by rememberSaveable(s.id) { mutableStateOf(s.maxWeeks.toString()) }
    var routineText by
        rememberSaveable(s.id) {
            mutableStateOf(
                s.routines.first().sections.joinToString("\n") {
                    "${it.number},${it.start},${it.end}"
                }
            )
        }
    var activate by rememberSaveable(s.id) { mutableStateOf(vm.state.value.active == null) }
    var error by remember { mutableStateOf("") }
    var restoreConfirm by remember { mutableStateOf(false) }
    fun edited(): Schedule {
        val sections =
            routineText
                .lines()
                .filter { it.isNotBlank() }
                .map {
                    val row = it.split(',', '，').map { v -> v.trim() }
                    require(row.size == 3) { "作息每行必须是三项" }
                    Section(row[0].toInt(), row[1], row[2])
                }
        return s.copy(
                name = name,
                week1Monday = monday,
                maxWeeks = weeks.toInt(),
                routines =
                    s.routines.mapIndexed { i, r ->
                        if (i == 0) r.copy(sections = sections) else r
                    },
            )
            .also(Validation::requireValid)
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (backup.schedules.size > 1)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                backup.schedules.forEachIndexed { i, v ->
                    FilterChip(selected == i, { selected = i }, label = { Text(v.name) })
                }
            }
        Panel {
            Text(
                "已读取 ${s.courses.size} 门课程 / ${s.sessions.size} 个时段",
                fontWeight = FontWeight.Medium,
            )
            Muted("来源：${s.source}")
            warnings.forEach { Text(it, color = LocalVan.current.blue) }
        }
        Field(name, { name = it }, "新课表名称")
        Field(monday, { monday = it }, "第1周周一 YYYY-MM-DD")
        Field(weeks, { weeks = it }, "总周数")
        Field(routineText, { routineText = it }, "作息（节次,开始时间,结束时间）", multi = true)
        val conflicts =
            remember(s) {
                (0 until s.maxWeeks * 7).sumOf { d ->
                    CourseEngine.conflicts(
                            CourseEngine.resolveDay(
                                s,
                                LocalDate.parse(s.week1Monday).plusDays(d.toLong()),
                            )
                        )
                        .size
                }
            }
        Panel {
            Text("冲突检查：${if(conflicts==0)"未发现"else"$conflicts 处，全部保留"}")
            Muted("同时间课程保存后可在日列表查看和选择提醒。")
        }
        Panel {
            Text("全部课程", fontWeight = FontWeight.Medium)
            s.courses.forEach { c ->
                Text(c.name)
                s.sessions
                    .filter { it.courseId == c.id }
                    .forEach { n ->
                        Muted(
                            if (n.mode == TimeMode.UNTIMED) "无固定上课时间"
                            else
                                "${if(n.day in 1..7)weekdays[n.day-1]else"整周"} ${if(n.mode==TimeMode.SECTIONS)"${n.startSection}–${n.endSection}节"else"${n.start}–${n.end}"}"
                        )
                        Muted(
                            "老师：${n.teacher.ifBlank { "未读取到" }} · 地点：${n.place.ifBlank { if(n.mode == TimeMode.UNTIMED) "未安排" else "未读取到" }}"
                        )
                        Muted("第${n.weeks.sorted().joinToString("、")}周")
                    }
            }
        }
        Toggle("保存后启用这份课表", activate, { activate = it }, "启用后提醒和桌面小组件切换到新表")
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        Button(
            onClick = {
                runCatching {
                        val copy = Importers.freshCopy(edited())
                        vm.save(copy, activate) {
                            vm.importData.value = null
                            finished()
                        }
                    }
                    .onFailure { error = it.message.orEmpty() }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("保存为新课表")
        }
        if (backup.scope == "full")
            OutlinedButton(
                onClick = { restoreConfirm = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("恢复完整备份 · 替换全部数据")
            }
        TextButton(onClick = back, modifier = Modifier.fillMaxWidth()) { Text("取消导入") }
    }
    if (restoreConfirm)
        Confirm(
            "恢复完整备份",
            "将用文件内的全部 ${backup.schedules.size} 份课表和设置替换本机数据。上方编辑仅用于另存新课表，不影响完整恢复。操作前会保存本机恢复快照。",
            { restoreConfirm = false },
        ) {
            restoreConfirm = false
            vm.work {
                vm.store.restore(backup)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    vm.importData.value = null
                    finished()
                }
            }
        }
}

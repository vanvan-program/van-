// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.van.timetable.domain.*
import app.van.timetable.system.DocumentSource

@Composable
fun DocumentReview(vm: VanViewModel, source: DocumentSource, preview: () -> Unit) {
    var selected by rememberSaveable(source.id) { mutableIntStateOf(0) }
    val sheetStates = rememberSaveableStateHolder()
    sheetStates.SaveableStateProvider("${source.id}:$selected") {
        DocumentReviewPage(vm, source, selected, { selected = it }, preview)
    }
}

@Composable
private fun DocumentReviewPage(
    vm: VanViewModel,
    source: DocumentSource,
    selected: Int,
    select: (Int) -> Unit,
    preview: () -> Unit,
) {
    val pages =
        if (source.kind == "Excel") listOf(source.pages[selected.coerceIn(source.pages.indices)])
        else source.pages
    var rows by
        remember(vm, source.id, selected) {
            vm.documentDrafts.getOrPut("${source.id}:$selected") {
                mutableStateOf(pages.flatMap { it.rows })
            }
        }
    var expanded by rememberSaveable(source.id, selected) { mutableIntStateOf(-1) }
    var showText by rememberSaveable(source.id, selected) { mutableStateOf(false) }
    var confirmed by rememberSaveable(source.id, selected) { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val rawText = pages.joinToString("\n\n") { "${it.name}\n${it.rawText}" }
    fun update(index: Int, value: DocumentCourseRow) {
        rows = rows.toMutableList().apply { set(index, value) }
        confirmed = false
        error = ""
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Panel {
                Text("核对识别结果", style = MaterialTheme.typography.headlineSmall)
                Text(source.name, fontWeight = FontWeight.Medium)
                Muted("${source.kind} · ${rows.size}个课程时段。点击课程可修改识别内容，缺少的信息请对照原课表补全。")
                if (source.kind != "Excel")
                    Muted("图片与PDF在本机识别。请使用包含星期、节次和周次的清晰完整课表；仅一周的截图通常不包含整个学期周次。")
                pages
                    .flatMap { it.warnings }
                    .distinct()
                    .forEach { Text(it, color = LocalVan.current.blue) }
                if (pages.sumOf { it.unresolvedCount } > 0) Muted("原文件中有内容未能完整识别，请展开原文检查遗漏。")
            }
        }
        if (source.kind == "Excel" && source.pages.size > 1)
            item {
                Column {
                    Text("选择工作表")
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        source.pages.forEachIndexed { i, page ->
                            FilterChip(
                                selected == i,
                                {
                                    select(i)
                                    error = ""
                                },
                                label = { Text(page.name) },
                            )
                        }
                    }
                }
            }
        item {
            TextButton(onClick = { showText = !showText }) {
                Text(if (showText) "收起识别原文" else "查看识别原文")
            }
            if (showText)
                Panel {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(rawText.ifBlank { "没有识别到文字，请换用清晰课表文件。" })
                    }
                }
        }
        itemsIndexed(rows) { index, row ->
            Panel {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { expanded = if (expanded == index) -1 else index }) {
                        Text(row.name.ifBlank { "课程${index + 1}：待填写" })
                    }
                    TextButton(
                        onClick = {
                            rows = rows.filterIndexed { i, _ -> i != index }
                            expanded = -1
                            confirmed = false
                        }
                    ) {
                        Text("移除")
                    }
                }
                Muted(
                    if (row.untimed) "无固定时间 · ${row.weeks.ifBlank { "周次待定，仅保存课程信息" }}"
                    else
                        "星期${row.day.ifBlank { "待填写" }} · ${row.startSection.ifBlank { "?" }}–${row.endSection.ifBlank { "?" }}节 · ${row.weeks.ifBlank { "周次待填写" }}"
                )
                if (expanded == index) {
                    Field(row.name, { update(index, row.copy(name = it)) }, "课程名称")
                    Toggle("无固定上课时间", row.untimed, { update(index, row.copy(untimed = it)) })
                    if (!row.untimed) {
                        Field(row.day, { update(index, row.copy(day = it)) }, "星期（1至7）")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Field(
                                row.startSection,
                                { update(index, row.copy(startSection = it)) },
                                "开始节次",
                                Modifier.weight(1f),
                            )
                            Field(
                                row.endSection,
                                { update(index, row.copy(endSection = it)) },
                                "结束节次",
                                Modifier.weight(1f),
                            )
                        }
                    }
                    Field(
                        row.weeks,
                        { update(index, row.copy(weeks = it)) },
                        if (row.untimed) "周次（可留空待定）" else "周次（如3-4、6-8或1-16单）",
                    )
                    Field(row.teacher, { update(index, row.copy(teacher = it)) }, "老师")
                    Field(row.place, { update(index, row.copy(place = it)) }, "地点")
                } else if (row.teacher.isNotBlank() || row.place.isNotBlank())
                    Muted("${row.teacher} · ${row.place}")
            }
        }
        item {
            OutlinedButton(
                onClick = {
                    rows = rows + DocumentCourseRow()
                    expanded = rows.lastIndex
                    confirmed = false
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("补充遗漏课程")
            }
        }
        item {
            Toggle("已对照原课表核对", confirmed, { confirmed = it }, "核对课程数量、星期、节次、周次、老师和地点")
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            Button(
                onClick = {
                    runCatching {
                            require(rows.isNotEmpty()) { "没有可导入的课程，请补充课程或重新选择文件" }
                            val draft = DocumentImport.toSchedule(rows, mondayNow())
                            vm.importData.value =
                                Backup(
                                    scope = "single",
                                    schedules =
                                        listOf(
                                            draft.copy(
                                                name = source.name.substringBeforeLast('.'),
                                                source = "${source.kind}导入",
                                            )
                                        ),
                                )
                            vm.importWarnings.value = listOf("识别结果已由你核对，请继续确认开学日期、总周数和真实作息。")
                            preview()
                        }
                        .onFailure { error = it.message.orEmpty() }
                },
                enabled = confirmed,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("继续核对学期与作息")
            }
        }
    }
}

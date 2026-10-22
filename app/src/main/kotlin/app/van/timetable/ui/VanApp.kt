// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.ui

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.van.timetable.domain.*
import app.van.timetable.school.*
import app.van.timetable.system.*
import app.van.timetable.widget.*
import java.time.*
import kotlinx.coroutines.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VanApp(
    vm: VanViewModel,
    initialCourse: String?,
    initialDate: String?,
    initialSchedule: String? = null,
    openTimetable: Boolean = false,
    launchSequence: Int = 0,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val imported by vm.importData.collectAsStateWithLifecycle()
    val warnings by vm.importWarnings.collectAsStateWithLifecycle()
    val document by vm.documentSource.collectAsStateWithLifecycle()
    val importProgress by vm.importProgress.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var route by rememberSaveable { mutableStateOf("main") }
    val stack =
        rememberSaveable(
            saver =
                listSaver<SnapshotStateList<String>, String>(
                    save = { it.toList() },
                    restore = { it.toMutableStateList() },
                )
        ) {
            mutableStateListOf<String>()
        }
    val screenStates = rememberSaveableStateHolder()
    var message by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableStateOf(Instant.now()) }
    var viewedScheduleId by rememberSaveable { mutableStateOf(initialSchedule) }
    var handledLaunch by rememberSaveable { mutableIntStateOf(-1) }
    var handledLaunchTarget by rememberSaveable { mutableStateOf<String?>(null) }
    val launchTarget = listOf(initialCourse, initialDate, initialSchedule, openTimetable).toString()
    val displayed =
        if (viewedScheduleId == null) state.active
        else state.schedules.firstOrNull { it.id == viewedScheduleId }
    fun nav(r: String) {
        if (r == "schedules") viewedScheduleId = null
        stack += route
        // Each visit owns a draft; restored editors must never overwrite a previously saved course.
        route = if (r.startsWith("edit:") || r.startsWith("quick:")) "$r:${newId()}" else r
    }
    fun back() {
        if (route.startsWith("edit:") || route.startsWith("quick:"))
            screenStates.removeState("$route:$tab:${displayed?.id.orEmpty()}:")
        route = if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) else "main"
    }
    fun home() {
        stack.clear()
        route = "main"
    }
    LaunchedEffect(Unit) { vm.messages.collect { message = it } }
    LaunchedEffect(Unit) {
        while (true) {
            now = Instant.now()
            delay(15_000)
        }
    }
    LaunchedEffect(
        state.ready,
        initialCourse,
        initialDate,
        initialSchedule,
        openTimetable,
        launchSequence,
    ) {
        if (!state.ready || (handledLaunch == launchSequence && handledLaunchTarget == launchTarget))
            return@LaunchedEffect
        handledLaunch = launchSequence
        handledLaunchTarget = launchTarget
        if (state.ready && openTimetable) {
            viewedScheduleId = initialSchedule
            home()
            tab = 0
        } else if (state.ready && initialCourse != null) {
            viewedScheduleId = initialSchedule
            home()
            tab = 0
            val targetSchedule =
                initialSchedule?.let { id -> state.schedules.firstOrNull { it.id == id } }
                    ?: state.active
            if (targetSchedule?.courses?.any { it.id == initialCourse } == true)
                nav("detail:$initialCourse${initialDate?.let{":$it"}.orEmpty()}")
            else message = "这门课程或绑定的课表已删除"
        }
    }
    BackHandler(route != "main") { back() }
    val pick =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null)
                vm.work {
                    try {
                        val result =
                            DocumentFiles.read(context, uri) { vm.importProgress.value = it }
                        vm.importData.value = result.backup
                        vm.importWarnings.value = result.warnings
                        vm.documentSource.value = result.document
                        withContext(Dispatchers.Main) {
                            vm.documentDrafts.clear()
                            nav(if (result.document != null) "document" else "preview")
                        }
                    } finally {
                        vm.importProgress.value = ""
                    }
                }
        }
    val export =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri ->
            if (uri != null)
                vm.work {
                    val text = vm.store.backup()
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(text.toByteArray())
                    } ?: error("无法写入备份文件")
                    vm.messages.emit("完整备份已保存")
                }
        }
    val schoolLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result
            ->
            if (result.resultCode == Activity.RESULT_OK) {
                val text = result.data?.getStringExtra("result")
                val school = result.data?.getStringExtra("schoolName")
                if (text != null && school != null)
                    vm.work {
                        val draft = Importers.school(text, school, mondayNow())
                        vm.importData.value =
                            Backup(scope = "single", schedules = listOf(draft.schedule))
                        vm.importWarnings.value = draft.warnings
                        withContext(Dispatchers.Main) { nav("preview") }
                    }
            }
        }
    val c = LocalVan.current
    Scaffold(
        modifier = Modifier.background(Brush.verticalGradient(listOf(c.top, c.background))),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            if (route != "main")
                TopAppBar(
                    title = { Text(title(route)) },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                (context as? ComponentActivity)
                                    ?.onBackPressedDispatcher
                                    ?.onBackPressed() ?: back()
                            }
                        ) {
                            Icon(Icons.Outlined.ArrowBack, "返回")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = c.top),
                )
        },
        bottomBar = {
            if (route == "main")
                Surface(
                    Modifier.navigationBarsPadding().padding(horizontal = 18.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(28.dp),
                    color =
                        if (c.dark) c.surface else androidx.compose.ui.graphics.Color(0xFF243249),
                ) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        listOf(
                                "课表" to Icons.Outlined.CalendarMonth,
                                "课程" to Icons.Outlined.MenuBook,
                                "我的" to Icons.Outlined.PersonOutline,
                            )
                            .forEachIndexed { i, (label, icon) ->
                                val tint =
                                    if (tab == i) androidx.compose.ui.graphics.Color.White
                                    else androidx.compose.ui.graphics.Color(0xFFB2BED1)
                                Column(
                                    Modifier.weight(1f)
                                        .heightIn(min = 56.dp)
                                        .selectable(
                                            selected = tab == i,
                                            role = Role.Tab,
                                            onClick = { tab = i },
                                        )
                                        .padding(vertical = 5.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    Box(
                                        Modifier.background(
                                                if (tab == i)
                                                    androidx.compose.ui.graphics.Color(0xFF3874F6)
                                                else androidx.compose.ui.graphics.Color.Transparent,
                                                RoundedCornerShape(14.dp),
                                            )
                                            .padding(horizontal = 15.dp, vertical = 2.dp)
                                    ) {
                                        Icon(icon, null, Modifier.size(20.dp), tint = tint)
                                    }
                                    Text(label, color = tint, fontSize = 11.sp, lineHeight = 14.sp)
                                }
                            }
                    }
                }
        },
    ) { insets ->
        Box(Modifier.padding(insets).fillMaxSize()) {
            if (!state.ready) CircularProgressIndicator(Modifier.align(Alignment.Center))
            else {
                val active = displayed
                val importStateKey =
                    when (route) {
                        "document" -> document?.id.orEmpty()
                        "preview" -> imported?.schedules?.firstOrNull()?.id.orEmpty()
                        else -> ""
                    }
                screenStates.SaveableStateProvider(
                    "$route:$tab:${active?.id.orEmpty()}:$importStateKey"
                ) {
                    when {
                        route == "main" ->
                            when (tab) {
                                0 ->
                                    if (active != null)
                                        key(
                                            active.week1Monday,
                                            active.maxWeeks,
                                            if (openTimetable) launchSequence else -1,
                                        ) {
                                            Timetable(
                                                active,
                                                state.preferences,
                                                now,
                                                ::nav,
                                                if (openTimetable) initialDate else null,
                                            )
                                        }
                                    else if (state.schedules.isEmpty()) Welcome(vm, ::nav)
                                    else
                                        Column(Modifier.padding(24.dp)) {
                                            Text(
                                                "尚未启用课表",
                                                style = MaterialTheme.typography.headlineMedium,
                                            )
                                            Action("选择启用课表", "已有 ${state.schedules.size} 份课表") {
                                                nav("schedules")
                                            }
                                        }
                                1 ->
                                    if (active != null) Courses(active, now, ::nav)
                                    else
                                        Column(Modifier.padding(24.dp)) {
                                            Action("先选择启用课表") { nav("schedules") }
                                        }
                                else -> Mine(state, ::nav)
                            }
                        route == "notes" -> active?.let { CourseNotesScreen(it, vm) } ?: Missing(::nav)
                        route == "import" -> ImportEntry({ pick.launch(arrayOf("*/*")) }, ::nav)
                        route == "document" ->
                            document?.let { DocumentReview(vm, it) { nav("preview") } }
                                ?: Missing(::nav)
                        route == "preview" ->
                            if (imported != null)
                                ImportPreview(vm, imported!!, warnings, ::back) {
                                    vm.documentSource.value = null
                                    vm.documentDrafts.clear()
                                    stack.clear()
                                    route = "schedules"
                                }
                            else
                                Column(Modifier.padding(24.dp)) {
                                    Text("导入草稿已清除")
                                    TextButton(onClick = ::back) { Text("返回") }
                                }
                        route == "school" ->
                            SchoolEntry(
                                state.preferences,
                                vm,
                                { url ->
                                    schoolLauncher.launch(
                                        Intent(context, SchoolActivity::class.java)
                                            .putExtra("url", url)
                                    )
                                },
                                { pick.launch(arrayOf("*/*")) },
                            )
                        route == "schedules" -> ScheduleList(state, vm, ::nav)
                        route == "newSchedule" -> ScheduleEditor(null, vm, ::back)
                        route.startsWith("schedule:") ->
                            state.schedules
                                .firstOrNull { it.id == route.substringAfter(':') }
                                ?.let { ScheduleEditor(it, vm, ::back) }
                        route == "semester" ->
                            active?.let { ScheduleEditor(it, vm, ::back) } ?: Missing(::nav)
                        route == "routines" ->
                            active?.let { Routines(it) { s -> vm.save(s) } } ?: Missing(::nav)
                        route == "days" -> active?.let { DayAdjustments(it, vm) } ?: Missing(::nav)
                        route == "holidays" ->
                            active?.let { HolidayScreen(it, vm) } ?: Missing(::nav)
                        route.startsWith("detail:") ->
                            active?.let { s ->
                                val parts = route.split(':')
                                s.courses
                                    .firstOrNull { it.id == parts[1] }
                                    ?.let { Detail(s, it, parts.getOrNull(2), vm, ::nav, ::back, parts.getOrNull(3), parts.getOrNull(4)) }
                                    ?: Missing(::nav)
                            } ?: Missing(::nav)
                        route.startsWith("edit:") ->
                            active?.let { s ->
                                val id = route.split(':')[1]
                                CourseEditor(s, s.courses.firstOrNull { it.id == id }, vm, ::back)
                            } ?: Missing(::nav)
                        route.startsWith("quick:") ->
                            active?.let { s ->
                                val parts = route.split(':')
                                s.courses
                                    .firstOrNull { it.id == parts[1] }
                                    ?.let { parent ->
                                        CourseEditor(
                                            s,
                                            null,
                                            vm,
                                            ::back,
                                            parent,
                                            if (parts[2] == "exam") "期末考试" else "实验课",
                                        )
                                    } ?: Missing(::nav)
                            } ?: Missing(::nav)
                        route.startsWith("adjust:") ->
                            active?.let { s ->
                                val parts = route.split(':')
                                SingleAdjustment(s, parts[1], parts[2], vm, ::back)
                            } ?: Missing(::nav)
                        route == "appearance" -> Appearance(state.preferences, vm, state.active)
                        route == "notifications" -> NotificationSettings(state.preferences, vm)
                        route == "widgets" -> WidgetGallery(state, vm)
                        route == "backup" ->
                            Column(
                                Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Action("导出完整备份", "包含全部课表、作息、调课和设置") {
                                    if (state.schedules.isNotEmpty())
                                        export.launch("van课表-${LocalDate.now()}.van.json")
                                    else message = "还没有课表可备份"
                                }
                                Action("从文件恢复或导入", "先预览，再确认是否覆盖") { pick.launch(arrayOf("*/*")) }
                                Action("恢复上一次覆盖前快照", "替换前自动保存在本机") {
                                    vm.work {
                                        val text =
                                            context
                                                .openFileInput("before-restore.van.json")
                                                .bufferedReader()
                                                .use { it.readText() }
                                        vm.importData.value = Codec.restore(text)
                                        vm.importWarnings.value = listOf("这是上一次完整恢复前的本机快照")
                                        withContext(Dispatchers.Main) { nav("preview") }
                                    }
                                }
                                active?.let { s ->
                                    Action("分享当前课表", s.name) { nav("share:${s.id}") }
                                }
                                Muted("备份文件不含教务登录Cookie、密码和系统授权。当前不提供自动云同步。")
                            }
                        route.startsWith("share:") ->
                            state.schedules
                                .firstOrNull { it.id == route.substringAfter(':') }
                                ?.let { ShareScreen(it, c.dark, vm) } ?: Missing(::nav)
                        route == "about" -> About(::nav)
                        route == "changelog" -> ChangelogScreen()
                        else -> Missing(::nav)
                    }
                }
            }
            if (busy)
                Column(Modifier.fillMaxWidth().align(Alignment.TopCenter)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (importProgress.isNotBlank())
                        Text(
                            importProgress,
                            Modifier.background(c.surface).fillMaxWidth().padding(8.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                }
        }
    }
    message?.let { value ->
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text("操作提示") },
            text = { Text(value, Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("知道了") } },
        )
    }
}

private fun title(route: String) =
    when (route.substringBefore(':')) {
        "import" -> "导入课表"
        "preview" -> "导入预览"
        "document" -> "识别结果"
        "school" -> "教务导入"
        "schedules" -> "课表管理"
        "newSchedule" -> "新建课表"
        "schedule",
        "semester" -> "学期设置"
        "routines" -> "作息管理"
        "days" -> "调课管理"
        "holidays" -> "节假日设置"
        "detail" -> "课程详情"
        "notes" -> "课程备注"
        "edit" -> "编辑课程"
        "quick" -> "快捷添加"
        "adjust" -> "单次调课"
        "appearance" -> "外观设置"
        "notifications" -> "提醒与手环"
        "widgets" -> "桌面小组件"
        "backup" -> "备份与恢复"
        "share" -> "分享课表"
        "about" -> "帮助与关于"
        "changelog" -> "历史版本更新"
        else -> "van课表"
    }

@Composable
private fun Missing(nav: (String) -> Unit) {
    Column(Modifier.padding(24.dp)) {
        Text("请先启用一份课表，或该数据已经删除。")
        TextButton(onClick = { nav("schedules") }) { Text("打开课表管理") }
    }
}

@Composable
private fun ShareScreen(s: Schedule, dark: Boolean, vm: VanViewModel) {
    val context = LocalContext.current
    var week by rememberSaveable {
        mutableStateOf(CourseEngine.week(s, LocalDate.now()).coerceIn(1, s.maxWeeks).toString())
    }
    var teachers by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Action("分享课表数据文件", "对方使用van课表可完整导入") {
            vm.work { withContext(Dispatchers.Main) { Files.shareData(context, s) } }
        }
        Field(week, { week = it }, "分享图片的周次")
        Toggle("图片显示老师", teachers, { teachers = it })
        Button(
            onClick = {
                val w = week.toIntOrNull()
                if (w == null || w !in 1..s.maxWeeks) error = "周次超出学期"
                else
                    vm.work {
                        val bytes = Files.image(s, w, dark, teachers)
                        withContext(Dispatchers.Main) {
                            Files.share(context, "van课表-第${w}周.png", bytes, "image/png")
                        }
                    }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("分享这一周的课表图片")
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        Muted("图片包含课程名称、日期、时间和地点，不包含私人备注。")
    }
}

@Composable
private fun WidgetGallery(state: VanState, vm: VanViewModel) {
    val context = LocalContext.current
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("今天的安排，桌面上就能看。", style = MaterialTheme.typography.headlineSmall)
        Muted("双日两列可独立上下滑动，今日已结束课程不显示。每个小组件可绑定不同课表，点击底部课表名称可以重新设置课表和主题。")
        Muted("课程和假期保存后自动同步，开始、结束与跨天自动刷新；无需手动点击更新。点击课程或日期打开所绑定的课表。")
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            TextButton(
                onClick = {
                    context.startActivity(
                        Intent(
                            android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            android.net.Uri.parse("package:${context.packageName}"),
                        )
                    )
                }
            ) {
                Text("准时刷新授权")
            }
            Muted("允许闹钟与提醒后可准时刷新；未授权或受系统省电限制时可能延迟。")
        }
        listOf(
                "双日课表" to DualWidget::class.java,
                "今日列表" to TodayWidget::class.java,
                "下一节课" to NextWidget::class.java,
            )
            .forEach { (label, clazz) ->
                Panel {
                    Text(label, fontWeight = FontWeight.Medium)
                    Text(
                        if (clazz == DualWidget::class.java) "今天 ↕     明天 ↕"
                        else if (clazz == TodayWidget::class.java) "当前课 → 后续课程 ↕" else "正在上课或最近一节"
                    )
                    Button(
                        onClick = {
                            val m = AppWidgetManager.getInstance(context)
                            if (m.isRequestPinAppWidgetSupported)
                                m.requestPinAppWidget(ComponentName(context, clazz), null, null)
                            else vm.work { vm.messages.emit("此桌面不支持直接添加，请长按桌面进入小组件列表选择van课表") }
                        }
                    ) {
                        Text("添加到桌面")
                    }
                }
            }
        OutlinedButton(
            onClick = { vm.work { Widgets.updateAll(context) } },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("刷新所有小组件")
        }
    }
}

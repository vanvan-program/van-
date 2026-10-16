// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.ui

import android.Manifest
import android.app.AlarmManager
import android.content.*
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.van.timetable.domain.*
import app.van.timetable.system.Scheduler

@Composable
fun Mine(state: VanState, nav: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("我的", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Panel {
            Text("van课表", fontWeight = FontWeight.Bold)
            Muted(state.active?.let { "${it.name} · ${it.courses.size}门课程" } ?: "尚未启用课表")
            Muted("本地优先 · 无广告 · GPL-3.0")
        }
        listOf(
                "schedules" to "课表管理",
                "semester" to "学期设置",
                "routines" to "作息管理",
                "holidays" to "节假日设置",
                "days" to "调课管理",
                "appearance" to "外观设置",
                "notifications" to "提醒与小米手环",
                "widgets" to "桌面小组件",
                "backup" to "备份与分享",
                "about" to "帮助与关于",
            )
            .forEach { (r, t) -> Action(t) { nav(r) } }
    }
}

@Composable
fun ScheduleList(state: VanState, vm: VanViewModel, nav: (String) -> Unit) {
    var delete by remember { mutableStateOf<Schedule?>(null) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Button(onClick = { nav("newSchedule") }, modifier = Modifier.fillMaxWidth()) {
            Text("新建课表")
        }
        OutlinedButton(onClick = { nav("import") }, modifier = Modifier.fillMaxWidth()) {
            Text("导入新课表")
        }
        state.schedules.forEach { s ->
            Panel {
                Text(s.name, fontWeight = FontWeight.Bold)
                Muted("${s.courses.size}门 · ${s.source} · ${s.week1Monday} 起 ${s.maxWeeks}周")
                if (s.id == state.preferences.activeScheduleId)
                    Text("已启用 · 提醒和小组件使用此表", color = LocalVan.current.blue)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(onClick = { vm.prefs { it.copy(activeScheduleId = s.id) } }) {
                        Text("启用")
                    }
                    TextButton(onClick = { nav("schedule:${s.id}") }) { Text("设置") }
                    TextButton(onClick = { vm.save(Importers.freshCopy(s, "${s.name}副本")) }) {
                        Text("复制")
                    }
                    TextButton(onClick = { nav("share:${s.id}") }) { Text("分享") }
                    TextButton(onClick = { delete = s }) { Text("删除") }
                }
            }
        }
    }
    delete?.let { s ->
        Confirm("删除课表", "将删除“${s.name}”及其课程和调课。如果它已启用，提醒和小组件将停止展示其课程。", { delete = null }) {
            vm.delete(s.id)
            delete = null
        }
    }
}

@Composable
fun Appearance(p: Preferences, vm: VanViewModel) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Panel {
            Text("主题模式", fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (id, label) ->
                    FilterChip(
                        p.theme == id,
                        { vm.prefs { it.copy(theme = id) } },
                        label = { Text(label) },
                    )
                }
            }
        }
        Panel {
            Text("字体大小")
            Row {
                listOf(0.85f to "小", 1f to "标准", 1.15f to "大", 1.3f to "更大").forEach { (v, t) ->
                    TextButton(onClick = { vm.prefs { it.copy(fontScale = v) } }) {
                        Text(if (p.fontScale == v) "✓ $t" else t)
                    }
                }
            }
            Toggle(
                "自动适配整周",
                p.fitWeek,
                { v -> vm.prefs { it.copy(fitWeek = v) } },
                "根据屏幕高度显示全部节次；大字体或节次较多时仍可上下滑动",
            )
            Text(if (p.fitWeek) "最大节次行高：${p.rowHeight}" else "节次行高：${p.rowHeight}")
            Row {
                listOf(48, 56, 68, 80).forEach { v ->
                    TextButton(onClick = { vm.prefs { it.copy(rowHeight = v) } }) { Text("$v") }
                }
            }
        }
        Panel {
            Toggle("显示周末", p.showWeekend, { v -> vm.prefs { it.copy(showWeekend = v) } })
            Toggle("显示非本周课程", p.showInactive, { v -> vm.prefs { it.copy(showInactive = v) } })
            Toggle(
                "显示自定义时间课时间",
                p.showCustomTimeLabels,
                { v -> vm.prefs { it.copy(showCustomTimeLabels = v) } },
                "在课表左侧显示自定义时间课程的时间；关闭后保留正常节次时间，不影响桌面小组件",
            )
        }
        Muted("课程配色在编辑课程中调整。小组件可以独立设置浅色、深色或跟随App。教务网页保留学校原色。")
    }
}

@Composable
fun NotificationSettings(p: Preferences, vm: VanViewModel) {
    val context = LocalContext.current
    val manager = context.getSystemService(AlarmManager::class.java)
    var lead by rememberSaveable(p.leadMinutes) { mutableStateOf(p.leadMinutes.toString()) }
    var error by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            refresh++
            vm.work {}
        }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Panel {
            Toggle(
                "课前提醒",
                p.reminders,
                { v ->
                    vm.prefs { it.copy(reminders = v) }
                    if (v && Build.VERSION.SDK_INT >= 33)
                        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                },
            )
            Toggle(
                "当前／下一节状态通知",
                p.statusNotification,
                { v -> vm.prefs { it.copy(statusNotification = v) } },
            )
            Field(lead, { lead = it }, "提前分钟数")
            Button(
                onClick = {
                    val value = lead.toIntOrNull()
                    if (value == null || value !in 0..1440) error = "填写0–1440之间的整数"
                    else vm.prefs { it.copy(leadMinutes = value) }
                }
            ) {
                Text("保存提前量")
            }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        }
        Panel {
            val notificationAllowed =
                androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
            val exact = Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()
            Text("通知：${if(notificationAllowed)"已允许"else"尚未允许"}")
            Text("精确提醒：${if(exact)"已允许"else"普通提醒，可能延迟"}")
            TextButton(
                onClick = {
                    if (Build.VERSION.SDK_INT >= 31)
                        context.startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                Uri.parse("package:${context.packageName}"),
                            )
                        )
                    refresh++
                }
            ) {
                Text("打开精确提醒授权")
            }
            TextButton(
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    )
                    refresh++
                }
            ) {
                Text("打开系统通知设置")
            }
            TextButton(onClick = { refresh++ }) { Text("重新检查状态 $refresh") }
        }
        Panel {
            Text("小米手环通知镜像", fontWeight = FontWeight.Medium)
            Text(
                "1. 连接小米运动健康与手环。\n2. 在消息通知中允许van课表。\n3. 点击下面的测试按钮，检查手机和手环是否都收到。\n4. 未收到时检查通知授权、运动健康连接和手机省电设置。"
            )
            Button(onClick = { Scheduler.testNotification(context) }) { Text("发送测试通知") }
            Muted("是否转发取决于运动健康设置和实际设备，不使用额外蓝牙权限。")
        }
    }
}

@Composable
fun About() {
    val context = LocalContext.current
    val version =
        remember(context) {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Panel {
            Text("van课表 $version", style = MaterialTheme.typography.headlineSmall)
            Text("GPL-3.0-only · 本地开发版")
            Muted("尚未发布到GitHub。不同教务系统、实际导出文件与国产系统通知仍需要验证。")
        }
        Panel {
            Text("隐私说明", fontWeight = FontWeight.Medium)
            Text(
                "课程与设置保存在本机，不收集使用统计。教务登录内容直接进入学校网站，App不读取或保存账号密码。默认导入完成清除登录会话；可主动保留。导出文件包含你选择的课程信息，请自行决定分享对象。备份不包含登录Cookie和系统权限。"
            )
            Text("使用网络仅为访问学校教务。没有广告、内购、分析SDK或自建服务器。")
        }
        Panel {
            Text("帮助", fontWeight = FontWeight.Medium)
            Text(
                "PDF、Excel和课表图片先核对识别结果；缺少的星期、节次或周次需补全。图片和PDF在本机识别，请使用包含表头的清晰完整课表。CSV、Excel和图片通常不携带作息与开学日期，保存前必须核对。教务获取失败时进入所选学期的个人课表页后手动获取。已结束课程只在小组件隐藏，App日列表仍可查。后台刷新和提醒受系统授权及省电策略影响。"
            )
            Text("完整恢复会替换全部课表，操作前自动保存本机恢复快照。")
        }
        Action("开源与第三方许可", "查看本地许可声明") {
            context.startActivity(Intent(context, app.van.timetable.LicenseActivity::class.java))
        }
    }
}

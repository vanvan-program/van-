// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.*
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.van.timetable.*
import app.van.timetable.domain.Preferences
import app.van.timetable.domain.Schedule
import app.van.timetable.system.Scheduler
import app.van.timetable.ui.VanTheme
import kotlinx.coroutines.*

class WidgetSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id =
            intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID,
            )
        setResult(Activity.RESULT_CANCELED)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val app = application as VanApplication
        setContent {
            var p by remember { mutableStateOf(Preferences()) }
            var schedules by remember { mutableStateOf(emptyList<Schedule>()) }
            LaunchedEffect(Unit) { app.store.preferences.collect { p = it } }
            LaunchedEffect(Unit) { app.store.schedules.collect { schedules = it } }
            VanTheme(p) {
                var mode by rememberSaveable {
                    mutableStateOf(
                        getSharedPreferences("widgets", MODE_PRIVATE).getString("theme_$id", "app")
                            ?: "app"
                    )
                }
                var selected by rememberSaveable {
                    mutableStateOf(
                        getSharedPreferences("widgets", MODE_PRIVATE)
                            .getString("schedule_$id", "")
                            .orEmpty()
                    )
                }
                Column(
                    Modifier.fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("桌面小组件", style = MaterialTheme.typography.headlineMedium)
                    Text("为这个小组件选择课表；桌面上的其他小组件可使用不同课表。")
                    Text("显示课表", style = MaterialTheme.typography.titleMedium)
                    FilterChip(selected.isBlank(), { selected = "" }, label = { Text("跟随App启用课表") })
                    schedules.forEach { s ->
                        FilterChip(selected == s.id, { selected = s.id }, label = { Text(s.name) })
                    }
                    if (selected.isNotBlank() && schedules.none { it.id == selected })
                        Text("绑定的课表已删除，请重新选择", color = MaterialTheme.colorScheme.error)
                    Text("主题", style = MaterialTheme.typography.titleMedium)
                    listOf("app" to "跟随App", "light" to "浅色", "dark" to "深色").forEach { (v, t) ->
                        FilterChip(mode == v, { mode = v }, label = { Text(t) })
                    }
                    Text("课程和假期保存后自动同步，课程开始、结束和跨天自动刷新，无需手动更新。")
                    if (android.os.Build.VERSION.SDK_INT >= 31) {
                        TextButton(
                            onClick = {
                                startActivity(
                                    Intent(
                                        android.provider.Settings
                                            .ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                        android.net.Uri.parse("package:$packageName"),
                                    )
                                )
                            }
                        ) {
                            Text("准时刷新授权")
                        }
                        Text("允许闹钟与提醒后可准时刷新；未授权或受系统省电限制时可能延迟。")
                    }
                    Button(
                        onClick = {
                            getSharedPreferences("widgets", MODE_PRIVATE)
                                .edit()
                                .putString("theme_$id", mode)
                                .putString("schedule_$id", selected)
                                .apply()
                            app.scope.launch {
                                Widgets.update(this@WidgetSettingsActivity, id)
                                Scheduler.reconcile(this@WidgetSettingsActivity)
                                withContext(Dispatchers.Main) {
                                    setResult(
                                        Activity.RESULT_OK,
                                        Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
                                    )
                                    finish()
                                }
                            }
                        },
                        enabled = selected.isBlank() || schedules.any { it.id == selected },
                    ) {
                        Text("保存设置")
                    }
                }
            }
        }
    }
}

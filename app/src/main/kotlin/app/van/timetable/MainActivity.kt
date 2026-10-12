// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.van.timetable.system.Scheduler
import app.van.timetable.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: VanViewModel by viewModels()
    private var launchIntent by mutableStateOf<Intent?>(null)
    private var launchSequence by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        launchIntent = intent
        setContent {
            val state by vm.state.collectAsStateWithLifecycle()
            val target = launchIntent ?: intent
            VanTheme(state.preferences) {
                VanApp(
                    vm,
                    target.getStringExtra("courseId"),
                    target.getStringExtra("date"),
                    target.getStringExtra("scheduleId"),
                    target.getBooleanExtra("openTimetable", false),
                    launchSequence,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launchIntent = intent
        launchSequence++
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Scheduler.reconcile(this@MainActivity)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("VanWidgets", "进入课表时刷新失败", e)
            }
        }
    }
}

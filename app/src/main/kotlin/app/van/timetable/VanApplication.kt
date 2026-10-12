// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable

import android.app.Application
import app.van.timetable.data.Store
import app.van.timetable.system.Scheduler
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class VanApplication : Application() {
    val store by lazy { Store(this) }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        scope.launch {
            combine(store.schedules, store.preferences) { schedules, preferences ->
                    schedules to preferences
                }
                .distinctUntilChanged()
                .debounce(150)
                .collect {
                    try {
                        Scheduler.reconcile(this@VanApplication)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.w("VanWidgets", "自动同步失败，等待下次更新", e)
                    }
                }
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.ui

import android.app.Application
import androidx.compose.runtime.MutableState
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.van.timetable.VanApplication
import app.van.timetable.data.Demo
import app.van.timetable.domain.*
import app.van.timetable.system.DocumentSource
import app.van.timetable.system.Scheduler
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class VanState(
    val schedules: List<Schedule> = emptyList(),
    val preferences: Preferences = Preferences(),
    val ready: Boolean = false,
) {
    val active
        get() = schedules.firstOrNull { it.id == preferences.activeScheduleId }
}

class VanViewModel(app: Application) : AndroidViewModel(app) {
    val store = (app as VanApplication).store
    val state =
        combine(store.schedules, store.preferences) { s, p -> VanState(s, p, true) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, VanState())
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val busy = MutableStateFlow(false)
    val importData = MutableStateFlow<Backup?>(null)
    val importWarnings = MutableStateFlow<List<String>>(emptyList())
    val documentSource = MutableStateFlow<DocumentSource?>(null)
    // Large recognition drafts stay in the ViewModel, outside the Activity's size-limited Bundle.
    val documentDrafts = mutableMapOf<String, MutableState<List<DocumentCourseRow>>>()
    val importProgress = MutableStateFlow("")

    fun work(block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
                withContext(Dispatchers.IO) { Scheduler.reconcile(getApplication()) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                messages.emit(e.message ?: "操作失败，请重试")
            } finally {
                busy.value = false
            }
        }
    }

    fun save(s: Schedule, activate: Boolean = false, after: () -> Unit = {}) = work {
        store.save(s)
        if (activate) store.updatePreferences { it.copy(activeScheduleId = s.id) }
        withContext(Dispatchers.Main) { after() }
    }

    fun prefs(transform: (Preferences) -> Preferences) = work { store.updatePreferences(transform) }

    fun delete(id: String) = work { store.delete(id) }

    fun refreshWidgets() =
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Scheduler.reconcile(getApplication())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("VanWidgets", "课程页刷新失败", e)
            }
        }

    fun demo() = save(Demo.schedule(), activate = true)

    fun course(
        schedule: Schedule,
        course: Course,
        sessions: List<CourseSession>,
        after: () -> Unit,
    ) =
        save(
            schedule.copy(
                courses = schedule.courses.filterNot { it.id == course.id } + course,
                sessions = schedule.sessions.filterNot { it.courseId == course.id } + sessions,
            ),
            after = after,
        )

    fun deleteCourse(schedule: Schedule, id: String, after: () -> Unit) =
        save(
            schedule.copy(
                courses = schedule.courses.filterNot { it.id == id },
                sessions = schedule.sessions.filterNot { it.courseId == id },
                sessionOverrides =
                    schedule.sessionOverrides.filterNot { o ->
                        schedule.sessions.any { it.courseId == id && it.id == o.sessionId }
                    },
            ),
            after = after,
        )
}

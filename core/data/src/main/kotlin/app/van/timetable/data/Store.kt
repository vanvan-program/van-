// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.*
import app.van.timetable.domain.*
import java.time.*
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Entity(tableName = "schedules")
data class ScheduleRecord(@PrimaryKey val id: String, val payload: String)

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM schedules ORDER BY rowid") fun observe(): Flow<List<ScheduleRecord>>

    @Query("SELECT * FROM schedules ORDER BY rowid") suspend fun all(): List<ScheduleRecord>

    @Upsert suspend fun put(record: ScheduleRecord)

    @Query("DELETE FROM schedules WHERE id=:id") suspend fun delete(id: String)

    @Query("DELETE FROM schedules") suspend fun clear()
}

@Database(entities = [ScheduleRecord::class], version = 1, exportSchema = true)
abstract class VanDatabase : RoomDatabase() {
    abstract fun schedules(): ScheduleDao
}

private val Context.settings by preferencesDataStore("van_settings")

class Store(private val context: Context) {
    private val db =
        Room.databaseBuilder(context.applicationContext, VanDatabase::class.java, "van.db").build()
    private val preferencesKey = stringPreferencesKey("preferences_v1")
    private val lock = Mutex()
    val schedules: Flow<List<Schedule>> =
        db.schedules().observe().map { rows -> rows.map { Codec.decode(it.payload) } }
    val preferences: Flow<Preferences> =
        context.settings.data.map { p ->
            p[preferencesKey]?.let { Codec.json.decodeFromString(Preferences.serializer(), it) }
                ?: Preferences()
        }

    suspend fun all(): List<Schedule> = db.schedules().all().map { Codec.decode(it.payload) }

    suspend fun prefs(): Preferences = preferences.first()

    suspend fun save(s: Schedule) {
        Validation.requireValid(s)
        db.schedules().put(ScheduleRecord(s.id, Codec.encode(s)))
    }

    suspend fun updatePreferences(transform: (Preferences) -> Preferences) {
        context.settings.edit { p ->
            val before =
                p[preferencesKey]?.let { Codec.json.decodeFromString(Preferences.serializer(), it) }
                    ?: Preferences()
            p[preferencesKey] =
                Codec.json.encodeToString(Preferences.serializer(), transform(before))
        }
    }

    suspend fun delete(id: String) {
        lock.withLock {
            db.schedules().delete(id)
            updatePreferences {
                if (it.activeScheduleId == id) it.copy(activeScheduleId = null) else it
            }
        }
    }

    suspend fun backup(): String = Codec.backup(Backup(schedules = all(), preferences = prefs()))

    suspend fun restore(b: Backup) {
        require(b.scope == "full")
        b.schedules.forEach(Validation::requireValid)
        lock.withLock {
            val before = backup()
            context.openFileOutput("before-restore.van.json", Context.MODE_PRIVATE).use {
                it.write(before.toByteArray())
            }
            val oldPrefs = prefs()
            db.withTransaction {
                db.schedules().clear()
                b.schedules.forEach { db.schedules().put(ScheduleRecord(it.id, Codec.encode(it))) }
                try {
                    updatePreferences {
                        (b.preferences ?: oldPrefs).copy(
                            activeScheduleId =
                                b.preferences?.activeScheduleId?.takeIf { id ->
                                    b.schedules.any { it.id == id }
                                }
                        )
                    }
                } catch (e: Exception) {
                    updatePreferences { oldPrefs }
                    throw e
                }
            }
        }
    }

    suspend fun active(): Schedule? {
        val p = prefs()
        return all().firstOrNull { it.id == p.activeScheduleId }
    }
}

object Demo {
    fun schedule(): Schedule {
        val monday =
            LocalDate.now(ZoneId.of("Asia/Shanghai"))
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .minusWeeks(5)
        val names = listOf("大数据技术", "操作系统", "数据库原理", "Java程序设计", "汇编语言程序设计")
        val courses = names.mapIndexed { i, n ->
            Course(name = n, color = i, note = "示例课程，时间与地点可编辑")
        }
        val slots =
            listOf(
                1 to 1,
                1 to 5,
                2 to 3,
                3 to 1,
                3 to 7,
                4 to 3,
                4 to 5,
                5 to 1,
                5 to 7,
                6 to 3,
                7 to 1,
                7 to 3,
            )
        val sessions = slots.mapIndexed { i, p ->
            CourseSession(
                courseId = courses[i % 5].id,
                day = p.first,
                startSection = p.second,
                endSection = p.second + 1,
                teacher = listOf("李老师", "张老师", "王老师")[i % 3],
                place = "立德楼 A${listOf(414,214,302,101)[i%4]}",
            )
        }
        return Schedule(
            name = "示例 · 秋季课表",
            week1Monday = monday.toString(),
            courses = courses,
            sessions = sessions,
            source = "示例数据",
        )
    }
}

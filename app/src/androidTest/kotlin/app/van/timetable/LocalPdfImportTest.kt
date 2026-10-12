package app.van.timetable

import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.van.timetable.domain.*
import app.van.timetable.system.DocumentFiles
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalPdfImportTest {
    @Test
    fun realPdfsReadThroughAndroidFileProviderAndSaveWithoutReplacingExistingTable() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val files = (0..2).map { File(context.getExternalFilesDir(null), "sample-$it.pdf") }
        assumeTrue(files.all { it.exists() })
        val store = (context.applicationContext as VanApplication).store
        val before = store.all().map { it.id }.toSet()
        files.forEachIndexed { index, file ->
            val copy = File(context.cacheDir, "exports/local-sample.pdf")
            copy.parentFile!!.mkdirs()
            file.copyTo(copy, overwrite = true)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", copy)
            val source = DocumentFiles.read(context, uri).document!!
            val rows = source.pages.flatMap { it.rows }
            assertEquals(listOf(25, 20, 35)[index], rows.size)
            assertEquals(0, source.pages.sumOf { it.unresolvedCount })
            val draft = DocumentImport.toSchedule(rows, LocalDate.of(2026, 8, 31))
            store.save(draft)
            val saved = store.all().first { it.id == draft.id }
            assertEquals(draft, saved)
            assertTrue(store.all().map { it.id }.containsAll(before))
            store.delete(draft.id)
            copy.delete()
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable

import app.van.timetable.school.*
import org.junit.Assert.*
import org.junit.Test

class SchoolAddressTest {
    @Test
    fun acceptsUserAddressesWithPortsQueriesAndFragments() {
        assertEquals(
            "https://portal.example.edu.cn/login?from=app#/table",
            schoolAddress("  portal.example.edu.cn/login?from=app#/table  "),
        )
        assertEquals("http://10.1.2.3:8080/jw/", schoolAddress("HTTP://10.1.2.3:8080/jw/"))
        assertEquals("custom", schoolForAddress("https://portal.example.edu.cn/login").id)
    }

    @Test
    fun retainsFourSchoolAdaptersWithoutAUserFacingSchoolList() {
        schools.forEach { original ->
            val chosen = schoolForAddress(original.url)
            assertEquals(original.id, chosen.id)
            assertEquals(original.url, chosen.url)
            assertEquals(
                when (original.id) {
                    "hebau" -> "hebau"
                    "hebiace" -> "hebiace"
                    else -> "dom"
                },
                schoolAdapter(original.url),
            )
        }
        assertEquals("custom", schoolForAddress("https://hebau.edu.cn.example.com/").id)
        assertEquals(listOf("dom", "hebiace"), schoolAdapterAssets(schools[1].url))
        assertEquals("hebiace", schoolAdapter("https://jwx.hebiace.edu.cn/xtgl/login_slogin.html"))
        assertEquals("dom", schoolAdapter("https://hebiace.edu.cn.example.com/kbcx/"))
    }

    @Test
    fun rejectsNonWebLinksAndEmbeddedCredentials() {
        listOf(
                "",
                "javascript:alert(1)",
                "file:///sdcard/table",
                "https://",
                "https://user:password@example.edu.cn/",
                "https://example.edu.cn:99999/",
            )
            .forEach {
                assertTrue("Unexpectedly accepted $it", runCatching { schoolAddress(it) }.isFailure)
            }
    }
}

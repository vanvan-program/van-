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
    fun selectsProtocolsByPagePathWithoutSchoolDomains() {
        assertEquals("wdkb", schoolAdapter("https://portal.example.edu.cn/jwapp/sys/home/"))
        assertEquals("zhengfang", schoolAdapter("https://portal.example.edu.cn/kbcx/query"))
        assertEquals(listOf("dom", "zhengfang"), schoolAdapterAssets("https://portal.example.edu.cn/xtgl/login_slogin.html"))
        assertEquals("dom", schoolAdapter("https://portal.example.edu.cn/login"))
        assertEquals("教务系统", schoolForAddress("https://portal.example.edu.cn/kbcx/query").name)
        assertEquals("dom", schoolAdapter("https://portal.example.edu.cn/other/kbcx/"))
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

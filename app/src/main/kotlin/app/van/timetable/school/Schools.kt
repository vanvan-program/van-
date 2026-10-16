// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.school

import java.net.URI

data class School(
    val id: String,
    val name: String,
    val url: String,
    val domain: String,
    val note: String,
)

// Synthetic protocol examples used by import regression tests; no real school directory.
val schools = listOf(
    School("wdkb", "教务示例甲", "https://wdkb.example.edu.cn/jwapp/sys/home/", "wdkb.example.edu.cn", "示例网址"),
    School("zhengfang", "教务示例乙", "https://portal.example.edu.cn/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151&layout=default", "portal.example.edu.cn", "示例网址"),
)

/** Accept only browser URLs; keep paths, queries and fragments used by campus portals. */
fun schoolAddress(input: String): String {
    val value = input.trim()
    require(value.isNotEmpty()) { "请输入教务系统地址" }
    val url = if (value.contains("://")) value else "https://$value"
    val uri = runCatching { URI(url) }.getOrNull()
    require(
        uri != null &&
            uri.scheme.lowercase() in setOf("http", "https") &&
            !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null &&
            uri.port in -1..65535
    ) {
        "请输入有效的 http 或 https 教务系统地址"
    }
    return URI("${uri.scheme.lowercase()}:${url.substringAfter(':')}").toASCIIString()
}

fun schoolForAddress(address: String): School {
    val url = schoolAddress(address)
    val uri = URI(url)
    val host = uri.host.lowercase()
    val path = uri.path.orEmpty().lowercase()
    val adapter = when {
        path.startsWith("/jwapp/") -> "wdkb"
        path.startsWith("/kbcx/") || path.startsWith("/xtgl/") -> "zhengfang"
        else -> "custom"
    }
    return School(adapter, "教务系统", url, host, "登录后进入个人课表，点击获取当前课表")
}

fun schoolAdapter(address: String): String =
    when (schoolForAddress(address).id) {
        "wdkb" -> "wdkb"
        "zhengfang" -> "zhengfang"
        else -> "dom"
    }

fun schoolAdapterAssets(address: String): List<String> =
    if (schoolAdapter(address) == "zhengfang") listOf("dom", "zhengfang")
    else listOf(schoolAdapter(address))

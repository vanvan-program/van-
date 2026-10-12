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

val schools =
    listOf(
        School(
            "hebau",
            "河北农业大学",
            "http://urp.hebau.edu.cn:1009/jwapp/sys/homeapp/home/index.html#/",
            "hebau.edu.cn",
            "接口式获取；需要登录后选择学期，尚待本校真实数据验证",
        ),
        School(
            "hebiace",
            "河北建筑工程学院",
            "https://jwx.hebiace.edu.cn/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151&layout=default",
            "hebiace.edu.cn",
            "登录后进入学生课表查询，选择学年和学期并查询，再获取当前课表",
        ),
        School(
            "stdu",
            "石家庄铁道大学",
            "https://jw.stdu.edu.cn/xtgl/login_slogin.html",
            "stdu.edu.cn",
            "进入个人课表后获取；正方页面结构尚待本校验证",
        ),
        School(
            "xtu",
            "湘潭大学",
            "http://jwxt.xtu.edu.cn/",
            "xtu.edu.cn",
            "旧入口/ jsxsd 曾返回404，请确认校内实际入口；强智页面尚待验证",
        ),
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
    val host = URI(url).host.lowercase()
    return schools
        .firstOrNull { host == it.domain || host.endsWith(".${it.domain}") }
        ?.copy(url = url) ?: School("custom", "教务系统", url, host, "登录后进入个人课表，点击获取当前课表")
}

fun schoolAdapter(address: String): String =
    when (schoolForAddress(address).id) {
        "hebau" -> "hebau"
        "hebiace" -> "hebiace"
        else -> "dom"
    }

fun schoolAdapterAssets(address: String): List<String> =
    if (schoolAdapter(address) == "hebiace") listOf("dom", "hebiace")
    else listOf(schoolAdapter(address))

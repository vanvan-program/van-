// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.domain

/** Stable indices: the first five colors remain compatible with existing backups. */
object CoursePalette {
    val light =
        listOf(
            0xFFEEB2B2,
            0xFFF3B898,
            0xFFF5D78E,
            0xFF91DEB2,
            0xFFBBAAE1,
            0xFF9DC9F3,
            0xFF8DDAD6,
            0xFFF2B6D5,
            0xFFBCD790,
            0xFFAEBCE8,
            0xFFD4BBA3,
            0xFFBACBD6,
        )
    val dark =
        listOf(
            0xFFD6A0A7,
            0xFFDAAA8F,
            0xFFDCC183,
            0xFF98C9AC,
            0xFFB1A2CF,
            0xFF94B7D9,
            0xFF8CC3C1,
            0xFFD1A0BB,
            0xFFAABF89,
            0xFFA3AED0,
            0xFFC1AC97,
            0xFFA6B7C3,
        )
    val size
        get() = light.size

    fun argb(course: Course, isDark: Boolean = false): Long =
        course.customColor ?: (if (isDark) dark else light)[course.color]

    fun textArgb(background: Long): Long {
        fun linear(channel: Long): Double {
            val value = channel / 255.0
            return if (value <= 0.04045) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
        }
        val luminance =
            0.2126 * linear((background shr 16) and 255) +
                0.7152 * linear((background shr 8) and 255) +
                0.0722 * linear(background and 255)
        return if (luminance > 0.179) 0xFF142036 else 0xFFFFFFFF
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import app.van.timetable.domain.CoursePalette
import app.van.timetable.domain.Preferences

data class VanColors(
    val dark: Boolean,
    val top: Color,
    val background: Color,
    val surface: Color,
    val text: Color,
    val muted: Color,
    val blue: Color,
    val tones: List<Color>,
    val inactive: Color,
    val inactiveText: Color,
)

private val light =
    VanColors(
        false,
        Color(0xFFE8F2FC),
        Color(0xFFF8F9FE),
        Color.White,
        Color(0xFF18233A),
        Color(0xFF67748A),
        Color(0xFF3874F6),
        CoursePalette.light.map { Color(it) },
        Color(0xFFD4D7DE),
        Color(0xFF55565A),
    )
private val dark =
    VanColors(
        true,
        Color(0xFF1B2E4A),
        Color(0xFF111A2A),
        Color(0xFF1D2A40),
        Color(0xFFEFF3FA),
        Color(0xFFB2BED1),
        Color(0xFF91B5FF),
        CoursePalette.dark.map { Color(it) },
        Color(0xFF344155),
        Color(0xFFC1CBDA),
    )
val LocalVan = staticCompositionLocalOf { light }

fun VanColors.courseColor(course: app.van.timetable.domain.Course): Color =
    Color(CoursePalette.argb(course, dark))

fun courseTextColor(color: Color): Color =
    Color(CoursePalette.textArgb(color.toArgb().toLong() and 0xFFFFFFFF))

@Composable
fun VanTheme(p: Preferences, content: @Composable () -> Unit) {
    val isDark =
        when (p.theme) {
            "dark" -> true
            "light" -> false
            else -> isSystemInDarkTheme()
        }
    val colors = if (isDark) dark else light
    val scheme =
        if (isDark)
            darkColorScheme(
                primary = colors.blue,
                background = colors.background,
                surface = colors.surface,
                onSurface = colors.text,
                onBackground = colors.text,
            )
        else
            lightColorScheme(
                primary = colors.blue,
                background = colors.background,
                surface = colors.surface,
                onSurface = colors.text,
                onBackground = colors.text,
            )
    val density = LocalDensity.current
    val view = LocalView.current
    SideEffect {
        (view.context as? android.app.Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !isDark
                isAppearanceLightNavigationBars = !isDark
            }
        }
    }
    CompositionLocalProvider(
        LocalVan provides colors,
        LocalDensity provides Density(density.density, density.fontScale * p.fontScale),
    ) {
        MaterialTheme(colorScheme = scheme) {
            Surface(Modifier.fillMaxSize(), color = colors.background) { content() }
        }
    }
}

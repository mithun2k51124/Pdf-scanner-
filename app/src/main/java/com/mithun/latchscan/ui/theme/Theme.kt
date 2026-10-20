package com.mithun.latchscan.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.mithun.latchscan.R

/** Fixed brand colours. The highlighter yellow is reserved for "latch" moments and primary actions. */
object Brand {
    val Highlighter = Color(0xFFFFD43B)
    val HighlighterDeep = Color(0xFFE0B100)
    val Ink = Color(0xFF161A22)
    val InkSoft = Color(0xFF232833)
    val Paper = Color(0xFFEEF1F5)
    val Sheet = Color(0xFFFFFFFF)
    val Graphite = Color(0xFF5D6573)
    val Danger = Color(0xFFD9434B)
    val Ok = Color(0xFF2E9E6A)
}

@Immutable
data class LatchColors(
    val background: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val text: Color,
    val textMuted: Color,
    val outline: Color,
    val isDark: Boolean,
)

private val LightLatch = LatchColors(
    background = Brand.Paper,
    surface = Brand.Sheet,
    surfaceAlt = Color(0xFFE2E6EC),
    text = Brand.Ink,
    textMuted = Brand.Graphite,
    outline = Color(0xFFD3D8E0),
    isDark = false,
)

private val DarkLatch = LatchColors(
    background = Color(0xFF12151B),
    surface = Color(0xFF1C2029),
    surfaceAlt = Color(0xFF272C37),
    text = Color(0xFFEEF1F5),
    textMuted = Color(0xFF9AA2B1),
    outline = Color(0xFF323846),
    isDark = true,
)

val LocalLatchColors = staticCompositionLocalOf { LightLatch }

val Bricolage = FontFamily(
    Font(R.font.bricolage_regular, FontWeight.Normal),
    Font(R.font.bricolage_medium, FontWeight.Medium),
    Font(R.font.bricolage_semibold, FontWeight.SemiBold),
    Font(R.font.bricolage_extrabold, FontWeight.ExtraBold),
)

private val LatchTypography = Typography(
    displaySmall = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.ExtraBold, fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = (-0.02).em),
    headlineSmall = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, lineHeight = 29.sp, letterSpacing = (-0.01).em),
    titleLarge = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 25.sp),
    titleMedium = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 21.sp),
    titleSmall = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp),
    bodyLarge = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 18.sp),
    labelMedium = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp),
)

@Composable
fun LatchTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val latch = if (dark) DarkLatch else LightLatch
    val scheme = if (dark) {
        darkColorScheme(
            primary = Brand.Highlighter,
            onPrimary = Brand.Ink,
            background = latch.background,
            onBackground = latch.text,
            surface = latch.surface,
            onSurface = latch.text,
            surfaceVariant = latch.surfaceAlt,
            onSurfaceVariant = latch.textMuted,
            outline = latch.outline,
            error = Brand.Danger,
            surfaceContainerHigh = latch.surface,
            surfaceContainer = latch.surface,
        )
    } else {
        lightColorScheme(
            primary = Brand.Ink,
            onPrimary = Color.White,
            background = latch.background,
            onBackground = latch.text,
            surface = latch.surface,
            onSurface = latch.text,
            surfaceVariant = latch.surfaceAlt,
            onSurfaceVariant = latch.textMuted,
            outline = latch.outline,
            error = Brand.Danger,
            surfaceContainerHigh = latch.surface,
            surfaceContainer = latch.surface,
        )
    }
    CompositionLocalProvider(LocalLatchColors provides latch) {
        MaterialTheme(colorScheme = scheme, typography = LatchTypography, content = content)
    }
}

object Latch {
    val colors: LatchColors
        @Composable get() = LocalLatchColors.current
}

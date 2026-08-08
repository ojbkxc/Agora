package com.newoether.agora.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf

import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.File

enum class ThemeMode { LIGHT, DARK, FOLLOW_DEVICE }

// ─────────────────────────────────────────────────────────────────────────────
// AgoraShapes — cf-ai-gw corner radii.
//  · card   20dp (glass cards, modals)
//  · button 10dp (gradient buttons)
//  · input  10dp (glass text fields)
// ─────────────────────────────────────────────────────────────────────────────

object AgoraShapes {
    val cardShape = RoundedCornerShape(20.dp)
    val buttonShape = RoundedCornerShape(10.dp)
    val inputShape = RoundedCornerShape(10.dp)
    /** Modal / dialog shape — same 20dp as cards but kept distinct for future tuning. */
    val modalShape = RoundedCornerShape(20.dp)
}

// ─────────────────────────────────────────────────────────────────────────────
// AgoraColorScheme — theme-aware view onto AgoraColors.
//
// AgoraColors itself is a static object holding BOTH dark and light tokens (so
// the raw constants are reachable without a CompositionLocal). AgoraColorScheme
// is the per-theme projection consumed by components via LocalAgoraColors.current,
// which avoids every call site having to branch on isSystemInDarkTheme().
// ─────────────────────────────────────────────────────────────────────────────

@Immutable
data class AgoraColorScheme(
    val bg: Color,
    val cardBg: Color,
    val border: Color,
    val textMain: Color,
    val textMuted: Color,
    val inputBg: Color,
    val orb1: Color,
    val orb2: Color,
    val accent: Color,
    val isDark: Boolean,
)

/** Dark-theme projection of [AgoraColors]. */
fun darkAgoraColorScheme(): AgoraColorScheme = AgoraColorScheme(
    bg = AgoraColors.darkBg,
    cardBg = AgoraColors.darkCardBg,
    border = AgoraColors.darkBorder,
    textMain = AgoraColors.darkTextMain,
    textMuted = AgoraColors.darkTextMuted,
    inputBg = AgoraColors.darkInputBg,
    orb1 = AgoraColors.darkOrb1,
    orb2 = AgoraColors.darkOrb2,
    accent = AgoraColors.accent,
    isDark = true,
)

/** Light-theme projection of [AgoraColors]. */
fun lightAgoraColorScheme(): AgoraColorScheme = AgoraColorScheme(
    bg = AgoraColors.lightBg,
    cardBg = AgoraColors.lightCardBg,
    border = AgoraColors.lightBorder,
    textMain = AgoraColors.lightTextMain,
    textMuted = AgoraColors.lightTextMuted,
    inputBg = AgoraColors.lightInputBg,
    orb1 = AgoraColors.lightOrb1,
    orb2 = AgoraColors.lightOrb2,
    accent = AgoraColors.accentLight,
    isDark = false,
)

/**
 * CompositionLocal exposing the current theme's [AgoraColorScheme].
 * Defaults to the dark scheme so previews / off-tree composition still resolve.
 */
val LocalAgoraColors = compositionLocalOf { darkAgoraColorScheme() }

/**
 * CompositionLocal exposing the [AgoraGradients] brush provider.
 * AgoraGradients' getters are already @Composable + theme-aware, so this Local
 * mainly exists to allow tests/previews to swap in a fixed brush set.
 */
val LocalAgoraGradients = compositionLocalOf { AgoraGradients }

/**
 * Returns the effective [FontFamily] for non-mono typography based on the font preference.
 */
@Composable
private fun effectiveFontFamily(
    fontPreference: String,
    customFontPath: String
): FontFamily = remember(fontPreference, customFontPath) {
    when (fontPreference) {
        "system" -> FontFamily.Default
        "custom" -> {
            val file = File(customFontPath)
            if (file.exists()) {
                try {
                    FontFamily(
                        Font(file, FontWeight.ExtraLight),
                        Font(file, FontWeight.Light),
                        Font(file, FontWeight.Normal),
                        Font(file, FontWeight.Medium),
                        Font(file, FontWeight.Bold),
                    )
                } catch (_: Exception) {
                    OutfitFamily
                }
            } else OutfitFamily
        }
        else -> OutfitFamily
    }
}

/**
 * Builds the [Typography] with the given [FontFamily] replacing all non-mono styles.
 */
private fun typographyWithFont(family: FontFamily): Typography {
    fun TextStyle.withFamily(f: FontFamily) = copy(fontFamily = f)
    return Typography.copy(
        displayLarge = Typography.displayLarge.withFamily(family),
        displayMedium = Typography.displayMedium.withFamily(family),
        displaySmall = Typography.displaySmall.withFamily(family),
        headlineLarge = Typography.headlineLarge.withFamily(family),
        headlineMedium = Typography.headlineMedium.withFamily(family),
        headlineSmall = Typography.headlineSmall.withFamily(family),
        titleLarge = Typography.titleLarge.withFamily(family),
        titleMedium = Typography.titleMedium.withFamily(family),
        titleSmall = Typography.titleSmall.withFamily(family),
        bodyLarge = Typography.bodyLarge.withFamily(family),
        bodyMedium = Typography.bodyMedium.withFamily(family),
        bodySmall = Typography.bodySmall.withFamily(family),
        labelLarge = Typography.labelLarge.withFamily(family),
        labelMedium = Typography.labelMedium.withFamily(family),
        labelSmall = Typography.labelSmall.withFamily(family),
    )
}

@Composable
fun AgoraTheme(
    themeMode: ThemeMode = ThemeMode.FOLLOW_DEVICE,
    colorSchemePreset: ColorSchemePreset = ColorSchemePreset.MIDNIGHT,
    schemeStyle: SchemeStyle = SchemeStyle.TONAL_SPOT,
    dynamicColor: Boolean = true,
    fontPreference: String = "app_default",
    customFontPath: String = "",
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.FOLLOW_DEVICE -> systemDark
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        else -> remember(colorSchemePreset, schemeStyle, darkTheme) {
            colorSchemeForPreset(colorSchemePreset, schemeStyle, darkTheme)
        }
    }

    val fontFamily = effectiveFontFamily(fontPreference, customFontPath)
    SideEffect { chatFontFamily = fontFamily }
    val typography = remember(fontFamily) { typographyWithFont(fontFamily) }

    // Project the static AgoraColors onto a theme-aware scheme for components.
    val agoraColors = remember(darkTheme) {
        if (darkTheme) darkAgoraColorScheme() else lightAgoraColorScheme()
    }
    val agoraGradients = AgoraGradients

    CompositionLocalProvider(
        LocalAgoraColors provides agoraColors,
        LocalAgoraGradients provides agoraGradients,
        LocalContentColor provides colorScheme.onBackground,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            content = content
        )
    }
}

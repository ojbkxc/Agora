package com.newoether.agora.ui.theme


import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.materialkolor.hct.Hct
import com.materialkolor.scheme.DynamicScheme
import com.materialkolor.scheme.SchemeExpressive
import com.materialkolor.scheme.SchemeNeutral
import com.materialkolor.scheme.SchemeTonalSpot
import com.materialkolor.scheme.SchemeVibrant

// ─────────────────────────────────────────────────────────────────────────────
// Backward-compatible preset enums & seed-based Material color scheme generation.
// Kept intact so existing call sites (Settings, ThemeStore, AgoraTheme) keep working.
// ─────────────────────────────────────────────────────────────────────────────

enum class SchemeStyle { TONAL_SPOT, EXPRESSIVE, VIBRANT, NEUTRAL }

enum class ColorSchemePreset { MIDNIGHT, NORDIC, FOREST, SUNSET, ROSE, LAVENDER, SLATE, OCEAN }

private val seedColors = mapOf(
    // MIDNIGHT seed retuned to cf-ai-gw indigo (#6366F1) so the default Material
    // scheme harmonises with the new glassmorphism palette.
    ColorSchemePreset.MIDNIGHT to 0xFF6366F1,
    ColorSchemePreset.NORDIC   to 0xFF546E7A,
    ColorSchemePreset.FOREST   to 0xFF2E7D32,
    ColorSchemePreset.SUNSET   to 0xFFE65100,
    ColorSchemePreset.ROSE     to 0xFFAD1457,
    ColorSchemePreset.LAVENDER to 0xFF7B1FA2,
    ColorSchemePreset.SLATE    to 0xFF455A64,
    ColorSchemePreset.OCEAN    to 0xFF0277BD,
)

fun colorSchemeForPreset(
    preset: ColorSchemePreset,
    style: SchemeStyle = SchemeStyle.TONAL_SPOT,
    isDark: Boolean = false
): ColorScheme {
    val seedArgb = seedColors[preset]!!.toInt()
    val hct = Hct.fromInt(seedArgb)
    val scheme: DynamicScheme = when (style) {
        SchemeStyle.TONAL_SPOT -> SchemeTonalSpot(hct, isDark, 0.0)
        SchemeStyle.EXPRESSIVE -> SchemeExpressive(hct, isDark, 0.0)
        SchemeStyle.VIBRANT   -> SchemeVibrant(hct, isDark, 0.0)
        SchemeStyle.NEUTRAL   -> SchemeNeutral(hct, isDark, 0.0)
    }
    return scheme.toColorScheme()
}

private fun DynamicScheme.toColorScheme(): ColorScheme {
    val c = { argb: Int -> Color(argb) }
    return if (isDark) darkColorScheme(
        primary = c(primary), onPrimary = c(onPrimary),
        primaryContainer = c(primaryContainer), onPrimaryContainer = c(onPrimaryContainer),
        secondary = c(secondary), onSecondary = c(onSecondary),
        secondaryContainer = c(secondaryContainer), onSecondaryContainer = c(onSecondaryContainer),
        tertiary = c(tertiary), onTertiary = c(onTertiary),
        tertiaryContainer = c(tertiaryContainer), onTertiaryContainer = c(onTertiaryContainer),
        error = c(error), onError = c(onError),
        errorContainer = c(errorContainer), onErrorContainer = c(onErrorContainer),
        background = c(background), onBackground = c(onBackground),
        surface = c(surface), onSurface = c(onSurface),
        surfaceVariant = c(surfaceVariant), onSurfaceVariant = c(onSurfaceVariant),
        outline = c(outline), outlineVariant = c(outlineVariant),
    ) else lightColorScheme(
        primary = c(primary), onPrimary = c(onPrimary),
        primaryContainer = c(primaryContainer), onPrimaryContainer = c(onPrimaryContainer),
        secondary = c(secondary), onSecondary = c(onSecondary),
        secondaryContainer = c(secondaryContainer), onSecondaryContainer = c(onSecondaryContainer),
        tertiary = c(tertiary), onTertiary = c(onTertiary),
        tertiaryContainer = c(tertiaryContainer), onTertiaryContainer = c(onTertiaryContainer),
        error = c(error), onError = c(onError),
        errorContainer = c(errorContainer), onErrorContainer = c(onErrorContainer),
        background = c(background), onBackground = c(onBackground),
        surface = c(surface), onSurface = c(onSurface),
        surfaceVariant = c(surfaceVariant), onSurfaceVariant = c(onSurfaceVariant),
        outline = c(outline), outlineVariant = c(outlineVariant),
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// AgoraColors — cf-ai-gw design tokens.
//
// Static colour constants for the new glassmorphism aesthetic: deep navy ground,
// indigo→violet→pink gradient, translucent slate cards, purple accent, indigo
// & pink background orbs. Two parallel sets (dark / light) plus shared accent,
// gradient and status colours. Alpha values are pre-baked into the ARGB hex so
// callers can use them directly without an extra .copy(alpha = …) hop.
// ─────────────────────────────────────────────────────────────────────────────

@Stable
object AgoraColors {

    // ── Dark theme ───────────────────────────────────────────────────────
    /** Deep navy-black ground: #0b0f19. */
    val darkBg = Color(0xFF0B0F19)
    /** Translucent slate card: rgba(30,41,59,0.45). */
    val darkCardBg = Color(0x731E293B)
    /** Faint white hairline: rgba(255,255,255,0.08). */
    val darkBorder = Color(0x14FFFFFF)
    /** Near-white primary text: #f8fafc. */
    val darkTextMain = Color(0xFFF8FAFC)
    /** Soft slate muted text: #94a3b8. */
    val darkTextMuted = Color(0xFF94A3B8)
    /** Translucent input field: rgba(15,23,42,0.6). */
    val darkInputBg = Color(0x990F172A)
    /** Indigo background orb: rgba(99,102,241,0.15). */
    val darkOrb1 = Color(0x266366F1)
    /** Pink background orb: rgba(236,72,153,0.12). */
    val darkOrb2 = Color(0x1FEC4899)

    // ── Light theme ──────────────────────────────────────────────────────
    /** Light slate ground: #f1f5f9. */
    val lightBg = Color(0xFFF1F5F9)
    /** Translucent white card: rgba(255,255,255,0.7). */
    val lightCardBg = Color(0xB3FFFFFF)
    /** Faint black hairline: rgba(0,0,0,0.06). */
    val lightBorder = Color(0x0F000000)
    /** Near-black primary text: #0f172a. */
    val lightTextMain = Color(0xFF0F172A)
    /** Slate muted text: #64748b. */
    val lightTextMuted = Color(0xFF64748B)
    /** Translucent input field: rgba(241,245,249,0.8). */
    val lightInputBg = Color(0xCCF1F5F9)
    /** Indigo background orb (light): rgba(99,102,241,0.08). */
    val lightOrb1 = Color(0x146366F1)
    /** Pink background orb (light): rgba(236,72,153,0.06). */
    val lightOrb2 = Color(0x0FEC4899)

    // ── Accent (shared) ──────────────────────────────────────────────────
    /** Purple accent (dark): #a855f7. */
    val accent = Color(0xFFA855F7)
    /** Purple accent (light): #9333ea. */
    val accentLight = Color(0xFF9333EA)

    // ── Gradient endpoints ───────────────────────────────────────────────
    /** Dark gradient start — indigo: #6366f1. */
    val gradientStart = Color(0xFF6366F1)
    /** Dark gradient mid — violet: #a855f7. */
    val gradientMid = Color(0xFFA855F7)
    /** Dark gradient end — pink: #ec4899. */
    val gradientEnd = Color(0xFFEC4899)
    /** Light gradient start — indigo: #4f46e5. */
    val gradientStartLight = Color(0xFF4F46E5)
    /** Light gradient mid — violet: #9333ea. */
    val gradientMidLight = Color(0xFF9333EA)
    /** Light gradient end — pink: #db2777. */
    val gradientEndLight = Color(0xFFDB2777)

    // ── Status colours ───────────────────────────────────────────────────
    val success = Color(0xFF10B981)
    val warning = Color(0xFFF59E0B)
    val danger = Color(0xFFEF4444)
}

// ─────────────────────────────────────────────────────────────────────────────
// AgoraGradients — cf-ai-gw gradient brushes.
//
// All brushes are @Composable getters so they can be consumed directly in
// Modifier.background(...) or Text style.copy(brush = ...). The 135° diagonal
// is approximated with start = Offset(0,0) → end = Offset.Infinite, which the
// Compose layout engine resolves to the drawn node's bottom-right corner.
// ─────────────────────────────────────────────────────────────────────────────

@Stable
object AgoraGradients {

    /** Primary 135° indigo→violet→pink gradient (dark theme). */
    val primaryGradient: Brush
        @Composable get() = Brush.linearGradient(
            colors = listOf(AgoraColors.gradientStart, AgoraColors.gradientMid, AgoraColors.gradientEnd),
            start = Offset(0f, 0f),
            end = Offset.Infinite,
        )

    /** Primary 135° gradient tuned for the light theme. */
    val lightGradient: Brush
        @Composable get() = Brush.linearGradient(
            colors = listOf(
                AgoraColors.gradientStartLight,
                AgoraColors.gradientMidLight,
                AgoraColors.gradientEndLight,
            ),
            start = Offset(0f, 0f),
            end = Offset.Infinite,
        )

    /**
     * Theme-aware primary gradient — picks [primaryGradient] in dark UI and
     * [lightGradient] in light UI. Resolves the active theme via [LocalAgoraColors]
     * so it stays consistent with the user's explicit ThemeMode choice (not just
     * the system default). This is the brush most call sites should use.
     */
    val gradient: Brush
        @Composable get() = if (LocalAgoraColors.current.isDark) primaryGradient else lightGradient

    /** 3dp card top-border gradient (indigo→violet→pink, full opacity). */
    val cardTopBorderGradient: Brush
        @Composable get() = Brush.linearGradient(
            colors = listOf(AgoraColors.gradientStart, AgoraColors.gradientMid, AgoraColors.gradientEnd),
            start = Offset(0f, 0f),
            end = Offset.Infinite,
        )

    /** Light-theme card top-border gradient. */
    val cardTopBorderGradientLight: Brush
        @Composable get() = Brush.linearGradient(
            colors = listOf(
                AgoraColors.gradientStartLight,
                AgoraColors.gradientMidLight,
                AgoraColors.gradientEndLight,
            ),
            start = Offset(0f, 0f),
            end = Offset.Infinite,
        )

    /**
     * Theme-aware card top-border gradient. Resolves via [LocalAgoraColors].
     */
    val cardTopBorder: Brush
        @Composable get() = if (LocalAgoraColors.current.isDark) cardTopBorderGradient else cardTopBorderGradientLight
}

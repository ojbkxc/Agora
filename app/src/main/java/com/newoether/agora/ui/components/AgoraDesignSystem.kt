package com.newoether.agora.ui.components

import android.os.Build
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.newoether.agora.ui.theme.AgoraShapes

import com.newoether.agora.ui.theme.LocalAgoraColors
import com.newoether.agora.ui.theme.LocalAgoraGradients

// ─────────────────────────────────────────────────────────────────────────────
// Internal helpers
// ─────────────────────────────────────────────────────────────────────────────

/** True when Modifier.blur is usable (API 31+, Android S). Below this blur is a no-op. */
private val canBlur: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * Applies a glass blur to the modifier. On API < 31 this is a no-op so callers
 * get a plain translucent fallback instead of a crash.
 */
private fun Modifier.glassBlur(radius: Dp): Modifier =
    if (canBlur) this.blur(radius) else this

// ─────────────────────────────────────────────────────────────────────────────
// GlassCard
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Glassmorphism card: translucent slate background, 20dp corner radius, soft
 * shadow, and hover lift. Optionally renders a 3dp indigo→violet→pink gradient
 * top border that appears on hover.
 *
 * @param cornerRadius  Corner radius (default 20dp, cf-ai-gw card spec).
 * @param showGradientBorder When true, a 3dp gradient top border fades in on hover.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    showGradientBorder: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = LocalAgoraColors.current
    val gradients = LocalAgoraGradients.current
    val shape = RoundedCornerShape(cornerRadius)

    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    // Hover lift: 4dp up + shadow grows 4dp → 8dp.
    val elevation by animateDpAsState(
        targetValue = if (isHovered) 8.dp else 4.dp,
        label = "glassCardElevation",
    )
    val lift by animateFloatAsState(
        targetValue = if (isHovered) -4f else 0f,
        label = "glassCardLift",
    )
    // Gradient top-border alpha fades in on hover.
    val borderAlpha by animateFloatAsState(
        targetValue = if (isHovered && showGradientBorder) 1f else 0f,
        label = "glassCardBorderAlpha",
    )

    Box(
        modifier = modifier
            .hoverable(interactionSource)
            .shadow(elevation = elevation, shape = shape, clip = false)
            .graphicsLayer { translationY = lift }
            .clip(shape)
            .background(colors.cardBg, shape)
            .glassBlur(20.dp),
    ) {
        content()
        // Gradient top border — 3dp strip at the top edge, faded by hover.
        if (showGradientBorder) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .graphicsLayer { alpha = borderAlpha }
                    .background(gradients.cardTopBorder),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// GradientButton
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Gradient button: 135° indigo→violet→pink background, 10dp corners, purple
 * glow shadow, 2dp hover lift, press dip.
 *
 * @param icon Optional leading icon composable.
 */
@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: @Composable (() -> Unit)? = null,
) {
    val colors = LocalAgoraColors.current
    val gradients = LocalAgoraGradients.current
    val shape = AgoraShapes.buttonShape

    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()

    // Hover lift 2dp, press dip 1dp.
    val lift by animateFloatAsState(
        targetValue = when {
            isPressed -> 1f
            isHovered -> -2f
            else -> 0f
        },
        label = "gradientButtonLift",
    )
    val elevation by animateDpAsState(
        targetValue = if (isHovered && enabled) 8.dp else 4.dp,
        label = "gradientButtonElevation",
    )

    Surface(
        onClick = onClick,
        modifier = modifier
            .graphicsLayer {
                translationY = lift
                alpha = if (enabled) 1f else 0.4f
            }
            .shadow(
                elevation = elevation,
                shape = shape,
                clip = false,
                spotColor = colors.accent.copy(alpha = 0.4f),
                ambientColor = colors.accent.copy(alpha = 0.15f),
            )
            .clip(shape)
            .background(gradients.gradient, shape),
        shape = shape,
        color = Color.Transparent,
        enabled = enabled,
        interactionSource = interactionSource,

    ) {
        Row(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon?.invoke()
            Text(
                text = text,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// GradientText
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Gradient text: applies the 135° indigo→violet→pink brush to the text style.
 * Use for brand wordmarks, section headings, or logos.
 */
@Composable
fun GradientText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleLarge,
) {
    val gradients = LocalAgoraGradients.current
    Text(
        text = text,
        modifier = modifier,
        style = style.copy(brush = gradients.gradient),
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// BackgroundOrbs
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Background orbs: two large blurred circles — indigo (top-left) and pink
 * (bottom-right) — that sit behind content to give the cf-ai-gw ambient glow.
 * Falls back to un-blurred translucent circles on API < 31.
 */
@Composable
fun BackgroundOrbs(
    modifier: Modifier = Modifier,
) {
    val colors = LocalAgoraColors.current
    val orbSize = 420.dp
    val blurRadius = 80.dp

    Box(modifier = modifier.fillMaxSize()) {
        // Indigo orb — top-left, offset out of frame for a partial bleed.
        Box(
            modifier = Modifier
                .size(orbSize)
                .align(Alignment.TopStart)
                .graphicsLayer { translationX = -120f; translationY = -120f }
                .clip(CircleShape)
                .background(colors.orb1)
                .glassBlur(blurRadius),
        )
        // Pink orb — bottom-right, offset out of frame.
        Box(
            modifier = Modifier
                .size(orbSize)
                .align(Alignment.BottomEnd)
                .graphicsLayer { translationX = 120f; translationY = 120f }
                .clip(CircleShape)
                .background(colors.orb2)
                .glassBlur(blurRadius),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// GlassTextField
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Glass text field: translucent background, 10dp corners, faint border at rest,
 * purple border + purple glow on focus. Placeholder text uses the muted colour.
 */
@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
) {
    val colors = LocalAgoraColors.current
    val shape = AgoraShapes.inputShape

    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val borderColor = if (isFocused) colors.accent.copy(alpha = 0.6f) else colors.border
    val borderWidth = if (isFocused) 1.5.dp else 1.dp
    // Focus glow: purple spot shadow approximating box-shadow(0 0 0 3dp rgba(168,85,247,0.2)).
    val glowElevation by animateDpAsState(
        targetValue = if (isFocused) 6.dp else 0.dp,
        label = "glassFieldGlow",
    )

    Box(
        modifier = modifier
            .shadow(
                elevation = glowElevation,
                shape = shape,
                clip = false,
                spotColor = colors.accent.copy(alpha = 0.3f),
                ambientColor = Color.Transparent,
            )
            .clip(shape)
            .background(colors.inputBg, shape)
            .border(borderWidth, borderColor, shape),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.textMain),
            singleLine = singleLine,
            interactionSource = interactionSource,
            cursorBrush = SolidColor(colors.accent),
            decorationBox = { innerTextField ->
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        color = colors.textMuted,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                innerTextField()
            },
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// GlassSurface
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Glass surface: large-area translucent background with 20dp corners and blur.
 * Use for sidebars, bottom bars, or any panel that needs the glass treatment
 * without the hover/gradient-border behaviour of [GlassCard].
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = LocalAgoraColors.current
    val shape = AgoraShapes.cardShape

    Box(
        modifier = modifier
            .clip(shape)
            .background(colors.cardBg, shape)
            .glassBlur(20.dp),
    ) {
        content()
    }
}
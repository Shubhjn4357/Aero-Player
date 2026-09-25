package com.aerotech.aeroplayer.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aerotech.aeroplayer.data.database.PreferenceEntity
import com.aerotech.aeroplayer.player.subtitle.SubtitleCue

/**
 * High-performance Jetpack Compose subtitle overlay with real-time dynamic styling,
 * drop shadows, multi-direction stroke outlines, glass background boxes, and responsive positioning.
 */
@Composable
fun UnifiedSubtitleOverlay(
    cues: List<SubtitleCue>,
    prefs: PreferenceEntity,
    modifier: Modifier = Modifier,
    isVisible: Boolean = true
) {
    if (!isVisible || cues.isEmpty()) return

    val fullSubtitleText = remember(cues) {
        cues.joinToString("\n") { it.text }.trim()
    }

    if (fullSubtitleText.isBlank()) return

    // Calculate colors and typography from user preferences
    val textColor = remember(prefs.subtitleTextColor, prefs.subtitleOpacity) {
        try {
            val parsed = android.graphics.Color.parseColor(prefs.subtitleTextColor)
            val alpha = (prefs.subtitleOpacity.coerceIn(0.1f, 1.0f) * 255).toInt().coerceIn(0, 255)
            Color((parsed and 0x00FFFFFF) or (alpha shl 24))
        } catch (e: Exception) {
            Color.White
        }
    }

    val backgroundColor = remember(prefs.subtitleBackground, prefs.subtitleBackgroundEnabled) {
        try {
            val bg = prefs.subtitleBackground
            if (!prefs.subtitleBackgroundEnabled && (bg == "#00000000" || bg.isEmpty())) {
                Color.Transparent
            } else {
                Color(android.graphics.Color.parseColor(bg))
            }
        } catch (e: Exception) {
            Color.Transparent
        }
    }

    val fontWeight = remember(prefs.subtitleFontStyle, prefs.subtitleBold) {
        when {
            prefs.subtitleBold -> FontWeight.Bold
            prefs.subtitleFontStyle == "Bold" || prefs.subtitleFontStyle == "Bold Italic" -> FontWeight.Bold
            else -> FontWeight.SemiBold
        }
    }

    val fontStyle = remember(prefs.subtitleFontStyle) {
        when (prefs.subtitleFontStyle) {
            "Italic", "Bold Italic" -> FontStyle.Italic
            else -> FontStyle.Normal
        }
    }

    val fontFamily = remember(prefs.subtitleFontStyle) {
        when (prefs.subtitleFontStyle) {
            "Monospace" -> FontFamily.Monospace
            "Serif" -> FontFamily.Serif
            "Sans-Serif" -> FontFamily.SansSerif
            else -> FontFamily.Default
        }
    }

    val outlineColor = remember(prefs.subtitleOutlineColor, prefs.subtitleOutlineOpacity) {
        try {
            if (prefs.subtitleOutlineColor == "#00000000" || prefs.subtitleOutlineColor.isEmpty()) {
                Color.Transparent
            } else {
                val parsed = android.graphics.Color.parseColor(prefs.subtitleOutlineColor)
                val alpha = (prefs.subtitleOutlineOpacity.coerceIn(0f, 1.0f) * 255).toInt().coerceIn(0, 255)
                Color((parsed and 0x00FFFFFF) or (alpha shl 24))
            }
        } catch (e: Exception) {
            Color.Black
        }
    }

    val shadowColor = remember(prefs.subtitleShadowColor, prefs.subtitleShadowOpacity, prefs.subtitleShadowEnabled) {
        if (!prefs.subtitleShadowEnabled) {
            Color.Transparent
        } else {
            try {
                if (prefs.subtitleShadowColor == "#00000000" || prefs.subtitleShadowColor.isEmpty()) {
                    Color.Transparent
                } else {
                    val parsed = android.graphics.Color.parseColor(prefs.subtitleShadowColor)
                    val alpha = (prefs.subtitleShadowOpacity.coerceIn(0f, 1.0f) * 255).toInt().coerceIn(0, 255)
                    Color((parsed and 0x00FFFFFF) or (alpha shl 24))
                }
            } catch (e: Exception) {
                Color.Black.copy(alpha = 0.85f)
            }
        }
    }

    val textShadow = remember(shadowColor, outlineColor, prefs.subtitleShadowRadius) {
        when {
            shadowColor != Color.Transparent -> {
                Shadow(
                    color = shadowColor,
                    offset = Offset(2f, 2f),
                    blurRadius = (prefs.subtitleShadowRadius * 2.5f).coerceIn(1f, 12f)
                )
            }
            outlineColor != Color.Transparent -> {
                Shadow(
                    color = outlineColor,
                    offset = Offset(1.5f, 1.5f),
                    blurRadius = 3f
                )
            }
            else -> {
                Shadow(
                    color = Color.Black.copy(alpha = 0.9f),
                    offset = Offset(1.5f, 1.5f),
                    blurRadius = 2.5f
                )
            }
        }
    }

    val fontSizeSp = remember(prefs.subtitleSize) {
        prefs.subtitleSize.coerceIn(10f, 40f).sp
    }

    val verticalOffsetPercent = remember(prefs.subtitleVerticalOffset) {
        prefs.subtitleVerticalOffset.coerceIn(0.01f, 0.45f)
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxSize()
    ) {
        val bottomOffsetPx = (maxHeight * verticalOffsetPercent)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    bottom = bottomOffsetPx.coerceAtLeast(12.dp),
                    start = 24.dp,
                    end = 24.dp
                ),
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(
                modifier = Modifier
                    .wrapContentSize()
                    .widthIn(max = 680.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .then(
                        if (backgroundColor != Color.Transparent) {
                            Modifier.background(backgroundColor)
                        } else {
                            Modifier
                        }
                    )
                    .padding(
                        horizontal = if (backgroundColor != Color.Transparent) 10.dp else 4.dp,
                        vertical = if (backgroundColor != Color.Transparent) 4.dp else 2.dp
                    ),
                contentAlignment = Alignment.Center
            ) {
                // If outline color is configured with high opacity, render outline layer underneath
                if (outlineColor != Color.Transparent && outlineColor != Color.Black) {
                    val outlineStrokeShadow = Shadow(
                        color = outlineColor,
                        offset = Offset.Zero,
                        blurRadius = 4f
                    )
                    Text(
                        text = fullSubtitleText,
                        color = outlineColor,
                        fontSize = fontSizeSp,
                        fontWeight = fontWeight,
                        fontStyle = fontStyle,
                        fontFamily = fontFamily,
                        textAlign = TextAlign.Center,
                        lineHeight = (fontSizeSp.value * 1.25f).sp,
                        style = TextStyle(shadow = outlineStrokeShadow)
                    )
                }

                Text(
                    text = fullSubtitleText,
                    color = textColor,
                    fontSize = fontSizeSp,
                    fontWeight = fontWeight,
                    fontStyle = fontStyle,
                    fontFamily = fontFamily,
                    textAlign = TextAlign.Center,
                    lineHeight = (fontSizeSp.value * 1.25f).sp,
                    style = TextStyle(shadow = textShadow)
                )
            }
        }
    }
}

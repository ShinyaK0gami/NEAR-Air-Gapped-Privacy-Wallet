package com.nearair.vault.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class VaultAppearanceConfig(
    val themeMode: String = "dark",       // "dark" | "midnight" | "light"
    val accentColor: String = "emerald",  // "emerald" | "cyan" | "violet"
    val glassStyle: String = "frosted"    // "frosted" | "liquid"
) {
    val primaryAccent: Color
        get() = when (accentColor) {
            "cyan" -> Color(0xFF38BDF8)
            "violet" -> Color(0xFFA855F7)
            else -> Color(0xFF00EC97)
        }

    val secondaryAccent: Color
        get() = when (accentColor) {
            "cyan" -> Color(0xFF818CF8)
            "violet" -> Color(0xFFEC4899)
            else -> Color(0xFF38BDF8)
        }

    val canvasBackground: Color
        get() = when (themeMode) {
            "midnight" -> Color(0xFF070B19)
            "light" -> Color(0xFF0E172A)
            else -> Color(0xFF05070E)
        }
}

val LocalVaultAppearance = compositionLocalOf { VaultAppearanceConfig() }

object GlassiFyColors {
    val DeepSpace = Color(0xFF05070E)
    val EmeraldGlow = Color(0xFF00EC97)
    val CyanGlow = Color(0xFF38BDF8)
    val VioletGlow = Color(0xFF8B5CF6)
    val AmberGlow = Color(0xFFFBBF24)
    val CrimsonGlow = Color(0xFFF87171)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFFCBD5E1)
    val TextMuted = Color(0xFF94A3B8)
}

@Composable
fun GlassiFyBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val appearance = LocalVaultAppearance.current
    val blobAlphaMultiplier = if (appearance.glassStyle == "liquid") 1.35f else 1.0f

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(appearance.canvasBackground)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // Top-Left Primary Accent Glow Orb
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        appearance.primaryAccent.copy(alpha = (0.26f * blobAlphaMultiplier).coerceAtMost(0.45f)),
                        appearance.primaryAccent.copy(alpha = 0.08f),
                        Color.Transparent
                    ),
                    center = Offset(w * 0.14f, h * 0.10f),
                    radius = w * 0.80f
                ),
                radius = w * 0.80f,
                center = Offset(w * 0.14f, h * 0.10f)
            )

            // Bottom-Right Secondary Glow Orb
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        appearance.secondaryAccent.copy(alpha = (0.24f * blobAlphaMultiplier).coerceAtMost(0.42f)),
                        Color(0x208B5CF6),
                        Color.Transparent
                    ),
                    center = Offset(w * 0.88f, h * 0.84f),
                    radius = w * 0.85f
                ),
                radius = w * 0.85f,
                center = Offset(w * 0.88f, h * 0.84f)
            )

            // Center Ambient Orb
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        appearance.primaryAccent.copy(alpha = 0.12f * blobAlphaMultiplier),
                        Color.Transparent
                    ),
                    center = Offset(w * 0.5f, h * 0.44f),
                    radius = w * 0.65f
                ),
                radius = w * 0.65f,
                center = Offset(w * 0.5f, h * 0.44f)
            )
        }

        content()
    }
}

/**
 * Floating Pill Top Navbar matching the Online PC App (`NA` Logo + `NearAir` + `Cold Vault` pill + Network & Settings)
 */
@Composable
fun NearAirTopHeaderBar(
    subtitleBadge: String = "Cold Vault",
    networkLabel: String = "TESTNET",
    onOpenSettings: (() -> Unit)? = null,
    rightActionLabel: String? = null,
    onRightActionClick: (() -> Unit)? = null
) {
    val appearance = LocalVaultAppearance.current

    GlassiFyPanel(
        cornerRadius = 50.dp,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // NA Circular Gradient Logo Badge (identical to Online App)
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    appearance.primaryAccent,
                                    appearance.secondaryAccent
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "NA",
                        color = Color(0xFF03120C),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black
                    )
                }

                Text(
                    text = "NearAir",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold
                )

                // Pill Badge (matches Online App's "Watch-Only" pill)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.08f))
                        .border(1.dp, appearance.primaryAccent.copy(alpha = 0.35f), RoundedCornerShape(50))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "● $subtitleBadge",
                        color = appearance.primaryAccent,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(appearance.primaryAccent)
                        .padding(horizontal = 9.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = networkLabel,
                        color = Color(0xFF03120C),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }

                if (rightActionLabel != null && onRightActionClick != null) {
                    GlassiFyPillButton(
                        text = rightActionLabel,
                        textColor = Color.White,
                        onClick = onRightActionClick
                    )
                } else if (onOpenSettings != null) {
                    GlassiFyPillButton(
                        text = "⚙",
                        textColor = Color.White,
                        onClick = onOpenSettings
                    )
                }
            }
        }
    }
}

@Composable
fun GlassiFyPanel(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 26.dp,
    accentBorderColor: Color? = null,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val appearance = LocalVaultAppearance.current
    val shape = RoundedCornerShape(cornerRadius)
    val baseAlpha = when (appearance.themeMode) {
        "light" -> 0.16f
        "midnight" -> 0.11f
        else -> 0.095f
    }
    val surfaceBrush = Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = baseAlpha),
            Color.White.copy(alpha = baseAlpha * 0.38f),
            Color.White.copy(alpha = baseAlpha * 0.65f)
        ),
        start = Offset(0f, 0f),
        end = Offset(900f, 900f)
    )
    val borderBrush = if (accentBorderColor != null) {
        Brush.linearGradient(
            colors = listOf(
                accentBorderColor.copy(alpha = 0.72f),
                Color.White.copy(alpha = 0.24f),
                accentBorderColor.copy(alpha = 0.32f)
            )
        )
    } else {
        Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.34f),
                Color.White.copy(alpha = 0.10f),
                Color(0xFF64748B).copy(alpha = 0.26f)
            )
        )
    }

    val clickableModifier = if (onClick != null) {
        Modifier
            .clip(shape)
            .clickable { onClick() }
    } else {
        Modifier.clip(shape)
    }

    Column(
        modifier = modifier
            .then(clickableModifier)
            .background(brush = surfaceBrush, shape = shape)
            .border(width = 1.2.dp, brush = borderBrush, shape = shape)
            .padding(contentPadding),
        content = content
    )
}

@Composable
fun GlassiFySubCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 18.dp,
    contentPadding: PaddingValues = PaddingValues(14.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    Column(
        modifier = modifier
            .clip(shape)
            .background(Color(0x99080E1E), shape)
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.20f),
                        Color.White.copy(alpha = 0.06f)
                    )
                ),
                shape = shape
            )
            .padding(contentPadding),
        content = content
    )
}

@Composable
fun GlassiFyPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    textColor: Color = GlassiFyColors.EmeraldGlow,
    fontSize: TextUnit = 11.sp
) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.13f),
                        Color.White.copy(alpha = 0.05f)
                    )
                ),
                shape = shape
            )
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.32f),
                        textColor.copy(alpha = 0.28f)
                    )
                ),
                shape = shape
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = fontSize,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun GlassiFyPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val appearance = LocalVaultAppearance.current
    val shape = RoundedCornerShape(18.dp)
    val bgBrush = if (enabled) {
        Brush.linearGradient(
            colors = listOf(
                appearance.primaryAccent,
                appearance.primaryAccent.copy(alpha = 0.85f),
                appearance.secondaryAccent
            )
        )
    } else {
        Brush.linearGradient(
            colors = listOf(
                Color(0xFF1E293B),
                Color(0xFF0F172A)
            )
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(shape)
            .background(brush = bgBrush, shape = shape)
            .border(
                width = 1.2.dp,
                color = if (enabled) Color.White.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.1f),
                shape = shape
            )
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (enabled) Color(0xFF03120C) else Color(0xFF64748B),
            fontSize = 14.sp,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

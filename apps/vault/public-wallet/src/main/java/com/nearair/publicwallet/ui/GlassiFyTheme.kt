package com.nearair.publicwallet.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class PublicWalletAppearanceConfig(
    val themeMode: String = "dark",
    val accentColor: String = "emerald"
) {
    val primaryAccent: Color
        get() = when (accentColor) {
            "cyan" -> Color(0xFF38BDF8)
            "violet" -> Color(0xFFA855F7)
            else -> Color(0xFF00EC97)
        }

    val canvasBackground: Color
        get() = Color(0xFF04060C)
}

val LocalWalletAppearance = compositionLocalOf { PublicWalletAppearanceConfig() }

object WalletColors {
    val DeepSpace = Color(0xFF04060C)
    val SurfaceDark = Color(0xFF0A0F1D)
    val CardBackground = Color(0xDD0D1527)
    val CardBorder = Color(0x1FFFFFFF)
    val EmeraldGlow = Color(0xFF00EC97)
    val CyanGlow = Color(0xFF38BDF8)
    val VioletGlow = Color(0xFF8B5CF6)
    val AmberGlow = Color(0xFFFBBF24)
    val CrimsonGlow = Color(0xFFF87171)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFF94A3B8)
    val TextMuted = Color(0xFF64748B)
    val Divider = Color(0x12FFFFFF)
}

@Composable
fun GlassBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val appearance = LocalWalletAppearance.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(appearance.canvasBackground)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // Top-Left Soft Ambient Radial Glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        appearance.primaryAccent.copy(alpha = 0.16f),
                        appearance.primaryAccent.copy(alpha = 0.04f),
                        Color.Transparent
                    ),
                    center = Offset(w * 0.20f, h * 0.08f),
                    radius = w * 0.90f
                ),
                radius = w * 0.90f,
                center = Offset(w * 0.20f, h * 0.08f)
            )

            // Bottom-Right Soft Cyan Ambient Glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF38BDF8).copy(alpha = 0.12f),
                        Color(0xFF38BDF8).copy(alpha = 0.03f),
                        Color.Transparent
                    ),
                    center = Offset(w * 0.85f, h * 0.88f),
                    radius = w * 0.95f
                ),
                radius = w * 0.95f,
                center = Offset(w * 0.85f, h * 0.88f)
            )
        }
        content()
    }
}

@Composable
fun PhantomCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    borderColor: Color = WalletColors.CardBorder,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(WalletColors.CardBackground)
            .border(1.dp, borderColor, RoundedCornerShape(cornerRadius))
            .padding(contentPadding)
    ) {
        Column(content = content)
    }
}

@Composable
fun PhantomSectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        color = WalletColors.TextMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.2.sp,
        modifier = modifier.padding(horizontal = 4.dp, vertical = 6.dp)
    )
}

@Composable
fun PhantomDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(WalletColors.Divider)
    )
}

@Composable
fun PhantomCircleButton(
    iconText: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val appearance = LocalWalletAppearance.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(Color(0xFF0F172A))
                .border(1.dp, Color(0x28FFFFFF), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = iconText,
                color = appearance.primaryAccent,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(7.dp))
        Text(
            text = label,
            color = WalletColors.TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun PhantomPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val appearance = LocalWalletAppearance.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (enabled) {
                    Brush.horizontalGradient(
                        listOf(appearance.primaryAccent, Color(0xFF00C781))
                    )
                } else {
                    Brush.horizontalGradient(
                        listOf(Color(0xFF1E293B), Color(0xFF131D2D))
                    )
                }
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (enabled) Color(0xFF04120A) else WalletColors.TextMuted,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun PhantomPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    textColor: Color = Color.White
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0x1AFFFFFF))
            .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

package com.nearair.vault.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.nearair.vault.crypto.Signer

/**
 * Hardware Signed Transaction Output Screen (SafePal S1 Style)
 *
 * Displays the cryptographically signed QR code prominently so the Online
 * Near Wallet Public (or PC Watcher) can instantly scan it.
 */
@Composable
fun QROutputScreen(
    signedJson: String,
    signedResult: Signer.SignedTransactionResult,
    actualPublicKey: String = "",
    onDone: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val appearance = LocalVaultAppearance.current
    var copiedLabel by remember { mutableStateOf<String?>(null) }
    var isCompactQr by remember { mutableStateOf(true) }

    val compactPayload = if (actualPublicKey.isNotEmpty()) {
        "${signedResult.signatureBase58}:$actualPublicKey"
    } else {
        signedResult.signatureBase58
    }
    val qrContent = if (isCompactQr) compactPayload else signedJson

    val qrBitmap = remember(qrContent) {
        generateQrCodeBitmap(qrContent, 640)
    }

    GlassiFyBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            NearAirTopHeaderBar(
                subtitleBadge = "Signed QR",
                networkLabel = "TESTNET",
                onOpenSettings = onDone
            )

            Spacer(modifier = Modifier.height(18.dp))

            // Main Signed QR Card
            GlassiFyPanel(
                cornerRadius = 28.dp,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(22.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "TRANSACTION SIGNED",
                        color = appearance.primaryAccent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.4.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Scan with NEAR Public Wallet",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Point your everyday phone's camera at this screen to broadcast to NEAR blockchain.",
                        color = GlassiFyColors.TextSecondary,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // QR Mode Selector Pills
                    Row(
                        modifier = Modifier
                            .background(Color(0xFF141A26), RoundedCornerShape(14.dp))
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isCompactQr) appearance.primaryAccent else Color.Transparent,
                            onClick = { isCompactQr = true }
                        ) {
                            Text(
                                text = "⚡ Fast Compact QR",
                                color = if (isCompactQr) Color.Black else Color.Gray,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (!isCompactQr) appearance.primaryAccent else Color.Transparent,
                            onClick = { isCompactQr = false }
                        ) {
                            Text(
                                text = "Full JSON",
                                color = if (!isCompactQr) Color.Black else Color.Gray,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // High-Contrast QR Code Viewport
                    Box(
                        modifier = Modifier
                            .size(260.dp)
                            .background(Color.White, RoundedCornerShape(20.dp))
                            .padding(10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "Signed Transaction QR",
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = if (isCompactQr) "⚡ 96-Byte Signature QR • Scans in <50ms" else "📦 450-Byte Full JSON Payload",
                        color = appearance.primaryAccent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Cryptographic Details Card
            GlassiFyPanel(
                cornerRadius = 24.dp,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "SHA-256 TX Hash",
                        color = GlassiFyColors.TextMuted,
                        fontSize = 11.sp
                    )
                    GlassiFyPillButton(
                        text = if (copiedLabel == "txhash") "✓ Copied" else "Copy Hash",
                        textColor = GlassiFyColors.AmberGlow,
                        onClick = {
                            clipboardManager.setText(AnnotatedString(signedResult.txHashBase58))
                            copiedLabel = "txhash"
                        }
                    )
                }
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = signedResult.txHashBase58,
                    color = GlassiFyColors.AmberGlow,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Ed25519 Signature",
                        color = GlassiFyColors.TextMuted,
                        fontSize = 11.sp
                    )
                    GlassiFyPillButton(
                        text = if (copiedLabel == "sig") "✓ Copied" else "Copy Signature",
                        textColor = appearance.primaryAccent,
                        onClick = {
                            clipboardManager.setText(AnnotatedString(signedResult.signatureBase58))
                            copiedLabel = "sig"
                        }
                    )
                }
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = "${signedResult.signatureBase58.take(28)}...${signedResult.signatureBase58.takeLast(8)}",
                    color = appearance.primaryAccent,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Signed JSON Payload",
                        color = GlassiFyColors.TextMuted,
                        fontSize = 11.sp
                    )
                    GlassiFyPillButton(
                        text = if (copiedLabel == "json") "✓ Copied" else "Copy Signed JSON",
                        textColor = GlassiFyColors.CyanGlow,
                        onClick = {
                            clipboardManager.setText(AnnotatedString(signedJson))
                            copiedLabel = "json"
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            GlassiFyPrimaryButton(
                text = "Done — Return to Vault Home",
                onClick = onDone
            )
        }
    }
}

fun generateQrCodeBitmap(content: String, sizePx: Int = 720): Bitmap {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
        EncodeHintType.CHARACTER_SET to "UTF-8",
        EncodeHintType.MARGIN to 2
    )
    val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val pixels = IntArray(sizePx * sizePx)
    for (y in 0 until sizePx) {
        val offset = y * sizePx
        for (x in 0 until sizePx) {
            pixels[offset + x] = if (bitMatrix.get(x, y)) -0x1000000 else -0x1
        }
    }
    return Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
    }
}

package com.nearair.publicwallet.ui

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.*
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import java.nio.ByteBuffer
import java.util.concurrent.Executors

@Composable
fun CameraScanScreen(
    title: String = "Scan QR",
    subtitle: String = "Scan QR on cold storage device",
    onPayloadScanned: (String) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val clipboardManager = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current

    var manualInputOpen by remember { mutableStateOf(false) }
    var manualText by remember { mutableStateOf("") }
    var scanError by remember { mutableStateOf<String?>(null) }
    var isTorchOn by remember { mutableStateOf(false) }
    var cameraControlRef by remember { mutableStateOf<CameraControl?>(null) }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "scanner")
    val laserOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "laser"
    )

    GlassBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 18.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = subtitle,
                        color = WalletColors.TextSecondary,
                        fontSize = 12.sp
                    )
                }
                Text(
                    text = "✕",
                    color = WalletColors.TextMuted,
                    fontSize = 18.sp,
                    modifier = Modifier.clickable { onCancel() }
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Camera Viewport (Expanded 420.dp Height)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(Color.Black)
                    .border(1.5.dp, WalletColors.CardBorder, RoundedCornerShape(26.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (hasCameraPermission) {
                    var isScanned by remember { mutableStateOf(false) }

                    AndroidView(
                        factory = { ctx ->
                            val previewView = PreviewView(ctx).apply {
                                implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                                scaleType = PreviewView.ScaleType.FILL_CENTER
                            }
                            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                            val executor = Executors.newSingleThreadExecutor()

                            // Tap to focus on preview
                            previewView.setOnTouchListener { view, event ->
                                if (event.action == android.view.MotionEvent.ACTION_UP) {
                                    val factory = previewView.meteringPointFactory
                                    val point = factory.createPoint(event.x, event.y)
                                    val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                                        .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
                                        .build()
                                    cameraControlRef?.startFocusAndMetering(action)
                                    view.performClick()
                                }
                                true
                            }

                            cameraProviderFuture.addListener({
                                val cameraProvider = cameraProviderFuture.get()
                                val preview = Preview.Builder().build().also {
                                    it.setSurfaceProvider(previewView.surfaceProvider)
                                }

                                val imageAnalysis = ImageAnalysis.Builder()
                                    .setTargetResolution(Size(1280, 720))
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .build()

                                imageAnalysis.setAnalyzer(executor) { imageProxy ->
                                    if (!isScanned) {
                                        val result = RobustQrDecoder.decode(imageProxy)
                                        if (!result.isNullOrBlank()) {
                                            isScanned = true
                                            previewView.post {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                onPayloadScanned(result.trim())
                                            }
                                        }
                                    }
                                    imageProxy.close()
                                }

                                try {
                                    cameraProvider.unbindAll()
                                    val camera = cameraProvider.bindToLifecycle(
                                        lifecycleOwner,
                                        CameraSelector.DEFAULT_BACK_CAMERA,
                                        preview,
                                        imageAnalysis
                                    )
                                    cameraControlRef = camera.cameraControl
                                } catch (e: Exception) {
                                    scanError = e.message
                                }
                            }, ContextCompat.getMainExecutor(ctx))

                            previewView
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    // Target Viewfinder (Expanded 280.dp with SafePal / Phantom Style Corner Brackets)
                    Box(
                        modifier = Modifier
                            .size(280.dp)
                            .border(1.dp, WalletColors.EmeraldGlow.copy(alpha = 0.35f), RoundedCornerShape(22.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        // Top-Left Corner Bracket
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .size(28.dp)
                                .border(
                                    width = 3.5.dp,
                                    color = WalletColors.EmeraldGlow,
                                    shape = RoundedCornerShape(topStart = 16.dp)
                                )
                        )
                        // Top-Right Corner Bracket
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(28.dp)
                                .border(
                                    width = 3.5.dp,
                                    color = WalletColors.EmeraldGlow,
                                    shape = RoundedCornerShape(topEnd = 16.dp)
                                )
                        )
                        // Bottom-Left Corner Bracket
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .size(28.dp)
                                .border(
                                    width = 3.5.dp,
                                    color = WalletColors.EmeraldGlow,
                                    shape = RoundedCornerShape(bottomStart = 16.dp)
                                )
                        )
                        // Bottom-Right Corner Bracket
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(28.dp)
                                .border(
                                    width = 3.5.dp,
                                    color = WalletColors.EmeraldGlow,
                                    shape = RoundedCornerShape(bottomEnd = 16.dp)
                                )
                        )

                        // Animated Laser
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.5.dp)
                                .offset(y = (280 * (laserOffset - 0.5f)).dp)
                                .background(WalletColors.EmeraldGlow)
                        )
                    }

                    // Floating Torch / Flash Button
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (isTorchOn) WalletColors.EmeraldGlow else Color.Black.copy(alpha = 0.65f),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isTorchOn) WalletColors.EmeraldGlow else Color.White.copy(alpha = 0.2f)
                        ),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(14.dp),
                        onClick = {
                            val next = !isTorchOn
                            isTorchOn = next
                            cameraControlRef?.enableTorch(next)
                        }
                    ) {
                        Text(
                            text = if (isTorchOn) "🔦 Flash On" else "🔦 Flash Off",
                            color = if (isTorchOn) Color.Black else Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Camera Permission Required",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        PhantomPrimaryButton(
                            text = "Grant Permission",
                            onClick = { launcher.launch(Manifest.permission.CAMERA) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Point camera at the signed QR code on cold storage",
                color = WalletColors.TextMuted,
                fontSize = 12.sp
            )

            scanError?.let { err ->
                Spacer(modifier = Modifier.height(6.dp))
                Text(text = err, color = WalletColors.CrimsonGlow, fontSize = 11.sp)
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Expandable Manual Paste Fallback
            PhantomCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 16.dp,
                contentPadding = PaddingValues(14.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { manualInputOpen = !manualInputOpen },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "⌨️ Can't scan? Paste QR Payload",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (manualInputOpen) "Close ▲" else "Paste ▼",
                            color = WalletColors.EmeraldGlow,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (manualInputOpen) {
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = manualText,
                            onValueChange = { manualText = it },
                            placeholder = {
                                Text(
                                    "Paste signed transaction JSON or ed25519:... signature",
                                    color = WalletColors.TextMuted,
                                    fontSize = 11.sp
                                )
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = WalletColors.EmeraldGlow,
                                unfocusedBorderColor = Color(0x30FFFFFF)
                            ),
                            maxLines = 4,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            PhantomPillButton(
                                text = "Paste Clipboard",
                                textColor = WalletColors.EmeraldGlow,
                                onClick = {
                                    val clip = clipboardManager.getText()?.text
                                    if (!clip.isNullOrBlank()) {
                                        manualText = clip.trim()
                                    }
                                }
                            )

                            Button(
                                onClick = {
                                    if (manualText.isNotBlank()) {
                                        onPayloadScanned(manualText.trim())
                                    }
                                },
                                enabled = manualText.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(containerColor = WalletColors.EmeraldGlow),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = "Submit Payload",
                                    color = Color.Black,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

object RobustQrDecoder {
    private val qrReader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
                DecodeHintType.CHARACTER_SET to "UTF-8"
            )
        )
    }

    @Synchronized
    fun decode(imageProxy: ImageProxy): String? {
        val plane = imageProxy.planes.firstOrNull() ?: return null
        val buffer: ByteBuffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val width = imageProxy.width
        val height = imageProxy.height
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees

        // 1. Correctly unpack luminance data handling rowStride padding
        val cleanData = ByteArray(width * height)
        var destOffset = 0
        if (rowStride == width && pixelStride == 1) {
            val toRead = minOf(buffer.remaining(), cleanData.size)
            buffer.get(cleanData, 0, toRead)
        } else {
            val rowBuffer = ByteArray(rowStride)
            for (row in 0 until height) {
                val bytesToRead = minOf(buffer.remaining(), rowStride)
                if (bytesToRead <= 0) break
                buffer.get(rowBuffer, 0, bytesToRead)
                if (pixelStride == 1) {
                    System.arraycopy(rowBuffer, 0, cleanData, destOffset, minOf(width, bytesToRead))
                } else {
                    for (col in 0 until width) {
                        val srcIdx = col * pixelStride
                        if (srcIdx < bytesToRead) {
                            cleanData[destOffset + col] = rowBuffer[srcIdx]
                        }
                    }
                }
                destOffset += width
            }
        }

        // 2. Rotate according to device camera sensor orientation
        val (rotatedData, finalWidth, finalHeight) = rotateYuv(cleanData, width, height, rotationDegrees)

        // 3. Fast Pass 1: Center-crop 75% for ultra-low latency (<20ms) directly in viewfinder
        val cropW = (finalWidth * 0.75).toInt()
        val cropH = (finalHeight * 0.75).toInt()
        val cropLeft = (finalWidth - cropW) / 2
        val cropTop = (finalHeight - cropH) / 2

        val croppedSource = PlanarYUVLuminanceSource(
            rotatedData,
            finalWidth,
            finalHeight,
            cropLeft,
            cropTop,
            cropW,
            cropH,
            false
        )

        try {
            val bitmap = BinaryBitmap(HybridBinarizer(croppedSource))
            return qrReader.decodeWithState(bitmap).text
        } catch (_: NotFoundException) {
            // continue to full frame passes
        } catch (_: Exception) {}

        // 4. Multi-pass full frame decoding
        val source = PlanarYUVLuminanceSource(
            rotatedData,
            finalWidth,
            finalHeight,
            0,
            0,
            finalWidth,
            finalHeight,
            false
        )

        try {
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            return qrReader.decodeWithState(bitmap).text
        } catch (_: NotFoundException) {
            // Pass 2: Invert for dark screen reflection / glare
            try {
                val invertedBitmap = BinaryBitmap(HybridBinarizer(source.invert()))
                return qrReader.decodeWithState(invertedBitmap).text
            } catch (_: NotFoundException) {
                // Pass 3: Global histogram for low contrast screens
                try {
                    val globalBitmap = BinaryBitmap(GlobalHistogramBinarizer(source))
                    return qrReader.decodeWithState(globalBitmap).text
                } catch (_: Exception) {
                    return null
                }
            } catch (_: Exception) {
                return null
            }
        } catch (_: Exception) {
            return null
        } finally {
            qrReader.reset()
        }
    }

    private fun rotateYuv(data: ByteArray, width: Int, height: Int, rotationDegrees: Int): Triple<ByteArray, Int, Int> {
        return when (rotationDegrees) {
            90 -> {
                val rotated = ByteArray(data.size)
                var k = 0
                for (x in 0 until width) {
                    for (y in height - 1 downTo 0) {
                        rotated[k++] = data[y * width + x]
                    }
                }
                Triple(rotated, height, width)
            }
            180 -> {
                val rotated = ByteArray(data.size)
                var k = 0
                for (i in data.indices.reversed()) {
                    rotated[k++] = data[i]
                }
                Triple(rotated, width, height)
            }
            270 -> {
                val rotated = ByteArray(data.size)
                var k = 0
                for (x in width - 1 downTo 0) {
                    for (y in 0 until height) {
                        rotated[k++] = data[y * width + x]
                    }
                }
                Triple(rotated, height, width)
            }
            else -> Triple(data, width, height)
        }
    }
}

package com.nearair.vault.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.nearair.vault.model.MultiFrameQrCollector
import java.util.concurrent.Executors

/**
 * Air-Gapped Optical QR Scanner Screen (CameraX + ZXing Offline Decoder)
 */
@Composable
fun CameraScanScreen(
    onPayloadScanned: (String) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val appearance = LocalVaultAppearance.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val collector = remember { MultiFrameQrCollector() }
    var frameProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var manualPasteOpen by remember { mutableStateOf(false) }
    var manualJsonText by remember { mutableStateOf("") }

    GlassiFyBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            NearAirTopHeaderBar(
                subtitleBadge = "Optical Scanner",
                rightActionLabel = "← Back",
                onRightActionClick = onCancel
            )

            Spacer(modifier = Modifier.height(14.dp))

            // CameraX Preview Box
            if (hasCameraPermission) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(28.dp))
                        .border(2.dp, appearance.primaryAccent, RoundedCornerShape(28.dp))
                        .background(Color.Black, RoundedCornerShape(28.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            val previewView = PreviewView(ctx)
                            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                            val analysisExecutor = Executors.newSingleThreadExecutor()

                            cameraProviderFuture.addListener({
                                val cameraProvider = cameraProviderFuture.get()
                                val preview = Preview.Builder().build().also {
                                    it.setSurfaceProvider(previewView.surfaceProvider)
                                }

                                val qrReader = MultiFormatReader().apply {
                                    setHints(
                                        mapOf(
                                            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                                            DecodeHintType.CHARACTER_SET to "UTF-8"
                                        )
                                    )
                                }

                                val imageAnalysis = ImageAnalysis.Builder()
                                    .setTargetResolution(android.util.Size(1280, 720))
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .build()
                                    .also { analysis ->
                                        analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                                            val decodedText = decodeQrFromImageProxy(imageProxy, qrReader)
                                            if (!decodedText.isNullOrBlank()) {
                                                try {
                                                    val status = collector.ingest(decodedText)
                                                    if (status.totalCount > 1) {
                                                        frameProgress = Pair(
                                                            status.receivedCount,
                                                            status.totalCount
                                                        )
                                                    }
                                                    if (status.complete && status.fullPayload != null) {
                                                        previewView.post {
                                                            onPayloadScanned(status.fullPayload)
                                                        }
                                                    }
                                                } catch (e: Exception) {
                                                    scanError = e.message
                                                }
                                            }
                                            imageProxy.close()
                                        }
                                    }

                                try {
                                    cameraProvider.unbindAll()
                                    cameraProvider.bindToLifecycle(
                                        lifecycleOwner,
                                        CameraSelector.DEFAULT_BACK_CAMERA,
                                        preview,
                                        imageAnalysis
                                    )
                                } catch (e: Exception) {
                                    scanError = e.message
                                }
                            }, ContextCompat.getMainExecutor(ctx))

                            previewView
                        }
                    )

                    // Target Reticle Overlay
                    Box(
                        modifier = Modifier
                            .size(230.dp)
                            .border(2.5.dp, appearance.primaryAccent, RoundedCornerShape(22.dp))
                    )

                    // Multi-Frame Progress Indicator
                    frameProgress?.let { (received, total) ->
                        Surface(
                            color = Color(0xDD080E1E),
                            shape = RoundedCornerShape(50),
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(16.dp)
                        ) {
                            Text(
                                text = "Multi-Frame QR: $received / $total frames captured",
                                color = appearance.primaryAccent,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            } else {
                GlassiFyPanel(
                    cornerRadius = 28.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Camera Permission Required",
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 17.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "NearAir Vault uses ONLY the optical camera to receive unsigned transactions.",
                            color = GlassiFyColors.TextSecondary,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(18.dp))
                        GlassiFyPrimaryButton(
                            text = "Grant Camera Permission",
                            onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }
                        )
                    }
                }
            }

            scanError?.let { err ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = err, color = GlassiFyColors.CrimsonGlow, fontSize = 12.sp)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Emulator / Manual Paste Fallback
            GlassiFyPillButton(
                text = if (manualPasteOpen) "Hide Manual Input" else "Emulator / Paste Raw QR JSON",
                textColor = GlassiFyColors.CyanGlow,
                onClick = { manualPasteOpen = !manualPasteOpen }
            )

            if (manualPasteOpen) {
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = manualJsonText,
                    onValueChange = { manualJsonText = it },
                    placeholder = { Text("Paste unsigned QR JSON envelope...") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 4
                )
                Spacer(modifier = Modifier.height(8.dp))
                GlassiFyPrimaryButton(
                    text = "Verify & Inspect Payload",
                    onClick = {
                        if (manualJsonText.isNotBlank()) {
                            onPayloadScanned(manualJsonText.trim())
                        }
                    }
                )
            }
        }
    }
}

private fun decodeQrFromImageProxy(imageProxy: ImageProxy, reader: MultiFormatReader): String? {
    val plane = imageProxy.planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val width = imageProxy.width
    val height = imageProxy.height
    val rotationDegrees = imageProxy.imageInfo.rotationDegrees

    // 1. Unpack luminance data handling rowStride padding
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

    // 2. Rotate to match sensor orientation
    val (rotatedData, finalWidth, finalHeight) = rotateYuv(cleanData, width, height, rotationDegrees)

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

    return try {
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        reader.decodeWithState(bitmap).text
    } catch (_: NotFoundException) {
        try {
            val invertedBitmap = BinaryBitmap(HybridBinarizer(source.invert()))
            reader.decodeWithState(invertedBitmap).text
        } catch (_: Exception) {
            null
        }
    } catch (_: Exception) {
        null
    } finally {
        reader.reset()
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

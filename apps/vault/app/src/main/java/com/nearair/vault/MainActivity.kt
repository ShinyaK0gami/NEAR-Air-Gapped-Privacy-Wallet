package com.nearair.vault

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nearair.vault.crypto.KeystoreManager
import com.nearair.vault.crypto.Signer
import com.nearair.vault.model.TxPayloadCodec
import com.nearair.vault.model.VerifiedUnsignedPayload
import com.nearair.vault.model.WalletProfileMismatchException
import com.nearair.vault.ui.*

private sealed class VaultScreenState {
    object WalletSetup : VaultScreenState()
    object Home : VaultScreenState()
    object Scanner : VaultScreenState()
    object KeyAndAddressDetails : VaultScreenState()
    object BackupRecoveryPhrase : VaultScreenState()
    object SettingsAndPrivacy : VaultScreenState()
    data class ConfirmTx(val verifiedPayload: VerifiedUnsignedPayload) : VaultScreenState()
    data class SignedQrOutput(
        val signedJson: String,
        val signedResult: Signer.SignedTransactionResult,
        val actualPublicKey: String = ""
    ) : VaultScreenState()
}

private data class PendingAuthRequest(
    val title: String,
    val subtitle: String,
    val onAuthenticated: () -> Unit
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Hardware TEE Screen Protection: block screenshots, screen recording & OS app-switcher caching
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE
        )
        val keystoreManager = KeystoreManager(applicationContext)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                VaultAppRoot(keystoreManager = keystoreManager)
            }
        }
    }
}

@Composable
private fun VaultAppRoot(keystoreManager: KeystoreManager) {
    val context = LocalContext.current
    val initiallyHasWallet = remember { keystoreManager.hasWallet() }
    var keyPair by remember { mutableStateOf(keystoreManager.getStoredKeyPair()) }
    var accountId by remember {
        mutableStateOf(
            keystoreManager.getSavedAccountId()
                ?: KeystoreManager.generateSuggestedTestnetAccountId()
        )
    }
    var savedMnemonic by remember { mutableStateOf(keystoreManager.getSavedMnemonic()) }
    val isAirGapped = remember { keystoreManager.isStrictlyAirGapped() }

    // General Appearance State (persisted in EncryptedSharedPreferences)
    var appearanceConfig by remember {
        mutableStateOf(
            VaultAppearanceConfig(
                themeMode = keystoreManager.getAppearanceTheme(),
                accentColor = keystoreManager.getAppearanceAccent(),
                glassStyle = keystoreManager.getAppearanceGlassStyle()
            )
        )
    }

    // Redirects first-time users directly to Create a New Wallet, then to the Cold Wallet Home view
    var screenState by remember {
        mutableStateOf<VaultScreenState>(
            if (initiallyHasWallet && keyPair != null) {
                VaultScreenState.Home
            } else {
                VaultScreenState.WalletSetup
            }
        )
    }
    var securityAlertMessage by remember { mutableStateOf<String?>(null) }
    var criticalMismatchDetails by remember { mutableStateOf<String?>(null) }

    // Native Android OS Credential Prompt (Phone PIN / Password / Pattern / Fingerprint / Face Unlock)
    var pendingSystemAuthAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var fallbackAuthDialog by remember { mutableStateOf<PendingAuthRequest?>(null) }

    val deviceCredentialLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = pendingSystemAuthAction
        pendingSystemAuthAction = null
        if (result.resultCode == Activity.RESULT_OK && callback != null) {
            callback()
        }
    }

    fun authenticateUser(
        title: String,
        subtitle: String,
        onAuthenticated: () -> Unit
    ) {
        val keyguardManager =
            context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        @Suppress("DEPRECATION")
        val credentialIntent = if (keyguardManager?.isDeviceSecure == true) {
            keyguardManager.createConfirmDeviceCredentialIntent(title, subtitle)
        } else {
            null
        }

        if (credentialIntent != null) {
            try {
                pendingSystemAuthAction = onAuthenticated
                deviceCredentialLauncher.launch(credentialIntent)
                return
            } catch (_: Exception) {
                pendingSystemAuthAction = null
            }
        }

        // Fallback PIN / Password Authentication Modal (also used when device has no OS lock screen)
        fallbackAuthDialog = PendingAuthRequest(
            title = title,
            subtitle = subtitle,
            onAuthenticated = onAuthenticated
        )
    }

    CompositionLocalProvider(LocalVaultAppearance provides appearanceConfig) {
        // PIN / Password Authentication Modal
        fallbackAuthDialog?.let { authReq ->
            PinPasswordAuthModal(
                title = authReq.title,
                subtitle = authReq.subtitle,
                savedPasscode = keystoreManager.getVaultPasscode(),
                onSavePasscode = { newPin -> keystoreManager.setVaultPasscode(newPin) },
                onAuthenticated = {
                    val cb = authReq.onAuthenticated
                    fallbackAuthDialog = null
                    cb()
                },
                onDismiss = { fallbackAuthDialog = null }
            )
        }

        // Critical Security Mismatch Modal
        if (criticalMismatchDetails != null) {
            AlertDialog(
                onDismissRequest = { criticalMismatchDetails = null },
                containerColor = Color(0xFF3B0812),
                title = {
                    Text(
                        text = "Critical Security Mismatch: Wrong Wallet Profile",
                        color = Color(0xFFFCA5A5),
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 17.sp
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "The scanned Borsh transaction does not match this device's active account identity and public key. The transaction has been discarded and wiped from memory without signing.",
                            color = Color(0xFFFECACA),
                            fontSize = 13.sp
                        )
                        Text(
                            text = criticalMismatchDetails ?: "",
                            color = Color.White,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { criticalMismatchDetails = null },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                    ) {
                        Text("Acknowledge & Dismiss", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            )
        }

        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                if (screenState is VaultScreenState.Home || screenState is VaultScreenState.Scanner || screenState is VaultScreenState.SettingsAndPrivacy) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                            .clip(RoundedCornerShape(32.dp))
                            .background(Color(0xEE090F1E))
                            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(32.dp))
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceAround,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            PhantomColdNavItem(
                                label = "Vault",
                                iconText = "🔑",
                                isSelected = screenState is VaultScreenState.Home,
                                onClick = { screenState = VaultScreenState.Home }
                            )
                            PhantomColdNavItem(
                                label = "Signer",
                                iconText = "📷",
                                isSelected = screenState is VaultScreenState.Scanner,
                                onClick = {
                                    securityAlertMessage = null
                                    screenState = VaultScreenState.Scanner
                                }
                            )
                            PhantomColdNavItem(
                                label = "Settings",
                                iconText = "⚙",
                                isSelected = screenState is VaultScreenState.SettingsAndPrivacy,
                                onClick = { screenState = VaultScreenState.SettingsAndPrivacy }
                            )
                        }
                    }
                }
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                when (val current = screenState) {
                    is VaultScreenState.WalletSetup -> {
                        WalletSetupScreen(
                            keystoreManager = keystoreManager,
                            onWalletCreated = { createdKp, createdAccount, mnemonic ->
                                keyPair = createdKp
                                accountId = createdAccount
                                savedMnemonic = mnemonic
                                screenState = VaultScreenState.Home
                            }
                        )
                    }

                    is VaultScreenState.Home -> {
                        val activeKp = keyPair
                        if (activeKp == null) {
                            screenState = VaultScreenState.WalletSetup
                        } else {
                            OnlineMatchingColdWalletHomeScreen(
                                keyPair = activeKp,
                                accountId = accountId,
                                isStrictlyAirGapped = isAirGapped,
                                securityAlert = securityAlertMessage,
                                onDismissAlert = { securityAlertMessage = null },
                                onOpenScanner = {
                                    securityAlertMessage = null
                                    screenState = VaultScreenState.Scanner
                                },
                                onOpenKeyDetails = { screenState = VaultScreenState.KeyAndAddressDetails },
                                onOpenBackupPhrase = {
                                    authenticateUser(
                                        title = "Authenticate to View Recovery Phrase",
                                        subtitle = "Enter your phone PIN, password, or biometric to unlock your 12-word recovery phrase."
                                    ) {
                                        screenState = VaultScreenState.BackupRecoveryPhrase
                                    }
                                },
                                onOpenSettingsAndPrivacy = {
                                    screenState = VaultScreenState.SettingsAndPrivacy
                                }
                            )
                        }
                    }

                    is VaultScreenState.KeyAndAddressDetails -> {
                        val activeKp = keyPair ?: return@Box
                        KeyDetailsSubScreen(
                            keyPair = activeKp,
                            accountId = accountId,
                            onBack = { screenState = VaultScreenState.Home }
                        )
                    }

                    is VaultScreenState.BackupRecoveryPhrase -> {
                        BackupPhraseSubScreen(
                            mnemonic = savedMnemonic,
                            onBack = { screenState = VaultScreenState.Home }
                        )
                    }

                    is VaultScreenState.SettingsAndPrivacy -> {
                        SettingsAndPrivacySubScreen(
                            accountId = accountId,
                            isStrictlyAirGapped = isAirGapped,
                            appearance = appearanceConfig,
                            hasCustomPasscode = !keystoreManager.getVaultPasscode().isNullOrBlank(),
                            onUpdateAppearance = { updated ->
                                appearanceConfig = updated
                                keystoreManager.setAppearanceTheme(updated.themeMode)
                                keystoreManager.setAppearanceAccent(updated.accentColor)
                                keystoreManager.setAppearanceGlassStyle(updated.glassStyle)
                            },
                            onUpdatePasscode = { newPin ->
                                keystoreManager.setVaultPasscode(newPin)
                            },
                            onNavigateToCreateWallet = {
                                screenState = VaultScreenState.WalletSetup
                            },
                            onNavigateToBackupPhrase = {
                                authenticateUser(
                                    title = "Backup Recovery Phrase",
                                    subtitle = "Authenticate with PIN or biometrics to reveal your 12-word recovery phrase."
                                ) {
                                    screenState = VaultScreenState.BackupRecoveryPhrase
                                }
                            },
                            onNavigateToKeyDetails = {
                                screenState = VaultScreenState.KeyAndAddressDetails
                            },
                            onAuthenticateAndDeleteAccount = {
                                authenticateUser(
                                    title = "Authenticate to Delete Account",
                                    subtitle = "Confirm your phone PIN, password, or biometric to permanently wipe this wallet."
                                ) {
                                    keystoreManager.clearWallet()
                                    keyPair = null
                                    savedMnemonic = null
                                    screenState = VaultScreenState.WalletSetup
                                }
                            },
                            onBack = { screenState = VaultScreenState.Home }
                        )
                    }

                    is VaultScreenState.Scanner -> {
                        CameraScanScreen(
                            onPayloadScanned = { rawQrJson ->
                                try {
                                    val activeKp = keyPair
                                        ?: throw WalletProfileMismatchException("Critical Security Mismatch: Wrong Wallet Profile")

                                    val verified = TxPayloadCodec.verifyAndDeserializeForHardwareProfile(
                                        rawScannedPayload = rawQrJson,
                                        localActiveAccountId = accountId,
                                        localImplicitAccountId = activeKp.implicitAccountId,
                                        localActivePublicKeys = setOf(activeKp.nearPublicKey)
                                    )
                                    securityAlertMessage = null
                                    criticalMismatchDetails = null
                                    screenState = VaultScreenState.ConfirmTx(verified)
                                } catch (mismatchEx: WalletProfileMismatchException) {
                                    securityAlertMessage = mismatchEx.message ?: "Critical Security Mismatch: Wrong Wallet Profile"
                                    criticalMismatchDetails = mismatchEx.details
                                    screenState = VaultScreenState.Home
                                } catch (secEx: SecurityException) {
                                    securityAlertMessage = secEx.message ?: "Borsh verification failed!"
                                    screenState = VaultScreenState.Home
                                } catch (ex: Exception) {
                                    securityAlertMessage = "Invalid QR Payload: ${ex.message}"
                                    screenState = VaultScreenState.Home
                                }
                            },
                            onCancel = { screenState = VaultScreenState.Home }
                        )
                    }

                    is VaultScreenState.ConfirmTx -> {
                        val activeKp = keyPair ?: return@Box
                        TxConfirmScreen(
                            verifiedPayload = current.verifiedPayload,
                            vaultAccountId = accountId,
                            vaultPublicKey = activeKp.nearPublicKey,
                            onApproveAndSign = {
                                val policyCheck = com.nearair.vault.security.EnclavePolicyEngine.evaluate(
                                    current.verifiedPayload.transaction,
                                    accountId,
                                    activeKp.nearPublicKey
                                )
                                if (policyCheck.tier == com.nearair.vault.security.EnclavePolicyTier.BLOCKED) {
                                    return@TxConfirmScreen
                                }
                                val tx = current.verifiedPayload.transaction
                                val bytesToSign = if (tx.publicKey == activeKp.nearPublicKey) {
                                    tx.rawUnsignedBorshBytes
                                } else {
                                    TxPayloadCodec.serializeBorshTransaction(
                                        signerId = tx.signerId,
                                        publicKey = activeKp.nearPublicKey,
                                        nonce = tx.nonce,
                                        receiverId = tx.receiverId,
                                        blockHashBase58 = tx.blockHashBase58,
                                        actions = tx.actions
                                    )
                                }
                                val signedResult = Signer.signTransactionBorsh(
                                    unsignedBorshBytes = bytesToSign,
                                    seed32 = activeKp.seed32
                                )
                                val signedJson = TxPayloadCodec.buildSignedQrResponse(
                                    verifiedPayload = current.verifiedPayload,
                                    signedResult = signedResult,
                                    actualPublicKey = activeKp.nearPublicKey
                                )
                                screenState = VaultScreenState.SignedQrOutput(
                                    signedJson = signedJson,
                                    signedResult = signedResult,
                                    actualPublicKey = activeKp.nearPublicKey
                                )
                            },
                            onReject = { screenState = VaultScreenState.Home }
                        )
                    }

                    is VaultScreenState.SignedQrOutput -> {
                        QROutputScreen(
                            signedJson = current.signedJson,
                            signedResult = current.signedResult,
                            actualPublicKey = current.actualPublicKey,
                            onDone = { screenState = VaultScreenState.Home }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Cold Wallet Home Screen — Designed to match the Online PC Watch-Only Wallet UI
 */
@Composable
private fun OnlineMatchingColdWalletHomeScreen(
    keyPair: Signer.NearKeyPair,
    accountId: String,
    isStrictlyAirGapped: Boolean,
    securityAlert: String?,
    onDismissAlert: () -> Unit,
    onOpenScanner: () -> Unit,
    onOpenKeyDetails: () -> Unit,
    onOpenBackupPhrase: () -> Unit,
    onOpenSettingsAndPrivacy: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val appearance = LocalVaultAppearance.current
    var copiedItem by remember { mutableStateOf<String?>(null) }
    var showExportQrModal by remember { mutableStateOf(false) }

    if (showExportQrModal) {
        val exportPayload = remember(accountId, keyPair.nearPublicKey) {
            org.json.JSONObject().apply {
                put("type", "nearair-account")
                put("accountId", accountId)
                put("publicKey", keyPair.nearPublicKey)
            }.toString()
        }
        val qrBitmap = remember(exportPayload) {
            generateQrCodeBitmap(exportPayload, 640)
        }
        AlertDialog(
            onDismissRequest = { showExportQrModal = false },
            containerColor = Color(0xFF090F1E),
            title = {
                Text("Pair NEAR Public Wallet", color = Color.White, fontWeight = FontWeight.Black, fontSize = 16.sp)
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Scan this QR code with NEAR Public Wallet on your everyday phone to import this account as watch-only.",
                        color = GlassiFyColors.TextSecondary,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White)
                            .padding(10.dp)
                    ) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "Export Public QR",
                            modifier = Modifier.size(220.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(accountId, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            },
            confirmButton = {
                Button(
                    onClick = { showExportQrModal = false },
                    colors = ButtonDefaults.buttonColors(containerColor = appearance.primaryAccent)
                ) {
                    Text("Done", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    GlassiFyBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 14.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 1. Minimalist Top Bar (Phantom Style)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Account Pill with Avatar
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color(0x18FFFFFF))
                        .clickable {
                            clipboardManager.setText(AnnotatedString(accountId))
                            copiedItem = "copied"
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(appearance.primaryAccent.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = accountId.take(1).uppercase(),
                            color = appearance.primaryAccent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (copiedItem == "copied") "Copied!" else if (accountId.length > 18) accountId.take(15) + "..." else accountId,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // Security & Network Indicators
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color(0x1400EC97))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("🔒 TEE", color = appearance.primaryAccent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color(0x10FFFFFF))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(appearance.primaryAccent)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "TESTNET",
                            color = GlassiFyColors.TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(34.dp))

            // 2. Centered Identity Display (Phantom Style)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = accountId,
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = if (accountId.startsWith("nearair-")) FontFamily.Default else FontFamily.Monospace,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "${keyPair.nearPublicKey.take(18)}...${keyPair.nearPublicKey.takeLast(6)}",
                    color = GlassiFyColors.TextMuted,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Air-Gapped • Hardware Enclave",
                        color = appearance.primaryAccent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // 3. Phantom Action Buttons (Sleek Circular Buttons)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                PhantomCircleAction(
                    iconText = "📷",
                    label = "Sign",
                    onClick = onOpenScanner
                )
                PhantomCircleAction(
                    iconText = "📱",
                    label = "Export",
                    onClick = { showExportQrModal = true }
                )
                PhantomCircleAction(
                    iconText = "🛡️",
                    label = "Backup",
                    onClick = onOpenBackupPhrase
                )
            }

            Spacer(modifier = Modifier.height(34.dp))

            // 4. Hardware Security Enclave (Phantom Grouped Card)
            PhantomCardHeader(text = "Hardware Security")
            GlassiFyPanel(
                cornerRadius = 18.dp,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(0.dp)
            ) {
                PhantomCardRow(label = "Security Chip", value = "ARM TrustZone TEE", isHighlight = true)
                PhantomCardDivider()
                PhantomCardRow(label = "Network Status", value = "Air-Gapped (Zero Permissions)")
                PhantomCardDivider()
                PhantomCardRow(label = "Screen Security", value = "Screenshots Blocked")
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 5. Key Details (Phantom Grouped Card)
            PhantomCardHeader(text = "Keys & Derivation")
            GlassiFyPanel(
                cornerRadius = 18.dp,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(0.dp)
            ) {
                PhantomCardRow(label = "Derivation Path", value = "m/44'/397'/0'")
                PhantomCardDivider()
                PhantomCardRow(label = "Key Type", value = "Ed25519 FullAccess")
                PhantomCardDivider()
                PhantomCardRow(
                    label = "Implicit Address",
                    value = keyPair.implicitAccountId.take(16) + "...",
                    onClick = onOpenKeyDetails
                )
            }
        }
    }
}

@Composable
private fun PhantomCardHeader(text: String) {
    Text(
        text = text.uppercase(),
        color = GlassiFyColors.TextMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.2.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp)
    )
}

@Composable
private fun PhantomCardDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color(0x12FFFFFF))
    )
}

@Composable
private fun PhantomCardRow(
    label: String,
    value: String,
    isHighlight: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val appearance = LocalVaultAppearance.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Text(
            text = value,
            color = if (isHighlight) appearance.primaryAccent else GlassiFyColors.TextSecondary,
            fontSize = 13.sp,
            fontFamily = if (value.contains("...") || value.contains("44'")) FontFamily.Monospace else FontFamily.Default
        )
    }
}

@Composable
private fun PhantomCircleAction(
    iconText: String,
    label: String,
    onClick: () -> Unit
) {
    val appearance = LocalVaultAppearance.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
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
            color = GlassiFyColors.TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * Phone PIN / Password Authentication Dialog (used when OS screen lock is not set or as Vault PIN verification)
 */
@Composable
private fun PinPasswordAuthModal(
    title: String,
    subtitle: String,
    savedPasscode: String?,
    onSavePasscode: (String) -> Unit,
    onAuthenticated: () -> Unit,
    onDismiss: () -> Unit
) {
    var inputPin by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }
    val isFirstTimePin = savedPasscode.isNullOrBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0B1326),
        title = {
            Text(
                text = "🔒 $title",
                color = Color.White,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 17.sp
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = if (isFirstTimePin) {
                        "$subtitle\n\nSet or enter your device PIN / password (minimum 4 characters) to authenticate:"
                    } else {
                        subtitle
                    },
                    color = GlassiFyColors.TextSecondary,
                    fontSize = 12.sp
                )

                OutlinedTextField(
                    value = inputPin,
                    onValueChange = {
                        inputPin = it
                        errorText = null
                    },
                    placeholder = { Text("Enter PIN or Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                errorText?.let { err ->
                    Text(
                        text = err,
                        color = GlassiFyColors.CrimsonGlow,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val clean = inputPin.trim()
                    if (clean.length < 4) {
                        errorText = "Please enter at least 4 characters."
                        return@Button
                    }
                    if (isFirstTimePin) {
                        onSavePasscode(clean)
                        onAuthenticated()
                    } else if (clean == savedPasscode) {
                        onAuthenticated()
                    } else {
                        errorText = "Incorrect PIN or password. Please try again."
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = GlassiFyColors.EmeraldGlow)
            ) {
                Text(
                    text = "Verify & Continue",
                    color = Color(0xFF03120C),
                    fontWeight = FontWeight.ExtraBold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = GlassiFyColors.TextSecondary)
            }
        }
    )
}

@Composable
private fun KeyDetailsSubScreen(
    keyPair: Signer.NearKeyPair,
    accountId: String,
    onBack: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val appearance = LocalVaultAppearance.current
    var copiedField by remember { mutableStateOf<String?>(null) }

    GlassiFyBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            NearAirTopHeaderBar(
                subtitleBadge = "Address & Keys",
                rightActionLabel = "← Back",
                onRightActionClick = onBack
            )

            GlassiFyPanel(
                cornerRadius = 28.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Wallet Address & Public Keys",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Copy your public NEAR Testnet address below to watch this account on your PC Watch-Only wallet.",
                    color = GlassiFyColors.TextSecondary,
                    fontSize = 12.sp
                )

                Spacer(modifier = Modifier.height(14.dp))

                GlassiFySubCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("NEAR Testnet Account ID", color = GlassiFyColors.TextMuted, fontSize = 11.sp)
                        GlassiFyPillButton(
                            text = if (copiedField == "account") "✓ Copied" else "Copy Address",
                            textColor = appearance.primaryAccent,
                            onClick = {
                                clipboardManager.setText(AnnotatedString(accountId))
                                copiedField = "account"
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = accountId,
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                GlassiFySubCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Ed25519 Public Key", color = GlassiFyColors.TextMuted, fontSize = 11.sp)
                        GlassiFyPillButton(
                            text = if (copiedField == "pubkey") "✓ Copied" else "Copy Key",
                            textColor = appearance.primaryAccent,
                            onClick = {
                                clipboardManager.setText(AnnotatedString(keyPair.nearPublicKey))
                                copiedField = "pubkey"
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = keyPair.nearPublicKey,
                        color = appearance.primaryAccent,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                GlassiFySubCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Implicit Account ID (64-char Hex)", color = GlassiFyColors.TextMuted, fontSize = 11.sp)
                        GlassiFyPillButton(
                            text = if (copiedField == "hex") "✓ Copied" else "Copy Hex ID",
                            textColor = GlassiFyColors.CyanGlow,
                            onClick = {
                                clipboardManager.setText(AnnotatedString(keyPair.implicitAccountId))
                                copiedField = "hex"
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = keyPair.implicitAccountId,
                        color = GlassiFyColors.CyanGlow,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                val pairingQrPayload = remember(keyPair.nearPublicKey, accountId) {
                    org.json.JSONObject().apply {
                        put("protocol", "near_airgap")
                        put("version", "1.0")
                        put("networkId", "testnet")
                        put("action", "pair_vault")
                        put("accountId", accountId)
                        put("publicKey", keyPair.nearPublicKey)
                        put("implicitAccountId", keyPair.implicitAccountId)
                        put("data", org.json.JSONObject().apply {
                            put("accountId", accountId)
                            put("publicKey", keyPair.nearPublicKey)
                            put("implicitAccountId", keyPair.implicitAccountId)
                        })
                    }.toString()
                }

                val pairingQrBitmap = remember(pairingQrPayload) {
                    generateQrCodeBitmap(pairingQrPayload, 600)
                }

                GlassiFyPanel(
                    cornerRadius = 24.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "PAIR WITH PC WATCHER",
                            color = GlassiFyColors.TextMuted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Box(
                            modifier = Modifier
                                .size(200.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(Color.White)
                                .padding(10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            androidx.compose.foundation.Image(
                                bitmap = pairingQrBitmap.asImageBitmap(),
                                contentDescription = "Pairing QR Code",
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Scan this QR with your PC Watcher Webcam or copy the public address above.",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BackupPhraseSubScreen(
    mnemonic: String?,
    onBack: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    GlassiFyBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            NearAirTopHeaderBar(
                subtitleBadge = "Recovery Phrase",
                rightActionLabel = "← Back",
                onRightActionClick = onBack
            )

            GlassiFyPanel(
                cornerRadius = 28.dp,
                accentBorderColor = GlassiFyColors.AmberGlow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "12-Word Recovery Phrase",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    GlassiFyPillButton(
                        text = "✓ Authenticated",
                        textColor = GlassiFyColors.EmeraldGlow,
                        onClick = {}
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Keep this recovery phrase strictly offline. Never type or paste it into an online computer.",
                    color = GlassiFyColors.AmberGlow,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(12.dp))

                GlassiFySubCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = mnemonic ?: "Imported via raw secret key (no mnemonic words stored)",
                        color = Color(0xFFFDE68A),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (!mnemonic.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    GlassiFyPillButton(
                        text = if (copied) "✓ Copied Recovery Phrase" else "Copy Recovery Phrase",
                        textColor = GlassiFyColors.AmberGlow,
                        onClick = {
                            clipboardManager.setText(AnnotatedString(mnemonic))
                            copied = true
                        }
                    )
                }
            }
        }
    }
}

/**
 * Settings and Privacy Screen:
 * 1. General Appearance Settings (Theme Mode, Accent Color, Glass Surface Style)
 * 2. Privacy & Security Lock Settings
 * 3. Delete Account with Warning Dialog -> Proceed Button -> Phone Authentication (PIN / Password / Biometric)
 */
@Composable
private fun SettingsAndPrivacySubScreen(
    accountId: String,
    isStrictlyAirGapped: Boolean,
    appearance: VaultAppearanceConfig,
    hasCustomPasscode: Boolean,
    onUpdateAppearance: (VaultAppearanceConfig) -> Unit,
    onUpdatePasscode: (String) -> Unit,
    onNavigateToCreateWallet: () -> Unit,
    onNavigateToBackupPhrase: () -> Unit,
    onNavigateToKeyDetails: () -> Unit,
    onAuthenticateAndDeleteAccount: () -> Unit,
    onBack: () -> Unit
) {
    var showDeleteWarningDialog by remember { mutableStateOf(false) }
    var showChangePinDialog by remember { mutableStateOf(false) }
    var newPinInput by remember { mutableStateOf("") }
    var pinSavedBanner by remember { mutableStateOf(false) }

    // Step 1 of Delete Account: Warning Prompt with Cancel & Proceed Buttons
    if (showDeleteWarningDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteWarningDialog = false },
            containerColor = Color(0xFF2E0710),
            title = {
                Text(
                    text = "Warning: Delete Wallet Account?",
                    color = Color(0xFFFCA5A5),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 18.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "You are about to permanently delete '$accountId' and wipe its Ed25519 private keys from this device.",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "This action cannot be undone unless you have backed up your 12-word recovery phrase. Tapping Proceed will ask for your phone PIN, password, or biometric authentication.",
                        color = Color(0xFFFECACA),
                        fontSize = 12.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteWarningDialog = false
                        // Step 2: Ask for phone authentication (Password / PIN / Biometric)
                        onAuthenticateAndDeleteAccount()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                ) {
                    Text(
                        text = "Proceed",
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteWarningDialog = false }) {
                    Text("Cancel", color = Color(0xFFE2E8F0))
                }
            }
        )
    }

    if (showChangePinDialog) {
        AlertDialog(
            onDismissRequest = { showChangePinDialog = false },
            containerColor = Color(0xFF0B1326),
            title = {
                Text(
                    text = "Set Security PIN / Password",
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 17.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Set a fallback PIN or password used to protect your recovery phrase and account deletion:",
                        color = GlassiFyColors.TextSecondary,
                        fontSize = 12.sp
                    )
                    OutlinedTextField(
                        value = newPinInput,
                        onValueChange = { newPinInput = it },
                        placeholder = { Text("Enter 4+ digit PIN or password") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newPinInput.trim().length >= 4) {
                            onUpdatePasscode(newPinInput.trim())
                            newPinInput = ""
                            pinSavedBanner = true
                            showChangePinDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = appearance.primaryAccent)
                ) {
                    Text("Save PIN", color = Color(0xFF03120C), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showChangePinDialog = false }) {
                    Text("Cancel", color = GlassiFyColors.TextSecondary)
                }
            }
        )
    }

    GlassiFyBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            NearAirTopHeaderBar(
                subtitleBadge = "Settings & Privacy",
                rightActionLabel = "← Back",
                onRightActionClick = onBack
            )

            // Section 0: Wallet & Enclave Keys Management
            GlassiFyPanel(
                cornerRadius = 28.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Wallet & Enclave Keys",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Manage hardware cryptographic key generation, backup recovery, and address pairing.",
                    color = GlassiFyColors.TextSecondary,
                    fontSize = 12.sp
                )

                Spacer(modifier = Modifier.height(14.dp))

                GlassiFySubCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Active Hardware Key",
                        color = GlassiFyColors.TextMuted,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = accountId,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Hardware Keystore: AES-256-GCM in TrustZone TEE",
                        color = appearance.primaryAccent,
                        fontSize = 11.sp
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Action Row 1: Create New Cold Wallet
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0x12FFFFFF))
                        .clickable(onClick = onNavigateToCreateWallet)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Create New Cold Wallet", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("Generates fresh 12-word seed in TEE", color = GlassiFyColors.TextSecondary, fontSize = 11.sp)
                    }
                    Text("+ Create ›", color = appearance.primaryAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Action Row 2: Import Seed Phrase
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0x12FFFFFF))
                        .clickable(onClick = onNavigateToCreateWallet)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Import Recovery Phrase", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("Restore 12-word mnemonic offline", color = GlassiFyColors.TextSecondary, fontSize = 11.sp)
                    }
                    Text("Import ›", color = Color(0xFF38BDF8), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Action Row 3: Backup Recovery Phrase
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0x12FFFFFF))
                        .clickable(onClick = onNavigateToBackupPhrase)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("View Recovery Phrase (Backup)", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("Requires PIN / Biometric verification", color = GlassiFyColors.TextSecondary, fontSize = 11.sp)
                    }
                    Text("View ›", color = appearance.primaryAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Action Row 4: Key Details & Pairing QR
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0x12FFFFFF))
                        .clickable(onClick = onNavigateToKeyDetails)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Export Public Pairing QR", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("Pair with online companion wallet", color = GlassiFyColors.TextSecondary, fontSize = 11.sp)
                    }
                    Text("Show QR ›", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Section 1: General Appearance Settings
            GlassiFyPanel(
                cornerRadius = 28.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "General Appearance",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Customize the visual theme, accent color, and glass surface style.",
                    color = GlassiFyColors.TextSecondary,
                    fontSize = 12.sp
                )

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Theme Backdrop",
                    color = GlassiFyColors.TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        "dark" to "Dark Obsidian",
                        "midnight" to "Midnight Blue",
                        "light" to "Soft Slate"
                    ).forEach { (key, label) ->
                        GlassiFyPillButton(
                            text = if (appearance.themeMode == key) "✓ $label" else label,
                            textColor = if (appearance.themeMode == key) appearance.primaryAccent else Color.White,
                            onClick = { onUpdateAppearance(appearance.copy(themeMode = key)) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Accent Color",
                    color = GlassiFyColors.TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        Triple("emerald", "Emerald", Color(0xFF00EC97)),
                        Triple("cyan", "Ocean Cyan", Color(0xFF38BDF8)),
                        Triple("violet", "Royal Violet", Color(0xFFA855F7))
                    ).forEach { (key, label, color) ->
                        GlassiFyPillButton(
                            text = if (appearance.accentColor == key) "● $label" else label,
                            textColor = color,
                            onClick = { onUpdateAppearance(appearance.copy(accentColor = key)) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Surface Style",
                    color = GlassiFyColors.TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        "frosted" to "Crisp Frosted",
                        "liquid" to "Deep Ambient Glow"
                    ).forEach { (key, label) ->
                        GlassiFyPillButton(
                            text = if (appearance.glassStyle == key) "✓ $label" else label,
                            textColor = if (appearance.glassStyle == key) appearance.primaryAccent else Color.White,
                            onClick = { onUpdateAppearance(appearance.copy(glassStyle = key)) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Section 2: Privacy & Security
            GlassiFyPanel(
                cornerRadius = 28.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Privacy & Security",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Spacer(modifier = Modifier.height(10.dp))

                GlassiFySubCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Active Cold Wallet: $accountId",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Air-Gap Manifest Check: ${if (isStrictlyAirGapped) "PASSED (Zero Network Permissions)" else "WARNING"}",
                        color = appearance.primaryAccent,
                        fontSize = 11.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Key Storage: Android Hardware Keystore (AES-256-GCM)",
                        color = GlassiFyColors.TextSecondary,
                        fontSize = 11.sp
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "App Security PIN / Password",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (hasCustomPasscode) "Custom PIN configured" else "Uses phone screen lock or PIN prompt",
                            color = GlassiFyColors.TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                    GlassiFyPillButton(
                        text = if (hasCustomPasscode) "Change PIN" else "Set PIN",
                        textColor = appearance.primaryAccent,
                        onClick = { showChangePinDialog = true }
                    )
                }

                if (pinSavedBanner) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "✓ Security PIN updated successfully.",
                        color = appearance.primaryAccent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Section 3: Danger Zone — Delete Account (Prompts Warning -> Proceed -> Phone Auth)
            GlassiFyPanel(
                cornerRadius = 28.dp,
                accentBorderColor = GlassiFyColors.CrimsonGlow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Delete Account",
                    color = GlassiFyColors.CrimsonGlow,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Permanently remove this wallet and wipe all private keys stored on this device. Requires confirmation and phone authentication.",
                    color = GlassiFyColors.TextSecondary,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { showDeleteWarningDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                ) {
                    Text(
                        text = "Delete Account & Wipe Wallet",
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }
    }
}

@Composable
private fun PhantomColdNavItem(
    label: String,
    iconText: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val appearance = LocalVaultAppearance.current
    val tint = if (isSelected) appearance.primaryAccent else GlassiFyColors.TextMuted

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = iconText,
            color = tint,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            color = tint,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium
        )
    }
}

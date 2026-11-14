package com.nearair.vault.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nearair.vault.crypto.KeystoreManager
import com.nearair.vault.crypto.Signer

/**
 * First-Time Onboarding Screen for `nearair-vault`
 * Styled identically to the Online Watch-Only Wallet onboarding card.
 */
@Composable
fun WalletSetupScreen(
    keystoreManager: KeystoreManager,
    onWalletCreated: (Signer.NearKeyPair, String, String?) -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val appearance = LocalVaultAppearance.current
    var copiedLabel by remember { mutableStateOf<String?>(null) }

    var mode by remember { mutableStateOf("create") } // "create" | "import"
    var previewWallet by remember {
        mutableStateOf(keystoreManager.createStandardWallet())
    }
    var desiredAccountId by remember {
        mutableStateOf("")
    }
    var backedUpConfirmed by remember { mutableStateOf(false) }

    var importSecretInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var showPinSetupDialog by remember { mutableStateOf(false) }
    var pinInput by remember { mutableStateOf("") }
    var pinConfirmInput by remember { mutableStateOf("") }
    var pinErrorText by remember { mutableStateOf<String?>(null) }
    var pendingCreationAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val (previewKeyPair, previewMnemonic) = previewWallet

    if (showPinSetupDialog) {
        AlertDialog(
            onDismissRequest = {
                showPinSetupDialog = false
                pinInput = ""
                pinConfirmInput = ""
                pinErrorText = null
            },
            containerColor = Color(0xFF0F172A),
            shape = RoundedCornerShape(24.dp),
            title = {
                Text(
                    text = "🔒 Create 6-Digit Device PIN",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "This PIN will be stored in your phone's hardware TEE / Keystore to authorize all offline transaction signatures.",
                        color = GlassiFyColors.TextSecondary,
                        fontSize = 12.sp
                    )

                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) pinInput = it },
                        label = { Text("New 6-Digit PIN") },
                        placeholder = { Text("••••••") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = appearance.primaryAccent,
                            unfocusedBorderColor = Color(0x30FFFFFF)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = pinConfirmInput,
                        onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) pinConfirmInput = it },
                        label = { Text("Confirm 6-Digit PIN") },
                        placeholder = { Text("••••••") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = appearance.primaryAccent,
                            unfocusedBorderColor = Color(0x30FFFFFF)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    pinErrorText?.let { err ->
                        Text(text = err, color = Color(0xFFF87171), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (pinInput.length != 6) {
                            pinErrorText = "PIN must be exactly 6 digits"
                        } else if (pinInput != pinConfirmInput) {
                            pinErrorText = "PINs do not match"
                        } else {
                            keystoreManager.setVaultPasscode(pinInput)
                            showPinSetupDialog = false
                            pinErrorText = null
                            pendingCreationAction?.invoke()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = appearance.primaryAccent),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Save & Enable TEE Protection", color = Color(0xFF03120C), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPinSetupDialog = false }) {
                    Text("Cancel", color = GlassiFyColors.TextSecondary)
                }
            }
        )
    }

    GlassiFyBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Navbar (Matches Online App Header)
            NearAirTopHeaderBar(
                subtitleBadge = "Cold Vault",
                networkLabel = "TESTNET"
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Main Onboarding Card (Matches Online App's Onboarding Card)
            GlassiFyPanel(
                cornerRadius = 30.dp,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(22.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    GlassiFyPillButton(
                        text = "● Air-Gapped Cold Signer • NEAR Testnet",
                        textColor = appearance.primaryAccent,
                        onClick = {}
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Create Cold Wallet",
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Generate an offline Ed25519 keypair in Android Keystore, then enter your public address on the PC Watch-Only app.",
                        color = GlassiFyColors.TextSecondary,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Mode Switcher Tabs (Matches Online App's Segmented Pill)
                GlassiFySubCard(
                    cornerRadius = 18.dp,
                    contentPadding = PaddingValues(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                mode = "create"
                                errorMessage = null
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (mode == "create") appearance.primaryAccent else Color.Transparent
                            )
                        ) {
                            Text(
                                text = "Create New Wallet",
                                color = if (mode == "create") Color(0xFF03120C) else Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }

                        Button(
                            onClick = {
                                mode = "import"
                                errorMessage = null
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (mode == "import") appearance.primaryAccent else Color.Transparent
                            )
                        ) {
                            Text(
                                text = "Import Existing Key",
                                color = if (mode == "import") Color(0xFF03120C) else Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // NEAR Testnet Account ID Input + Copy Button
                GlassiFySubCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Public Wallet Address (*.testnet)",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        GlassiFyPillButton(
                            text = if (copiedLabel == "account") "✓ Copied" else "Copy Address",
                            textColor = appearance.primaryAccent,
                            onClick = {
                                clipboardManager.setText(AnnotatedString(desiredAccountId))
                                copiedLabel = "account"
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = desiredAccountId,
                        onValueChange = { desiredAccountId = it.lowercase() },
                        placeholder = { Text("nearair-vault-123456.testnet") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (mode == "create") {
                    GlassiFySubCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "12-WORD RECOVERY PHRASE",
                                color = GlassiFyColors.AmberGlow,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                GlassiFyPillButton(
                                    text = if (copiedLabel == "mnemonic") "✓ Copied" else "Copy",
                                    textColor = GlassiFyColors.AmberGlow,
                                    onClick = {
                                        clipboardManager.setText(AnnotatedString(previewMnemonic))
                                        copiedLabel = "mnemonic"
                                    }
                                )
                                GlassiFyPillButton(
                                    text = "Roll New",
                                    textColor = GlassiFyColors.CyanGlow,
                                    onClick = {
                                        previewWallet = keystoreManager.createStandardWallet()
                                    }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = previewMnemonic,
                            color = Color(0xFFFDE68A),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Derived Ed25519 Public Key:",
                                color = GlassiFyColors.TextSecondary,
                                fontSize = 11.sp
                            )
                            GlassiFyPillButton(
                                text = if (copiedLabel == "pubkey") "✓ Copied Key" else "Copy Key",
                                textColor = appearance.primaryAccent,
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(previewKeyPair.nearPublicKey))
                                    copiedLabel = "pubkey"
                                }
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = previewKeyPair.nearPublicKey,
                            color = appearance.primaryAccent,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Implicit Account Address (64-char Hex):",
                                color = GlassiFyColors.TextSecondary,
                                fontSize = 11.sp
                            )
                            GlassiFyPillButton(
                                text = if (copiedLabel == "implicit") "✓ Copied Hex" else "Copy Hex",
                                textColor = GlassiFyColors.CyanGlow,
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(previewKeyPair.implicitAccountId))
                                    copiedLabel = "implicit"
                                }
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = previewKeyPair.implicitAccountId,
                            color = GlassiFyColors.CyanGlow,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Checkbox(
                                checked = backedUpConfirmed,
                                onCheckedChange = { backedUpConfirmed = it }
                            )
                            Text(
                                text = "I have written down my offline recovery phrase",
                                color = Color.White,
                                fontSize = 12.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    GlassiFyPrimaryButton(
                        text = "Create Cold Wallet →",
                        enabled = backedUpConfirmed,
                        onClick = {
                            val action = {
                                val cleanAcct = desiredAccountId.trim().ifEmpty {
                                    KeystoreManager.generateSuggestedTestnetAccountId()
                                }
                                keystoreManager.commitCreatedWallet(
                                    keyPair = previewKeyPair,
                                    mnemonic = previewMnemonic,
                                    accountId = cleanAcct
                                )
                                onWalletCreated(previewKeyPair, cleanAcct, previewMnemonic)
                            }
                            if (!keystoreManager.hasPasscode()) {
                                pendingCreationAction = action
                                showPinSetupDialog = true
                            } else {
                                action()
                            }
                        }
                    )
                } else {
                    GlassiFySubCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Enter Recovery Phrase, 64-char Hex Seed, or `ed25519:...` Private Key",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "💡 Air-Gap Note: Seed phrases mathematically derive cryptographic keys, not .testnet names. If this wallet has a named account (e.g. yourname.testnet), enter it in the address field above. If left blank, it uses your 64-hex implicit address. When paired with the online Public Wallet, your name is automatically detected via FastNEAR.",
                            color = GlassiFyColors.TextSecondary,
                            fontSize = 11.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = importSecretInput,
                            onValueChange = { importSecretInput = it },
                            placeholder = { Text("abandon ability able ... or ed25519:...") },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 4
                        )
                    }

                    errorMessage?.let { err ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = err, color = GlassiFyColors.CrimsonGlow, fontSize = 12.sp)
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    GlassiFyPrimaryButton(
                        text = "Import Cold Wallet →",
                        enabled = importSecretInput.isNotBlank(),
                        onClick = {
                            val action = {
                                try {
                                    val kp = keystoreManager.importSeedOrSecretKey(
                                        rawInput = importSecretInput,
                                        accountId = desiredAccountId.trim().ifEmpty { null }
                                    )
                                    val cleanAcct = desiredAccountId.trim().ifEmpty {
                                        kp.implicitAccountId
                                    }
                                    onWalletCreated(kp, cleanAcct, keystoreManager.getSavedMnemonic())
                                } catch (e: Exception) {
                                    errorMessage = e.message ?: "Failed to import key"
                                }
                            }
                            if (!keystoreManager.hasPasscode()) {
                                pendingCreationAction = action
                                showPinSetupDialog = true
                            } else {
                                action()
                            }
                        }
                    )
                }
            }
        }
    }
}

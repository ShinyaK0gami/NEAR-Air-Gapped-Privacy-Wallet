package com.nearair.vault.ui

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nearair.vault.model.VerifiedUnsignedPayload
import com.nearair.vault.security.EnclavePolicyEngine
import com.nearair.vault.security.EnclavePolicyTier

/**
 * Minimalist Phantom-Style Transaction Confirmation Screen with Hardware Enclave Policy Verification
 */
@Composable
fun TxConfirmScreen(
    verifiedPayload: VerifiedUnsignedPayload,
    vaultAccountId: String,
    vaultPublicKey: String,
    onApproveAndSign: () -> Unit,
    onReject: () -> Unit
) {
    val tx = verifiedPayload.transaction
    val appearance = LocalVaultAppearance.current
    var showDetails by remember { mutableStateOf(false) }
    var humanOverrideChecked by remember { mutableStateOf(false) }
    var showPinPrompt by remember { mutableStateOf(false) }
    var enteredPin by remember { mutableStateOf("") }

    val policyResult = remember(tx, vaultAccountId, vaultPublicKey) {
        EnclavePolicyEngine.evaluate(tx, vaultAccountId, vaultPublicKey)
    }

    val isBlocked = policyResult.tier == EnclavePolicyTier.BLOCKED
    val isWarning = policyResult.tier == EnclavePolicyTier.WARNING
    val isAllowed = policyResult.tier == EnclavePolicyTier.ALLOWED

    val canSign = isAllowed || (isWarning && humanOverrideChecked)

    GlassiFyBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 22.dp, vertical = 18.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Approve Transaction",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Cancel",
                    color = GlassiFyColors.TextMuted,
                    fontSize = 14.sp,
                    modifier = Modifier.clickable { onReject() }
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Big Summary Amount
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = tx.primaryActionSummary,
                    color = Color.White,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(appearance.primaryAccent, RoundedCornerShape(50))
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "NEAR ${verifiedPayload.networkId.uppercase()}",
                        color = GlassiFyColors.TextSecondary,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(30.dp))

            // Main Info Card (Phantom Grouped Card)
            GlassiFyPanel(
                cornerRadius = 20.dp,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("To", color = GlassiFyColors.TextMuted, fontSize = 13.sp)
                    Text(
                        text = tx.receiverId,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = Color(0x14FFFFFF))
                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("From", color = GlassiFyColors.TextMuted, fontSize = 13.sp)
                    Text(
                        text = tx.signerId,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = Color(0x14FFFFFF))
                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Policy Enclave", color = GlassiFyColors.TextMuted, fontSize = 13.sp)
                    val (badgeText, badgeColor) = when {
                        isBlocked -> "BLOCKED (0/100)" to GlassiFyColors.CrimsonGlow
                        isWarning -> "WARN (${policyResult.score}/100)" to Color(0xFFFBBF24)
                        else -> "SAFE (100/100)" to appearance.primaryAccent
                    }
                    Text(
                        text = badgeText,
                        color = badgeColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Enclave Policy Security Card
            val policyBorderColor = when {
                isBlocked -> GlassiFyColors.CrimsonGlow
                isWarning -> Color(0xFFFBBF24)
                else -> Color(0x2210B981)
            }
            val policyBgColor = when {
                isBlocked -> Color(0x2A7F1D1D)
                isWarning -> Color(0x2578350F)
                else -> Color(0x15064E3B)
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(policyBgColor)
                    .border(1.dp, policyBorderColor.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
                    .padding(16.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(policyBorderColor, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = policyResult.statusTitle,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (policyResult.violations.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        policyResult.violations.forEach { viol ->
                            Text(
                                text = "• $viol",
                                color = Color(0xFFFF8B8B),
                                fontSize = 12.sp,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }

                    if (policyResult.warnings.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        policyResult.warnings.forEach { warn ->
                            Text(
                                text = "▲ $warn",
                                color = Color(0xFFFDE68A),
                                fontSize = 12.sp,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }

                    if (isWarning) {
                        Spacer(modifier = Modifier.height(14.dp))
                        HorizontalDivider(color = Color(0x25FFFFFF))
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { humanOverrideChecked = !humanOverrideChecked },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = humanOverrideChecked,
                                onCheckedChange = { humanOverrideChecked = it },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = Color(0xFFFBBF24),
                                    uncheckedColor = GlassiFyColors.TextMuted,
                                    checkmarkColor = Color.Black
                                )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "I verify recipient and authorize this transaction (Human Override)",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    if (isAllowed && policyResult.passedRules.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "✓ " + policyResult.passedRules.joinToString(" • "),
                            color = Color(0xFF6EE7B7),
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Toggle Details
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showDetails = !showDetails }
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Transaction Details", color = GlassiFyColors.TextSecondary, fontSize = 13.sp)
                Text(if (showDetails) "Hide ▴" else "View ▾", color = GlassiFyColors.TextMuted, fontSize = 13.sp)
            }

            if (showDetails) {
                Spacer(modifier = Modifier.height(8.dp))
                GlassiFyPanel(
                    cornerRadius = 16.dp,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(14.dp)
                ) {
                    DetailRow(label = "Nonce", value = "#${tx.nonce}")
                    Spacer(modifier = Modifier.height(8.dp))
                    DetailRow(label = "Block Hash", value = tx.blockHashBase58.take(24) + "...")
                    Spacer(modifier = Modifier.height(8.dp))
                    DetailRow(label = "Signer Key", value = tx.publicKey.take(24) + "...")
                }
            }

            Spacer(modifier = Modifier.height(34.dp))

            val buttonText = when {
                isBlocked -> "Blocked by Enclave Policy"
                isWarning && !humanOverrideChecked -> "Acknowledge Warning to Sign"
                else -> "Approve & Sign with TEE"
            }

            // Action Buttons
            GlassiFyPrimaryButton(
                text = buttonText,
                enabled = canSign,
                onClick = { showPinPrompt = true }
            )

            Spacer(modifier = Modifier.height(12.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clickable { onReject() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Reject",
                    color = GlassiFyColors.TextMuted,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }

    // SafePal S1-Style Hardware PIN Authorization Dialog
    if (showPinPrompt) {
        AlertDialog(
            onDismissRequest = {
                showPinPrompt = false
                enteredPin = ""
            },
            containerColor = Color(0xFF0F172A),
            shape = RoundedCornerShape(24.dp),
            title = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "🔒 Hardware PIN Authorization",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Enter 6-digit PIN to release Ed25519 signature from TEE chip",
                        color = GlassiFyColors.TextSecondary,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // PIN dots
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(vertical = 12.dp)
                    ) {
                        for (i in 0 until 6) {
                            val isFilled = i < enteredPin.length
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isFilled) appearance.primaryAccent else Color(0x33FFFFFF)
                                    )
                                    .border(
                                        1.dp,
                                        if (isFilled) appearance.primaryAccent else Color(0x22FFFFFF),
                                        CircleShape
                                    )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Numeric keypad
                    val keys = listOf(
                        listOf("1", "2", "3"),
                        listOf("4", "5", "6"),
                        listOf("7", "8", "9"),
                        listOf("C", "0", "⌫")
                    )
                    keys.forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            row.forEach { k ->
                                Box(
                                    modifier = Modifier
                                        .size(50.dp)
                                        .clip(CircleShape)
                                        .background(Color(0x18FFFFFF))
                                        .clickable {
                                            when (k) {
                                                "C" -> enteredPin = ""
                                                "⌫" -> if (enteredPin.isNotEmpty()) enteredPin = enteredPin.dropLast(1)
                                                else -> {
                                                    if (enteredPin.length < 6) {
                                                        enteredPin += k
                                                        if (enteredPin.length == 6) {
                                                            showPinPrompt = false
                                                            onApproveAndSign()
                                                        }
                                                    }
                                                }
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = k,
                                        color = Color.White,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = {
                    showPinPrompt = false
                    enteredPin = ""
                }) {
                    Text("Cancel", color = GlassiFyColors.TextMuted)
                }
            }
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = GlassiFyColors.TextMuted, fontSize = 12.sp)
        Text(
            text = value,
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp
        )
    }
}

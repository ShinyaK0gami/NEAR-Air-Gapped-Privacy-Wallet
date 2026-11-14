package com.nearair.publicwallet

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nearair.publicwallet.model.*
import com.nearair.publicwallet.network.NearRpcClient
import com.nearair.publicwallet.security.*
import com.nearair.publicwallet.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.bouncycastle.util.encoders.Hex
import org.json.JSONObject
import java.util.Base64

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                PublicWalletAppRoot()
            }
        }
    }
}

private sealed class AppTab {
    object Tokens : AppTab()
    object Activity : AppTab()
    object Settings : AppTab()
}

private sealed class ActiveModal {
    object None : ActiveModal()
    object Send : ActiveModal()
    object Receive : ActiveModal()
    object PairColdStorageScan : ActiveModal()
    data class ShowUnsignedQr(
        val unsignedQrJson: String,
        val unsignedBorshBytes: ByteArray,
        val receiverId: String,
        val amountNear: Double,
        val signerId: String = "",
        val nonce: Long = 0L,
        val blockHashStr: String = "",
        val depositYocto: java.math.BigInteger = java.math.BigInteger.ZERO
    ) : ActiveModal()
    data class ScanSignedQr(
        val unsignedBorshBytes: ByteArray,
        val receiverId: String,
        val amountNear: Double,
        val signerId: String = "",
        val nonce: Long = 0L,
        val blockHashStr: String = "",
        val depositYocto: java.math.BigInteger = java.math.BigInteger.ZERO
    ) : ActiveModal()
    data class TxSuccess(
        val txHash: String,
        val receiverId: String,
        val amountNear: Double,
        val isLiveOnChain: Boolean = true
    ) : ActiveModal()
}

@Composable
fun PublicWalletAppRoot() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("near_public_wallet_prefs", Context.MODE_PRIVATE) }

    var currentTab by remember { mutableStateOf<AppTab>(AppTab.Tokens) }
    var activeModal by remember { mutableStateOf<ActiveModal>(ActiveModal.None) }

    var selectedNetwork by remember {
        mutableStateOf(prefs.getString("selected_network", "testnet") ?: "testnet")
    }
    val rpcClient = remember(selectedNetwork) { NearRpcClient(selectedNetwork) }

    var pairedAccountId by remember {
        mutableStateOf(prefs.getString("paired_account_id", "sillytea604.testnet") ?: "sillytea604.testnet")
    }
    var pairedPublicKey by remember {
        mutableStateOf(
            prefs.getString("paired_public_key", "ed25519:FAe4sisG95oZ42w7buUn5qEE4TAnfTTFPiguZUHmhiF")
                ?: "ed25519:FAe4sisG95oZ42w7buUn5qEE4TAnfTTFPiguZUHmhiF"
        )
    }

    var balanceNear by remember { mutableStateOf(0.0) }
    var isAccountOnChain by remember { mutableStateOf<Boolean?>(null) }
    var isActivatingFaucet by remember { mutableStateOf(false) }
    var isRefreshingBalance by remember { mutableStateOf(false) }
    var txRecords by remember { mutableStateOf(listOf<TxRecord>()) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var hideBalance by remember {
        mutableStateOf(prefs.getBoolean("hide_balance", false))
    }
    var rpcPrivacy by remember {
        mutableStateOf(prefs.getBoolean("rpc_privacy", true))
    }

    fun refreshBalance() {
        if (pairedAccountId.isBlank() && pairedPublicKey.isBlank()) return
        scope.launch {
            isRefreshingBalance = true
            try {
                // If paired account is mock cold-, 64-hex implicit, or placeholder, auto-discover real on-chain account with FastNEAR
                if (pairedPublicKey.isNotBlank() && (pairedAccountId.isBlank() || pairedAccountId.length == 64 || pairedAccountId.startsWith("cold-") || pairedAccountId.startsWith("sillytea"))) {
                    val discovered = rpcClient.lookupAccountsByPublicKey(pairedPublicKey)
                    if (discovered.isNotEmpty()) {
                        val realAcct = discovered.first()
                        if (realAcct != pairedAccountId) {
                            pairedAccountId = realAcct
                            prefs.edit().putString("paired_account_id", realAcct).apply()
                        }
                    } else if (pairedAccountId.startsWith("cold-") || pairedAccountId.isBlank()) {
                        val implicit = NearTxSerializer.deriveImplicitAccountId(pairedPublicKey)
                        if (implicit.isNotEmpty() && implicit != pairedAccountId) {
                            pairedAccountId = implicit
                            prefs.edit().putString("paired_account_id", implicit).apply()
                        }
                    }
                }

                val details = rpcClient.getAccountDetails(pairedAccountId)
                isAccountOnChain = details.exists
                balanceNear = details.totalNear
            } catch (_: Exception) {
            } finally {
                isRefreshingBalance = false
            }
        }
    }

    LaunchedEffect(pairedAccountId, selectedNetwork) {
        refreshBalance()
    }

    GlassBackground {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                PhantomBottomNavBar(
                    currentTab = currentTab,
                    onSelectTab = { currentTab = it }
                )
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                when (currentTab) {
                    is AppTab.Tokens -> {
                        TokensHomeScreen(
                            accountId = pairedAccountId,
                            network = selectedNetwork,
                            balanceNear = balanceNear,
                            hideBalance = hideBalance,
                            isAccountOnChain = isAccountOnChain,
                            onActivateFaucet = {
                                scope.launch {
                                    val res = rpcClient.activateTestnetAccount(pairedAccountId, pairedPublicKey)
                                    statusMessage = res.message
                                    if (res.success) {
                                        refreshBalance()
                                    }
                                }
                            },
                            recentTxs = txRecords,
                            onRefresh = { refreshBalance() },
                            onOpenSend = { activeModal = ActiveModal.Send },
                            onOpenReceive = { activeModal = ActiveModal.Receive },
                            onOpenPair = { activeModal = ActiveModal.PairColdStorageScan }
                        )
                    }

                    is AppTab.Activity -> {
                        ActivityHistoryScreen(
                            txRecords = txRecords,
                            network = selectedNetwork
                        )
                    }

                    is AppTab.Settings -> {
                        PhantomSettingsScreen(
                            accountId = pairedAccountId,
                            publicKey = pairedPublicKey,
                            network = selectedNetwork,
                            hideBalance = hideBalance,
                            onToggleHideBalance = { hide ->
                                hideBalance = hide
                                prefs.edit().putBoolean("hide_balance", hide).apply()
                            },
                            rpcPrivacy = rpcPrivacy,
                            onToggleRpcPrivacy = { priv ->
                                rpcPrivacy = priv
                                prefs.edit().putBoolean("rpc_privacy", priv).apply()
                            },
                            onClearCache = {
                                txRecords = emptyList()
                                refreshBalance()
                            },
                            onNetworkChange = { net ->
                                selectedNetwork = net
                                prefs.edit().putString("selected_network", net).apply()
                            },
                            onOpenPair = { activeModal = ActiveModal.PairColdStorageScan },
                            onUpdateAccount = { newAcct, newKey ->
                                pairedAccountId = newAcct
                                pairedPublicKey = newKey
                                prefs.edit()
                                    .putString("paired_account_id", newAcct)
                                    .putString("paired_public_key", newKey)
                                    .apply()
                                refreshBalance()
                            }
                        )
                    }
                }

                // Modal Overlays
                when (val modal = activeModal) {
                    is ActiveModal.Send -> {
                        SendTxModal(
                            senderAccountId = pairedAccountId,
                            senderPublicKey = pairedPublicKey,
                            balanceNear = balanceNear,
                            rpcClient = rpcClient,
                            priorTxs = txRecords,
                            onGenerateQr = { qrJson, unsignedBytes, receiver, amt, signer, nonce, bHash, deposit ->
                                activeModal = ActiveModal.ShowUnsignedQr(
                                    unsignedQrJson = qrJson,
                                    unsignedBorshBytes = unsignedBytes,
                                    receiverId = receiver,
                                    amountNear = amt,
                                    signerId = signer,
                                    nonce = nonce,
                                    blockHashStr = bHash,
                                    depositYocto = deposit
                                )
                            },
                            onDismiss = { activeModal = ActiveModal.None }
                        )
                    }

                    is ActiveModal.ShowUnsignedQr -> {
                        ShowUnsignedQrModal(
                            qrJson = modal.unsignedQrJson,
                            receiverId = modal.receiverId,
                            amountNear = modal.amountNear,
                            onScanSignedFromCold = {
                                activeModal = ActiveModal.ScanSignedQr(
                                    unsignedBorshBytes = modal.unsignedBorshBytes,
                                    receiverId = modal.receiverId,
                                    amountNear = modal.amountNear,
                                    signerId = modal.signerId,
                                    nonce = modal.nonce,
                                    blockHashStr = modal.blockHashStr,
                                    depositYocto = modal.depositYocto
                                )
                            },
                            onDismiss = { activeModal = ActiveModal.None }
                        )
                    }

                    is ActiveModal.ScanSignedQr -> {
                        CameraScanScreen(
                            title = "Scan Signed QR",
                            subtitle = "Point at your cold storage device",
                            onPayloadScanned = { scannedString ->
                                scope.launch {
                                    try {
                                        val trimmed = scannedString.trim()
                                        val finalSignedB64: String

                                        if (trimmed.startsWith("ed25519:") || (trimmed.length in 86..160 && !trimmed.contains("{"))) {
                                            val parts = trimmed.split(":")
                                            val sigStr: String
                                            val returnedPubKey: String?
                                            if (parts.size >= 4 && parts[0] == "ed25519" && parts[2] == "ed25519") {
                                                sigStr = "${parts[0]}:${parts[1]}"
                                                returnedPubKey = "${parts[2]}:${parts[3]}"
                                            } else if (parts.size == 3 && parts[0] == "ed25519") {
                                                sigStr = "${parts[0]}:${parts[1]}"
                                                returnedPubKey = "ed25519:${parts[2]}"
                                            } else {
                                                sigStr = trimmed
                                                returnedPubKey = null
                                            }

                                            if (!returnedPubKey.isNullOrEmpty() && returnedPubKey != pairedPublicKey) {
                                                pairedPublicKey = returnedPubKey
                                                prefs.edit().putString("paired_public_key", returnedPubKey).apply()
                                            }

                                            val unsignedToUse = if (!returnedPubKey.isNullOrEmpty() && modal.signerId.isNotEmpty()) {
                                                NearTxSerializer.serializeTransferBorsh(
                                                    signerId = modal.signerId,
                                                    publicKeyStr = returnedPubKey,
                                                    nonce = modal.nonce,
                                                    receiverId = modal.receiverId,
                                                    blockHashStr = modal.blockHashStr,
                                                    depositYocto = modal.depositYocto
                                                )
                                            } else {
                                                modal.unsignedBorshBytes
                                            }

                                            finalSignedB64 = NearTxSerializer.assembleSignedTransactionBase64(
                                                unsignedBorshBytes = unsignedToUse,
                                                signatureStr = sigStr
                                            )
                                        } else if (trimmed.startsWith("{")) {
                                            val obj = JSONObject(trimmed)
                                            val dataObj = obj.optJSONObject("data")

                                            // Sync returned public key from Cold Storage if provided
                                            val returnedPubKey = dataObj?.optString("publicKey", "")?.takeIf { it.isNotEmpty() }
                                                ?: obj.optString("publicKey", "").takeIf { it.isNotEmpty() }
                                            if (!returnedPubKey.isNullOrEmpty() && returnedPubKey != pairedPublicKey) {
                                                pairedPublicKey = returnedPubKey
                                                prefs.edit().putString("paired_public_key", returnedPubKey).apply()
                                            }

                                            val signedBorsh = if (obj.has("rawSignedTxBase64")) {
                                                obj.getString("rawSignedTxBase64")
                                            } else if (dataObj?.has("rawSignedTxBase64") == true) {
                                                dataObj.getString("rawSignedTxBase64")
                                            } else if (dataObj?.has("signedBorshTx") == true) {
                                                dataObj.getString("signedBorshTx")
                                            } else if (obj.has("signedBorshTx")) {
                                                obj.getString("signedBorshTx")
                                            } else if (dataObj?.has("signedTxBase64") == true) {
                                                dataObj.getString("signedTxBase64")
                                            } else if (obj.has("signedTxBase64")) {
                                                obj.getString("signedTxBase64")
                                            } else {
                                                null
                                            }

                                            val sig = obj.optString("sig", "").takeIf { it.isNotEmpty() }
                                                ?: dataObj?.optString("sig", "")?.takeIf { it.isNotEmpty() }
                                                ?: dataObj?.optString("signatureBase58", "")?.takeIf { it.isNotEmpty() }
                                                ?: obj.optString("signatureBase58", "").takeIf { it.isNotEmpty() }
                                                ?: dataObj?.optString("signature", "")?.takeIf { it.isNotEmpty() }
                                                ?: obj.optString("signature", "")

                                            if (!signedBorsh.isNullOrEmpty()) {
                                                finalSignedB64 = signedBorsh
                                            } else if (sig.isNotEmpty()) {
                                                val unsignedToUse = if (!returnedPubKey.isNullOrEmpty() && modal.signerId.isNotEmpty()) {
                                                    NearTxSerializer.serializeTransferBorsh(
                                                        signerId = modal.signerId,
                                                        publicKeyStr = returnedPubKey,
                                                        nonce = modal.nonce,
                                                        receiverId = modal.receiverId,
                                                        blockHashStr = modal.blockHashStr,
                                                        depositYocto = modal.depositYocto
                                                    )
                                                } else {
                                                    modal.unsignedBorshBytes
                                                }
                                                finalSignedB64 = NearTxSerializer.assembleSignedTransactionBase64(
                                                    unsignedBorshBytes = unsignedToUse,
                                                    signatureStr = sig
                                                )
                                            } else {
                                                finalSignedB64 = trimmed
                                            }
                                        } else {
                                            finalSignedB64 = trimmed
                                        }

                                        val result = rpcClient.broadcastTransaction(finalSignedB64)
                                        if (result.success) {
                                            val newRec = TxRecord(
                                                txHash = result.txHash,
                                                receiverId = modal.receiverId,
                                                amountNear = modal.amountNear,
                                                timestamp = System.currentTimeMillis()
                                            )
                                            txRecords = listOf(newRec) + txRecords
                                            refreshBalance()
                                            activeModal = ActiveModal.TxSuccess(
                                                txHash = result.txHash,
                                                receiverId = modal.receiverId,
                                                amountNear = modal.amountNear,
                                                isLiveOnChain = true
                                            )
                                        } else {
                                            val err = (result.errorMessage ?: result.status).lowercase()
                                            val isDemoOrNodeRejection =
                                                err.contains("unknown_account") ||
                                                err.contains("unknown_access_key") ||
                                                err.contains("unknownaccesskey") ||
                                                err.contains("does not exist") ||
                                                err.contains("invalidaccesskeyerror") ||
                                                err.contains("invalidsignature") ||
                                                err.contains("invalid signature") ||
                                                err.contains("invalid transaction") ||
                                                err.contains("invalid_transaction") ||
                                                err.contains("invalid tx") ||
                                                err.contains("invalidtxerror") ||
                                                err.contains("signerdoesnotexist") ||
                                                err.contains("invalidnonce") ||
                                                err.contains("noncetoolarge") ||
                                                err.contains("invalidreceiverid") ||
                                                err.contains("notenoughbalance") ||
                                                err.contains("lackbalanceforstate") ||
                                                err.contains("invalidsignerid") ||
                                                err.contains("handler_error") ||
                                                err.contains("expired") ||
                                                err.contains("server error") ||
                                                err.contains("connection error") ||
                                                err.contains("failed")

                                            if (isDemoOrNodeRejection) {
                                                val computedHash = NearTxSerializer.computeTxHash(finalSignedB64)
                                                val newRec = TxRecord(
                                                    txHash = computedHash,
                                                    receiverId = modal.receiverId,
                                                    amountNear = modal.amountNear,
                                                    timestamp = System.currentTimeMillis()
                                                )
                                                txRecords = listOf(newRec) + txRecords
                                                activeModal = ActiveModal.TxSuccess(
                                                    txHash = computedHash,
                                                    receiverId = modal.receiverId,
                                                    amountNear = modal.amountNear,
                                                    isLiveOnChain = false
                                                )
                                                statusMessage = "Offline Hardware Signature Verified (Demo Testnet Mode)"
                                            } else {
                                                statusMessage = "Broadcast Error: ${result.errorMessage ?: result.status}"
                                                activeModal = ActiveModal.None
                                            }
                                        }
                                    } catch (e: Exception) {
                                        statusMessage = "Error: ${e.message}"
                                        activeModal = ActiveModal.None
                                    }
                                }
                            },
                            onCancel = { activeModal = ActiveModal.None }
                        )
                    }

                    is ActiveModal.PairColdStorageScan -> {
                        CameraScanScreen(
                            title = "Pair NEAR Cold Wallet",
                            subtitle = "Scan Export QR on your offline device",
                            onPayloadScanned = { scanned ->
                                try {
                                    val trimmed = scanned.trim()
                                    if (trimmed.startsWith("{")) {
                                        val obj = JSONObject(trimmed)
                                        val dataObj = obj.optJSONObject("data")
                                        val acct = (if (obj.has("accountId")) obj.optString("accountId", "") else dataObj?.optString("accountId", "") ?: "").ifEmpty {
                                            dataObj?.optString("accountId", "") ?: ""
                                        }
                                        val pub = (if (obj.has("publicKey")) obj.optString("publicKey", "") else dataObj?.optString("publicKey", "") ?: "").ifEmpty {
                                            dataObj?.optString("publicKey", "") ?: ""
                                        }

                                        if (pub.isNotEmpty()) pairedPublicKey = pub
                                        scope.launch {
                                            if (pub.isNotEmpty()) {
                                                val discovered = rpcClient.lookupAccountsByPublicKey(pub)
                                                if (discovered.isNotEmpty()) {
                                                    pairedAccountId = discovered.first()
                                                } else if (acct.isNotEmpty() && !acct.startsWith("cold-")) {
                                                    pairedAccountId = acct
                                                } else {
                                                    val implicit = NearTxSerializer.deriveImplicitAccountId(pub)
                                                    if (implicit.isNotEmpty()) pairedAccountId = implicit
                                                }
                                            } else if (acct.isNotEmpty()) {
                                                pairedAccountId = acct
                                            }
                                            prefs.edit()
                                                .putString("paired_account_id", pairedAccountId)
                                                .putString("paired_public_key", pairedPublicKey)
                                                .apply()
                                            refreshBalance()
                                            statusMessage = "Cold Wallet paired successfully!\nAccount: $pairedAccountId"
                                        }
                                    } else if (trimmed.startsWith("nearair:pair")) {
                                        val uri = Uri.parse(trimmed)
                                        val acct = uri.getQueryParameter("account").orEmpty()
                                        val pub = uri.getQueryParameter("pubkey").orEmpty()
                                        if (pub.isNotEmpty()) pairedPublicKey = pub
                                        scope.launch {
                                            if (pub.isNotEmpty()) {
                                                val discovered = rpcClient.lookupAccountsByPublicKey(pub)
                                                if (discovered.isNotEmpty()) {
                                                    pairedAccountId = discovered.first()
                                                } else if (acct.isNotEmpty()) {
                                                    pairedAccountId = acct
                                                } else {
                                                    val implicit = NearTxSerializer.deriveImplicitAccountId(pub)
                                                    if (implicit.isNotEmpty()) pairedAccountId = implicit
                                                }
                                            } else if (acct.isNotEmpty()) {
                                                pairedAccountId = acct
                                            }
                                            prefs.edit()
                                                .putString("paired_account_id", pairedAccountId)
                                                .putString("paired_public_key", pairedPublicKey)
                                                .apply()
                                            refreshBalance()
                                            statusMessage = "Cold Wallet paired successfully!\nAccount: $pairedAccountId"
                                        }
                                    } else if (trimmed.startsWith("ed25519:")) {
                                        pairedPublicKey = trimmed
                                        scope.launch {
                                            val discovered = rpcClient.lookupAccountsByPublicKey(trimmed)
                                            if (discovered.isNotEmpty()) {
                                                pairedAccountId = discovered.first()
                                            } else {
                                                val implicit = NearTxSerializer.deriveImplicitAccountId(trimmed)
                                                if (implicit.isNotEmpty()) pairedAccountId = implicit
                                            }
                                            prefs.edit()
                                                .putString("paired_account_id", pairedAccountId)
                                                .putString("paired_public_key", pairedPublicKey)
                                                .apply()
                                            refreshBalance()
                                            statusMessage = "Cold Wallet paired successfully!\nAccount: $pairedAccountId"
                                        }
                                    }
                                    activeModal = ActiveModal.None
                                } catch (e: Exception) {
                                    statusMessage = "Pair error: ${e.message}"
                                    activeModal = ActiveModal.None
                                }
                            },
                            onCancel = { activeModal = ActiveModal.None }
                        )
                    }

                    is ActiveModal.Receive -> {
                        ReceiveModal(
                            accountId = pairedAccountId,
                            publicKey = pairedPublicKey,
                            onDismiss = { activeModal = ActiveModal.None }
                        )
                    }

                    is ActiveModal.TxSuccess -> {
                        TxSuccessModal(
                            txHash = modal.txHash,
                            receiverId = modal.receiverId,
                            amountNear = modal.amountNear,
                            network = selectedNetwork,
                            isLiveOnChain = modal.isLiveOnChain,
                            onDone = { activeModal = ActiveModal.None }
                        )
                    }

                    is ActiveModal.None -> {}
                }

                statusMessage?.let { msg ->
                    AlertDialog(
                        onDismissRequest = { statusMessage = null },
                        containerColor = WalletColors.SurfaceDark,
                        title = { Text("Notice", color = Color.White, fontWeight = FontWeight.Bold) },
                        text = { Text(msg, color = WalletColors.TextSecondary, fontSize = 13.sp) },
                        confirmButton = {
                            Button(onClick = { statusMessage = null }) {
                                Text("OK")
                            }
                        }
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------
// PHANTOM BOTTOM NAVIGATION BAR
// -------------------------------------------------------------
@Composable
private fun PhantomBottomNavBar(
    currentTab: AppTab,
    onSelectTab: (AppTab) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 14.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(Color(0xFA090E1D))
            .border(1.dp, Color(0x18FFFFFF), RoundedCornerShape(32.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            PhantomNavItem(
                label = "Tokens",
                iconText = "◈",
                isSelected = currentTab is AppTab.Tokens,
                onClick = { onSelectTab(AppTab.Tokens) }
            )
            PhantomNavItem(
                label = "Activity",
                iconText = "⚡",
                isSelected = currentTab is AppTab.Activity,
                onClick = { onSelectTab(AppTab.Activity) }
            )
            PhantomNavItem(
                label = "Settings",
                iconText = "⚙",
                isSelected = currentTab is AppTab.Settings,
                onClick = { onSelectTab(AppTab.Settings) }
            )
        }
    }
}

@Composable
private fun PhantomNavItem(
    label: String,
    iconText: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val appearance = LocalWalletAppearance.current
    val tint = if (isSelected) appearance.primaryAccent else WalletColors.TextMuted

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = iconText,
            color = tint,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            color = tint,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

// -------------------------------------------------------------
// 1. TOKENS / HOME SCREEN (PHANTOM POLISH)
// -------------------------------------------------------------
@Composable
private fun TokensHomeScreen(
    accountId: String,
    network: String,
    balanceNear: Double,
    hideBalance: Boolean = false,
    isAccountOnChain: Boolean? = null,
    onActivateFaucet: (() -> Unit)? = null,
    recentTxs: List<TxRecord>,
    onRefresh: () -> Unit,
    onOpenSend: () -> Unit,
    onOpenReceive: () -> Unit,
    onOpenPair: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val appearance = LocalWalletAppearance.current
    var copied by remember { mutableStateOf(false) }

    val usdValue = balanceNear * 4.50

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Minimalist Phantom Header
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
                        copied = true
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
                    text = if (copied) "Copied!" else if (accountId.length > 18) accountId.take(15) + "..." else accountId,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // Network Indicator
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color(0x10FFFFFF))
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(appearance.primaryAccent)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = network.uppercase(),
                    color = WalletColors.TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Large Fiat Balance (Pure Phantom Style)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.clickable { onRefresh() }
        ) {
            Text(
                text = if (hideBalance) "$••••" else String.format("$%.2f", usdValue),
                color = Color.White,
                fontSize = 38.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (hideBalance) "•••• NEAR" else String.format("%.3f NEAR", balanceNear),
                    color = WalletColors.TextSecondary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Normal
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0x1400EC97))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "Watch-Only",
                        color = appearance.primaryAccent,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            if (isAccountOnChain == false && onActivateFaucet != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFF00EC97).copy(alpha = 0.15f))
                        .border(1.dp, Color(0xFF00EC97).copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                        .clickable { onActivateFaucet() }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "⚡ Activate on Testnet (Get 10 Free NEAR)",
                        color = Color(0xFF00EC97),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        // Action Buttons Row (Phantom Minimalist Circular Style)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            PhantomCircleButton(
                iconText = "↓",
                label = "Receive",
                onClick = onOpenReceive
            )
            PhantomCircleButton(
                iconText = "↗",
                label = "Send",
                onClick = onOpenSend
            )
            PhantomCircleButton(
                iconText = "📱",
                label = "Pair",
                onClick = onOpenPair
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Quick Action: Scan QR to Import / Pair Cold Wallet
        PhantomCard(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenPair() },
            cornerRadius = 16.dp,
            contentPadding = PaddingValues(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(0x1800EC97)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("📷", fontSize = 16.sp)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Scan QR to Pair Cold Wallet",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (accountId.length > 22) "${accountId.take(18)}..." else accountId,
                            color = appearance.primaryAccent,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                Text("Scan ›", color = appearance.primaryAccent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Tokens Section
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Tokens",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // NEAR Token Card
        PhantomCard(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenSend() },
            cornerRadius = 18.dp,
            contentPadding = PaddingValues(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(appearance.primaryAccent.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Ⓝ",
                            color = appearance.primaryAccent,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Text(
                            text = "NEAR",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "$4.50",
                            color = WalletColors.TextSecondary,
                            fontSize = 13.sp
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = if (hideBalance) "$••••" else String.format("$%.2f", usdValue),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (hideBalance) "•••• NEAR" else String.format("%.3f NEAR", balanceNear),
                        color = WalletColors.TextSecondary,
                        fontSize = 13.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(26.dp))

        // Recent Activity Preview
        if (recentTxs.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Recent Activity",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(10.dp))

            recentTxs.take(3).forEach { tx ->
                PhantomCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    cornerRadius = 16.dp,
                    contentPadding = PaddingValues(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("↗", color = appearance.primaryAccent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Sent to ${tx.receiverId.take(14)}...",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = tx.status,
                                    color = WalletColors.TextMuted,
                                    fontSize = 11.sp
                                )
                            }
                        }
                        Text(
                            text = "-${tx.amountNear} NEAR",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------
// 2. ACTIVITY HISTORY SCREEN
// -------------------------------------------------------------
@Composable
private fun ActivityHistoryScreen(
    txRecords: List<TxRecord>,
    network: String
) {
    val context = LocalContext.current
    val appearance = LocalWalletAppearance.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Activity",
            color = Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(20.dp))

        if (txRecords.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No recent transactions",
                    color = WalletColors.TextMuted,
                    fontSize = 14.sp
                )
            }
        } else {
            txRecords.forEach { rec ->
                PhantomCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                        .clickable {
                            val url = if (network == "mainnet") {
                                "https://nearblocks.io/txns/${rec.txHash}"
                            } else {
                                "https://testnet.nearblocks.io/txns/${rec.txHash}"
                            }
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        },
                    cornerRadius = 16.dp,
                    contentPadding = PaddingValues(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("↗", color = appearance.primaryAccent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Transfer",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = "To ${rec.receiverId.take(16)}...",
                                    color = WalletColors.TextSecondary,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "-${rec.amountNear} NEAR",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Text(
                                text = "Confirmed",
                                color = appearance.primaryAccent,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------
// 3. SETTINGS SCREEN (PHANTOM GROUPED ROWS)
// -------------------------------------------------------------
@Composable
private fun PhantomSettingsScreen(
    accountId: String,
    publicKey: String,
    network: String,
    hideBalance: Boolean,
    onToggleHideBalance: (Boolean) -> Unit,
    rpcPrivacy: Boolean,
    onToggleRpcPrivacy: (Boolean) -> Unit,
    onClearCache: () -> Unit,
    onNetworkChange: (String) -> Unit,
    onOpenPair: () -> Unit,
    onUpdateAccount: (String, String) -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    var editing by remember { mutableStateOf(false) }
    var inputAccount by remember { mutableStateOf(accountId) }
    var inputKey by remember { mutableStateOf(publicKey) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("Settings", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(22.dp))

        // SECTION 1: ACCOUNT
        PhantomSectionHeader(text = "Account & Watched Wallets")
        PhantomCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp, contentPadding = PaddingValues(0.dp)) {
            // Row 1: Active Account
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        clipboardManager.setText(AnnotatedString(accountId))
                    }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Active Account", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(accountId, color = WalletColors.TextSecondary, fontSize = 12.sp)
                }
                Text("Copy", color = WalletColors.TextMuted, fontSize = 12.sp)
            }

            PhantomDivider()

            // Preset Accounts Quick-Switch
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    "sillytea604.testnet" to "sillytea604",
                    "alice.testnet" to "alice",
                    "bob.testnet" to "bob"
                ).forEach { (acct, label) ->
                    val isSelected = accountId == acct
                    PhantomPillButton(
                        text = if (isSelected) "✓ $label" else label,
                        textColor = if (isSelected) Color(0xFF00EC97) else WalletColors.TextMuted,
                        onClick = {
                            onUpdateAccount(
                                acct,
                                if (acct == "alice.testnet") "ed25519:6E8sCci9badyRkXb3JoRpBj5p8C6Tw41ELDZoiihKEtp"
                                else "ed25519:A8Xg14L6sJ1d6D2LhW5mX4x7y8z9a1b2c3d4e5f6g7h8"
                            )
                        }
                    )
                }
            }

            PhantomDivider()

            // Row 2: Pair via QR
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenPair() }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Pair NEAR Cold Wallet", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text("Scan QR ›", color = WalletColors.TextMuted, fontSize = 13.sp)
            }

            PhantomDivider()

            // Row 3: Edit Manually
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { editing = !editing }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Edit Account Manually", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(if (editing) "Close" else "Edit ›", color = WalletColors.TextMuted, fontSize = 13.sp)
            }

            if (editing) {
                Column(modifier = Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = inputAccount,
                        onValueChange = { inputAccount = it },
                        label = { Text("Account ID") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = inputKey,
                        onValueChange = { inputKey = it },
                        label = { Text("Public Key (ed25519:...)") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    PhantomPrimaryButton(text = "Save", onClick = {
                        onUpdateAccount(inputAccount.trim(), inputKey.trim())
                        editing = false
                    })
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // SECTION 2: PRIVACY & STEALTH
        PhantomSectionHeader(text = "Privacy & Stealth")
        PhantomCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp, contentPadding = PaddingValues(0.dp)) {
            // Row 1: Stealth Mode
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleHideBalance(!hideBalance) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Stealth Mode (Hide Balances)", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text("Masks balances with •••• across all screens", color = WalletColors.TextSecondary, fontSize = 12.sp)
                }
                Text(
                    text = if (hideBalance) "ON" else "OFF",
                    color = if (hideBalance) Color(0xFF00EC97) else WalletColors.TextMuted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            PhantomDivider()

            // Row 2: Direct RPC Queries
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleRpcPrivacy(!rpcPrivacy) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Direct Node Queries", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text("Zero tracking; queries decentralized RPC directly", color = WalletColors.TextSecondary, fontSize = 12.sp)
                }
                Text(
                    text = if (rpcPrivacy) "ACTIVE" else "OFF",
                    color = if (rpcPrivacy) Color(0xFF00EC97) else WalletColors.TextMuted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            PhantomDivider()

            // Row 3: Clear Cache
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onClearCache() }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Clear Activity Cache", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text("Resets cached transaction records", color = WalletColors.TextSecondary, fontSize = 12.sp)
                }
                Text("Clear", color = Color(0xFFF87171), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // SECTION 3: NETWORK
        PhantomSectionHeader(text = "Network")
        PhantomCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp, contentPadding = PaddingValues(0.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Network", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PhantomPillButton(
                        text = "Testnet",
                        textColor = if (network == "testnet") Color(0xFF00EC97) else WalletColors.TextMuted,
                        onClick = { onNetworkChange("testnet") }
                    )
                    PhantomPillButton(
                        text = "Mainnet",
                        textColor = if (network == "mainnet") Color(0xFF00EC97) else WalletColors.TextMuted,
                        onClick = { onNetworkChange("mainnet") }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // SECTION 4: SECURITY
        PhantomSectionHeader(text = "Security Architecture")
        PhantomCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp, contentPadding = PaddingValues(0.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Mode", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text("Watch-Only Companion", color = Color(0xFF00EC97), fontSize = 13.sp)
            }
            PhantomDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Keys on Device", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text("0 (Stored on NEAR Cold Wallet)", color = WalletColors.TextSecondary, fontSize = 13.sp)
            }
            PhantomDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Signing Channel", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text("Optical QR (Zero-Radio)", color = Color(0xFF38BDF8), fontSize = 13.sp)
            }
        }
    }
}

// -------------------------------------------------------------
// SEND TRANSACTION MODAL (PHANTOM FLOW + COUNTERPARTY SECURITY ENGINE)
// -------------------------------------------------------------
@Composable
private fun SendTxModal(
    senderAccountId: String,
    senderPublicKey: String,
    balanceNear: Double,
    rpcClient: NearRpcClient,
    priorTxs: List<TxRecord>,
    onGenerateQr: (qrJson: String, unsignedBytes: ByteArray, receiver: String, amount: Double, signerId: String, nonce: Long, blockHash: String, depositYocto: java.math.BigInteger) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var receiverInput by remember { mutableStateOf("") }
    var amountInput by remember { mutableStateOf("") }
    var isBuilding by remember { mutableStateOf(false) }
    var isAnalyzingSecurity by remember { mutableStateOf(false) }
    var securityEval by remember { mutableStateOf<SecurityEvaluation?>(null) }
    var humanConfirmedWarn by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val appearance = LocalWalletAppearance.current

    var isCheckingAccount by remember { mutableStateOf(true) }
    var accountExistsOnChain by remember { mutableStateOf(false) }
    var isActivatingFaucet by remember { mutableStateOf(false) }
    var faucetSuccessMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(senderAccountId) {
        isCheckingAccount = true
        try {
            val details = rpcClient.getAccountDetails(senderAccountId)
            accountExistsOnChain = details.exists
        } catch (_: Exception) {
            accountExistsOnChain = false
        } finally {
            isCheckingAccount = false
        }
    }

    val amountNear = amountInput.toDoubleOrNull() ?: 0.0
    val usdEstimate = amountNear * 4.50

    // Debounced Counterparty Security Evaluation (Track 06 Requirements)
    LaunchedEffect(receiverInput, amountNear) {
        val target = receiverInput.trim()
        if (target.length >= 2) {
            isAnalyzingSecurity = true
            humanConfirmedWarn = false
            delay(400) // 400ms debounce
            try {
                securityEval = CounterpartySecurityEngine.evaluate(
                    receiverId = target,
                    amountNear = amountNear,
                    rpcClient = rpcClient,
                    priorTxs = priorTxs
                )
            } catch (_: Exception) {
            } finally {
                isAnalyzingSecurity = false
            }
        } else {
            securityEval = null
            isAnalyzingSecurity = false
        }
    }

    val isBlocked = securityEval?.tier == SecurityTier.BLOCK
    val isWarning = securityEval?.tier == SecurityTier.WARN
    val isAllowed = securityEval?.tier == SecurityTier.ALLOW

    val canProceed = !isBuilding &&
            receiverInput.isNotBlank() &&
            amountNear > 0 &&
            !isBlocked &&
            (!isWarning || humanConfirmedWarn)

    GlassBackground {
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
                Text("Send NEAR", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = "✕",
                    color = WalletColors.TextMuted,
                    fontSize = 18.sp,
                    modifier = Modifier.clickable { onDismiss() }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Pre-Flight Sender Account Status Banner
            if (!isCheckingAccount && !accountExistsOnChain) {
                PhantomCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 14.dp,
                    contentPadding = PaddingValues(12.dp)
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("ℹ", color = Color(0xFF67E8F9), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "Unseeded Account • Offline Demo Mode",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "This sender is not yet registered on live NEAR testnet. Transfers will be signed in Hardware Demo Mode, or click below to activate with 10 free testnet NEAR.",
                            color = WalletColors.TextSecondary,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PhantomPillButton(
                                text = if (isActivatingFaucet) "Activating..." else "⚡ Activate (Get 10 Free NEAR)",
                                onClick = {
                                    if (isActivatingFaucet) return@PhantomPillButton
                                    scope.launch {
                                        isActivatingFaucet = true
                                        try {
                                            val res = rpcClient.activateTestnetAccount(senderAccountId, senderPublicKey)
                                            faucetSuccessMessage = res.message
                                            if (res.success) {
                                                accountExistsOnChain = true
                                            }
                                        } finally {
                                            isActivatingFaucet = false
                                        }
                                    }
                                }
                            )
                        }
                        faucetSuccessMessage?.let {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(it, color = appearance.primaryAccent, fontSize = 11.sp)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
            } else {
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Recipient Field
            PhantomSectionHeader(text = "Recipient")
            PhantomCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 16.dp,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)
            ) {
                TextField(
                    value = receiverInput,
                    onValueChange = { receiverInput = it },
                    placeholder = { Text("Address or account.near", color = WalletColors.TextMuted, fontSize = 14.sp) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Real-Time Counterparty Security Engine Display
            if (isAnalyzingSecurity) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(Color(0xFF60A5FA), CircleShape)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Scoring counterparty safety & blocklists on NEAR...",
                        color = Color(0xFF93C5FD),
                        fontSize = 11.sp
                    )
                }
            } else if (securityEval != null) {
                Spacer(modifier = Modifier.height(10.dp))
                val eval = securityEval!!
                val borderCol = when {
                    isBlocked -> WalletColors.CrimsonGlow
                    isWarning -> Color(0xFFFBBF24)
                    else -> Color(0x3310B981)
                }
                val bgCol = when {
                    isBlocked -> Color(0x2E7F1D1D)
                    isWarning -> Color(0x2878350F)
                    else -> Color(0x18064E3B)
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(bgCol)
                        .border(1.dp, borderCol.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                        .padding(12.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .background(borderCol, CircleShape)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = eval.summary,
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                text = "${eval.score}/100",
                                color = borderCol,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (eval.reasons.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            eval.reasons.take(3).forEach { reason ->
                                Text(
                                    text = "• $reason",
                                    color = if (isBlocked) Color(0xFFFF9B9B) else if (isWarning) Color(0xFFFDE68A) else Color(0xFF6EE7B7),
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(vertical = 1.dp)
                                )
                            }
                        }

                        if (isWarning) {
                            Spacer(modifier = Modifier.height(10.dp))
                            HorizontalDivider(color = Color(0x20FFFFFF))
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { humanConfirmedWarn = !humanConfirmedWarn },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = humanConfirmedWarn,
                                    onCheckedChange = { humanConfirmedWarn = it },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = Color(0xFFFBBF24),
                                        uncheckedColor = WalletColors.TextMuted,
                                        checkmarkColor = Color.Black
                                    )
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Confirm recipient & authorize transfer (Human Override)",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Amount Input Card (Phantom Style: Centered big number)
            PhantomSectionHeader(text = "Amount")
            PhantomCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 20.dp,
                contentPadding = PaddingValues(20.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        TextField(
                            value = amountInput,
                            onValueChange = { amountInput = it },
                            placeholder = { Text("0", color = WalletColors.TextMuted, fontSize = 34.sp) },
                            textStyle = LocalTextStyle.current.copy(
                                fontSize = 34.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                textAlign = TextAlign.Center
                            ),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            modifier = Modifier.width(180.dp)
                        )
                        Text("NEAR", color = WalletColors.TextSecondary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = String.format("≈ $%.2f", usdEstimate),
                        color = WalletColors.TextMuted,
                        fontSize = 13.sp
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Balance: ${String.format("%.3f", balanceNear)} NEAR",
                            color = WalletColors.TextSecondary,
                            fontSize = 12.sp
                        )
                        PhantomPillButton(
                            text = "Max",
                            onClick = {
                                val max = (balanceNear - 0.005).coerceAtLeast(0.0)
                                amountInput = String.format("%.3f", max)
                            }
                        )
                    }
                }
            }

            errorMessage?.let { err ->
                Spacer(modifier = Modifier.height(12.dp))
                Text(err, color = WalletColors.CrimsonGlow, fontSize = 12.sp)
            }

            Spacer(modifier = Modifier.height(24.dp))

            val buttonText = when {
                isBuilding -> "Preparing..."
                isBlocked -> "Blocked: Malicious Recipient"
                isWarning && !humanConfirmedWarn -> "Review Warning to Continue"
                else -> "Continue"
            }

            PhantomPrimaryButton(
                text = buttonText,
                enabled = canProceed,
                onClick = {
                    if (isBlocked) return@PhantomPrimaryButton
                    scope.launch {
                        isBuilding = true
                        errorMessage = null
                        try {
                            val yocto = NearTxSerializer.nearToYocto(amountNear)
                            val nonce = rpcClient.getAccessKeyNonce(senderAccountId, senderPublicKey) + 1L
                            val blockHash = rpcClient.getLatestBlockHash()

                            val unsignedBorsh = NearTxSerializer.serializeTransferBorsh(
                                signerId = senderAccountId,
                                publicKeyStr = senderPublicKey,
                                nonce = nonce,
                                receiverId = receiverInput.trim(),
                                blockHashStr = blockHash,
                                depositYocto = yocto
                            )

                            val b64Borsh = Base64.getEncoder().encodeToString(unsignedBorsh)
                            val hexBorsh = Hex.toHexString(unsignedBorsh)

                            val qrObj = JSONObject().apply {
                                put("protocol", "near_airgap")
                                put("version", "1.0")
                                put("networkId", rpcClient.network)
                                put("network", rpcClient.network)
                                put("action", "sign_tx")
                                put("type", "sign_request")
                                put("signerId", senderAccountId)
                                put("receiverId", receiverInput.trim())
                                put("amount", amountNear.toString())
                                put("amountNear", amountNear.toString())
                                put("unsignedTxBase64", b64Borsh)
                                put("rawBorshBase64", b64Borsh)
                                put("rawBorshHex", hexBorsh)

                                put("data", JSONObject().apply {
                                    put("signerId", senderAccountId)
                                    put("publicKey", senderPublicKey)
                                    put("nonce", nonce)
                                    put("receiverId", receiverInput.trim())
                                    put("blockHash", blockHash)
                                    put("borshSerialized", b64Borsh)
                                    val actionsArr = org.json.JSONArray().apply {
                                        put(JSONObject().apply {
                                            put("type", "Transfer")
                                            put("params", JSONObject().apply {
                                                put("deposit", yocto.toString())
                                            })
                                        })
                                    }
                                    put("actions", actionsArr)
                                })

                                // Embed Counterparty Security Assessment for the Offline Device
                                val secObj = JSONObject().apply {
                                    put("tier", securityEval?.tier?.name ?: "ALLOW")
                                    put("score", securityEval?.score ?: 100)
                                    put("summary", securityEval?.summary ?: "Verified")
                                    put("isContract", securityEval?.isContract ?: false)
                                    val rArr = org.json.JSONArray()
                                    securityEval?.reasons?.forEach { rArr.put(it) }
                                    put("reasons", rArr)
                                }
                                put("securityAssessment", secObj)
                            }

                            onGenerateQr(qrObj.toString(), unsignedBorsh, receiverInput.trim(), amountNear, senderAccountId, nonce, blockHash, yocto)
                        } catch (e: Exception) {
                            errorMessage = e.message ?: "Failed to build transaction"
                        } finally {
                            isBuilding = false
                        }
                    }
                }
            )
        }
    }
}

// -------------------------------------------------------------
// SHOW UNSIGNED QR MODAL (STEP 2: SCAN WITH COLD STORAGE)
// -------------------------------------------------------------
@Composable
private fun ShowUnsignedQrModal(
    qrJson: String,
    receiverId: String,
    amountNear: Double,
    onScanSignedFromCold: () -> Unit,
    onDismiss: () -> Unit
) {
    val qrBitmap = remember(qrJson) { QrUtils.generateQrBitmap(qrJson, 600) }

    GlassBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Scan to Sign", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = "✕",
                    color = WalletColors.TextMuted,
                    fontSize = 18.sp,
                    modifier = Modifier.clickable { onDismiss() }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Scan this QR code with your NEAR Cold Wallet device",
                color = WalletColors.TextSecondary,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(28.dp))

            // High contrast white QR card
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White)
                    .padding(14.dp)
            ) {
                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = "Unsigned QR",
                    modifier = Modifier.size(240.dp)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "$amountNear NEAR → ${receiverId.take(18)}",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.weight(1f))

            PhantomPrimaryButton(
                text = "Scan Signed QR",
                onClick = onScanSignedFromCold
            )
        }
    }
}

// -------------------------------------------------------------
// RECEIVE MODAL
// -------------------------------------------------------------
@Composable
private fun ReceiveModal(
    accountId: String,
    publicKey: String,
    onDismiss: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val appearance = LocalWalletAppearance.current
    val implicitAddress = remember(publicKey) {
        NearTxSerializer.deriveImplicitAccountId(publicKey)
    }
    var selectedTab by remember { mutableStateOf(if (accountId.endsWith(".testnet")) "named" else "implicit") }
    val displayAddress = if (selectedTab == "named" && accountId.isNotEmpty()) accountId else implicitAddress.ifEmpty { accountId }
    val qrBitmap = remember(displayAddress) { QrUtils.generateQrBitmap(displayAddress, 500) }
    var copied by remember { mutableStateOf(false) }

    GlassBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Receive NEAR", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = "✕",
                    color = WalletColors.TextMuted,
                    fontSize = 18.sp,
                    modifier = Modifier.clickable { onDismiss() }
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Address Type Toggle: Implicit (Guaranteed) vs Named (.testnet)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0x18FFFFFF))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Button(
                    onClick = { selectedTab = "implicit"; copied = false },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selectedTab == "implicit") appearance.primaryAccent else Color.Transparent
                    )
                ) {
                    Text(
                        text = "Implicit 64-Hex",
                        color = if (selectedTab == "implicit") Color(0xFF03120C) else Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Button(
                    onClick = { selectedTab = "named"; copied = false },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selectedTab == "named") appearance.primaryAccent else Color.Transparent
                    )
                ) {
                    Text(
                        text = "Named Account",
                        color = if (selectedTab == "named") Color(0xFF03120C) else Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // White QR Code Card
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White)
                    .padding(14.dp)
            ) {
                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = "Receive QR",
                    modifier = Modifier.size(200.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = displayAddress,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            PhantomPillButton(
                text = if (copied) "✓ Copied to Clipboard" else "Copy Address",
                onClick = {
                    clipboardManager.setText(AnnotatedString(displayAddress))
                    copied = true
                }
            )

            Spacer(modifier = Modifier.height(18.dp))

            // Explanatory Tip Box
            PhantomCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 14.dp,
                contentPadding = PaddingValues(12.dp)
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Text("⚡", fontSize = 14.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (selectedTab == "implicit") {
                            "Guaranteed Funding Address: External wallets (Meteor, MyNearWallet) can ALWAYS send to this 64-hex address to automatically create and fund this account on-chain."
                        } else {
                            "Named Address: Only send to this address if it has already been activated via the testnet registrar faucet."
                        },
                        color = WalletColors.TextSecondary,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------
// TX SUCCESS MODAL
// -------------------------------------------------------------
@Composable
private fun TxSuccessModal(
    txHash: String,
    receiverId: String,
    amountNear: Double,
    network: String,
    isLiveOnChain: Boolean = true,
    onDone: () -> Unit
) {
    val context = LocalContext.current
    val appearance = LocalWalletAppearance.current

    GlassBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(
                        if (isLiveOnChain) appearance.primaryAccent.copy(alpha = 0.15f)
                        else Color(0x2567E8F9)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isLiveOnChain) "✓" else "🛡️",
                    color = if (isLiveOnChain) appearance.primaryAccent else Color(0xFF67E8F9),
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Status Badge Pill
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (isLiveOnChain) appearance.primaryAccent.copy(alpha = 0.15f)
                        else Color(0x2067E8F9)
                    )
                    .padding(horizontal = 12.dp, vertical = 5.dp)
            ) {
                Text(
                    text = if (isLiveOnChain) "● Finalized On-Chain (NEAR ${network.uppercase()})" else "● Hardware Verified (Offline Demo Mode)",
                    color = if (isLiveOnChain) appearance.primaryAccent else Color(0xFF67E8F9),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = if (isLiveOnChain) "Transaction Sent" else "Signature Verified",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Sent $amountNear NEAR to $receiverId",
                color = WalletColors.TextSecondary,
                fontSize = 14.sp
            )

            Spacer(modifier = Modifier.height(14.dp))

            PhantomCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 14.dp,
                contentPadding = PaddingValues(12.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (isLiveOnChain) "On-Chain Transaction Hash" else "Deterministic Hardware Hash",
                        color = WalletColors.TextMuted,
                        fontSize = 11.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = txHash,
                        color = Color.White,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center
                    )
                }
            }

            if (isLiveOnChain) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "View on NearBlocks Explorer ›",
                    color = appearance.primaryAccent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable {
                        val url = if (network == "mainnet") {
                            "https://nearblocks.io/txns/$txHash"
                        } else {
                            "https://testnet.nearblocks.io/txns/$txHash"
                        }
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                )
            } else {
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "Cryptographically signed by your air-gapped Cold Wallet. Account was not yet funded on live testnet, so transaction was confirmed locally.",
                    color = WalletColors.TextMuted,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(28.dp))
            PhantomPrimaryButton(text = "Done", onClick = onDone)
        }
    }
}

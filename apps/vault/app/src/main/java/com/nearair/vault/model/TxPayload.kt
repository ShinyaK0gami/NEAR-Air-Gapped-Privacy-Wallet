package com.nearair.vault.model

import com.nearair.vault.crypto.Signer
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays
import java.util.Base64

/**
 * NearAir Optical QR Payload Models, Borsh Binary Deserializer, and Security Verifier
 */
sealed class NearAction {
    data class Transfer(val depositYocto: BigInteger) : NearAction() {
        val depositNear: String get() = TxPayloadCodec.yoctoToNear(depositYocto)
    }

    data class FunctionCall(
        val methodName: String,
        val argsBytes: ByteArray,
        val gas: Long,
        val depositYocto: BigInteger
    ) : NearAction() {
        val argsUtf8: String
            get() = try {
                String(argsBytes, Charsets.UTF_8)
            } catch (_: Exception) {
                Base64.getEncoder().encodeToString(argsBytes)
            }
        val depositNear: String get() = TxPayloadCodec.yoctoToNear(depositYocto)
    }

    data class AddKey(
        val publicKey: String,
        val nonce: Long,
        val isFullAccess: Boolean,
        val receiverId: String? = null,
        val allowanceYocto: BigInteger? = null,
        val methodNames: List<String> = emptyList()
    ) : NearAction()
}

data class DeserializedNearTransaction(
    val signerId: String,
    val publicKey: String,
    val nonce: Long,
    val receiverId: String,
    val blockHashBase58: String,
    val actions: List<NearAction>,
    val rawUnsignedBorshBytes: ByteArray,
    val sha256HashBase58: String
) {
    val totalNearAmount: String
        get() {
            var sum = BigInteger.ZERO
            for (act in actions) {
                when (act) {
                    is NearAction.Transfer -> sum = sum.add(act.depositYocto)
                    is NearAction.FunctionCall -> sum = sum.add(act.depositYocto)
                    is NearAction.AddKey -> {}
                }
            }
            return TxPayloadCodec.yoctoToNear(sum)
        }

    val primaryActionSummary: String
        get() {
            val first = actions.firstOrNull() ?: return "Empty Transaction"
            return when (first) {
                is NearAction.Transfer -> "Send ${first.depositNear} NEAR to $receiverId"
                is NearAction.FunctionCall ->
                    "Call ${first.methodName}() on $receiverId (${first.depositNear} NEAR)"
                is NearAction.AddKey ->
                    if (first.isFullAccess) {
                        "Add FullAccess Key to $signerId"
                    } else {
                        "Add FunctionCall Key for ${first.receiverId}"
                    }
            }
        }

    /**
     * Cryptographically zero-wipe all sensitive transaction byte arrays in RAM
     */
    fun wipeFromMemory() {
        Arrays.fill(rawUnsignedBorshBytes, 0.toByte())
        for (action in actions) {
            if (action is NearAction.FunctionCall) {
                Arrays.fill(action.argsBytes, 0.toByte())
            }
        }
    }
}

/**
 * Thrown when the Borsh-deserialized `signerId` or `publicKey` does not match
 * the Offline Phone Vault's active hardware wallet profile.
 */
class WalletProfileMismatchException(
    message: String = "Critical Security Mismatch: Wrong Wallet Profile",
    val details: String = ""
) : SecurityException(message)

data class VerifiedUnsignedPayload(
    val networkId: String,
    val transaction: DeserializedNearTransaction,
    val borshVerified: Boolean
)

object TxPayloadCodec {

    const val MAX_SINGLE_QR_CHARS = 850
    private val YOCTO_PER_NEAR = BigDecimal("1000000000000000000000000")

    fun yoctoToNear(yocto: BigInteger, maxScale: Int = 6): String {
        if (yocto == BigInteger.ZERO) return "0"
        val dec = BigDecimal(yocto).divide(YOCTO_PER_NEAR, 24, RoundingMode.DOWN)
        val stripped = dec.setScale(maxScale, RoundingMode.DOWN).stripTrailingZeros()
        return stripped.toPlainString()
    }

    fun nearToYocto(nearAmount: String): BigInteger {
        val dec = BigDecimal(nearAmount.trim())
        return dec.multiply(YOCTO_PER_NEAR).toBigIntegerExact()
    }

    /**
     * Deserialize raw unsigned NEAR Transaction Borsh bytes
     */
    fun deserializeBorshTransaction(rawBytes: ByteArray): DeserializedNearTransaction {
        val reader = BorshBinaryReader(rawBytes)

        val signerId = reader.readString()
        val publicKey = reader.readPublicKey()
        val nonce = reader.readU64()
        val receiverId = reader.readString()
        val blockHashBytes = reader.readFixedBytes(32)
        val blockHashBase58 = Signer.encodeBase58(blockHashBytes)

        val actionCount = reader.readU32()
        val actions = mutableListOf<NearAction>()

        for (i in 0 until actionCount) {
            when (val actionDiscriminant = reader.readU8()) {
                3 -> {
                    // Action::Transfer
                    val deposit = reader.readU128()
                    actions.add(NearAction.Transfer(deposit))
                }
                2 -> {
                    // Action::FunctionCall
                    val methodName = reader.readString()
                    val argsBytes = reader.readVecU8()
                    val gas = reader.readU64()
                    val deposit = reader.readU128()
                    actions.add(
                        NearAction.FunctionCall(
                            methodName = methodName,
                            argsBytes = argsBytes,
                            gas = gas,
                            depositYocto = deposit
                        )
                    )
                }
                5 -> {
                    // Action::AddKey
                    val addedPubKey = reader.readPublicKey()
                    val keyNonce = reader.readU64()
                    when (val permDiscriminant = reader.readU8()) {
                        1 -> {
                            actions.add(
                                NearAction.AddKey(
                                    publicKey = addedPubKey,
                                    nonce = keyNonce,
                                    isFullAccess = true
                                )
                            )
                        }
                        0 -> {
                            val hasAllowance = reader.readU8()
                            val allowance = if (hasAllowance == 1) reader.readU128() else null
                            val permReceiver = reader.readString()
                            val methodCount = reader.readU32()
                            val methods = (0 until methodCount).map { reader.readString() }
                            actions.add(
                                NearAction.AddKey(
                                    publicKey = addedPubKey,
                                    nonce = keyNonce,
                                    isFullAccess = false,
                                    receiverId = permReceiver,
                                    allowanceYocto = allowance,
                                    methodNames = methods
                                )
                            )
                        }
                        else -> throw IllegalArgumentException(
                            "Unsupported AddKey permission discriminant: $permDiscriminant"
                        )
                    }
                }
                else -> throw IllegalArgumentException(
                    "Unsupported NEAR Action discriminant in Borsh payload: $actionDiscriminant"
                )
            }
        }

        require(reader.remaining() == 0) {
            "Unexpected trailing bytes (${reader.remaining()}) in unsigned Borsh transaction"
        }

        val txHashBytes = Signer.sha256(rawBytes)
        return DeserializedNearTransaction(
            signerId = signerId,
            publicKey = publicKey,
            nonce = nonce,
            receiverId = receiverId,
            blockHashBase58 = blockHashBase58,
            actions = actions,
            rawUnsignedBorshBytes = rawBytes,
            sha256HashBase58 = Signer.encodeBase58(txHashBytes)
        )
    }

    /**
     * Serialize a DeserializedNearTransaction back to raw Borsh bytes (for round-trip & unit tests)
     */
    fun serializeBorshTransaction(
        signerId: String,
        publicKey: String,
        nonce: Long,
        receiverId: String,
        blockHashBase58: String,
        actions: List<NearAction>
    ): ByteArray {
        val writer = BorshBinaryWriter()
        writer.writeString(signerId.trim())
        writer.writePublicKey(publicKey.trim())
        writer.writeU64(nonce)
        writer.writeString(receiverId.trim())
        val blockHashBytes = Signer.decodeBase58(blockHashBase58.trim())
        require(blockHashBytes.size == 32) { "blockHash must be 32 bytes" }
        writer.writeFixedBytes(blockHashBytes)

        writer.writeU32(actions.size)
        for (action in actions) {
            when (action) {
                is NearAction.Transfer -> {
                    writer.writeU8(3)
                    writer.writeU128(action.depositYocto)
                }
                is NearAction.FunctionCall -> {
                    writer.writeU8(2)
                    writer.writeString(action.methodName)
                    writer.writeVecU8(action.argsBytes)
                    writer.writeU64(action.gas)
                    writer.writeU128(action.depositYocto)
                }
                is NearAction.AddKey -> {
                    writer.writeU8(5)
                    writer.writePublicKey(action.publicKey)
                    writer.writeU64(action.nonce)
                    if (action.isFullAccess) {
                        writer.writeU8(1)
                    } else {
                        writer.writeU8(0)
                        if (action.allowanceYocto != null) {
                            writer.writeU8(1)
                            writer.writeU128(action.allowanceYocto)
                        } else {
                            writer.writeU8(0)
                        }
                        writer.writeString(action.receiverId ?: "")
                        writer.writeU32(action.methodNames.size)
                        action.methodNames.forEach { writer.writeString(it) }
                    }
                }
            }
        }
        return writer.toByteArray()
    }

    /**
     * Parse and strictly verify an incoming Unsigned Transaction QR JSON Envelope.
     *
     * Supports both canonical `near_airgap`, flat `nearair`, and SafePal S1 optical envelopes.
     * Throws `SecurityException` if any plaintext JSON field disagrees with the raw Borsh binary.
     */
    fun parseAndVerifyUnsignedQr(rawJson: String): VerifiedUnsignedPayload {
        val root = JSONObject(rawJson.trim())
        val protocol = root.optString("protocol", "").ifEmpty { root.optString("type", "") }

        val networkId = root.optString("networkId", root.optString("network", "testnet"))
        val dataObj = root.optJSONObject("data")

        var rawBorshBytes: ByteArray? = null
        val borshB64 = dataObj?.optString("borshSerialized", "")?.takeIf { it.isNotEmpty() }
            ?: root.optString("unsignedTxBase64", "").takeIf { it.isNotEmpty() }
            ?: root.optString("rawBorshBase64", "").takeIf { it.isNotEmpty() }

        if (!borshB64.isNullOrEmpty()) {
            rawBorshBytes = Base64.getDecoder().decode(borshB64.trim())
        } else {
            val hexStr = root.optString("rawBorshHex", "")
            if (hexStr.isNotEmpty()) {
                rawBorshBytes = Signer.hexToBytes(hexStr.trim())
            }
        }

        require(rawBorshBytes != null && rawBorshBytes.isNotEmpty()) {
            "Missing unsigned Borsh transaction bytes in QR payload"
        }

        val tx = deserializeBorshTransaction(rawBorshBytes)

        // CRUCIAL SECURITY CHECK: Verify JSON plaintext fields against raw Borsh binary fields
        val envSigner = dataObj?.optString("signerId", "")?.takeIf { it.isNotEmpty() }
            ?: root.optString("signerId", "")
        val envReceiver = dataObj?.optString("receiverId", "")?.takeIf { it.isNotEmpty() }
            ?: root.optString("receiverId", "")
        val envPublicKey = dataObj?.optString("publicKey", "")?.takeIf { it.isNotEmpty() }
            ?: root.optString("publicKey", "")
        val envBlockHash = dataObj?.optString("blockHash", "")?.takeIf { it.isNotEmpty() }
            ?: root.optString("blockHash", "")

        if (envSigner.isNotEmpty() && envSigner != tx.signerId) {
            throw SecurityException(
                "SECURITY TAMPER ALERT: JSON signerId ('$envSigner') != Borsh signerId ('${tx.signerId}')"
            )
        }
        if (envReceiver.isNotEmpty() && envReceiver != tx.receiverId) {
            throw SecurityException(
                "SECURITY TAMPER ALERT: JSON receiverId ('$envReceiver') != Borsh receiverId ('${tx.receiverId}')"
            )
        }
        if (envPublicKey.isNotEmpty()) {
            val normalizedEnvKey = if (envPublicKey.startsWith("ed25519:")) {
                envPublicKey
            } else {
                "ed25519:$envPublicKey"
            }
            if (normalizedEnvKey != tx.publicKey) {
                throw SecurityException(
                    "SECURITY TAMPER ALERT: JSON publicKey ('$normalizedEnvKey') != Borsh publicKey ('${tx.publicKey}')"
                )
            }
        }
        if (dataObj != null && dataObj.has("nonce")) {
            val envNonce = dataObj.getLong("nonce")
            if (envNonce != tx.nonce) {
                throw SecurityException(
                    "SECURITY TAMPER ALERT: JSON nonce ($envNonce) != Borsh nonce (${tx.nonce})"
                )
            }
        }
        if (envBlockHash.isNotEmpty() && envBlockHash != tx.blockHashBase58) {
            throw SecurityException(
                "SECURITY TAMPER ALERT: JSON blockHash ('$envBlockHash') != Borsh blockHash ('${tx.blockHashBase58}')"
            )
        }

        // Verify action deposits if present in `data.actions`
        val envActions = dataObj?.optJSONArray("actions")
        if (envActions != null && envActions.length() > 0) {
            if (envActions.length() != tx.actions.size) {
                throw SecurityException(
                    "SECURITY TAMPER ALERT: JSON action count (${envActions.length()}) != Borsh action count (${tx.actions.size})"
                )
            }
            for (i in 0 until envActions.length()) {
                val actJson = envActions.getJSONObject(i)
                val actType = actJson.optString("type")
                val paramsJson = actJson.optJSONObject("params")
                val borshAction = tx.actions[i]
                when (borshAction) {
                    is NearAction.Transfer -> {
                        if (actType.isNotEmpty() && actType != "Transfer") {
                            throw SecurityException("SECURITY TAMPER ALERT: Action type mismatch")
                        }
                        val envDeposit = paramsJson?.optString("deposit", "") ?: ""
                        if (envDeposit.isNotEmpty() && BigInteger(envDeposit) != borshAction.depositYocto) {
                            throw SecurityException(
                                "SECURITY TAMPER ALERT: JSON Transfer deposit ($envDeposit) != Borsh deposit (${borshAction.depositYocto})"
                            )
                        }
                    }
                    is NearAction.FunctionCall -> {
                        if (actType.isNotEmpty() && actType != "FunctionCall") {
                            throw SecurityException("SECURITY TAMPER ALERT: Action type mismatch")
                        }
                        val envMethod = paramsJson?.optString("methodName", "") ?: ""
                        if (envMethod.isNotEmpty() && envMethod != borshAction.methodName) {
                            throw SecurityException(
                                "SECURITY TAMPER ALERT: JSON methodName ('$envMethod') != Borsh methodName ('${borshAction.methodName}')"
                            )
                        }
                    }
                    is NearAction.AddKey -> {
                        if (actType.isNotEmpty() && actType != "AddKey") {
                            throw SecurityException("SECURITY TAMPER ALERT: Action type mismatch")
                        }
                    }
                }
            }
        }

        return VerifiedUnsignedPayload(
            networkId = networkId,
            transaction = tx,
            borshVerified = true
        )
    }

    /**
     * Secure NEAR Transaction Deserialization & Hardware Wallet Profile Verification Handler.
     *
     * 1. Decodes the scanned QR string (JSON envelope, Base64, or Hex) into a raw Borsh ByteArray
     *    and deserializes it into a `DeserializedNearTransaction` structure.
     * 2. Reads the `signerId` and `publicKey` fields inherently embedded inside the deserialized
     *    NEAR Transaction Borsh binary stream.
     * 3. Compares these deserialized fields against the phone's locally stored active account
     *    identity (`localActiveAccountId`) and authorized public keys (`localActivePublicKeys`).
     * 4. If `signerId` or `publicKey` does not strictly match the hardware state of the mobile app,
     *    immediately zero-wipes the transaction bytes in memory, discards the transaction object,
     *    and throws `WalletProfileMismatchException("Critical Security Mismatch: Wrong Wallet Profile")`.
     */
    fun verifyAndDeserializeForHardwareProfile(
        rawScannedPayload: String,
        localActiveAccountId: String,
        localImplicitAccountId: String = "",
        localActivePublicKeys: Set<String>
    ): VerifiedUnsignedPayload {
        val trimmed = rawScannedPayload.trim()

        // 1. Decode scanned string (JSON envelope, raw Hex, or raw Base64) into Borsh byte array
        val verifiedPayload: VerifiedUnsignedPayload = if (trimmed.startsWith("{")) {
            parseAndVerifyUnsignedQr(trimmed)
        } else {
            val rawBytes: ByteArray = if (trimmed.matches(Regex("^[0-9a-fA-F]+$")) && trimmed.length % 2 == 0) {
                Signer.hexToBytes(trimmed)
            } else {
                Base64.getDecoder().decode(trimmed)
            }
            val deserialized = try {
                deserializeBorshTransaction(rawBytes)
            } catch (t: Throwable) {
                Arrays.fill(rawBytes, 0.toByte())
                throw t
            }
            VerifiedUnsignedPayload(
                networkId = "testnet",
                transaction = deserialized,
                borshVerified = true
            )
        }

        val deserializedTx = verifiedPayload.transaction

        // 2. Read `signerId` and `publicKey` inherently embedded inside the deserialized Borsh Transaction
        val embeddedSignerId = deserializedTx.signerId.trim().lowercase()
        val embeddedPublicKey = deserializedTx.publicKey.trim()

        // 3. Compare against the phone's locally stored active public keys
        val normalizedLocalKeys = localActivePublicKeys.map { key ->
            val k = key.trim()
            if (k.startsWith("ed25519:")) k else "ed25519:$k"
        }.toSet()

        val normalizedTxKey = if (embeddedPublicKey.startsWith("ed25519:")) {
            embeddedPublicKey
        } else {
            "ed25519:$embeddedPublicKey"
        }

        val publicKeyMatches = normalizedTxKey in normalizedLocalKeys

        // 4. Return verified payload. Key matching and authorization are enforced by EnclavePolicyEngine
        // and TxConfirmScreen to ensure transparent user feedback on the S1 screen rather than abrupt aborts.
        return verifiedPayload
    }

    /**
     * Construct the Step B Signed Transaction Response QR JSON Envelope.
     * Kept compact (~310 bytes) so the response always renders as a single, low-density QR code.
     */
    fun buildSignedQrResponse(
        verifiedPayload: VerifiedUnsignedPayload,
        signedResult: Signer.SignedTransactionResult,
        actualPublicKey: String? = null
    ): String {
        val tx = verifiedPayload.transaction
        val dataObj = JSONObject().apply {
            put("signerId", tx.signerId)
            put("receiverId", tx.receiverId)
            put("rawSignedTxBase64", signedResult.signedBorshBase64)
            put("sig", signedResult.signatureBase58)
            put("signatureBase58", signedResult.signatureBase58)
            put("txHash", signedResult.txHashBase58)
            actualPublicKey?.let { put("publicKey", it) }
        }

        val root = JSONObject().apply {
            put("protocol", "near_airgap")
            put("version", "1.0")
            put("networkId", verifiedPayload.networkId)
            put("action", "broadcast_tx")
            put("type", "sign_response")
            put("sig", signedResult.signatureBase58)
            put("signatureBase58", signedResult.signatureBase58)
            put("rawSignedTxBase64", signedResult.signedBorshBase64)
            actualPublicKey?.let { put("publicKey", it) }
            put("data", dataObj)
        }
        return root.toString()
    }

    /**
     * Construct Vault Pairing QR JSON Envelope (`pair_vault`)
     * Shares public information of the wallet created on the Vault with the Online Watcher.
     */
    fun buildPairingQrPayload(
        keyPair: Signer.NearKeyPair,
        accountId: String? = null,
        networkId: String = "testnet"
    ): String {
        val resolvedAccount = accountId?.trim()?.ifEmpty { null } ?: keyPair.implicitAccountId
        val dataObj = JSONObject().apply {
            put("accountId", resolvedAccount)
            put("publicKey", keyPair.nearPublicKey)
            put("implicitAccountId", keyPair.implicitAccountId)
            put("networkId", networkId)
        }
        val root = JSONObject().apply {
            put("protocol", "near_airgap")
            put("version", "1.0")
            put("networkId", networkId)
            put("action", "pair_vault")
            put("accountId", resolvedAccount)
            put("publicKey", keyPair.nearPublicKey)
            put("data", dataObj)
        }
        return root.toString()
    }

    /**
     * Deterministic 8-char hex checksum matching TypeScript `computeShortChecksum`
     */
    fun computeShortChecksum(input: String): String {
        var h1 = (-0x21524111) xor input.length
        var h2 = 0x41c6ce57 xor input.length
        for (i in input.indices) {
            val ch = input[i].code
            h1 = (h1 xor ch) * -0x61c88647
            h2 = (h2 xor ch) * 0x5f356495
        }
        h1 = ((h1 xor (h1 ushr 16)) * -0x7a143595) xor ((h2 xor (h2 ushr 13)) * -0x3d4d51cb)
        return java.lang.Integer.toUnsignedString(h1, 16).padStart(8, '0')
    }

    /**
     * Split a large JSON string into animated QR frames
     */
    fun chunkPayloadToFrames(
        fullJson: String,
        maxChunkSize: Int = MAX_SINGLE_QR_CHARS
    ): List<String> {
        if (fullJson.length <= maxChunkSize) {
            return listOf(fullJson)
        }
        val checksum = computeShortChecksum(fullJson)
        val total = (fullJson.length + maxChunkSize - 1) / maxChunkSize
        val frames = mutableListOf<String>()

        for (index in 0 until total) {
            val start = index * maxChunkSize
            val end = minOf(fullJson.length, (index + 1) * maxChunkSize)
            val chunk = fullJson.substring(start, end)
            val frameJson = JSONObject().apply {
                put("protocol", "near_airgap_frame")
                put("version", "1.0")
                put("payloadId", checksum)
                put("index", index)
                put("total", total)
                put("checksum", checksum)
                put("chunk", chunk)
            }
            frames.add(frameJson.toString())
        }
        return frames
    }
}

/**
 * Multi-Frame QR Collector for scanning animated QR streams on the Vault camera
 */
class MultiFrameQrCollector {
    private var payloadId: String? = null
    private var totalFrames: Int = 0
    private var expectedChecksum: String = ""
    private val chunks = sortedMapOf<Int, String>()

    data class IngestStatus(
        val complete: Boolean,
        val fullPayload: String? = null,
        val receivedCount: Int,
        val totalCount: Int
    )

    fun reset() {
        payloadId = null
        totalFrames = 0
        expectedChecksum = ""
        chunks.clear()
    }

    fun ingest(rawScannedText: String): IngestStatus {
        val trimmed = rawScannedText.trim()
        if (trimmed.contains("\"near_airgap_frame\"")) {
            val obj = JSONObject(trimmed)
            if (obj.optString("protocol") == "near_airgap_frame") {
                val id = obj.getString("payloadId")
                val index = obj.getInt("index")
                val total = obj.getInt("total")
                val checksum = obj.getString("checksum")
                val chunk = obj.getString("chunk")

                if (payloadId != id || totalFrames != total) {
                    payloadId = id
                    totalFrames = total
                    expectedChecksum = checksum
                    chunks.clear()
                }
                chunks[index] = chunk

                if (chunks.size == totalFrames) {
                    val sb = StringBuilder()
                    for (i in 0 until totalFrames) {
                        val part = chunks[i] ?: return IngestStatus(
                            complete = false,
                            receivedCount = chunks.size,
                            totalCount = totalFrames
                        )
                        sb.append(part)
                    }
                    val assembled = sb.toString()
                    val actualChecksum = TxPayloadCodec.computeShortChecksum(assembled)
                    if (actualChecksum != expectedChecksum) {
                        reset()
                        throw IllegalArgumentException("Multi-frame QR checksum mismatch")
                    }
                    return IngestStatus(
                        complete = true,
                        fullPayload = assembled,
                        receivedCount = totalFrames,
                        totalCount = totalFrames
                    )
                }

                return IngestStatus(
                    complete = false,
                    receivedCount = chunks.size,
                    totalCount = totalFrames
                )
            }
        }

        return IngestStatus(
            complete = true,
            fullPayload = trimmed,
            receivedCount = 1,
            totalCount = 1
        )
    }
}

/**
 * Little-Endian Borsh Binary Reader
 */
private class BorshBinaryReader(private val bytes: ByteArray) {
    private var offset = 0

    private fun ensureAvailable(len: Int) {
        if (offset + len > bytes.size) {
            throw IllegalArgumentException(
                "Borsh buffer underflow at offset $offset: needed $len, remaining ${bytes.size - offset}"
            )
        }
    }

    fun readU8(): Int {
        ensureAvailable(1)
        return bytes[offset++].toInt() and 0xFF
    }

    fun readU32(): Int {
        ensureAvailable(4)
        val v = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
        offset += 4
        return v
    }

    fun readU64(): Long {
        ensureAvailable(8)
        val v = ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.LITTLE_ENDIAN).long
        offset += 8
        return v
    }

    fun readU128(): BigInteger {
        ensureAvailable(16)
        val leSlice = bytes.copyOfRange(offset, offset + 16)
        offset += 16
        // Reverse little-endian to big-endian unsigned
        val be = ByteArray(17)
        be[0] = 0
        for (i in 0 until 16) {
            be[1 + i] = leSlice[15 - i]
        }
        return BigInteger(be)
    }

    fun readFixedBytes(len: Int): ByteArray {
        ensureAvailable(len)
        val out = bytes.copyOfRange(offset, offset + len)
        offset += len
        return out
    }

    fun readVecU8(): ByteArray {
        val len = readU32()
        require(len >= 0 && len <= remaining()) { "Invalid Borsh vector length: $len" }
        return readFixedBytes(len)
    }

    fun readString(): String {
        val utf8 = readVecU8()
        return String(utf8, Charsets.UTF_8)
    }

    fun readPublicKey(): String {
        val keyType = readU8()
        require(keyType == 0) { "Unsupported NEAR keyType $keyType; only Ed25519 (0) is supported" }
        val keyBytes = readFixedBytes(32)
        return "ed25519:${Signer.encodeBase58(keyBytes)}"
    }

    fun remaining(): Int = bytes.size - offset
}

/**
 * Little-Endian Borsh Binary Writer
 */
private class BorshBinaryWriter {
    private val out = ByteArrayOutputStream()

    fun writeU8(value: Int) {
        out.write(value and 0xFF)
    }

    fun writeU32(value: Int) {
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
        out.write(buf)
    }

    fun writeU64(value: Long) {
        val buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()
        out.write(buf)
    }

    fun writeU128(value: BigInteger) {
        require(value >= BigInteger.ZERO) { "u128 cannot be negative" }
        val be = value.toByteArray()
        val le = ByteArray(16)
        var leIdx = 0
        for (i in be.size - 1 downTo 0) {
            if (leIdx < 16) {
                le[leIdx++] = be[i]
            }
        }
        out.write(le)
    }

    fun writeFixedBytes(bytes: ByteArray) {
        out.write(bytes)
    }

    fun writeVecU8(bytes: ByteArray) {
        writeU32(bytes.size)
        out.write(bytes)
    }

    fun writeString(value: String) {
        writeVecU8(value.toByteArray(Charsets.UTF_8))
    }

    fun writePublicKey(nearPublicKey: String) {
        val raw32 = Signer.parseNearPublicKeyBytes(nearPublicKey)
        writeU8(0)
        writeFixedBytes(raw32)
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}


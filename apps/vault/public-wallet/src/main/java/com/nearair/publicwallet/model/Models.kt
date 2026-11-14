package com.nearair.publicwallet.model

import org.bouncycastle.util.encoders.Hex
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

data class WatchAccount(
    val accountId: String,
    val publicKey: String,
    val label: String = "Cold Vault Account"
)

data class TxRecord(
    val txHash: String,
    val receiverId: String,
    val amountNear: Double,
    val timestamp: Long,
    val status: String = "Success"
)

object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val INDEXES = IntArray(128) { -1 }.apply {
        for (i in ALPHABET.indices) {
            this[ALPHABET[i].code] = i
        }
    }

    fun decode(input: String): ByteArray {
        if (input.isEmpty()) return ByteArray(0)
        val input58 = ByteArray(input.length)
        for (i in input.indices) {
            val c = input[i]
            val digit = if (c.code < 128) INDEXES[c.code] else -1
            require(digit >= 0) { "Illegal character $c in Base58" }
            input58[i] = digit.toByte()
        }
        var zeros = 0
        while (zeros < input58.size && input58[zeros].toInt() == 0) {
            zeros++
        }
        val decoded = ByteArray(input.length)
        var outputStart = decoded.size
        var inputStart = zeros
        while (inputStart < input58.size) {
            decoded[--outputStart] = divmod(input58, inputStart, 58, 256)
            if (input58[inputStart].toInt() == 0) {
                inputStart++
            }
        }
        while (outputStart < decoded.size && decoded[outputStart].toInt() == 0) {
            outputStart++
        }
        val result = ByteArray(zeros + (decoded.size - outputStart))
        System.arraycopy(decoded, outputStart, result, zeros, decoded.size - outputStart)
        return result
    }

    fun encode(input: ByteArray): String {
        if (input.isEmpty()) return ""
        var zeros = 0
        while (zeros < input.size && input[zeros].toInt() == 0) {
            zeros++
        }
        val encoded = CharArray(input.size * 2)
        var outputStart = encoded.size
        var inputStart = zeros
        while (inputStart < input.size) {
            encoded[--outputStart] = ALPHABET[divmod(input, inputStart, 256, 58).toInt()]
            if (input[inputStart].toInt() == 0) {
                inputStart++
            }
        }
        while (outputStart < encoded.size && encoded[outputStart] == '1') {
            outputStart++
        }
        while (--zeros >= 0) {
            encoded[--outputStart] = '1'
        }
        return String(encoded, outputStart, encoded.size - outputStart)
    }

    private fun divmod(number: ByteArray, firstDigit: Int, base: Int, divisor: Int): Byte {
        var remainder = 0
        for (i in firstDigit until number.size) {
            val digit = number[i].toInt() and 0xFF
            val temp = remainder * base + digit
            number[i] = (temp / divisor).toByte()
            remainder = temp % divisor
        }
        return remainder.toByte()
    }
}

object NearTxSerializer {

    fun nearToYocto(amountNear: Double): BigInteger {
        val bd = BigDecimal.valueOf(amountNear)
        val yocto = bd.multiply(BigDecimal("1000000000000000000000000"))
        return yocto.toBigInteger()
    }

    fun decodePublicKey(pubKeyStr: String): ByteArray {
        val clean = pubKeyStr.removePrefix("ed25519:").trim()
        val decoded = Base58.decode(clean)
        require(decoded.size == 32) { "Expected 32-byte Ed25519 public key, got ${decoded.size}" }
        return decoded
    }

    /**
     * Derive the 64-character hexadecimal Implicit Account ID from an Ed25519 public key
     */
    fun deriveImplicitAccountId(pubKeyStr: String): String {
        return try {
            val pubBytes = decodePublicKey(pubKeyStr)
            org.bouncycastle.util.encoders.Hex.toHexString(pubBytes)
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Serialize a standard NEAR Transfer transaction into Borsh binary bytes
     */
    fun serializeTransferBorsh(
        signerId: String,
        publicKeyStr: String,
        nonce: Long,
        receiverId: String,
        blockHashStr: String,
        depositYocto: BigInteger
    ): ByteArray {
        val out = ByteArrayOutputStream()

        // 1. signer_id (string: u32 length + UTF-8 bytes)
        val signerBytes = signerId.trim().lowercase().toByteArray(Charsets.UTF_8)
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(signerBytes.size).array())
        out.write(signerBytes)

        // 2. public_key: key_type (0 = ed25519) + 32 bytes
        out.write(0)
        val pubKeyBytes = decodePublicKey(publicKeyStr)
        out.write(pubKeyBytes)

        // 3. nonce (u64 little-endian)
        out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(nonce).array())

        // 4. receiver_id (string: u32 length + UTF-8 bytes)
        val receiverBytes = receiverId.trim().lowercase().toByteArray(Charsets.UTF_8)
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(receiverBytes.size).array())
        out.write(receiverBytes)

        // 5. block_hash (32 bytes from Base58)
        val blockHashBytes = Base58.decode(blockHashStr.trim())
        require(blockHashBytes.size == 32) { "Expected 32-byte block hash, got ${blockHashBytes.size}" }
        out.write(blockHashBytes)

        // 6. actions (vector of Action): u32 count (1 action)
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(1).array())

        // Action enum: 3 = Transfer
        out.write(3)

        // deposit: u128 (16 bytes little-endian)
        val yoctoBytes = depositYocto.toByteArray()
        val u128 = ByteArray(16)
        // BigInteger.toByteArray() is big-endian, copy into little-endian 16-byte array
        for (i in yoctoBytes.indices) {
            val srcIdx = yoctoBytes.size - 1 - i
            if (i < 16 && srcIdx >= 0) {
                u128[i] = yoctoBytes[srcIdx]
            }
        }
        out.write(u128)

        return out.toByteArray()
    }

    /**
     * Decode a signature string that may be ed25519:<base58>, raw Base58, or Base64
     */
    fun decodeSignatureBytes(sigStr: String): ByteArray {
        val clean = sigStr.removePrefix("ed25519:").trim()
        // Try Base58 first
        try {
            val b58 = Base58.decode(clean)
            if (b58.size == 64) return b58
        } catch (_: Exception) {}

        // Try Base64
        try {
            val b64 = Base64.getDecoder().decode(clean)
            if (b64.size == 64) return b64
        } catch (_: Exception) {}

        // Fallback: try raw Base58 anyway
        val fallback = Base58.decode(clean)
        require(fallback.size == 64) { "Expected 64-byte Ed25519 signature, got ${fallback.size}" }
        return fallback
    }

    /**
     * Assemble final SignedTransaction Borsh payload
     */
    fun assembleSignedTransactionBase64(
        unsignedBorshBytes: ByteArray,
        signatureStr: String
    ): String {
        val out = ByteArrayOutputStream()
        out.write(unsignedBorshBytes)

        // Signature enum: 0 = ED25519
        out.write(0)

        val sigBytes = decodeSignatureBytes(signatureStr)
        out.write(sigBytes)

        return Base64.getEncoder().encodeToString(out.toByteArray())
    }

    /**
     * Compute SHA-256 transaction hash Base58 from signed Borsh Base64 payload
     */
    fun computeTxHash(signedTxBase64: String): String {
        return try {
            val bytes = Base64.getDecoder().decode(signedTxBase64.trim())
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            Base58.encode(digest)
        } catch (e: Exception) {
            "SimulatedTxHash_${System.currentTimeMillis()}"
        }
    }
}


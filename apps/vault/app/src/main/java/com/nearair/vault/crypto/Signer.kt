package com.nearair.vault.crypto

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import java.util.Base64

/**
 * NearAir Offline Cryptographic Signer (Ed25519 + SHA-256 + Base58)
 *
 * Implements NEAR Protocol native Ed25519 signing:
 * 1. Computes `txHash = SHA-256(unsignedBorshBytes)` (32 bytes)
 * 2. Signs `txHash` using Ed25519 secret key (32-byte seed) -> 64-byte signature
 * 3. Assembles `SignedTransaction` Borsh payload = `unsignedBorshBytes || 0x00 || signature64`
 */
object Signer {

    private const val BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    data class NearKeyPair(
        val seed32: ByteArray,
        val publicKey32: ByteArray,
        val secretKey64: ByteArray,
        val nearPublicKey: String,
        val nearSecretKey: String,
        val implicitAccountId: String
    )

    data class SignedTransactionResult(
        val unsignedBorshBytes: ByteArray,
        val txHashBytes: ByteArray,
        val txHashBase58: String,
        val signatureBytes: ByteArray,
        val signatureBase64: String,
        val signatureBase58: String,
        val signedBorshBytes: ByteArray,
        val signedBorshBase64: String,
        val approvalCode6Digit: String
    )

    /**
     * Derive a NEAR Ed25519 keypair from a 32-byte seed
     */
    fun keyPairFromSeed(seed32: ByteArray): NearKeyPair {
        require(seed32.size == 32) {
            "Ed25519 seed must be exactly 32 bytes (got ${seed32.size})"
        }
        val privParams = Ed25519PrivateKeyParameters(seed32, 0)
        val pubParams = privParams.generatePublicKey()
        val pubBytes = pubParams.encoded

        val secret64 = ByteArray(64)
        System.arraycopy(seed32, 0, secret64, 0, 32)
        System.arraycopy(pubBytes, 0, secret64, 32, 32)

        return NearKeyPair(
            seed32 = seed32.copyOf(),
            publicKey32 = pubBytes,
            secretKey64 = secret64,
            nearPublicKey = "ed25519:${encodeBase58(pubBytes)}",
            nearSecretKey = "ed25519:${encodeBase58(secret64)}",
            implicitAccountId = bytesToHex(pubBytes)
        )
    }

    /**
     * Compute 32-byte SHA-256 digest
     */
    fun sha256(input: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input)
    }

    /**
     * Sign a 32-byte SHA-256 transaction hash with a 32-byte Ed25519 seed
     */
    fun signDetached(message: ByteArray, seed32: ByteArray): ByteArray {
        require(seed32.size == 32) { "Expected 32-byte Ed25519 seed" }
        val privParams = Ed25519PrivateKeyParameters(seed32, 0)
        val signer = Ed25519Signer()
        signer.init(true, privParams)
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    /**
     * Verify a 64-byte Ed25519 signature against a 32-byte public key
     */
    fun verifyDetached(message: ByteArray, signature64: ByteArray, publicKey32: ByteArray): Boolean {
        if (signature64.size != 64 || publicKey32.size != 32) return false
        return try {
            val pubParams = Ed25519PublicKeyParameters(publicKey32, 0)
            val verifier = Ed25519Signer()
            verifier.init(false, pubParams)
            verifier.update(message, 0, message.size)
            verifier.verifySignature(signature64)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Hash and sign raw unsigned Borsh transaction bytes and build a NEAR `SignedTransaction`
     */
    fun signTransactionBorsh(
        unsignedBorshBytes: ByteArray,
        seed32: ByteArray
    ): SignedTransactionResult {
        val keyPair = keyPairFromSeed(seed32)
        val txHashBytes = sha256(unsignedBorshBytes)
        val signatureBytes = signDetached(txHashBytes, seed32)

        // Fault-injection guard: verify signature before emitting
        check(verifyDetached(txHashBytes, signatureBytes, keyPair.publicKey32)) {
            "Ed25519 fault-injection check failed: generated signature did not verify"
        }

        // NEAR SignedTransaction Borsh = TransactionBorsh + u8(0 = ED25519) + Signature[64]
        val signedBorshBytes = ByteArray(unsignedBorshBytes.size + 1 + 64)
        System.arraycopy(unsignedBorshBytes, 0, signedBorshBytes, 0, unsignedBorshBytes.size)
        signedBorshBytes[unsignedBorshBytes.size] = 0x00 // keyType = 0 (Ed25519)
        System.arraycopy(signatureBytes, 0, signedBorshBytes, unsignedBorshBytes.size + 1, 64)

        // Compute deterministic 6-digit Hardware Verification Code from the first 4 bytes of Ed25519 signature
        val u32 = ((signatureBytes[0].toLong() and 0xFF) shl 24) or
            ((signatureBytes[1].toLong() and 0xFF) shl 16) or
            ((signatureBytes[2].toLong() and 0xFF) shl 8) or
            (signatureBytes[3].toLong() and 0xFF)
        val approvalCode6Digit = String.format(java.util.Locale.US, "%06d", u32 % 1_000_000L)

        val encoder = Base64.getEncoder()
        return SignedTransactionResult(
            unsignedBorshBytes = unsignedBorshBytes,
            txHashBytes = txHashBytes,
            txHashBase58 = encodeBase58(txHashBytes),
            signatureBytes = signatureBytes,
            signatureBase64 = encoder.encodeToString(signatureBytes),
            signatureBase58 = "ed25519:${encodeBase58(signatureBytes)}",
            signedBorshBytes = signedBorshBytes,
            signedBorshBase64 = encoder.encodeToString(signedBorshBytes),
            approvalCode6Digit = approvalCode6Digit
        )
    }

    /**
     * Parse `"ed25519:<base58>"` or `"<base58>"` into raw 32-byte public key
     */
    fun parseNearPublicKeyBytes(nearPublicKey: String): ByteArray {
        val trimmed = nearPublicKey.trim()
        val base58Part = if (trimmed.startsWith("ed25519:")) {
            trimmed.substring("ed25519:".length)
        } else {
            trimmed
        }
        val decoded = decodeBase58(base58Part)
        require(decoded.size == 32) {
            "Invalid Ed25519 public key byte length: expected 32, got ${decoded.size}"
        }
        return decoded
    }

    /**
     * Base58 Encode
     */
    fun encodeBase58(input: ByteArray): String {
        if (input.isEmpty()) return ""
        var zeros = 0
        while (zeros < input.size && input[zeros].toInt() == 0) {
            zeros++
        }
        val encoded = ByteArray(input.size * 2)
        var outputStart = encoded.size
        val copy = input.copyOf()
        var startAt = zeros
        while (startAt < copy.size) {
            val mod = divmod(copy, startAt, 256, 58)
            if (copy[startAt].toInt() == 0) {
                startAt++
            }
            encoded[--outputStart] = BASE58_ALPHABET[mod].code.toByte()
        }
        while (outputStart < encoded.size && encoded[outputStart] == BASE58_ALPHABET[0].code.toByte()) {
            outputStart++
        }
        repeat(zeros) {
            encoded[--outputStart] = BASE58_ALPHABET[0].code.toByte()
        }
        return String(encoded, outputStart, encoded.size - outputStart, Charsets.US_ASCII)
    }

    /**
     * Base58 Decode
     */
    fun decodeBase58(input: String): ByteArray {
        val cleaned = input.trim()
        if (cleaned.isEmpty()) return ByteArray(0)
        val input58 = ByteArray(cleaned.length)
        for (i in cleaned.indices) {
            val c = cleaned[i]
            val digit = BASE58_ALPHABET.indexOf(c)
            require(digit >= 0) { "Invalid Base58 character '$c' in \"$input\"" }
            input58[i] = digit.toByte()
        }
        var zeros = 0
        while (zeros < input58.size && input58[zeros].toInt() == 0) {
            zeros++
        }
        val decoded = ByteArray(cleaned.length)
        var outputStart = decoded.size
        var startAt = zeros
        while (startAt < input58.size) {
            val mod = divmod(input58, startAt, 58, 256)
            if (input58[startAt].toInt() == 0) {
                startAt++
            }
            decoded[--outputStart] = mod.toByte()
        }
        while (outputStart < decoded.size && decoded[outputStart].toInt() == 0) {
            outputStart++
        }
        return decoded.copyOfRange(outputStart - zeros, decoded.size)
    }

    private fun divmod(number: ByteArray, firstDigit: Int, base: Int, divisor: Int): Int {
        var remainder = 0
        for (i in firstDigit until number.size) {
            val digit = number[i].toInt() and 0xFF
            val temp = remainder * base + digit
            number[i] = (temp / divisor).toByte()
            remainder = temp % divisor
        }
        return remainder
    }

    fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    fun hexToBytes(hex: String): ByteArray {
        val cleaned = hex.trim().removePrefix("0x")
        require(cleaned.length % 2 == 0) { "Hex string must have even length" }
        val out = ByteArray(cleaned.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(cleaned[i * 2], 16)
            val lo = Character.digit(cleaned[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "Invalid hex character in $hex" }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}


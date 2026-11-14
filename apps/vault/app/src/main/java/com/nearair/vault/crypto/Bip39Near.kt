package com.nearair.vault.crypto

import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.KeyParameter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Standard BIP-39 Mnemonic & SLIP-0010 Ed25519 Key Derivation for NEAR Protocol
 *
 * Implements standard NEAR wallet derivation:
 * - BIP-39 12-Word Mnemonic generation & validation (128-bit entropy + 4-bit checksum)
 * - PBKDF2-HMAC-SHA512 2048-round seed derivation
 * - SLIP-0010 Ed25519 hardened key derivation along path `m/44'/397'/0'`
 *
 * Interoperable with official NEAR wallets (Meteor, HERE, MyNearWallet) and SafePal S1.
 */
object Bip39Near {
    const val KEY_DERIVATION_PATH = "m/44'/397'/0'"
    private val ED25519_SEED_BYTES = "ed25519 seed".toByteArray(Charsets.UTF_8)
    private const val HARDENED_OFFSET = 0x80000000L

    val WORD_LIST: List<String> by lazy {
        val stream = Bip39Near::class.java.classLoader?.getResourceAsStream("bip39-english.txt")
            ?: error("Could not find bip39-english.txt in resources")
        stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
        }
    }

    private val WORD_INDEX_MAP: Map<String, Int> by lazy {
        WORD_LIST.withIndex().associate { it.value to it.index }
    }

    /**
     * Generates a secure 12-word BIP-39 mnemonic (128 bits entropy + 4 bits checksum)
     */
    fun generateMnemonic(entropyBytes: ByteArray? = null): String {
        val entropy = entropyBytes ?: ByteArray(16).apply { SecureRandom().nextBytes(this) }
        require(entropy.size == 16) { "Entropy for 12-word mnemonic must be exactly 16 bytes" }

        val hash = MessageDigest.getInstance("SHA-256").digest(entropy)
        val checksumByte = hash[0].toInt() and 0xFF // First 4 bits needed

        // 128 bits entropy + 4 bits checksum = 132 bits = 12 words * 11 bits
        val bits = BooleanArray(132)
        for (i in 0 until 16) {
            val b = entropy[i].toInt() and 0xFF
            for (j in 0 until 8) {
                bits[i * 8 + j] = ((b shr (7 - j)) and 1) == 1
            }
        }
        for (j in 0 until 4) {
            bits[128 + j] = ((checksumByte shr (7 - j)) and 1) == 1
        }

        val words = ArrayList<String>(12)
        for (i in 0 until 12) {
            var index = 0
            for (j in 0 until 11) {
                index = (index shl 1) or (if (bits[i * 11 + j]) 1 else 0)
            }
            words.add(WORD_LIST[index])
        }
        return words.joinToString(" ")
    }

    /**
     * Normalizes and validates a 12-word BIP-39 mnemonic
     */
    fun validateMnemonic(mnemonic: String): Boolean {
        val words = mnemonic.trim().lowercase().split("\\s+".toRegex()).filter { it.isNotEmpty() }
        if (words.size != 12) return false
        val indices = words.map { WORD_INDEX_MAP[it] ?: return false }

        val bits = BooleanArray(132)
        for (i in 0 until 12) {
            val idx = indices[i]
            for (j in 0 until 11) {
                bits[i * 11 + j] = ((idx shr (10 - j)) and 1) == 1
            }
        }

        val entropy = ByteArray(16)
        for (i in 0 until 16) {
            var b = 0
            for (j in 0 until 8) {
                b = (b shl 1) or (if (bits[i * 8 + j]) 1 else 0)
            }
            entropy[i] = b.toByte()
        }

        val hash = MessageDigest.getInstance("SHA-256").digest(entropy)
        val expectedChecksum = ((hash[0].toInt() and 0xFF) ushr 4) and 0x0F
        var actualChecksum = 0
        for (j in 0 until 4) {
            actualChecksum = (actualChecksum shl 1) or (if (bits[128 + j]) 1 else 0)
        }

        return expectedChecksum == actualChecksum
    }

    /**
     * PBKDF2-HMAC-SHA512 mnemonic to 64-byte seed (BIP-39)
     */
    fun mnemonicToSeed(mnemonic: String, passphrase: String = ""): ByteArray {
        val normalizedMnemonic = mnemonic.trim().lowercase().split("\\s+".toRegex())
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        val salt = ("mnemonic" + passphrase).toByteArray(Charsets.UTF_8)
        val gen = PKCS5S2ParametersGenerator(SHA512Digest())
        gen.init(
            normalizedMnemonic.toByteArray(Charsets.UTF_8),
            salt,
            2048
        )
        return (gen.generateDerivedParameters(512) as KeyParameter).key
    }

    /**
     * SLIP-0010 Ed25519 derivation for standard NEAR path `m/44'/397'/0'`
     */
    fun deriveNearKeyPair(mnemonic: String, passphrase: String = ""): Signer.NearKeyPair {
        val seed64 = mnemonicToSeed(mnemonic, passphrase)
        return deriveFromBip39Seed(seed64)
    }

    /**
     * Derive Ed25519 keypair from a 64-byte BIP-39 binary seed
     */
    fun deriveFromBip39Seed(seed64: ByteArray): Signer.NearKeyPair {
        require(seed64.size == 64) { "BIP-39 seed must be exactly 64 bytes (got ${seed64.size})" }

        // Master Key: HMAC-SHA512("ed25519 seed", seed64)
        val masterHmac = HMac(SHA512Digest())
        masterHmac.init(KeyParameter(ED25519_SEED_BYTES))
        masterHmac.update(seed64, 0, seed64.size)
        val masterI = ByteArray(64)
        masterHmac.doFinal(masterI, 0)

        var key = masterI.copyOfRange(0, 32)
        var chainCode = masterI.copyOfRange(32, 64)

        // Path segments: m/44'/397'/0'
        val segments = intArrayOf(44, 397, 0)
        for (seg in segments) {
            val index = (seg.toLong() + HARDENED_OFFSET)
            val indexBuffer = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(index.toInt()).array()

            // data = 0x00 || key (32 bytes) || index (4 bytes)
            val data = ByteArray(1 + 32 + 4)
            data[0] = 0x00
            System.arraycopy(key, 0, data, 1, 32)
            System.arraycopy(indexBuffer, 0, data, 33, 4)

            val childHmac = HMac(SHA512Digest())
            childHmac.init(KeyParameter(chainCode))
            childHmac.update(data, 0, data.size)
            val childI = ByteArray(64)
            childHmac.doFinal(childI, 0)

            key = childI.copyOfRange(0, 32)
            chainCode = childI.copyOfRange(32, 64)
        }

        return Signer.keyPairFromSeed(key)
    }
}

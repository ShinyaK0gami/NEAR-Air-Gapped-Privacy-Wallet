package com.nearair.vault

import com.nearair.vault.crypto.Bip39Near
import com.nearair.vault.crypto.Signer
import com.nearair.vault.model.MultiFrameQrCollector
import com.nearair.vault.model.NearAction
import com.nearair.vault.model.TxPayloadCodec
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.math.BigInteger
import java.util.Base64

class VaultCryptoAndBorshTest {

    companion object {
        private const val TEST_SEED_HEX =
            "421155a9d0b1c2d3e4f5061728394a5b6c7d8e9f00112233445566778899aabb"
        private const val EXPECTED_PUB_KEY =
            "ed25519:CHPTGYojFdCgbc3bWd18SnoGEN9iCayRJVjSyqs55nto"
        private const val EXPECTED_UNSIGNED_BORSH_B64 =
            "DQAAAGFsaWNlLnRlc3RuZXQAp6M8mN7ICz4kj03Zg6BdDCobnC7JByJFCEl6q2PWfjBLmAEAAAAAAAsAAABib2IudGVzdG5ldAEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQAAAAMAAIBxZDO2KaM9AQAAAAAA"
        private const val EXPECTED_TX_HASH_B58 =
            "H7yC6x6NuRjNw1FGgQ2kfvG5wCqmF3bbJvjdPzY11us2"
        private const val EXPECTED_SIG_B64 =
            "7xa5o34oWQL4Lma2d5s1/HT7yPzzaq/jyHmAC93N8NxFEMvTS3Zoy1NhxabdXfy08PZgM+gIneATXsrtz2ISBQ=="
        private const val EXPECTED_SIG_B58 =
            "ed25519:5nFRgpJ4YSEZg9TtZin64h3kKiedeFejmTH3VfGA9BoMcBkbQeL4HbDSa1ZeJgmnyjTxu6zobgY1aTpTqk38jGXW"
        private const val EXPECTED_SIGNED_BORSH_B64 =
            "DQAAAGFsaWNlLnRlc3RuZXQAp6M8mN7ICz4kj03Zg6BdDCobnC7JByJFCEl6q2PWfjBLmAEAAAAAAAsAAABib2IudGVzdG5ldAEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQAAAAMAAIBxZDO2KaM9AQAAAAAAAO8WuaN+KFkC+C5mtnebNfx0+8j882qv48h5gAvdzfDcRRDL00t2aMtTYcWm3V38tPD2YDPoCJ3gE17K7c9iEgU="
    }

    @Test
    fun `test standard NEAR BIP39 and SLIP0010 derivation matches official test vectors`() {
        val testPhrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        assertTrue("Vector phrase must pass BIP-39 validation", Bip39Near.validateMnemonic(testPhrase))

        val kp = Bip39Near.deriveNearKeyPair(testPhrase)
        assertEquals(
            "ed25519:6j4b6zUaty6fD1awqcGCCU9JYGCWYUgdJhQrzfZhqE25",
            kp.nearPublicKey
        )
        assertEquals(
            "ed25519:F1kPR175szkGxEL52A9H6Z5ocS2BtaipprK2Hiob9DjGzHTkScrBb1yt44baXPZ3LxyHcsTPdBjHmn6zx147txH",
            kp.nearSecretKey
        )
        assertEquals(
            "5510e2b44cae6eb807e3e0e45d579dda058c274abcba15e5cb84636f5d1ee412",
            kp.implicitAccountId
        )

        // Generate a random 12-word mnemonic and verify it validates
        val generated = Bip39Near.generateMnemonic()
        val words = generated.split(" ")
        assertEquals(12, words.size)
        assertTrue("Generated mnemonic must pass BIP-39 checksum", Bip39Near.validateMnemonic(generated))

        // Check invalid word or checksum failure
        assertFalse(Bip39Near.validateMnemonic("invalidword abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"))
        assertFalse(Bip39Near.validateMnemonic("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon"))
    }

    @Test
    fun `test Ed25519 key derivation and signing matches near-api-js byte-for-byte`() {
        val seed = Signer.hexToBytes(TEST_SEED_HEX)
        val kp = Signer.keyPairFromSeed(seed)
        assertEquals(EXPECTED_PUB_KEY, kp.nearPublicKey)

        // Serialize same Transfer transaction in Kotlin
        val borshBytes = TxPayloadCodec.serializeBorshTransaction(
            signerId = "alice.testnet",
            publicKey = kp.nearPublicKey,
            nonce = 104523L,
            receiverId = "bob.testnet",
            blockHashBase58 = "4vJ9JU1bJJE96FWSJKvHsmmFADCg4gpZQff4P3bkLKi",
            actions = listOf(
                NearAction.Transfer(TxPayloadCodec.nearToYocto("1.5"))
            )
        )

        val actualUnsignedB64 = Base64.getEncoder().encodeToString(borshBytes)
        assertEquals(EXPECTED_UNSIGNED_BORSH_B64, actualUnsignedB64)

        // Sign SHA-256(borshBytes) with Ed25519
        val signedResult = Signer.signTransactionBorsh(borshBytes, seed)
        assertEquals(EXPECTED_TX_HASH_B58, signedResult.txHashBase58)
        assertEquals(EXPECTED_SIG_B64, signedResult.signatureBase64)
        assertEquals(EXPECTED_SIG_B58, signedResult.signatureBase58)
        assertEquals(EXPECTED_SIGNED_BORSH_B64, signedResult.signedBorshBase64)

        // Verify signature directly
        assertTrue(
            Signer.verifyDetached(
                signedResult.txHashBytes,
                signedResult.signatureBytes,
                kp.publicKey32
            )
        )
    }

    @Test
    fun `test Borsh deserialization and security envelope verification catches tampering`() {
        val validEnvelope = JSONObject().apply {
            put("protocol", "near_airgap")
            put("version", "1.0")
            put("networkId", "testnet")
            put("action", "sign_tx")
            put(
                "data",
                JSONObject().apply {
                    put("signerId", "alice.testnet")
                    put("publicKey", EXPECTED_PUB_KEY)
                    put("nonce", 104523L)
                    put("receiverId", "bob.testnet")
                    put("blockHash", "4vJ9JU1bJJE96FWSJKvHsmmFADCg4gpZQff4P3bkLKi")
                    put(
                        "actions",
                        JSONArray().put(
                            JSONObject().apply {
                                put("type", "Transfer")
                                put(
                                    "params",
                                    JSONObject().put("deposit", "1500000000000000000000000")
                                )
                            }
                        )
                    )
                    put("borshSerialized", EXPECTED_UNSIGNED_BORSH_B64)
                }
            )
        }

        val verified = TxPayloadCodec.parseAndVerifyUnsignedQr(validEnvelope.toString())
        assertTrue(verified.borshVerified)
        assertEquals("alice.testnet", verified.transaction.signerId)
        assertEquals("bob.testnet", verified.transaction.receiverId)
        assertEquals("1.5", verified.transaction.totalNearAmount)
        assertEquals("Send 1.5 NEAR to bob.testnet", verified.transaction.primaryActionSummary)

        // Tamper with JSON receiverId while keeping Borsh bytes unchanged
        val tampered = JSONObject(validEnvelope.toString())
        tampered.getJSONObject("data").put("receiverId", "hacker.testnet")

        try {
            TxPayloadCodec.parseAndVerifyUnsignedQr(tampered.toString())
            fail("Expected SecurityException when JSON receiverId disagrees with Borsh bytes")
        } catch (expected: SecurityException) {
            assertTrue(expected.message!!.contains("SECURITY TAMPER ALERT"))
        }
    }

    @Test
    fun `test FunctionCall and AddKey Borsh round-trip serialization`() {
        val fnCallBytes = TxPayloadCodec.serializeBorshTransaction(
            signerId = "alice.testnet",
            publicKey = EXPECTED_PUB_KEY,
            nonce = 777L,
            receiverId = "token.testnet",
            blockHashBase58 = "4vJ9JU1bJJE96FWSJKvHsmmFADCg4gpZQff4P3bkLKi",
            actions = listOf(
                NearAction.FunctionCall(
                    methodName = "ft_transfer",
                    argsBytes = """{"receiver_id":"bob.testnet","amount":"100"}""".toByteArray(),
                    gas = 30_000_000_000_000L,
                    depositYocto = BigInteger.ONE
                ),
                NearAction.AddKey(
                    publicKey = EXPECTED_PUB_KEY,
                    nonce = 0L,
                    isFullAccess = true
                )
            )
        )

        val decoded = TxPayloadCodec.deserializeBorshTransaction(fnCallBytes)
        assertEquals(2, decoded.actions.size)
        val act0 = decoded.actions[0] as NearAction.FunctionCall
        assertEquals("ft_transfer", act0.methodName)
        assertEquals(30_000_000_000_000L, act0.gas)
        assertEquals(BigInteger.ONE, act0.depositYocto)

        val act1 = decoded.actions[1] as NearAction.AddKey
        assertEquals(EXPECTED_PUB_KEY, act1.publicKey)
        assertTrue(act1.isFullAccess)
    }

    @Test
    fun `test multi-frame QR chunking and out-of-order reassembly`() {
        val largeJson = JSONObject().apply {
            put("protocol", "near_airgap")
            put("payload", "X".repeat(2200))
        }.toString()

        val frames = TxPayloadCodec.chunkPayloadToFrames(largeJson, 400)
        assertTrue(frames.size >= 6)

        val collector = MultiFrameQrCollector()
        for (i in frames.indices.reversed()) {
            val status = collector.ingest(frames[i])
            if (i == 0) {
                assertTrue(status.complete)
                assertEquals(largeJson, status.fullPayload)
            } else {
                assertFalse(status.complete)
            }
        }
    }

    @Test
    fun `test AndroidManifest strictly excludes INTERNET and network permissions`() {
        val manifestCandidates = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml")
        )
        val manifestFile = manifestCandidates.firstOrNull { it.exists() }
        assertNotNull("AndroidManifest.xml must exist", manifestFile)

        val manifestText = manifestFile!!.readText()
        assertFalse(
            "AndroidManifest.xml MUST NOT contain android.permission.INTERNET",
            manifestText.contains("android.permission.INTERNET")
        )
        assertFalse(
            "AndroidManifest.xml MUST NOT contain ACCESS_NETWORK_STATE",
            manifestText.contains("ACCESS_NETWORK_STATE")
        )
        assertTrue(
            "AndroidManifest.xml MUST declare android.permission.CAMERA",
            manifestText.contains("android.permission.CAMERA")
        )
    }
}


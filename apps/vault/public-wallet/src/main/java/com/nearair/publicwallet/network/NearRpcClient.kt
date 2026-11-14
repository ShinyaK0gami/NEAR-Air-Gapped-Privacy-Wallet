package com.nearair.publicwallet.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger
import java.util.concurrent.TimeUnit

class NearRpcClient(var network: String = "testnet") {

    val rpcUrls: List<String>
        get() = if (network == "mainnet") {
            listOf(
                "https://rpc.mainnet.near.org",
                "https://free.rpc.fastnear.com"
            )
        } else {
            listOf(
                "https://rpc.testnet.near.org",
                "https://test.rpc.fastnear.com",
                "https://archival-rpc.testnet.near.org"
            )
        }

    val rpcUrl: String
        get() = rpcUrls.first()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    data class AccountBalance(
        val totalNear: Double,
        val availableNear: Double,
        val yoctoNear: String
    )

    data class AccountDetails(
        val exists: Boolean,
        val totalNear: Double = 0.0,
        val yoctoNear: String = "0",
        val codeHash: String = "11111111111111111111111111111111",
        val isContract: Boolean = false,
        val storageUsage: Long = 0L,
        val blockHeight: Long = 0L
    )

    data class BroadcastResult(
        val success: Boolean,
        val txHash: String,
        val status: String,
        val errorMessage: String? = null
    )

    /**
     * Query account balance from NEAR RPC
     */
    suspend fun getAccountBalance(accountId: String): AccountBalance = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", "balance-query")
            put("method", "query")
            put("params", JSONObject().apply {
                put("request_type", "view_account")
                put("finality", "final")
                put("account_id", accountId.trim().lowercase())
            })
        }

        val request = Request.Builder()
            .url(rpcUrl)
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val json = JSONObject(body)
            if (json.has("error")) {
                val err = json.getJSONObject("error")
                val cause = err.optString("message", "Account not found or not initialized yet")
                // Return 0 if not found yet
                return@withContext AccountBalance(0.0, 0.0, "0")
            }
            val result = json.getJSONObject("result")
            val amountYocto = result.getString("amount")
            val nearDouble = BigDecimal(amountYocto).divide(BigDecimal("1000000000000000000000000")).toDouble()
            AccountBalance(
                totalNear = nearDouble,
                availableNear = nearDouble,
                yoctoNear = amountYocto
            )
        }
    }

    /**
     * Query detailed account metadata & contract code status from NEAR RPC
     */
    suspend fun getAccountDetails(accountId: String): AccountDetails = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", "account-details-query")
            put("method", "query")
            put("params", JSONObject().apply {
                put("request_type", "view_account")
                put("finality", "final")
                put("account_id", accountId.trim().lowercase())
            })
        }

        val request = Request.Builder()
            .url(rpcUrl)
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val json = JSONObject(body)
            if (json.has("error")) {
                return@withContext AccountDetails(exists = false)
            }
            val result = json.optJSONObject("result") ?: return@withContext AccountDetails(exists = false)
            val amountYocto = result.optString("amount", "0")
            val nearDouble = try {
                BigDecimal(amountYocto).divide(BigDecimal("1000000000000000000000000")).toDouble()
            } catch (_: Exception) { 0.0 }
            val codeHash = result.optString("code_hash", "11111111111111111111111111111111")
            val isContract = codeHash != "11111111111111111111111111111111"
            val storageUsage = result.optLong("storage_usage", 0L)
            val blockHeight = result.optLong("block_height", 0L)

            AccountDetails(
                exists = true,
                totalNear = nearDouble,
                yoctoNear = amountYocto,
                codeHash = codeHash,
                isContract = isContract,
                storageUsage = storageUsage,
                blockHeight = blockHeight
            )
        }
    }

    /**
     * Query current nonce for the public key
     */
    suspend fun getAccessKeyNonce(accountId: String, publicKey: String): Long = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", "nonce-query")
            put("method", "query")
            put("params", JSONObject().apply {
                put("request_type", "view_access_key")
                put("finality", "final")
                put("account_id", accountId.trim().lowercase())
                put("public_key", publicKey.trim())
            })
        }

        val request = Request.Builder()
            .url(rpcUrl)
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val json = JSONObject(body)
            if (json.has("result")) {
                val result = json.getJSONObject("result")
                return@withContext result.optLong("nonce", 0L)
            }
            0L
        }
    }

    /**
     * Query latest block hash
     */
    suspend fun getLatestBlockHash(): String = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", "block-query")
            put("method", "block")
            put("params", JSONObject().apply {
                put("finality", "final")
            })
        }

        val request = Request.Builder()
            .url(rpcUrl)
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val json = JSONObject(body)
            val header = json.getJSONObject("result").getJSONObject("header")
            header.getString("hash")
        }
    }

    /**
     * Broadcast signed transaction base64 to NEAR RPC with endpoint fallback
     */
    suspend fun broadcastTransaction(signedTxBase64: String): BroadcastResult = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", "broadcast-tx")
            put("method", "broadcast_tx_commit")
            put("params", JSONArray().apply {
                put(signedTxBase64.trim())
            })
        }

        var lastErrorMsg = "Failed to reach NEAR RPC"

        for (endpoint in rpcUrls) {
            try {
                val request = Request.Builder()
                    .url(endpoint)
                    .post(payload.toString().toRequestBody(jsonMediaType))
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    val json = JSONObject(body)

                    if (json.has("error")) {
                        val err = json.get("error")
                        val msg = if (err is JSONObject) {
                            val data = err.optString("data", "")
                            val causeObj = err.optJSONObject("cause")
                            val causeName = causeObj?.optString("name", "") ?: ""
                            val causeInfo = causeObj?.optJSONObject("info")?.toString() ?: ""
                            val message = err.optString("message", "")
                            when {
                                data.isNotEmpty() -> data
                                causeName.isNotEmpty() && causeInfo.isNotEmpty() -> "$causeName: $causeInfo"
                                causeName.isNotEmpty() -> causeName
                                message.isNotEmpty() -> message
                                else -> err.toString()
                            }
                        } else err.toString()
                        return@withContext BroadcastResult(
                            success = false,
                            txHash = "",
                            status = "Failed",
                            errorMessage = msg
                        )
                    }

                    val result = json.optJSONObject("result")
                    if (result != null) {
                        val txObj = result.optJSONObject("transaction")
                        val hash = txObj?.optString("hash") ?: result.optString("transaction_hash", "")
                        val statusObj = result.optJSONObject("status")
                        val isSuccess = statusObj?.has("SuccessValue") == true || statusObj?.has("SuccessReceiptId") == true
                        return@withContext BroadcastResult(
                            success = isSuccess || hash.isNotEmpty(),
                            txHash = hash,
                            status = if (isSuccess) "Success" else "Finalized"
                        )
                    }

                    lastErrorMsg = body.take(200)
                }
            } catch (e: Exception) {
                lastErrorMsg = e.message ?: "Network error"
            }
        }

        BroadcastResult(
            success = false,
            txHash = "",
            status = "Connection Error",
            errorMessage = lastErrorMsg
        )
    }

    data class FaucetResult(
        val success: Boolean,
        val accountId: String,
        val txHash: String? = null,
        val message: String
    )

    /**
     * Reverse account lookup: Find all on-chain account IDs registered to an Ed25519 public key.
     * Uses high-speed FastNEAR Indexer API (supported for both testnet & mainnet).
     */
    suspend fun lookupAccountsByPublicKey(publicKey: String): List<String> = withContext(Dispatchers.IO) {
        val cleanKey = if (publicKey.startsWith("ed25519:")) publicKey else "ed25519:$publicKey"
        val endpoints = if (network == "mainnet") {
            listOf(
                "https://api.fastnear.com/v1/public_key/$cleanKey",
                "https://api.kitwallet.app/publicKey/$cleanKey/accounts"
            )
        } else {
            listOf(
                "https://test.api.fastnear.com/v1/public_key/$cleanKey",
                "https://testnet-api.kitwallet.app/publicKey/$cleanKey/accounts"
            )
        }

        for (url in endpoints) {
            try {
                val request = Request.Builder().url(url).get().build()
                client.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty().trim()
                        if (body.startsWith("[")) {
                            val arr = org.json.JSONArray(body)
                            val result = mutableListOf<String>()
                            for (i in 0 until arr.length()) {
                                val acc = arr.getString(i)
                                if (acc.isNotEmpty()) result.add(acc)
                            }
                            if (result.isNotEmpty()) return@withContext result
                        } else if (body.startsWith("{")) {
                            val json = JSONObject(body)
                            val arr = json.optJSONArray("account_ids")
                            if (arr != null && arr.length() > 0) {
                                val result = mutableListOf<String>()
                                for (i in 0 until arr.length()) {
                                    val acc = arr.getString(i)
                                    if (acc.isNotEmpty()) result.add(acc)
                                }
                                if (result.isNotEmpty()) return@withContext result
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        emptyList()
    }

    /**
     * 1-Click Testnet Account Faucet / Activation.
     * Calls the official NEAR Testnet Helper API to register a named `.testnet` account
     * and fund it with 10 free testnet NEAR on-chain.
     */
    suspend fun activateTestnetAccount(desiredAccountId: String, publicKey: String): FaucetResult = withContext(Dispatchers.IO) {
        try {
            val cleanAcc = desiredAccountId.trim().lowercase()
            val cleanKey = if (publicKey.startsWith("ed25519:")) publicKey else "ed25519:$publicKey"
            val payload = JSONObject().apply {
                put("newAccountId", cleanAcc)
                put("newAccountPublicKey", cleanKey)
            }
            val request = Request.Builder()
                .url("https://helper.testnet.near.org/account")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful) {
                    FaucetResult(
                        success = true,
                        accountId = cleanAcc,
                        message = "Account $cleanAcc successfully created & funded on NEAR Testnet with 10 NEAR!"
                    )
                } else {
                    FaucetResult(
                        success = false,
                        accountId = cleanAcc,
                        message = "Testnet Helper returned ${resp.code}: ${body.take(120)}"
                    )
                }
            }
        } catch (e: Exception) {
            FaucetResult(
                success = false,
                accountId = desiredAccountId,
                message = "Activation error: ${e.message}"
            )
        }
    }
}


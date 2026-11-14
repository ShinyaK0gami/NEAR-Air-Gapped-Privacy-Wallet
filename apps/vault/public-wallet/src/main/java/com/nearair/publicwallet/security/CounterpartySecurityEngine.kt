package com.nearair.publicwallet.security

import com.nearair.publicwallet.model.TxRecord
import com.nearair.publicwallet.network.NearRpcClient

enum class SecurityTier {
    ALLOW,
    WARN,
    BLOCK
}

data class BlocklistEntry(
    val accountId: String,
    val category: String, // DRAINER, PHISHING, PROMPT_INJECTION_HONEYPOT, RANSOMWARE
    val reportId: String,
    val severity: String, // CRITICAL, HIGH
    val evidence: String
)

data class SecurityEvaluation(
    val tier: SecurityTier,
    val score: Int, // 0 to 100
    val summary: String,
    val reasons: List<String>,
    val isContract: Boolean,
    val codeHash: String? = null,
    val isKnownCounterparty: Boolean,
    val isBlocklisted: Boolean,
    val blocklistEvidence: String? = null,
    val accountExists: Boolean = true,
    val accountBalanceNear: Double = 0.0
)

object CounterpartySecurityEngine {

    // Shared community blocklist of reported drainers, prompt-injection honey-pots, and scam contracts
    private val communityBlocklist = mutableMapOf<String, BlocklistEntry>(
        "drainer.testnet" to BlocklistEntry(
            accountId = "drainer.testnet",
            category = "DRAINER",
            reportId = "SEC-2026-4912",
            severity = "CRITICAL",
            evidence = "Reported #4912: Autonomous agent drainer that executes unauthorized ft_transfer callbacks"
        ),
        "drainer.near" to BlocklistEntry(
            accountId = "drainer.near",
            category = "DRAINER",
            reportId = "SEC-2026-4913",
            severity = "CRITICAL",
            evidence = "Known mainnet wallet drainer targeting programmatic agent access keys"
        ),
        "honeypot.testnet" to BlocklistEntry(
            accountId = "honeypot.testnet",
            category = "PROMPT_INJECTION_HONEYPOT",
            reportId = "SEC-2026-8821",
            severity = "CRITICAL",
            evidence = "Prompt-injection honeypot tricking LLMs into transferring gas reserves"
        ),
        "honeypot.near" to BlocklistEntry(
            accountId = "honeypot.near",
            category = "PROMPT_INJECTION_HONEYPOT",
            reportId = "SEC-2026-8822",
            severity = "CRITICAL",
            evidence = "Blacklisted prompt-injection honeypot"
        ),
        "fake-airdrop.testnet" to BlocklistEntry(
            accountId = "fake-airdrop.testnet",
            category = "PHISHING",
            reportId = "SEC-2026-1049",
            severity = "HIGH",
            evidence = "Phishing airdrop claimer executing AddKey FullAccess exploits"
        ),
        "exploit.near" to BlocklistEntry(
            accountId = "exploit.near",
            category = "DRAINER",
            reportId = "SEC-2026-9901",
            severity = "CRITICAL",
            evidence = "Verified on-chain exploit contract"
        ),
        "phish-wallet.testnet" to BlocklistEntry(
            accountId = "phish-wallet.testnet",
            category = "PHISHING",
            reportId = "SEC-2026-3304",
            severity = "HIGH",
            evidence = "Impersonation scam wallet targeting new users"
        ),
        "prompt-inject-drainer.testnet" to BlocklistEntry(
            accountId = "prompt-inject-drainer.testnet",
            category = "PROMPT_INJECTION_HONEYPOT",
            reportId = "SEC-2026-7719",
            severity = "CRITICAL",
            evidence = "Targeted autonomous agent prompt injection honeypot"
        )
    )

    // Curated list of known legitimate ecosystem contracts
    private val verifiedEcosystemContracts = setOf(
        "wrap.testnet", "wrap.near",
        "v2.ref-finance.near", "ref-finance-101.testnet",
        "aurora", "aurora.testnet",
        "app.nearcrowd.near", "staking.paras.near",
        "near", "testnet"
    )

    /**
     * Report an address to the shared blocklist with forensic evidence
     */
    fun reportMaliciousAddress(
        accountId: String,
        category: String,
        evidence: String,
        severity: String = "CRITICAL"
    ) {
        val normalized = accountId.trim().lowercase()
        communityBlocklist[normalized] = BlocklistEntry(
            accountId = normalized,
            category = category,
            reportId = "COMM-${System.currentTimeMillis() % 100000}",
            severity = severity,
            evidence = evidence
        )
    }

    /**
     * Check if an address is on the blocklist
     */
    fun getBlocklistEntry(accountId: String): BlocklistEntry? {
        return communityBlocklist[accountId.trim().lowercase()]
    }

    /**
     * Comprehensive 5-Signal Counterparty Security Evaluation
     *
     * Signal 1: Account age & on-chain activity history
     * Signal 2: Contract code presence & verification status
     * Signal 3: Prior interaction history between this wallet/agent and receiver
     * Signal 4: Shared community blocklist check (instant block on match)
     * Signal 5: Spending limits & high-value anomaly detection
     */
    suspend fun evaluate(
        receiverId: String,
        amountNear: Double,
        rpcClient: NearRpcClient,
        priorTxs: List<TxRecord>,
        spendingThresholdNear: Double = 10.0
    ): SecurityEvaluation {
        val target = receiverId.trim().lowercase()
        val reasons = mutableListOf<String>()

        // ----------------------------------------------------
        // SIGNAL 4: Shared Community Blocklist (Priority 1)
        // ----------------------------------------------------
        val blockEntry = getBlocklistEntry(target)
        if (blockEntry != null) {
            reasons.add("CRITICAL: Target is on the Shared Scam/Drainer Blocklist [${blockEntry.reportId}]")
            reasons.add("Evidence: ${blockEntry.evidence}")
            reasons.add("Category: ${blockEntry.category} (Severity: ${blockEntry.severity})")
            return SecurityEvaluation(
                tier = SecurityTier.BLOCK,
                score = 0,
                summary = "BLOCKED: Confirmed Malicious Counterparty",
                reasons = reasons,
                isContract = false,
                isKnownCounterparty = false,
                isBlocklisted = true,
                blocklistEvidence = blockEntry.evidence,
                accountExists = true
            )
        }

        var score = 100

        // ----------------------------------------------------
        // SIGNAL 1 & 2: Query Account & Contract Code via NEAR RPC
        // ----------------------------------------------------
        val accountDetails = try {
            rpcClient.getAccountDetails(target)
        } catch (_: Exception) {
            null
        }

        val accountExists = accountDetails?.exists == true
        val isContract = accountDetails?.isContract == true
        val codeHash = accountDetails?.codeHash

        if (!accountExists) {
            val isImplicitHex = target.matches(Regex("^[0-9a-fA-F]{64}$"))
            if (isImplicitHex) {
                score -= 25
                reasons.add("Implicit Account: 0 on-chain history (will be created upon first deposit)")
            } else {
                score -= 60
                reasons.add("Non-existent named account: '${target}' was not found on NEAR ${rpcClient.network.uppercase()}. Sending funds may fail.")
            }
        } else {
            reasons.add("Account verified on-chain (${accountDetails?.totalNear ?: 0.0} NEAR balance)")
        }

        // Signal 2: Contract Analysis
        if (isContract) {
            val isVerified = verifiedEcosystemContracts.contains(target)
            if (isVerified) {
                reasons.add("Verified ecosystem contract: '${target}'")
            } else {
                score -= 20
                reasons.add("Executable Smart Contract detected (Code Hash: ${codeHash?.take(10)}...). Ensure you trust contract logic.")
            }
        }

        // ----------------------------------------------------
        // SIGNAL 3: Prior Interactions History
        // ----------------------------------------------------
        val priorCount = priorTxs.count { it.receiverId.equals(target, ignoreCase = true) }
        val isKnownCounterparty = priorCount > 0

        if (isKnownCounterparty) {
            reasons.add("Known counterparty: $priorCount previous successful interaction(s)")
        } else {
            score -= 10
            reasons.add("First interaction: No prior transactions recorded with this address")
        }

        // ----------------------------------------------------
        // SIGNAL 5: Spending Limits & Anomaly Checks
        // ----------------------------------------------------
        if (amountNear > spendingThresholdNear) {
            score -= 25
            reasons.add("High-value transfer: ${amountNear} NEAR exceeds safety review threshold of ${spendingThresholdNear} NEAR")
        }

        // Final Score Clamping
        val finalScore = score.coerceIn(0, 100)

        // Tier Determination
        val tier = when {
            finalScore >= 75 && accountExists -> SecurityTier.ALLOW
            finalScore < 40 && !accountExists && !target.matches(Regex("^[0-9a-fA-F]{64}$")) -> SecurityTier.BLOCK
            else -> SecurityTier.WARN
        }

        val summary = when (tier) {
            SecurityTier.ALLOW -> "Security Passed (Risk Score: $finalScore/100)"
            SecurityTier.WARN -> "Warning: Human Review Advised (Risk Score: $finalScore/100)"
            SecurityTier.BLOCK -> "Transaction Blocked: High Counterparty Risk"
        }

        return SecurityEvaluation(
            tier = tier,
            score = finalScore,
            summary = summary,
            reasons = reasons,
            isContract = isContract,
            codeHash = codeHash,
            isKnownCounterparty = isKnownCounterparty,
            isBlocklisted = false,
            accountExists = accountExists,
            accountBalanceNear = accountDetails?.totalNear ?: 0.0
        )
    }
}


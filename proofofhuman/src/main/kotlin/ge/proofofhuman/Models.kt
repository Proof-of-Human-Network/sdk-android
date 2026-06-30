package ge.proofofhuman

import com.google.gson.annotations.SerializedName

// ── Options ────────────────────────────────────────────────────────────────────

/** Options forwarded with every scan request. */
data class ScanOptions(
    /** Restrict evaluation to these chain IDs (e.g. "1", "137"). */
    val chainIds: List<String>? = null,
    /** On-chain payment transaction hash (paid tier). */
    val txHash: String? = null,
    /** Per-request wallet address override (used for free-tier accounting). */
    val walletAddress: String? = null,
)

/** Polling / streaming behaviour for [POHClient.pollJob] and [POHClient.watchJob]. */
data class PollOptions(
    /** Milliseconds between status-check requests. Default 1500. */
    val intervalMs: Long = 1_500L,
    /** Maximum total wait in milliseconds before throwing. Default 120 s. */
    val timeoutMs: Long = 120_000L,
    /** Invoked on every status snapshot. Runs on the calling coroutine. */
    val onProgress: ((JobStatus) -> Unit)? = null,
)

/** Options for [POHClient.pollBrainVerdict]. */
data class BrainPollOptions(
    /** Milliseconds between brain verdict checks. Default 1500. */
    val intervalMs: Long = 1_500L,
    /** Maximum total wait in milliseconds before throwing. Default 30 s. */
    val timeoutMs: Long = 30_000L,
)

// ── Per-method result ──────────────────────────────────────────────────────────

/** Outcome of one signal-method evaluation for a wallet address. */
data class MethodResult(
    /** The wallet address that was evaluated. */
    val input: String,
    val methodId: String?,
    val description: String?,
    /** `true` = evidence of human activity, `false` = no evidence. */
    val result: Boolean?,
    val error: String?,
)

// ── OFAC sanctions ─────────────────────────────────────────────────────────────

/** Present in [ScanResponse.ofac] when the address is on the OFAC SDN list. */
data class OfacMatch(
    val name:           String,
    val program:        String,
    val chainCode:      String,
    /** `"direct"` = scanned address itself; `"counterparty"` = 1-hop tx partner. */
    val type:           String,
    val matchedAddress: String,
)

// ── Single-address scan ────────────────────────────────────────────────────────

/** Response from [POHClient.scan] (single address, synchronous). */
data class ScanResponse(
    /** Raw per-method results. Aggregate via [POHClient.getBrainVerdict]. */
    val result: List<MethodResult>,
    val count: Int,
    val source: String?,
    /** Pass to [POHClient.getBrainVerdict] once ready. */
    val brainKey: String?,
    val freeScansLeft: Int?,
    /** Set when the address (or a direct counterparty) is on the OFAC SDN list. */
    val ofac: OfacMatch? = null,
)

// ── Bulk scan job ──────────────────────────────────────────────────────────────

/** Reference returned immediately after submitting a bulk scan. */
data class BulkScanRef(
    val jobId: String,
    val status: String,
    val total: Int,
    val pollUrl: String?,
    val freeScansLeft: Int?,
)

/** Full job snapshot from [POHClient.getJob] / [POHClient.pollJob]. */
data class JobStatus(
    val jobId: String,
    /** One of: `queued`, `processing`, `done`, `error`. */
    val status: String,
    val total: Int,
    val done: Int,
    val percent: Int,
    val results: List<MethodResult>,
    val errors: List<String>,
    val createdAt: String,
    val completedAt: String?,
) {
    val isTerminal: Boolean get() = status == "done" || status == "error"
}

// ── AI brain verdict ───────────────────────────────────────────────────────────

/** AI verdict returned by [POHClient.getBrainVerdict] after scan completes. */
data class BrainVerdict(
    /** `pending` | `done` | `error` | `not_found` */
    val status: String,
    /** `"HUMAN"` | `"AI"` | `"UNCERTAIN"` — null while pending */
    val verdict: String?,
    val confidence: Double?,
    val reasoning: String?,
    val signals: List<MethodResult>?,
)

// ── Signal methods ─────────────────────────────────────────────────────────────

/** A registered signal verification method from [POHClient.getMethods]. */
data class Method(
    val id: String,
    /** `evm` | `solana` | `rest` */
    val type: String,
    val description: String,
    val address: String?,
    val method: String?,
    val score: Double,
    val voteCount: Int?,
    @SerializedName("chainId") val chainId: String?,
    val expression: String?,
)

// ── Scan + verdict combined ────────────────────────────────────────────────────

/** Combined result of [POHClient.scanAndVerdict]. */
data class ScanWithVerdict(
    val scan:    ScanResponse,
    val verdict: BrainVerdict,
)

// ── Pricing ────────────────────────────────────────────────────────────────────

data class PricingTier(
    val minAddresses: Int,
    val rate: Double,
    val label: String,
)

data class PricingResponse(
    val count: Int,
    val perAddress: Double,
    val total: Double,
    /** Payment currency — `"USDC/USDT"` */
    val currency: String,
    val tiers: List<PricingTier>,
)

// ── Natural language jobs ──────────────────────────────────────────────────────

data class AskOptions(
    /** Budget in POH units (e.g. 0.5 = 0.5 POH). Converted to μPOH internally. */
    val budget: Double = 0.0,
    /** Wallet address to charge budget from. Required when budget > 0. */
    val walletAddress: String? = null,
    /**
     * PKCS8 PEM Ed25519 private key used to sign the fee payment. Required when
     * budget > 0 — skill jobs always require a fee, and the node rejects the job
     * outright without a valid signed payment proof.
     */
    val privateKeyPem: String? = null,
)

/** Options for submitting a paid compute job (user-specified model + dataset). */
data class ComputeOptions(
    /** Which model to run, e.g. "qwen2.5:1.5b", "llama3.1:8b". */
    val model: String,
    /** Fee in POH (e.g. 0.5 = 0.5 POH). Required — compute jobs are never free. */
    val budget: Double,
    /** Wallet address paying the fee. */
    val walletAddress: String,
    /** PKCS8 PEM Ed25519 private key used to sign the fee payment. */
    val privateKeyPem: String,
    /** Optional Hugging Face dataset id to ground the answer in (must be installed on the node). */
    val dataset: String? = null,
    /** Optional explicit job id. Auto-generated if omitted. */
    val jobId: String? = null,
)

data class AskJobRef(
    val jobId: String,
    val status: String,
    val statusUrl: String?,
    val resultUrl: String?,
    val message: String?,
)

data class AskJobStatus(
    val jobId: String,
    val status: String,
    val error: String?,
    val updatedAt: String?,
)

/** Final result after a natural language job completes. */
data class AskJobResult(
    val jobId: String,
    val status: String,
    /** Raw skill output as a JsonElement. Use .asJsonObject, .asJsonArray etc. */
    val output: com.google.gson.JsonElement?,
    /** Natural language answer generated by the miner's LLM. Present when the job included a question. */
    val nlResponse: String?,
    val skillId: String?,
    val tokensUsed: Int?,
    val error: String?,
)

// ── Node info ──────────────────────────────────────────────────────────────────

/** Metadata about a PoH miner node. */
data class NodeInfo(
    val status: String,
    val nodeId: String?,
    val version: String?,
    val wallet: String?,
    val reputation: Double?,
    val uptime: Long?,
    val peers: Int?,
)

// ── Skills ─────────────────────────────────────────────────────────────────────

/** A skill available on the network. */
data class Skill(
    val id: String,
    val version: String?,
    val description: String?,
    val triggers: List<String>?,
    val feeMin: Long?,
)

// ── Wallet / blockchain ────────────────────────────────────────────────────────

/** Wallet balance returned by [POHClient.getBalance]. */
data class WalletBalance(
    val address: String,
    /** Balance in μPOH (1 POH = 1_000_000_000 μPOH). */
    val balance: Long,
)

/** Account nonce returned by [POHClient.getNonce]. Increment by 1 when building a transaction. */
data class AccountNonce(
    val address: String,
    val nonce: Long,
)

/** A single entry in the transaction history. */
data class TxHistoryEntry(
    val height: Long,
    val delta: Long,
    val txHash: String,
    val ts: Long,
    val label: String,
)

/** Transaction history returned by [POHClient.getTransactionHistory]. */
data class TxHistoryResult(
    val address: String,
    val entries: List<TxHistoryEntry>,
)

/**
 * A signed or unsigned PoH transaction.
 *
 * Build with [POHSigning.buildTransfer], sign with [POHSigning.signTransaction],
 * then submit with [POHClient.submitTransaction].
 */
data class PohTx(
    val from: String,
    val to: String,
    /** Amount in μPOH (1 POH = 1_000_000_000 μPOH). */
    val amount: Long,
    val fee: Long,
    val nonce: Long,
    val timestamp: Long,
    val memo: String,
    val txHash: String? = null,
    val signature: String? = null,
    val signingPublicKey: String? = null,
)

/** Result returned by [POHClient.submitTransaction]. */
data class TxSubmitResult(
    val ok: Boolean,
    val txHash: String,
    val queueSize: Long,
)

/** Result returned by [POHClient.getPendingTransactions]. */
data class PendingTxResult(
    val txs: List<com.google.gson.JsonElement>,
    val count: Long,
)

/** Miner information returned by [POHClient.getMinerInfo]. */
data class MinerInfo(
    val minerAddress: String,
    val gasPrice: Long,
    val model: String,
    val queueLength: Long,
    val reputation: Double,
)

/** An Ed25519 keypair for signing PoH transactions. */
data class KeyPair(
    /** PKCS8 PEM private key. Keep secret — used to sign transactions. */
    val signingPrivateKey: String,
    /** SPKI PEM public key. Register with the node via [POHClient.registerSigningKey]. */
    val signingPublicKey: String,
)

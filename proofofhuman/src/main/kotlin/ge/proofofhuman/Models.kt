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

/** Polling / streaming behaviour for [DAIClient.pollJob] and [DAIClient.watchJob]. */
data class PollOptions(
    /** Milliseconds between status-check requests. Default 1500. */
    val intervalMs: Long = 1_500L,
    /** Maximum total wait in milliseconds before throwing. Default 120 s. */
    val timeoutMs: Long = 120_000L,
    /** Invoked on every status snapshot. Runs on the calling coroutine. */
    val onProgress: ((JobStatus) -> Unit)? = null,
)

/** Options for [DAIClient.pollBrainVerdict]. */
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

/** Response from [DAIClient.scan] (single address, synchronous). */
data class ScanResponse(
    /** Raw per-method results. Aggregate via [DAIClient.getBrainVerdict]. */
    val result: List<MethodResult>,
    val count: Int,
    val source: String?,
    /** Pass to [DAIClient.getBrainVerdict] once ready. */
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

/** Full job snapshot from [DAIClient.getJob] / [DAIClient.pollJob]. */
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

/** AI verdict returned by [DAIClient.getBrainVerdict] after scan completes. */
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

/** A registered signal verification method from [DAIClient.getMethods]. */
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

/** Combined result of [DAIClient.scanAndVerdict]. */
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
    /** Budget in DAI units (e.g. 0.5 = 0.5 DAI). Converted to μDAI internally. */
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

/** Max attachment size accepted by the miner (1 MB). */
const val MAX_ATTACHMENT_BYTES = 1 * 1024 * 1024

/** One file attachment for chat/compute (≤1 MB). Prefer [dataUrl] for images. */
data class ChatAttachment(
    val name: String,
    val mime: String? = null,
    val content: String? = null,
    val contentBase64: String? = null,
    val dataUrl: String? = null,
) {
    fun toMap(): Map<String, Any?> = buildMap {
        put("name", name)
        mime?.let { put("mime", it) }
        content?.let { put("content", it) }
        contentBase64?.let { put("contentBase64", it) }
        dataUrl?.let { put("dataUrl", it) }
    }
}

// ── Fee estimation ────────────────────────────────────────────────────────────

/** Inclusive token bounds. `min == max` when the size is measured exactly. */
data class TokenRange(val min: Long = 0, val max: Long = 0)

/**
 * What to estimate — the same fields a job or chat request carries. Pass the prompt to
 * [DAIClient.estimate]; everything else is optional.
 */
data class EstimateOptions(
    /** `"compute"` (default, a paid job), `"chat"` (OpenAI-style [messages]) or `"skill"`. */
    val type: String? = null,
    /** OpenAI-style messages for `type = "chat"` (use this OR a prompt). */
    val messages: List<Map<String, String>>? = null,
    val history: List<Map<String, String>>? = null,
    /** Text is inlined and measured; images are not billed by the node. */
    val attachments: List<ChatAttachment>? = null,
    val skillId: String? = null,
    /** MCP tool names (`server__tool`) to run in a cascade. */
    val mcp: List<String>? = null,
    /** An installed Hugging Face dataset id — the rows the job would inject are measured. */
    val dataset: String? = null,
    /** Fee currency ticker; null for DAI. Non-DAI fees are quoted off the live P2P book. */
    val currency: String? = null,
    /** Output tokens to reserve (1..4096, default 512). Jobs cap output at 512 regardless. */
    val maxOutputTokens: Int? = null,
    /** `false` skips skill/cascade routing, as on a job. */
    val route: Boolean = true,
    val model: String? = null,
    /** With an address the on-chain public turns the executor merges in are counted too. */
    val requesterAddress: String? = null,
    /** The `/job` payload address, if any (it sets the job fee floor). */
    val address: String? = null,
) {
    internal fun toBody(prompt: String?): MutableMap<String, Any?> = mutableMapOf<String, Any?>().also { b ->
        if (!prompt.isNullOrEmpty()) b["prompt"] = prompt
        type?.let { b["type"] = it }
        messages?.takeIf { it.isNotEmpty() }?.let { b["messages"] = it }
        history?.takeIf { it.isNotEmpty() }?.let { b["history"] = it }
        attachments?.takeIf { it.isNotEmpty() }?.let { b["attachments"] = it.map { a -> a.toMap() } }
        skillId?.let { b["skillId"] = it }
        mcp?.takeIf { it.isNotEmpty() }?.let { b["mcp"] = it }
        dataset?.let { b["dataset"] = it }
        currency?.let { b["currency"] = it }
        maxOutputTokens?.let { b["maxOutputTokens"] = it }
        if (!route) b["route"] = false
        model?.let { b["model"] = it }
        requesterAddress?.let { b["requesterAddress"] = it }
        address?.let { b["address"] = it }
    }
}

/** One contributor to the prompt, tagged by how well its size is known. */
data class EstimateBreakdownItem(
    val id: String = "",
    val kind: String = "",
    val ref: String? = null,
    val tokens: TokenRange = TokenRange(),
    /** `"measured"` (counted exactly), `"bounded"` (capped by the executing code) or `"assumed"`. */
    val basis: String = "",
    val note: String? = null,
)

/** One model call the pipeline makes. */
data class EstimateCall(
    val purpose: String = "",
    val promptTokens: TokenRange = TokenRange(),
    val outputTokens: TokenRange = TokenRange(),
    val basis: String? = null,
    val note: String? = null,
)

/** A fee in one currency. [raw] is null when [unavailable] (nothing quotes that pair). */
data class FeeQuote(
    val tokens: Long = 0,
    /** Raw units of [currency] (μDAI for DAI). */
    val raw: Long? = null,
    val currency: String = "",
    /** The endpoint whose floor this is (set on `minimum`). */
    val gate: String? = null,
    val gasPrice: Double? = null,
    val source: String? = null,
    val via: String? = null,
    val display: Double? = null,
    val unavailable: Boolean = false,
    val message: String? = null,
)

data class EstimateDaiFees(val minimum: FeeQuote = FeeQuote(), val recommended: FeeQuote = FeeQuote())

data class EstimateFees(
    val currency: String = "DAI",
    /** The lowest fee the node accepts — bids below it are rejected. */
    val minimum: FeeQuote = FeeQuote(),
    /** Covers the pipeline's worst case (never below [minimum]). Escrow this. */
    val recommended: FeeQuote = FeeQuote(),
    /** DAI figures, present when [currency] is not DAI. */
    val dai: EstimateDaiFees? = null,
)

data class EstimateTask(
    val id: String = "",
    val kind: String = "",
    val skillId: String? = null,
    val tool: String? = null,
)

data class EstimateRoute(
    /** `"direct"`, `"routed-skill"`, `"cascade"` or `"skill-job"`. */
    val mode: String = "",
    /** True when the plan comes from the deterministic router; the live model-planner may differ. */
    val predicted: Boolean = false,
    val reason: String? = null,
    val skillId: String? = null,
    val tasks: List<EstimateTask>? = null,
)

data class EstimateTokens(
    val prompt: TokenRange = TokenRange(),
    val output: TokenRange = TokenRange(),
    val skillCompute: TokenRange = TokenRange(),
    val total: TokenRange = TokenRange(),
)

data class OutputCap(
    val budgetCapApplies: Boolean = false,
    val tokens: Long? = null,
    val note: String? = null,
)

/** Reply from [DAIClient.estimate] (`POST /api/estimate`). */
data class EstimateResult(
    val type: String = "",
    /** `"job"` (POST /job) or `"chat"` (/v1, /openai/v1) — decides which minimum applies. */
    val target: String = "",
    val model: String = "",
    val currency: String = "DAI",
    val gasPrice: Double = 0.0,
    val route: EstimateRoute = EstimateRoute(),
    val tokens: EstimateTokens = EstimateTokens(),
    val calls: List<EstimateCall> = emptyList(),
    val breakdown: List<EstimateBreakdownItem> = emptyList(),
    val fees: EstimateFees = EstimateFees(),
    val outputCap: OutputCap = OutputCap(),
    val warnings: List<String> = emptyList(),
)

/** Options for free-form chat (`POST /chat/ask`). */
data class ChatOptions(
    val history: List<Map<String, String>>? = null,
    val model: String? = null,
    val privateMode: Boolean = true,
    val attachments: List<ChatAttachment>? = null,
    /** Force a dataset after approving a 412 HF_DATASET_DOWNLOAD_REQUIRED. */
    val datasetId: String? = null,
    val requesterAddress: String? = null,
)

/** Reply from [DAIClient.chat]. */
data class ChatResult(
    val type: String? = null,
    val message: String = "",
    val skill: String? = null,
    val skillId: String? = null,
    val cascade: Boolean = false,
    val tasks: Boolean = false,
    val dataset: String? = null,
    val datasetId: String? = null,
    val fromChainHistory: Boolean = false,
    val code: String? = null,
    val raw: com.google.gson.JsonObject? = null,
)

/** Options for submitting a paid compute job (user-specified model + dataset). */
data class ComputeOptions(
    /** Which model to run, e.g. "qwen3-1.7b", "qwen3vl-2b". */
    val model: String,
    /** Fee in DAI (e.g. 0.5 = 0.5 DAI). Required — compute jobs are never free. */
    val budget: Double,
    /** Wallet address paying the fee. */
    val walletAddress: String,
    /** PKCS8 PEM Ed25519 private key used to sign the fee payment. */
    val privateKeyPem: String,
    /** Optional Hugging Face dataset id to ground the answer in (must be installed on the node). */
    val dataset: String? = null,
    /** Optional explicit job id. Auto-generated if omitted. */
    val jobId: String? = null,
    val history: List<Map<String, String>>? = null,
    val attachments: List<ChatAttachment>? = null,
    /** When false, skip skill/task-cascade auto-routing on the miner. */
    val route: Boolean? = null,
)

/** Installed HF datasets list. */
data class HfDatasetListResult(
    val datasets: List<com.google.gson.JsonElement> = emptyList(),
)

/** MCP status from the miner. */
data class McpStatusResult(
    val servers: List<com.google.gson.JsonElement>? = null,
    val tools: List<com.google.gson.JsonElement>? = null,
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

/** Metadata about a DAI miner node. */
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

/** Wallet balance returned by [DAIClient.getBalance]. */
data class WalletBalance(
    val address: String,
    /** Balance in μDAI (1 DAI = 1_000_000_000 μDAI). */
    val balance: Long,
)

/** Account nonce returned by [DAIClient.getNonce]. Increment by 1 when building a transaction. */
data class AccountNonce(
    val address: String,
    val nonce: Long,
    val pendingNonce: Long? = null,
)

/** A single entry in the transaction history. */
data class TxHistoryEntry(
    val height: Long,
    val delta: Long,
    val txHash: String,
    val ts: Long,
    val label: String,
)

/** Transaction history returned by [DAIClient.getTransactionHistory]. */
data class TxHistoryResult(
    val address: String,
    val entries: List<TxHistoryEntry>,
)

/**
 * A signed or unsigned DAI transaction.
 *
 * Build with [DAISigning.buildTransfer], sign with [DAISigning.signTransaction],
 * then submit with [DAIClient.submitTransaction].
 */
data class DAITx(
    val from: String,
    val to: String,
    /** Amount in μDAI (1 DAI = 1_000_000_000 μDAI). */
    val amount: Long,
    val fee: Long,
    val nonce: Long,
    val timestamp: Long,
    val memo: String,
    val txHash: String? = null,
    val signature: String? = null,
    val signingPublicKey: String? = null,
)

/** Result returned by [DAIClient.submitTransaction]. */
data class TxSubmitResult(
    val ok: Boolean,
    val txHash: String,
    val queueSize: Long,
)

/** Result returned by [DAIClient.getPendingTransactions]. */
data class PendingTxResult(
    val txs: List<com.google.gson.JsonElement>,
    val count: Long,
)

/** Miner information returned by [DAIClient.getMinerInfo]. */
data class MinerInfo(
    val minerAddress: String,
    val gasPrice: Long,
    val model: String,
    val queueLength: Long,
    val reputation: Double,
)

/** An Ed25519 keypair for signing DAI transactions. */
data class KeyPair(
    /** PKCS8 PEM private key. Keep secret — used to sign transactions. */
    val signingPrivateKey: String,
    /** SPKI PEM public key. Register with the node via [DAIClient.registerSigningKey]. */
    val signingPublicKey: String,
    /** Canonical `dai…` address derived from [signingPublicKey]. */
    val address: String,
)

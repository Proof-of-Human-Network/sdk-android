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
    val tiers: List<PricingTier>,
)

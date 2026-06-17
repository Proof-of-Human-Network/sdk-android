package ge.proofofhuman

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.lang.reflect.Type
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Default public network nodes used when no baseUrl/nodes is provided. */
val DEFAULT_NODES: List<String> = listOf(
    "https://bootnode.proofofhuman.ge",
    "https://proofofhuman.ge",
    "https://poh.assetux.com",
)

/**
 * Client for the Proof of Human checker API.
 *
 * ```kotlin
 * // Single node (legacy):
 * val poh = POHClient(baseUrl = "https://proofofhuman.ge", apiKey = "your-key")
 *
 * // Network mode — auto-picks fastest live node:
 * val poh = POHClient(nodes = listOf(
 *     "https://bootnode.proofofhuman.ge",
 *     "https://proofofhuman.ge"
 * ))
 *
 * // Single address
 * val scan = poh.scan("0xabc...")
 * val verdict = poh.getBrainVerdict(scan.brainKey!!)
 * ```
 *
 * @param baseUrl   Single-node base URL (legacy). Takes precedence over [nodes].
 * @param nodes     List of network node URLs to probe; first alive wins.
 *                  Defaults to [DEFAULT_NODES] when both [baseUrl] and [nodes] are null.
 * @param apiKey    Optional API key sent as `x-api-key` header.
 * @param timeoutMs Per-request HTTP timeout in milliseconds.
 */
class POHClient(
    baseUrl: String? = null,
    nodes: List<String>? = null,
    val apiKey: String? = null,
    val timeoutMs: Long = 30_000L,
) {
    private val json: MediaType = "application/json; charset=utf-8".toMediaType()

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .build()

    private val gson: Gson = GsonBuilder().create()

    private val _nodes: List<String> = when {
        baseUrl != null -> listOf(baseUrl.trimEnd('/'))
        nodes   != null -> nodes.map { it.trimEnd('/') }
        else            -> DEFAULT_NODES
    }

    /** URL of the selected node — resolved lazily on first use. */
    private var _resolvedUrl: String? = if (baseUrl != null) baseUrl.trimEnd('/') else null

    /** Probe [url]/healthz with a short timeout; returns true on success. */
    private suspend fun probeNode(url: String): Boolean {
        val req = Request.Builder()
            .url("$url/healthz")
            .head()
            .build()
        return try {
            val res = http.newCall(req).await()
            res.code < 500
        } catch (_: Exception) { false }
    }

    /** Try nodes in order; return the first one that responds. */
    private suspend fun resolveNode(): String {
        _resolvedUrl?.let { return it }
        for (url in _nodes) {
            if (probeNode(url)) { _resolvedUrl = url; return url }
        }
        // All nodes unreachable — fall back to first and let the real request fail naturally
        _resolvedUrl = _nodes.first()
        return _resolvedUrl!!
    }

    /** The URL of the currently selected node, or null before first request. */
    val activeNode: String? get() = _resolvedUrl

    // ── Core HTTP helper ───────────────────────────────────────────────────────

    private suspend fun <T> request(
        method: String,
        path: String,
        responseType: Type,
        body: Any? = null,
    ): T {
        val url = resolveNode() + path

        val requestBody = when {
            body != null -> gson.toJson(body).toRequestBody(json)
            method == "POST" -> "".toRequestBody(json)
            else -> null
        }

        val req = Request.Builder()
            .url(url)
            .method(method, requestBody)
            .apply { apiKey?.let { header("x-api-key", it) } }
            .build()

        val response = try {
            http.newCall(req).await()
        } catch (e: IOException) {
            throw POHException.NetworkException(e)
        }

        val rawBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            val msg = try {
                gson.fromJson(rawBody, JsonObject::class.java)
                    ?.get("error")?.asString ?: rawBody
            } catch (_: Exception) { rawBody }
            throw POHException.HttpException(response.code, msg)
        }

        return try {
            gson.fromJson(rawBody, responseType)
        } catch (e: Exception) {
            throw POHException.DecodingException(e)
        }
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Scan a single wallet address.
     * Returns synchronously with raw method results; call [getBrainVerdict] with
     * [ScanResponse.brainKey] once ready for the aggregated AI verdict.
     */
    suspend fun scan(input: String, options: ScanOptions = ScanOptions()): ScanResponse =
        request(
            "POST", "/checker",
            ScanResponse::class.java,
            buildCheckerBody(listOf(input), options),
        )

    /**
     * Scan multiple addresses as an async job.
     * Poll with [pollJob] or stream progress with [watchJob].
     * @throws POHException.EmptyInputsException if [inputs] is empty.
     */
    suspend fun scanBulk(inputs: List<String>, options: ScanOptions = ScanOptions()): BulkScanRef {
        if (inputs.isEmpty()) throw POHException.EmptyInputsException
        return request(
            "POST", "/checker",
            BulkScanRef::class.java,
            buildCheckerBody(inputs, options),
        )
    }

    /** Fetch the current status of a job without polling. */
    suspend fun getJob(jobId: String): JobStatus =
        request("GET", "/checker/job/$jobId", JobStatus::class.java)

    /**
     * Poll a job until it reaches a terminal state (`done` or `error`).
     * @throws POHException.JobTimedOutException if [PollOptions.timeoutMs] elapses.
     */
    suspend fun pollJob(jobId: String, options: PollOptions = PollOptions()): JobStatus {
        val deadline = System.currentTimeMillis() + options.timeoutMs
        while (true) {
            val status = getJob(jobId)
            options.onProgress?.invoke(status)
            if (status.isTerminal) return status
            if (System.currentTimeMillis() >= deadline) {
                throw POHException.JobTimedOutException(jobId, status.status)
            }
            delay(options.intervalMs)
        }
    }

    /**
     * Emit [JobStatus] snapshots as a [Flow] until the job reaches a terminal state.
     * Cancelling the collector cancels the polling loop.
     */
    fun watchJob(jobId: String, options: PollOptions = PollOptions()): Flow<JobStatus> = flow {
        val deadline = System.currentTimeMillis() + options.timeoutMs
        while (currentCoroutineContext().isActive) {
            val status = getJob(jobId)
            emit(status)
            if (status.isTerminal) return@flow
            if (System.currentTimeMillis() >= deadline) {
                throw POHException.JobTimedOutException(jobId, status.status)
            }
            delay(options.intervalMs)
        }
    }

    /**
     * Submit a bulk scan and block until all results are ready.
     * Convenience wrapper around [scanBulk] + [pollJob].
     */
    suspend fun scanAndWait(
        inputs: List<String>,
        options: ScanOptions = ScanOptions(),
        pollOptions: PollOptions = PollOptions(),
    ): JobStatus {
        val ref = scanBulk(inputs, options)
        return pollJob(ref.jobId, pollOptions)
    }

    /**
     * Retrieve the AI brain verdict for a completed single-address scan.
     * Pass the [ScanResponse.brainKey] returned by [scan].
     * Returns immediately with `status = "pending"` if analysis is still running.
     */
    suspend fun getBrainVerdict(brainKey: String): BrainVerdict =
        request("GET", "/checker/brain/$brainKey", BrainVerdict::class.java)

    /**
     * Poll the brain verdict until `status` leaves `"pending"`, then return it.
     * @throws POHException.JobTimedOutException if [BrainPollOptions.timeoutMs] elapses.
     */
    suspend fun pollBrainVerdict(
        brainKey: String,
        options: BrainPollOptions = BrainPollOptions(),
    ): BrainVerdict {
        val deadline = System.currentTimeMillis() + options.timeoutMs
        while (true) {
            val v = getBrainVerdict(brainKey)
            if (v.status != "pending") return v
            if (System.currentTimeMillis() + options.intervalMs >= deadline) {
                throw POHException.JobTimedOutException(brainKey, v.status)
            }
            delay(options.intervalMs)
        }
    }

    /**
     * Convenience: scan a single address and wait for the AI brain verdict.
     * Returns both the raw [ScanResponse] evidence and the resolved [BrainVerdict].
     */
    suspend fun scanAndVerdict(
        input: String,
        scanOptions: ScanOptions = ScanOptions(),
        brainOptions: BrainPollOptions = BrainPollOptions(),
    ): ScanWithVerdict {
        val scan = scan(input, scanOptions)
        val verdict = scan.brainKey?.let { pollBrainVerdict(it, brainOptions) }
            ?: BrainVerdict(status = "not_found", verdict = null, confidence = null, reasoning = null, signals = null)
        return ScanWithVerdict(scan = scan, verdict = verdict)
    }

    /** Fetch all registered signal verification methods. */
    suspend fun getMethods(): List<Method> =
        request(
            "GET", "/verifyer",
            object : TypeToken<List<Method>>() {}.type,
        )

    /** Fetch pricing for [count] addresses. */
    suspend fun getPricing(count: Int = 1): PricingResponse =
        request("GET", "/checker/pricing?count=$count", PricingResponse::class.java)

    /**
     * Submit a natural language question to the PoH network.
     * Automatically routes the question to the best available skill.
     *
     * Returns immediately with a [AskJobRef]; poll with [pollJobResult] or use [askAndWait].
     */
    suspend fun submitJob(question: String, options: AskOptions = AskOptions()): AskJobRef {
        val maxBudget = (options.budget * 1_000_000_000).toLong()

        // 1. Route to skill
        val routeBody: Map<String, Any?> = buildMap {
            put("message", question)
            put("budget", maxBudget)
        }
        val routeRaw: com.google.gson.JsonObject = request("POST", "/chat/route", com.google.gson.JsonObject::class.java, routeBody)
        val type = routeRaw.get("type")?.asString ?: "chat"
        val skillId = if (routeRaw.has("skillId") && !routeRaw.get("skillId").isJsonNull) routeRaw.get("skillId").asString else null
        if (type != "skill" || skillId == null) {
            throw POHException.HttpException(422, "No skill available for: \"$question\"")
        }
        val skillInput = routeRaw.get("input") ?: com.google.gson.JsonObject()

        // 2. Submit job
        val jobBody: Map<String, Any?> = buildMap {
            put("type", "skill")
            put("skillId", skillId)
            put("payload", skillInput)
            put("maxBudget", maxBudget)
            options.walletAddress?.let { put("requesterAddress", it) }
        }
        return request("POST", "/job", AskJobRef::class.java, jobBody)
    }

    /** Fetch the current status of a job without the full result. */
    suspend fun getJobStatus(jobId: String): AskJobStatus =
        request("GET", "/job/$jobId/status", AskJobStatus::class.java)

    /** Fetch the result of a completed job. Returns status="computing" if not ready yet. */
    suspend fun getJobResult(jobId: String): AskJobResult {
        val raw: com.google.gson.JsonObject = request("GET", "/job/$jobId/result", com.google.gson.JsonObject::class.java)
        val status = raw.get("status")?.asString ?: "computing"
        val profile = if (raw.has("profile") && !raw.get("profile").isJsonNull) raw.getAsJsonObject("profile") else null
        val output = profile?.get("skillOutput")
        val nlResponse = if (profile?.has("nlResponse") == true && !profile.get("nlResponse").isJsonNull)
            profile.get("nlResponse").asString else null
        val skillId = profile?.get("skillId")?.asString
        val tokensUsed = profile?.get("tokensUsed")?.asInt
        return AskJobResult(
            jobId = raw.get("jobId")?.asString ?: jobId,
            status = status,
            output = output,
            nlResponse = nlResponse,
            skillId = skillId,
            tokensUsed = tokensUsed,
            error = if (raw.has("error") && !raw.get("error").isJsonNull) raw.get("error").asString else null,
        )
    }

    /**
     * Poll a job until it reaches terminal state (`done` or `error`).
     * @throws [POHException.JobTimedOutException] if [PollOptions.timeoutMs] elapses.
     */
    suspend fun pollJobResult(
        jobId: String,
        options: PollOptions = PollOptions(),
    ): AskJobResult {
        val deadline = System.currentTimeMillis() + options.timeoutMs
        while (true) {
            val status = getJobStatus(jobId)
            if (status.status == "done" || status.status == "error") {
                return getJobResult(jobId)
            }
            if (System.currentTimeMillis() >= deadline) {
                throw POHException.JobTimedOutException(jobId, status.status)
            }
            delay(options.intervalMs)
        }
    }

    /**
     * Convenience: submit a question and wait for the answer in one call.
     *
     * ```kotlin
     * val result = poh.askAndWait(
     *     "What does vitalik.eth write about on Paragraph?",
     *     AskOptions(budget = 0.5, walletAddress = "poh..."),
     * )
     * println(result.output)
     * ```
     */
    suspend fun askAndWait(
        question: String,
        askOptions: AskOptions = AskOptions(),
        pollOptions: PollOptions = PollOptions(),
    ): AskJobResult {
        val ref = submitJob(question, askOptions)
        return pollJobResult(ref.jobId, pollOptions)
    }

    // ── Node info ──────────────────────────────────────────────────────────────

    /**
     * Fetch metadata about the currently connected node.
     * Returns node ID, version, wallet address, reputation, and peer count.
     */
    suspend fun getNodeInfo(): NodeInfo =
        request("GET", "/healthz", NodeInfo::class.java)

    /**
     * List all skills available on the connected node.
     */
    suspend fun listSkills(): List<Skill> =
        request(
            "GET", "/api/skills",
            object : com.google.gson.reflect.TypeToken<List<Skill>>() {}.type,
        )

    // ── Wallet / blockchain ────────────────────────────────────────────────────

    /** Fetch the POH balance for [address]. Balance is in μPOH (1 POH = 1_000_000_000 μPOH). */
    suspend fun getBalance(address: String): WalletBalance =
        request("GET", "/api/wallet/balance?address=${encode(address)}", WalletBalance::class.java)

    /** Fetch the current nonce for [address]. Increment by 1 when building a transaction. */
    suspend fun getNonce(address: String): AccountNonce =
        request("GET", "/api/wallet/nonce?address=${encode(address)}", AccountNonce::class.java)

    /** Fetch the transaction history for [address]. */
    suspend fun getTransactionHistory(address: String, limit: Int = 30): TxHistoryResult =
        request("GET", "/api/wallet/history?address=${encode(address)}&limit=$limit", TxHistoryResult::class.java)

    /** Fetch all transactions for [address]. */
    suspend fun getTransactions(address: String): com.google.gson.JsonObject =
        request("GET", "/api/wallet/transactions?address=${encode(address)}", com.google.gson.JsonObject::class.java)

    /** Fetch all currently pending transactions in the mempool. */
    suspend fun getPendingTransactions(): PendingTxResult =
        request("GET", "/api/tx/pending", PendingTxResult::class.java)

    /** Submit a pre-signed [PohTx] to the network. */
    suspend fun submitTransaction(tx: PohTx): TxSubmitResult =
        request("POST", "/api/tx/submit", TxSubmitResult::class.java, tx)

    /**
     * Register a signing key for [address] on the node.
     * The [proof] must be `POHSigning.createSigningProof(address, privateKeyPem)`.
     */
    suspend fun registerSigningKey(address: String, signingPublicKey: String, proof: String): com.google.gson.JsonObject {
        val body = mapOf("address" to address, "signingPublicKey" to signingPublicKey, "proof" to proof)
        return request("POST", "/api/wallet/register-key", com.google.gson.JsonObject::class.java, body)
    }

    /** Fetch detailed information about the connected miner node. */
    suspend fun getMinerInfo(): MinerInfo =
        request("GET", "/api/miner/info", MinerInfo::class.java)

    /**
     * Convenience: build, sign, and submit a POH transfer in one call.
     *
     * ```kotlin
     * val result = poh.transfer(
     *     from      = myAddress,
     *     to        = recipientAddress,
     *     amountPoh = 5.0,
     *     keyPair   = myKeyPair,
     * )
     * ```
     */
    suspend fun transfer(
        from: String,
        to: String,
        amountPoh: Double,
        keyPair: KeyPair,
        fee: Long = 0L,
        memo: String = "",
    ): TxSubmitResult {
        val nonceResp = getNonce(from)
        val tx        = POHSigning.buildTransfer(from, to, amountPoh, nonceResp.nonce + 1, fee, memo)
        val signed    = POHSigning.signTransaction(tx, keyPair)
        return submitTransaction(signed)
    }

    // ── Internal helpers ───────────────────────────────────────────────────────

    private fun encode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    private fun buildCheckerBody(inputs: List<String>, options: ScanOptions): Map<String, Any?> =
        buildMap {
            put("input", if (inputs.size == 1) inputs[0] else inputs)
            options.walletAddress?.let { put("walletAddress", it) }
            options.chainIds?.let { put("chainIds", it.joinToString(",")) }
            options.txHash?.let { put("txHash", it) }
            apiKey?.let { put("apiKey", it) }
        }
}

// ── OkHttp coroutine extension ─────────────────────────────────────────────────

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = cont.resume(response)
        override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
    })
    cont.invokeOnCancellation { cancel() }
}

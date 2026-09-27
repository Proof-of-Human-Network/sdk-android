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
    "https://miner.iamai.kg",
    "https://iamai.kg",
)

/**
 * Client for the Decentralized Artificial Intelligence checker API.
 *
 * ```kotlin
 * // Single node (legacy):
 * val dai = DAIClient(baseUrl = "https://iamai.kg", apiKey = "your-key")
 *
 * // Network mode — auto-picks fastest live node:
 * val dai = DAIClient(nodes = listOf(
 *     "https://miner.iamai.kg",
 *     "https://iamai.kg"
 * ))
 *
 * // Single address
 * val scan = dai.scan("0xabc...")
 * val verdict = dai.getBrainVerdict(scan.brainKey!!)
 * ```
 *
 * @param baseUrl   Single-node base URL (legacy). Takes precedence over [nodes].
 * @param nodes     List of network node URLs to probe; first alive wins.
 *                  Defaults to [DEFAULT_NODES] when both [baseUrl] and [nodes] are null.
 * @param apiKey    Optional API key sent as `x-api-key` header.
 * @param timeoutMs Per-request HTTP timeout in milliseconds.
 */
class DAIClient(
    baseUrl: String? = null,
    nodes: List<String>? = null,
    localBaseUrl: String? = null,
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

    private val _localBaseUrl: String? = localBaseUrl?.trimEnd('/')

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

    private fun needsLocalNode(method: String, path: String): Boolean {
        val m = method.uppercase()
        if (m == "GET" || m == "HEAD" || m == "OPTIONS") return false
        val p = path.split("?").first()
        // /gossip and /api/estimate (read-only) may go to any node
        return !(m == "POST" && (p == "/gossip" || p == "/api/estimate"))
    }

    private fun isLoopback(url: String): Boolean {
        val host = url.removePrefix("https://").removePrefix("http://")
            .substringBefore('/').substringBefore(':')
        return host in listOf("localhost", "127.0.0.1", "::1", "[::1]")
    }

    private suspend fun resolveBaseUrl(method: String, path: String): String {
        if (!needsLocalNode(method, path)) return resolveNode()
        _localBaseUrl?.let { return it }
        val remote = resolveNode()
        if (isLoopback(remote)) return remote
        throw DAIException.HttpException(
            403,
            "This operation requires a local miner node. Pass localBaseUrl = \"http://127.0.0.1:3456\".",
        )
    }

    private suspend fun <T> request(
        method: String,
        path: String,
        responseType: Type,
        body: Any? = null,
    ): T {
        val url = resolveBaseUrl(method, path) + path

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
            throw DAIException.NetworkException(e)
        }

        val rawBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            val json = try {
                gson.fromJson(rawBody, JsonObject::class.java)
            } catch (_: Exception) { null }
            val msg = json?.get("error")?.asString ?: rawBody
            throw DAIException.HttpException(response.code, msg, json)
        }

        return try {
            gson.fromJson(rawBody, responseType)
        } catch (e: Exception) {
            throw DAIException.DecodingException(e)
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
     * @throws DAIException.EmptyInputsException if [inputs] is empty.
     */
    suspend fun scanBulk(inputs: List<String>, options: ScanOptions = ScanOptions()): BulkScanRef {
        if (inputs.isEmpty()) throw DAIException.EmptyInputsException
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
     * @throws DAIException.JobTimedOutException if [PollOptions.timeoutMs] elapses.
     */
    suspend fun pollJob(jobId: String, options: PollOptions = PollOptions()): JobStatus {
        val deadline = System.currentTimeMillis() + options.timeoutMs
        while (true) {
            val status = getJob(jobId)
            options.onProgress?.invoke(status)
            if (status.isTerminal) return status
            if (System.currentTimeMillis() >= deadline) {
                throw DAIException.JobTimedOutException(jobId, status.status)
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
                throw DAIException.JobTimedOutException(jobId, status.status)
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
     * @throws DAIException.JobTimedOutException if [BrainPollOptions.timeoutMs] elapses.
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
                throw DAIException.JobTimedOutException(brainKey, v.status)
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
     * Submit a natural language question to the DAI network.
     * Automatically routes the question to the best available skill.
     *
     * Returns immediately with a [AskJobRef]; poll with [pollJobResult] or use [askAndWait].
     *
     * Skill jobs always require a fee — pass [AskOptions.budget], [AskOptions.walletAddress],
     * and [AskOptions.privateKeyPem] so the request can be signed. The node verifies the
     * signature and debits the fee before it will run the job at all.
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
        if (type in setOf("cascade", "tasks", "dataset", "hf-model", "sequence")) {
            throw DAIException.HttpException(
                422,
                "Route type \"$type\" is free (task cascade / dataset / media). Use chat() instead of submitJob().",
                routeRaw,
            )
        }
        val skillId = if (routeRaw.has("skillId") && !routeRaw.get("skillId").isJsonNull) routeRaw.get("skillId").asString else null
        if (type != "skill" || skillId == null) {
            throw DAIException.HttpException(422, "No skill available for: \"$question\"")
        }
        val skillInput = routeRaw.get("input") ?: com.google.gson.JsonObject()

        // 2. Submit job
        val jobId = DAISigning.generateJobId()
        val jobBody: MutableMap<String, Any?> = mutableMapOf(
            "id" to jobId,
            "type" to "skill",
            "skillId" to skillId,
            "payload" to skillInput,
            "maxBudget" to maxBudget,
        )
        options.walletAddress?.let { jobBody["requesterAddress"] = it }

        // Skill jobs always require a fee — sign the payment when budget > 0. No
        // "unverified" fallback: the node rejects the job outright (never runs it)
        // without a valid signed payment proof.
        if (maxBudget > 0) {
            val requester = options.walletAddress
                ?: throw DAIException.HttpException(402, "submitJob: walletAddress is required when budget > 0")
            val privateKeyPem = options.privateKeyPem
                ?: throw DAIException.HttpException(402, "submitJob: privateKeyPem is required when budget > 0 — skill jobs always require a signed fee.")
            val minerInfo = getMinerInfo()
            val nonceInfo = getNonce(requester)
            val (txHash, signature) = DAISigning.signJobPayment(
                jobId, requester, minerInfo.minerAddress, maxBudget, nonceInfo.nonce, privateKeyPem
            )
            jobBody["paymentTx"] = mapOf("txHash" to txHash, "signature" to signature)
        }

        return request("POST", "/job", AskJobRef::class.java, jobBody)
    }

    /**
     * Submit a paid compute job that runs a user-specified model (and, optionally,
     * grounds the answer in a Hugging Face dataset already installed on the node).
     * Compute jobs are never free — the node rejects the request outright unless it
     * carries a valid signed fee payment.
     */
    suspend fun runCompute(prompt: String, options: ComputeOptions): AskJobRef {
        if (options.budget <= 0.0) {
            throw DAIException.HttpException(402, "runCompute: budget must be > 0 — compute jobs always require a fee")
        }
        if (prompt.isEmpty() && options.attachments.isNullOrEmpty()) {
            throw DAIException.HttpException(400, "runCompute: prompt or attachments required")
        }
        val jobId = options.jobId ?: DAISigning.generateJobId()
        val maxBudget = (options.budget * 1_000_000_000).toLong()

        val minerInfo = getMinerInfo()
        val nonceInfo = getNonce(options.walletAddress)
        val (txHash, signature) = DAISigning.signJobPayment(
            jobId, options.walletAddress, minerInfo.minerAddress, maxBudget, nonceInfo.nonce, options.privateKeyPem
        )

        val payload: MutableMap<String, Any?> = mutableMapOf(
            "prompt" to (if (prompt.isEmpty()) "Please analyze the attached file(s)." else prompt),
        )
        options.history?.let { payload["history"] = it }
        options.attachments?.let { payload["attachments"] = it.map { a -> a.toMap() } }
        if (options.route == false) payload["route"] = false

        val jobBody: MutableMap<String, Any?> = mutableMapOf(
            "id" to jobId,
            "type" to "compute",
            "model" to options.model,
            "payload" to payload,
            "maxBudget" to maxBudget,
            "requesterAddress" to options.walletAddress,
            "paymentTx" to mapOf("txHash" to txHash, "signature" to signature),
        )
        options.dataset?.let { jobBody["dataset"] = it }
        if (options.route == false) jobBody["route"] = false

        return request("POST", "/job", AskJobRef::class.java, jobBody)
    }

    /**
     * Free-form chat via `POST /chat/ask` (no fee). Runs task cascade when needed.
     * Attachments ≤1 MB. On HTTP 412 with `code == HF_DATASET_DOWNLOAD_REQUIRED`,
     * call [downloadDataset] then retry with [ChatOptions.datasetId].
     */
    suspend fun chat(message: String, options: ChatOptions = ChatOptions()): ChatResult {
        if (message.isEmpty() && options.attachments.isNullOrEmpty()) {
            throw DAIException.HttpException(400, "chat: message or attachments required")
        }
        val body: MutableMap<String, Any?> = mutableMapOf(
            "message" to (if (message.isEmpty()) "Please analyze the attached file(s)." else message),
            "history" to (options.history ?: emptyList<Map<String, String>>()),
            "private" to options.privateMode,
        )
        options.model?.let { body["model"] = it }
        options.attachments?.let { body["attachments"] = it.map { a -> a.toMap() } }
        options.datasetId?.let { body["datasetId"] = it }
        options.requesterAddress?.let { body["requesterAddress"] = it }

        val raw: JsonObject = request("POST", "/chat/ask", JsonObject::class.java, body)
        return ChatResult(
            type = raw.get("type")?.asString,
            message = raw.get("message")?.asString
                ?: raw.get("reply")?.asString
                ?: "",
            skill = raw.get("skill")?.asString,
            skillId = raw.get("skillId")?.asString,
            cascade = raw.get("cascade")?.asBoolean ?: false,
            tasks = raw.get("tasks")?.asBoolean ?: false,
            dataset = raw.get("dataset")?.asString,
            datasetId = raw.get("datasetId")?.asString,
            fromChainHistory = raw.get("fromChainHistory")?.asBoolean ?: false,
            code = raw.get("code")?.asString,
            raw = raw,
        )
    }

    // ── Fee estimation ─────────────────────────────────────────────────────────

    /**
     * Estimate what a job or chat request will cost — the `eth_estimateGas` of DAI.
     *
     * Send the same fields you would submit (prompt, attachments, a skill, MCP tools, a
     * dataset). The node sizes the whole pipeline — attachment text, skill and MCP output,
     * dataset rows, planner and synthesis calls — and returns the AI tokens it will use, the
     * minimum fee it accepts, and a recommended budget. Read-only: nothing runs or is paid.
     *
     * Sizes that only exist after running (what a skill fetches) come back as [TokenRange]s
     * bounded by the executor's own caps, each tagged `measured` / `bounded` / `assumed`
     * in [EstimateResult.breakdown].
     *
     * Needs a node newer than 0.4.36 (it adds `POST /api/estimate`); older nodes answer 404.
     *
     * ```
     * val est = dai.estimate("Summarise this report",
     *     EstimateOptions(attachments = listOf(ChatAttachment(name = "report.md", content = text))))
     * est.fees.minimum.raw       // μDAI the node accepts, at minimum
     * est.fees.recommended.raw   // μDAI to escrow (covers the worst case)
     * // runCompute takes DAI; estimate returns μDAI:
     * val budgetDai = (est.fees.recommended.raw ?: 0) / 1e9
     * ```
     */
    suspend fun estimate(prompt: String? = null, options: EstimateOptions = EstimateOptions()): EstimateResult {
        val hasBody = !prompt.isNullOrEmpty() || !options.messages.isNullOrEmpty() || !options.attachments.isNullOrEmpty()
        if (!hasBody && !(options.type == "skill" && options.skillId != null)) {
            throw DAIException.HttpException(400, "estimate: prompt, messages or attachments required")
        }
        return request("POST", "/api/estimate", EstimateResult::class.java, options.toBody(prompt))
    }

    /** List Hugging Face datasets installed on the miner. */
    suspend fun listDatasets(): HfDatasetListResult {
        val raw: JsonObject = request("GET", "/api/hf-dataset", JsonObject::class.java)
        val arr = raw.getAsJsonArray("datasets")
        return HfDatasetListResult(datasets = arr?.toList() ?: emptyList())
    }

    /** Download + install a Hugging Face dataset on the miner (row-capped). */
    suspend fun downloadDataset(datasetId: String): JsonObject {
        if (datasetId.isEmpty()) throw DAIException.HttpException(400, "downloadDataset: datasetId required")
        return request(
            "POST",
            "/api/hf-dataset/${encode(datasetId)}/download",
            JsonObject::class.java,
        )
    }

    /** Remove an installed HF dataset from the miner. */
    suspend fun deleteDataset(datasetId: String): JsonObject {
        if (datasetId.isEmpty()) throw DAIException.HttpException(400, "deleteDataset: datasetId required")
        return request("DELETE", "/api/hf-dataset/${encode(datasetId)}", JsonObject::class.java)
    }

    /** Status of configured MCP servers and their tools. */
    suspend fun getMcpStatus(): McpStatusResult {
        val raw: JsonObject = request("GET", "/api/mcp/status", JsonObject::class.java)
        return McpStatusResult(
            servers = raw.getAsJsonArray("servers")?.toList(),
            tools = raw.getAsJsonArray("tools")?.toList(),
        )
    }

    /** Fetch the current status of a job without the full result. */
    suspend fun getJobStatus(jobId: String): AskJobStatus =
        request("GET", "/job/$jobId/status", AskJobStatus::class.java)

    /** Fetch the result of a completed job. Returns status="computing" if not ready yet. */
    suspend fun getJobResult(jobId: String): AskJobResult {
        val raw: com.google.gson.JsonObject = request("GET", "/job/$jobId/result", com.google.gson.JsonObject::class.java)
        val status = raw.get("status")?.asString ?: "computing"
        val profile = if (raw.has("profile") && !raw.get("profile").isJsonNull) raw.getAsJsonObject("profile") else null
        // skill jobs → skillOutput; compute jobs → computeOutput
        val output = profile?.get("skillOutput")
            ?: profile?.get("computeOutput")
        val nlResponse = when {
            profile?.has("nlResponse") == true && !profile.get("nlResponse").isJsonNull ->
                profile.get("nlResponse").asString
            profile?.get("computeOutput")?.isJsonPrimitive == true &&
                profile.get("computeOutput").asJsonPrimitive.isString ->
                profile.get("computeOutput").asString
            else -> null
        }
        val skillId = profile?.get("skillId")?.asString
        val tokensUsed = profile?.get("tokensUsed")?.asInt
        val replyCipher = profile?.get("replyCipher")?.takeUnless { it.isJsonNull }
        val err = if (raw.has("error") && !raw.get("error").isJsonNull) raw.get("error").asString else null
        return AskJobResult(
            jobId = raw.get("jobId")?.asString ?: jobId,
            status = if (status == "computing" && err == null && output != null) "done" else status,
            output = output,
            nlResponse = nlResponse,
            skillId = skillId,
            tokensUsed = tokensUsed,
            encrypted = replyCipher != null && output == null,
            replyCipher = replyCipher,
            error = err,
        )
    }

    /**
     * Poll a job until it reaches terminal state (`done` or `error`).
     * @throws [DAIException.JobTimedOutException] if [PollOptions.timeoutMs] elapses.
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
                throw DAIException.JobTimedOutException(jobId, status.status)
            }
            delay(options.intervalMs)
        }
    }

    /**
     * Convenience: submit a question and wait for the answer in one call.
     *
     * ```kotlin
     * val result = dai.askAndWait(
     *     "What does vitalik.eth write about on Paragraph?",
     *     AskOptions(budget = 0.5, walletAddress = "dai..."),
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
    suspend fun listSkills(): List<Skill> {
        val raw: com.google.gson.JsonElement = request("GET", "/api/skills", com.google.gson.JsonElement::class.java)
        val arr = if (raw.isJsonArray) raw.asJsonArray
                  else raw.asJsonObject.getAsJsonArray("skills")
        return gson.fromJson(arr, object : com.google.gson.reflect.TypeToken<List<Skill>>() {}.type)
    }

    // ── Wallet / blockchain ────────────────────────────────────────────────────

    /** Fetch the DAI balance for [address]. Balance is in μDAI (1 DAI = 1_000_000_000 μDAI). */
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

    /** Submit a pre-signed [DAITx] to the network. */
    suspend fun submitTransaction(tx: DAITx): TxSubmitResult =
        request("POST", "/api/tx/submit", TxSubmitResult::class.java, tx)

    /**
     * Register a signing key for [address] on the node.
     * The [proof] must be `DAISigning.createSigningProof(address, privateKeyPem)`.
     */
    suspend fun registerSigningKey(
        address: String,
        signingPublicKey: String,
        proof: String,
        rotationProof: String? = null,
    ): com.google.gson.JsonObject {
        val body = buildMap {
            put("address", address)
            put("signingPublicKey", signingPublicKey)
            put("proof", proof)
            rotationProof?.let { put("rotationProof", it) }
        }
        return request("POST", "/api/wallet/register-key", com.google.gson.JsonObject::class.java, body)
    }

    /** Register a [KeyPair] from [DAISigning.generateKeyPair]. */
    suspend fun registerKeyPair(keyPair: KeyPair, rotationProof: String? = null): com.google.gson.JsonObject {
        val proof = DAISigning.createSigningProof(keyPair.address, keyPair.signingPrivateKey)
        return registerSigningKey(keyPair.address, keyPair.signingPublicKey, proof, rotationProof)
    }

    /** Fetch detailed information about the connected miner node. */
    suspend fun getMinerInfo(): MinerInfo =
        request("GET", "/api/miner/info", MinerInfo::class.java)

    /**
     * Convenience: build, sign, and submit a DAI transfer in one call.
     *
     * ```kotlin
     * val result = dai.transfer(
     *     from      = myAddress,
     *     to        = recipientAddress,
     *     amountDai = 5.0,
     *     keyPair   = myKeyPair,
     * )
     * ```
     */
    suspend fun transfer(
        from: String,
        to: String,
        amountDai: Double,
        keyPair: KeyPair,
        fee: Long = 0L,
        memo: String = "",
    ): TxSubmitResult {
        val nonceResp = getNonce(from)
        val nextNonce = (nonceResp.pendingNonce ?: nonceResp.nonce) + 1
        val tx        = DAISigning.buildTransfer(from, to, amountDai, nextNonce, fee, memo)
        val signed    = DAISigning.signTransaction(tx, keyPair)
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

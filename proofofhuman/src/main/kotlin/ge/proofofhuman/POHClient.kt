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

/**
 * Client for the Proof of Human checker API.
 *
 * ```kotlin
 * val poh = POHClient(apiKey = "your-key")
 *
 * // Single address
 * val scan = poh.scan("0xabc...")
 * val verdict = poh.getBrainVerdict(scan.brainKey!!)
 *
 * // Bulk — stream progress
 * val ref = poh.scanBulk(listOf("0xaaa...", "0xbbb..."))
 * poh.watchJob(ref.jobId).collect { snapshot -> println("${snapshot.percent}%") }
 * ```
 *
 * @param baseUrl  API base URL without trailing slash.
 * @param apiKey   Optional API key sent as `x-api-key` header.
 * @param timeoutMs Per-request HTTP timeout in milliseconds.
 */
class POHClient(
    val baseUrl: String = "https://api.proofofhuman.com",
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

    // ── Core HTTP helper ───────────────────────────────────────────────────────

    private suspend fun <T> request(
        method: String,
        path: String,
        responseType: Type,
        body: Any? = null,
    ): T {
        val url = baseUrl.trimEnd('/') + path

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

    /** Fetch all registered signal verification methods. */
    suspend fun getMethods(): List<Method> =
        request(
            "GET", "/verifyer",
            object : TypeToken<List<Method>>() {}.type,
        )

    /** Fetch pricing for [count] addresses. */
    suspend fun getPricing(count: Int = 1): PricingResponse =
        request("GET", "/checker/pricing?count=$count", PricingResponse::class.java)

    // ── Internal helpers ───────────────────────────────────────────────────────

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

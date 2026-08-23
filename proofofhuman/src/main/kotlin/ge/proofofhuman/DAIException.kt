package ge.proofofhuman

/** All errors thrown by [DAIClient]. */
sealed class DAIException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /**
     * Server returned a non-2xx status.
     * [json] is the parsed body when available (e.g. 412 HF_DATASET_DOWNLOAD_REQUIRED).
     */
    class HttpException(
        val statusCode: Int,
        val body: String,
        val json: com.google.gson.JsonObject? = null,
    ) : DAIException("HTTP $statusCode: $body")
    /** Network-level failure (no connectivity, DNS, etc.). */
    class NetworkException(cause: Throwable)
        : DAIException("Network error: ${cause.message}", cause)

    /** Job did not finish within [PollOptions.timeoutMs]. */
    class JobTimedOutException(val jobId: String, val lastStatus: String)
        : DAIException("Job \"$jobId\" timed out (last status: $lastStatus)")

    /** [DAIClient.scanBulk] was called with an empty list. */
    object EmptyInputsException : DAIException("inputs list must not be empty")

    /** Response JSON could not be parsed into the expected type. */
    class DecodingException(cause: Throwable)
        : DAIException("Decoding failed: ${cause.message}", cause)
}

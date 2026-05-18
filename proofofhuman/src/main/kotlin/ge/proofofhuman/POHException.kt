package ge.proofofhuman

/** All errors thrown by [POHClient]. */
sealed class POHException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** Server returned a non-2xx status. */
    class HttpException(val statusCode: Int, val body: String)
        : POHException("HTTP $statusCode: $body")

    /** Network-level failure (no connectivity, DNS, etc.). */
    class NetworkException(cause: Throwable)
        : POHException("Network error: ${cause.message}", cause)

    /** Job did not finish within [PollOptions.timeoutMs]. */
    class JobTimedOutException(val jobId: String, val lastStatus: String)
        : POHException("Job \"$jobId\" timed out (last status: $lastStatus)")

    /** [POHClient.scanBulk] was called with an empty list. */
    object EmptyInputsException : POHException("inputs list must not be empty")

    /** Response JSON could not be parsed into the expected type. */
    class DecodingException(cause: Throwable)
        : POHException("Decoding failed: ${cause.message}", cause)
}

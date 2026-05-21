# proofofhuman-android

Android / JVM SDK for the [Proof of Human](https://proofofhuman.ge) API.

## Installation

Add the dependency to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation("ge.proofofhuman:proofofhuman:1.0.0")
}
```

The library requires **Java 11** or higher and ships with OkHttp, Kotlin Coroutines, and Gson as transitive dependencies.

## Quick start

```kotlin
import ge.proofofhuman.POHClient

val poh = POHClient(apiKey = "your-api-key")

// Single address
val scan = poh.scan("0xabc...")
println(scan.brainKey)   // use with getBrainVerdict()

// AI verdict
val verdict = poh.getBrainVerdict(scan.brainKey!!)
println(verdict.verdict)     // true = human, false = bot, null = inconclusive
println(verdict.confidence)  // 0.0 – 1.0
```

All methods are `suspend` — call them from a coroutine scope or `viewModelScope`.

## Bulk scanning

```kotlin
// Submit
val ref = poh.scanBulk(listOf("0xaaa...", "0xbbb...", "0xccc..."))

// Stream progress with a Flow
poh.watchJob(ref.jobId).collect { snapshot ->
    println("${snapshot.percent}% (${snapshot.done}/${snapshot.total})")
}

// Or block until done
val done = poh.pollJob(ref.jobId, PollOptions(
    intervalMs = 2_000L,
    onProgress = { println("${it.percent}%") }
))
done.results.forEach { println("${it.input} → ${it.result}") }
```

### Convenience: submit and wait in one call

```kotlin
val done = poh.scanAndWait(
    inputs = listOf("0xaaa...", "0xbbb..."),
    pollOptions = PollOptions(timeoutMs = 300_000L)
)
```

## Signal methods

```kotlin
val methods = poh.getMethods()
methods.forEach { println("${it.id} (${it.type}) — score ${it.score}") }
```

## Pricing

Flat rate: **$1 per 1,000 scans** ($0.001/scan), paid in USDC or USDT on Solana. First 100 scans per wallet are free.

```kotlin
val price = poh.getPricing(count = 1000)
// price.perAddress == 0.001, price.total == 1000000 (raw USDC/USDT), price.currency == "USDC/USDT"
println("${price.currency}: $${price.total / 1_000_000.0} for ${price.count} scans")
```

## Client options

```kotlin
val poh = POHClient(
    baseUrl   = "https://proofofhuman.ge",  // default
    apiKey    = "sk-...",                          // paid tier
    timeoutMs = 60_000L,                           // per-request timeout
)
```

## Scan options

```kotlin
val scan = poh.scan(
    input = "0xabc...",
    options = ScanOptions(
        chainIds      = listOf("1", "137"),   // restrict to specific chains
        txHash        = "0xdeadbeef...",       // verify a specific transaction
        walletAddress = "YourWallet...",       // free-tier tracking address
    )
)
```

## Poll options

```kotlin
val opts = PollOptions(
    intervalMs  = 2_000L,           // 2 s between polls
    timeoutMs   = 300_000L,         // 5 min total timeout
    onProgress  = { job ->
        println("${job.percent}% — ${job.done}/${job.total} done")
    }
)
```

## Error handling

All errors are subclasses of `POHException`:

```kotlin
try {
    val scan = poh.scan("0xabc...")
} catch (e: POHException.HttpException) {
    println("API error ${e.statusCode}: ${e.body}")
} catch (e: POHException.NetworkException) {
    println("No connection: ${e.message}")
} catch (e: POHException.JobTimedOutException) {
    println("Job ${e.jobId} timed out (was: ${e.lastStatus})")
} catch (e: POHException) {
    println("Error: ${e.message}")
}
```

| Exception | When |
|-----------|------|
| `HttpException(statusCode, body)` | Server returned non-2xx |
| `NetworkException(cause)` | Transport / DNS failure |
| `JobTimedOutException(jobId, lastStatus)` | `pollJob` deadline exceeded |
| `EmptyInputsException` | `scanBulk` called with empty list |
| `DecodingException(cause)` | Response JSON parse failure |

## Android integration example

```kotlin
class WalletViewModel : ViewModel() {
    private val poh = POHClient(apiKey = BuildConfig.POH_API_KEY)

    fun verify(address: String) = viewModelScope.launch {
        try {
            val scan    = poh.scan(address)
            val verdict = poh.getBrainVerdict(scan.brainKey!!)
            _uiState.value = if (verdict.verdict == true) UiState.Human else UiState.Bot
        } catch (e: POHException) {
            _uiState.value = UiState.Error(e.message)
        }
    }
}
```

Store your API key in `local.properties` and expose it via `BuildConfig` — never hardcode it in source.

## Publishing to Maven Central

```bash
./gradlew publishToMavenCentral
```

Requires `signingInMemoryKey`, `signingInMemoryKeyPassword`, `mavenCentralUsername`, and `mavenCentralPassword` in `~/.gradle/gradle.properties`.

## License

Apache License 2.0

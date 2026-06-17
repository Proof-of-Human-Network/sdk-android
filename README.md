# Proof of Human — Android / JVM SDK

Kotlin coroutine-based client for the [Proof of Human](https://proofofhuman.ge) API.
Scan wallet addresses for human-identity signals, query blockchain state, and sign/submit PoH transactions.

---

## Installation

**Gradle (Kotlin DSL)**
```kotlin
dependencies {
    implementation("ge.proofofhuman:proofofhuman:1.3.0")
}
```

**Gradle (Groovy)**
```groovy
dependencies {
    implementation 'ge.proofofhuman:proofofhuman:1.3.0'
}
```

**Maven**
```xml
<dependency>
    <groupId>ge.proofofhuman</groupId>
    <artifactId>proofofhuman</artifactId>
    <version>1.3.0</version>
</dependency>
```

Requires **Java 17+** and **Kotlin coroutines**.

---

## Quick start

```kotlin
import ge.proofofhuman.POHClient
import ge.proofofhuman.ScanOptions

val poh = POHClient(apiKey = "your-api-key")

// Scan a single address
val scan = poh.scan("0xabc123...")

// Get the AI brain verdict (polls until ready)
val verdict = poh.pollBrainVerdict(scan.brainKey!!)
println("${verdict.verdict} (${verdict.confidence})")   // e.g. "HUMAN (0.93)"

// One-shot convenience
val result = poh.scanAndVerdict("0xabc123...")
println(result.verdict.verdict)
```

**Bulk scan**
```kotlin
val job = poh.scanBulk(listOf("0xabc...", "0xdef...", "sol1..."))
val done = poh.pollJob(job.jobId)           // blocks until complete
done.results.forEach { println(it) }
```

**Stream progress**
```kotlin
poh.watchJob(ref.jobId).collect { snapshot ->
    println("${snapshot.percent}% (${snapshot.done}/${snapshot.total})")
}
```

---

## Natural language jobs

```kotlin
import ge.proofofhuman.AskOptions

// Block until the answer arrives
val answer = poh.askAndWait(
    "What does vitalik.eth write about on Paragraph?",
    AskOptions(budget = 0.5, walletAddress = "poh1abc..."),
)
println(answer.nlResponse)
println(answer.output)          // raw skill output as JsonElement

// Or fire-and-poll manually
val ref = poh.submitJob(
    "What does vitalik.eth write about on Paragraph?",
    AskOptions(budget = 0.5, walletAddress = "poh1abc..."),
)
val result = poh.pollJobResult(ref.jobId)
```

---

## Wallet / blockchain

```kotlin
// Balance (in μPOH — divide by 1_000_000_000 for whole POH)
val bal = poh.getBalance("poh1abc...")
println("${bal.balance / 1_000_000_000.0} POH")

// Nonce (current value; increment by 1 when building a tx)
val nonceResp = poh.getNonce("poh1abc...")
println("nonce: ${nonceResp.nonce}")

// Transaction history
val history = poh.getTransactionHistory("poh1abc...", limit = 20)
history.entries.forEach { entry ->
    println("${entry.txHash}  delta=${entry.delta}  label=${entry.label}")
}

// Pending mempool transactions
val pending = poh.getPendingTransactions()
println("${pending.count} pending txs")
```

---

## Signing and transactions

### 1. Generate a keypair

```kotlin
import ge.proofofhuman.POHSigning

val keyPair = POHSigning.generateKeyPair()
// keyPair.signingPrivateKey — PKCS8 PEM, keep secret
// keyPair.signingPublicKey  — SPKI PEM, share with node
```

### 2. Register the public key with the node

```kotlin
val proof = POHSigning.createSigningProof(myAddress, keyPair.signingPrivateKey)
poh.registerSigningKey(myAddress, keyPair.signingPublicKey, proof)
```

### 3. Build, sign, and submit a transaction

```kotlin
// Build an unsigned transfer (amountPoh is in whole POH units)
val nonceResp = poh.getNonce(myAddress)
val tx = POHSigning.buildTransfer(
    from       = myAddress,
    to         = recipientAddress,
    amountPoh  = 5.0,                   // 5 POH = 5_000_000_000 μPOH
    nonce      = nonceResp.nonce + 1,
    fee        = 0L,
    memo       = "payment",
)

// Sign with the keypair
val signed = POHSigning.signTransaction(tx, keyPair)

// Submit
val result = poh.submitTransaction(signed)
println("txHash: ${result.txHash}  queueSize: ${result.queueSize}")
```

### Convenience: one-call transfer

```kotlin
val result = poh.transfer(
    from      = myAddress,
    to        = recipientAddress,
    amountPoh = 5.0,
    keyPair   = keyPair,
    memo      = "payment",
)
println("txHash: ${result.txHash}")
```

### Sign with explicit PEM strings

```kotlin
val signed = POHSigning.signTransaction(tx, privateKeyPem, publicKeyPem)
```

### Low-level: compute tx hash

```kotlin
val hash = POHSigning.computeTxHash(
    from = myAddress, to = recipient,
    amount = 5_000_000_000L, fee = 0L,
    nonce = 42L, timestamp = System.currentTimeMillis(),
    memo = "",
)
```

---

## Node info

```kotlin
// Miner details
val info = poh.getMinerInfo()
println("miner=${info.minerAddress}  gasPrice=${info.gasPrice}  queue=${info.queueLength}")

// All available skills
val skills = poh.listSkills()
skills.forEach { println("${it.id}  ${it.description}") }

// Basic node health
val node = poh.getNodeInfo()
println("node=${node.nodeId}  version=${node.version}  peers=${node.peers}")
```

---

## Multi-node setup

The client probes nodes in order and uses the first one that responds. This happens
automatically on the first request.

```kotlin
val poh = POHClient(
    nodes = listOf(
        "https://bootnode.proofofhuman.ge",
        "https://proofofhuman.ge",
        "https://poh.assetux.com",
    ),
    apiKey = "your-api-key",
)

// Which node is active after the first request?
println(poh.activeNode)
```

The default node list (used when neither `baseUrl` nor `nodes` is supplied) is:
- `https://bootnode.proofofhuman.ge`
- `https://proofofhuman.ge`
- `https://poh.assetux.com`

---

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

---

## Publishing to Maven Central

```bash
./gradlew publishToMavenCentral
```

Requires `signing.key`, `signing.password`, `mavenCentralUsername`, and `mavenCentralPassword`
in `~/.gradle/gradle.properties`.

---

## License

Apache License 2.0

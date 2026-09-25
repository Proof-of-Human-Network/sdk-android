# Decentralized Artificial Intelligence — Android / JVM SDK

Kotlin coroutine-based client for the [Decentralized Artificial Intelligence](https://iamai.kg) API.
Scan wallet addresses for human-identity signals, query blockchain state, and sign/submit DAI transactions.

---

## Installation

**Gradle (Kotlin DSL)**
```kotlin
dependencies {
    implementation("ge.proofofhuman:proofofhuman:1.5.2")
}
```

**Gradle (Groovy)**
```groovy
dependencies {
    implementation 'ge.proofofhuman:proofofhuman:1.5.2'
}
```

**Maven**
```xml
<dependency>
    <groupId>ge.proofofhuman</groupId>
    <artifactId>proofofhuman</artifactId>
    <version>1.5.2</version>
</dependency>
```

Requires **Java 17+** and **Kotlin coroutines**.

---

## Quick start

```kotlin
import ge.proofofhuman.POHClient
import ge.proofofhuman.ScanOptions

val dai = POHClient(apiKey = "your-api-key")

// Scan a single address
val scan = dai.scan("0xabc123...")

// Get the AI brain verdict (polls until ready)
val verdict = dai.pollBrainVerdict(scan.brainKey!!)
println("${verdict.verdict} (${verdict.confidence})")   // e.g. "HUMAN (0.93)"

// One-shot convenience
val result = dai.scanAndVerdict("0xabc123...")
println(result.verdict.verdict)
```

**Bulk scan**
```kotlin
val job = dai.scanBulk(listOf("0xabc...", "0xdef...", "sol1..."))
val snap = dai.getJob(job.jobId)            // single snapshot
val done = dai.pollJob(job.jobId)           // blocks until complete
done.results.forEach { println(it) }
```

**Stream progress**
```kotlin
dai.watchJob(ref.jobId).collect { snapshot ->
    println("${snapshot.percent}% (${snapshot.done}/${snapshot.total})")
}
```

---

## Natural language jobs

Skill jobs always require a fee — pass `budget`, `walletAddress`, and
`privateKeyPem` on `AskOptions` so the SDK can sign the payment. The node
verifies the signature and debits the fee before it will run the job at all;
it rejects the request outright (no job ever runs) without a valid signed
payment.

```kotlin
import ge.proofofhuman.AskOptions

// Block until the answer arrives
val answer = dai.askAndWait(
    "What does vitalik.eth write about on Paragraph?",
    AskOptions(budget = 0.5, walletAddress = "dai1abc...", privateKeyPem = myPrivateKey),
)
println(answer.nlResponse)
println(answer.output)          // raw skill output as JsonElement

// Or fire-and-poll manually
val ref = dai.submitJob(
    "What does vitalik.eth write about on Paragraph?",
    AskOptions(budget = 0.5, walletAddress = "dai1abc...", privateKeyPem = myPrivateKey),
)
val status = dai.getJobStatus(ref.jobId)    // lightweight status check
val result = dai.getJobResult(ref.jobId)    // full result once done
val result = dai.pollJobResult(ref.jobId)   // or poll until it arrives
```

## Compute jobs (your own model + dataset)

Run inference with a model of your choice, optionally grounded in a Hugging
Face dataset already installed on the node. Like skill jobs, compute jobs are
never free — `runCompute` always signs a fee payment.

```kotlin
import ge.proofofhuman.ComputeOptions

val ref = dai.runCompute("Summarize the top 5 rows", ComputeOptions(
    model = "llama3.1:8b",
    dataset = "some-org/some-dataset", // optional
    budget = 0.5,                      // DAI
    walletAddress = "dai1abc...",
    privateKeyPem = myPrivateKey,
))
val result = dai.pollJobResult(ref.jobId)
println(result.output)
```

Before either of these will work, the wallet's signing key must be registered
with the node once via `registerSigningKey(...)` — the node has no way to
verify a signature for a key it has never seen.

---

## Estimating a job's fee

Before paying for a job, ask the node what it will cost — the `eth_estimateGas` of
DAI. Send the same fields you would submit (prompt, attachments, a skill, MCP tools, a
dataset). The node sizes the whole pipeline — attachment text, skill and MCP output, dataset
rows, planner and synthesis calls — and returns the AI tokens it will use, the **minimum fee
it accepts**, and a **recommended budget**. It is read-only: nothing runs and nothing is paid.

```kotlin
val est = dai.estimate(
    "Summarize this report and compare it with the latest news",
    EstimateOptions(
        attachments = listOf(ChatAttachment(name = "report.md", content = reportText)), // text is inlined and measured
        // skillId = "web_search", mcp = listOf("shop__search"), dataset = "some-org/some-dataset",
        // currency = "aiKGS", maxOutputTokens = 512, route = false,
    ),
)

est.fees.minimum.raw        // Long? — μDAI the node will accept, at minimum
est.fees.recommended.raw    // μDAI to escrow — covers the worst case
est.tokens.total            // TokenRange(min, max)
est.breakdown               // what each part contributed, and how sure that is

// runCompute takes DAI; estimate returns μDAI
val budgetDai = (est.fees.recommended.raw ?: 0L) / 1e9
```

What comes back:

| Field | Meaning |
|---|---|
| `tokens` | `prompt`, `output`, `skillCompute` and `total`, each a `{min, max}` range |
| `breakdown` | Every contributor, tagged `measured` (counted exactly — prompt, attachment text, dataset rows), `bounded` (capped by the executing code — what a skill fetched, an MCP tool returned) or `assumed` |
| `calls` | Each model call the pipeline makes (planner, skill answer, synthesis…) |
| `fees.minimum` | The lowest fee the node accepts — bids below it are rejected (`/job` floors at a fixed amount, chat at the prompt alone) |
| `fees.recommended` | Covers the pipeline's worst case, never below the minimum. Escrow this |
| `route` | Which pipeline would run; `predicted: true` means it comes from the deterministic router — the live model-planner may choose differently |
| `outputCap`, `warnings` | Whether the budget caps output, and anything unusual (images are not billed; job output is capped at 512 tokens) |

Amounts are in raw units of the fee currency (**μDAI** for DAI; 1 DAI = 1e9 μDAI), so divide by
1e9 for `runCompute`'s `budget`. For a non-DAI `currency` the price is quoted off the live P2P
book; if nothing quotes that pair the quote says `unavailable` instead of inventing a number,
and a DAI figure is returned alongside.

Needs a node newer than 0.4.36 (it adds `POST /api/estimate`); older nodes answer 404.
`estimate` is read-only, so — unlike the other POST methods — it works against remote nodes
without a `localBaseUrl`.

## Wallet / blockchain

```kotlin
// Balance (in μDAI — divide by 1_000_000_000 for whole DAI)
val bal = dai.getBalance("dai1abc...")
println("${bal.balance / 1_000_000_000.0} DAI")

// Nonce (current value; increment by 1 when building a tx)
val nonceResp = dai.getNonce("dai1abc...")
println("nonce: ${nonceResp.nonce}")

// Transaction history
val history = dai.getTransactionHistory("dai1abc...", limit = 20)
history.entries.forEach { entry ->
    println("${entry.txHash}  delta=${entry.delta}  label=${entry.label}")
}

// Raw transactions for an address (untyped JsonObject)
val txs = dai.getTransactions("dai1abc...")

// Pending mempool transactions
val pending = dai.getPendingTransactions()
println("${pending.count} pending txs")
```

---

## Signing and transactions

### 1. Generate a keypair

```kotlin
import ge.proofofhuman.DAISigning

val keyPair = DAISigning.generateKeyPair()
// keyPair.signingPrivateKey — PKCS8 PEM, keep secret
// keyPair.signingPublicKey  — SPKI PEM, share with node
```

### 2. Register the public key with the node

```kotlin
val proof = DAISigning.createSigningProof(myAddress, keyPair.signingPrivateKey)
dai.registerSigningKey(myAddress, keyPair.signingPublicKey, proof)

// Or in one call — uses keyPair.address and builds the proof itself
dai.registerKeyPair(keyPair)

// The address a keypair maps to (from its SPKI PEM public key)
val addr = DAISigning.deriveAddressFromSigningKey(keyPair.signingPublicKey)
```

**Rotating a key** — replacing an already-registered key requires a rotation
proof signed with the *old* private key:

```kotlin
val proof = DAISigning.createRotationProof(myAddress, newKeyPair.signingPublicKey, oldPrivateKeyPem)
dai.registerKeyPair(newKeyPair, rotationProof = proof)
```

### 3. Build, sign, and submit a transaction

```kotlin
// Build an unsigned transfer (amountDai is in whole DAI units)
val nonceResp = dai.getNonce(myAddress)
val tx = DAISigning.buildTransfer(
    from       = myAddress,
    to         = recipientAddress,
    amountDai  = 5.0,                   // 5 DAI = 5_000_000_000 μDAI
    nonce      = nonceResp.nonce + 1,
    fee        = 0L,
    memo       = "payment",
)

// Sign with the keypair
val signed = DAISigning.signTransaction(tx, keyPair)

// Submit
val result = dai.submitTransaction(signed)
println("txHash: ${result.txHash}  queueSize: ${result.queueSize}")
```

### Convenience: one-call transfer

```kotlin
val result = dai.transfer(
    from      = myAddress,
    to        = recipientAddress,
    amountDai = 5.0,
    keyPair   = keyPair,
    memo      = "payment",
)
println("txHash: ${result.txHash}")
```

`transfer` is pending-aware: it uses `pendingNonce + 1` when the account has
transactions waiting in the mempool (falling back to `nonce + 1`), so back-to-back
transfers don't collide.

### Sign with explicit PEM strings

```kotlin
val signed = DAISigning.signTransaction(tx, privateKeyPem, publicKeyPem)
```

### Low-level: compute tx hash

```kotlin
val hash = DAISigning.computeTxHash(
    from = myAddress, to = recipient,
    amount = 5_000_000_000L, fee = 0L,
    nonce = 42L, timestamp = System.currentTimeMillis(),
    memo = "",
)
```

### Job fee payments

Used internally by `submitJob`/`runCompute`; exposed for custom flows. The
payment hash binds the fee to one specific job + miner + amount + nonce.

```kotlin
val jobId = DAISigning.generateJobId()   // "job-<millis>-<8 hex>"; fix it before signing
val hash  = DAISigning.computeJobPaymentHash(jobId, myAddress, minerAddress, 500L, nonce)
val (txHash, signature) = DAISigning.signJobPayment(
    jobId, myAddress, minerAddress, 500L, nonce, keyPair.signingPrivateKey,
)
// txHash + signature go in the `paymentTx` field of a POST /job request
```

---

## Node info

```kotlin
// Miner details
val info = dai.getMinerInfo()
println("miner=${info.minerAddress}  gasPrice=${info.gasPrice}  queue=${info.queueLength}")

// All available skills
val skills = dai.listSkills()
skills.forEach { println("${it.id}  ${it.description}") }

// Basic node health
val node = dai.getNodeInfo()
println("node=${node.nodeId}  version=${node.version}  peers=${node.peers}")

// Signal verification methods
val methods = dai.getMethods()
methods.forEach { println(it.id) }

// Scan pricing (currency is "USDC/USDT")
val pricing = dai.getPricing(count = 100)
println("${pricing.total} ${pricing.currency} (${pricing.perAddress}/address)")
```

---

## Multi-node setup

The client probes nodes in order and uses the first one that responds. This happens
automatically on the first request.

```kotlin
val dai = POHClient(
    nodes = listOf(
        "https://miner.iamai.kg",
        "https://iamai.kg",
        "https://miner.iamai.kg",
    ),
    apiKey = "your-api-key",
)

// Which node is active after the first request?
println(dai.activeNode)
```

The default node list (used when neither `baseUrl` nor `nodes` is supplied) is:
- `https://miner.iamai.kg`
- `https://iamai.kg`
- `https://miner.iamai.kg`

### Local miner routing

Write operations (any non-GET request except `POST /gossip`) must go to a node
you control. Pass `localBaseUrl` to route them to your local miner while reads
still use the public nodes; without it, writes to a non-loopback node fail with
a 403 `HttpException` explaining the requirement.

```kotlin
val dai = POHClient(localBaseUrl = "http://127.0.0.1:3456")
```

---

## Chat encryption (ChatCrypto)

End-to-end encryption for chat payloads (X25519 + HKDF + AES-256-GCM),
compatible with the node's envelope format.

```kotlin
import ge.proofofhuman.ChatCrypto

// Deterministic X25519 keypair from a stable secret (ByteArray or String)
val kp = ChatCrypto.deriveEncryptionKeypair(stableSecret)

// Encrypt for a recipient (plaintext as String or ByteArray)
val env = ChatCrypto.seal(recipient.publicKeyB64, "hello")   // SealedEnvelope

// Decrypt an envelope
val plaintext = ChatCrypto.open(env, kp.privateKeyB64)

// Cheap shape check for incoming payloads
if (ChatCrypto.isEnvelope(v, epk, ct)) { /* it's a sealed envelope */ }
```

---

## Error handling

All errors are subclasses of `DAIException`:

```kotlin
try {
    val scan = dai.scan("0xabc...")
} catch (e: DAIException.HttpException) {
    println("API error ${e.statusCode}: ${e.body}")
} catch (e: DAIException.NetworkException) {
    println("No connection: ${e.message}")
} catch (e: DAIException.JobTimedOutException) {
    println("Job ${e.jobId} timed out (was: ${e.lastStatus})")
} catch (e: DAIException) {
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

## Stablecoins (multi-currency) — protocol notes

The chain now carries five regional stablecoins alongside DAI: `aiGEL`,
`aiKGS`, `aiAMD`, `aiETB`, `aiBTN` (displayed αιGEL …). They use **2 decimals**
(1 unit = 100 raw); DAI keeps 9 (1 DAI = 1e9 μDAI).

Wire protocol (implement when adding native support to this SDK):

- `DAITransaction` gains an optional `currency` field. **Hash preimage rule:**
  `currency` is appended after `memo` in the signed JSON payload ONLY when
  non-DAI — a DAI transaction hashes byte-identically to the historical shape
  and must NOT carry the key at all.
- Job payment hash: `currency` is the SIXTH key of
  `{jobId,requesterAddress,minerAddress,amount,nonce,currency}` ONLY when
  non-DAI.
- `GET /api/assets` lists the registry (tickers, decimals, display names, gas
  prices). `GET /api/wallet/balance` adds `assets: { ticker: {raw, display} }`.
- Job payloads accept `currency`; the miner receives exactly the currency paid.

Native Swift/Kotlin bindings for these fields are NOT yet implemented in this
SDK — see sdk-js (reference implementation) for exact semantics.

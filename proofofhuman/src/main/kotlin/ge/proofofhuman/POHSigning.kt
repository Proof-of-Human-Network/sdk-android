package ge.proofofhuman

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * Ed25519 signing utilities for PoH transactions.
 *
 * Key format: PKCS8 PEM private key, SPKI PEM public key — matching the PoH node
 * and the JS/Python SDKs.
 */
object POHSigning {

    // ── PEM helpers ───────────────────────────────────────────────────────────

    private fun pemToBytes(pem: String): ByteArray {
        val stripped = pem
            .lines()
            .filter { !it.startsWith("-----") }
            .joinToString("")
        return Base64.getDecoder().decode(stripped)
    }

    private fun bytesToPem(bytes: ByteArray, type: String): String {
        val b64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(bytes)
        return "-----BEGIN $type-----\n$b64\n-----END $type-----\n"
    }

    // ── Key generation ────────────────────────────────────────────────────────

    /**
     * Generate a fresh Ed25519 keypair compatible with the PoH node.
     *
     * ```kotlin
     * val kp = POHSigning.generateKeyPair()
     * poh.registerSigningKey(myAddress, kp.signingPublicKey, POHSigning.createSigningProof(myAddress, kp.signingPrivateKey))
     * ```
     */
    fun generateKeyPair(): KeyPair {
        val gen = KeyPairGenerator.getInstance("Ed25519")
        val kp  = gen.generateKeyPair()
        return KeyPair(
            signingPrivateKey = bytesToPem(kp.private.encoded, "PRIVATE KEY"),
            signingPublicKey  = bytesToPem(kp.public.encoded,  "PUBLIC KEY"),
        )
    }

    // ── Signing ───────────────────────────────────────────────────────────────

    /**
     * Sign an arbitrary UTF-8 message with an Ed25519 private key (PKCS8 PEM).
     * Returns a base64-encoded signature, matching the PoH node's signature format.
     */
    fun signData(message: String, privateKeyPem: String): String {
        val kf   = KeyFactory.getInstance("Ed25519")
        val spec = PKCS8EncodedKeySpec(pemToBytes(privateKeyPem))
        val key  = kf.generatePrivate(spec)
        val sig  = Signature.getInstance("Ed25519")
        sig.initSign(key)
        sig.update(message.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(sig.sign())
    }

    /**
     * Build the proof needed by [POHClient.registerSigningKey].
     *
     * The proof is a base64 signature of the wallet address, proving ownership
     * of the private key corresponding to the public key being registered.
     */
    fun createSigningProof(walletAddress: String, privateKeyPem: String): String =
        signData(walletAddress, privateKeyPem)

    // ── Transaction ───────────────────────────────────────────────────────────

    /**
     * Escape a string for embedding in the canonical JSON payload below, matching
     * JavaScript's `JSON.stringify` escaping exactly (`"`, `\`, and control characters).
     * Required for [computeTxHash] to byte-for-byte match the node's own hash — the node
     * recomputes this hash from the transaction's fields and rejects any transaction
     * whose `txHash` doesn't match, so any escaping divergence (e.g. an unescaped `"`
     * in a memo) would make the resulting transaction unsendable.
     */
    private fun jsonEscape(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.toString()
    }

    /** Compute the SHA-256 transaction hash over canonical fields. Returns hex string. */
    fun computeTxHash(
        from: String,
        to: String,
        amount: Long,
        fee: Long,
        nonce: Long,
        timestamp: Long,
        memo: String,
    ): String {
        val canonical = """{"from":"${jsonEscape(from)}","to":"${jsonEscape(to)}","amount":$amount,"fee":$fee,"nonce":$nonce,"timestamp":$timestamp,"memo":"${jsonEscape(memo)}"}"""
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    // ── Job fee payment ──────────────────────────────────────────────────────

    /**
     * Compute the canonical payment hash for a job fee. Binds the fee to one specific
     * job + miner + amount + nonce, so a signature over it can't be replayed against a
     * different job or a higher budget. Must byte-for-byte match the node's own
     * `computeJobPaymentHash`.
     */
    fun computeJobPaymentHash(
        jobId: String,
        requesterAddress: String,
        minerAddress: String,
        amount: Long,
        nonce: Long,
    ): String {
        val canonical = """{"jobId":"${jsonEscape(jobId)}","requesterAddress":"${jsonEscape(requesterAddress)}","minerAddress":"${jsonEscape(minerAddress)}","amount":$amount,"nonce":$nonce}"""
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * Sign a fee payment authorizing a fee-required job (skill execution, or a
     * model/dataset compute job). The result goes in the `paymentTx` field of a
     * `POST /job` request — the node verifies the signature and debits the
     * requester's balance before it will run the job at all.
     */
    fun signJobPayment(
        jobId: String,
        requesterAddress: String,
        minerAddress: String,
        amount: Long,
        nonce: Long,
        privateKeyPem: String,
    ): Pair<String, String> {
        val txHash = computeJobPaymentHash(jobId, requesterAddress, minerAddress, amount, nonce)
        val signature = signData(txHash, privateKeyPem)
        return txHash to signature
    }

    /**
     * Generate a client-side job id (`job-<millis>-<8 random hex chars>`), matching
     * the shape the node itself would generate if `id` were omitted from the request.
     * Fee-required jobs must fix the id before signing, since the payment proof is
     * bound to it.
     */
    fun generateJobId(): String {
        val millis = System.currentTimeMillis()
        val suffix = ByteArray(4).also { java.security.SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        return "job-$millis-$suffix"
    }

    /**
     * Build an unsigned PoH transfer transaction.
     *
     * @param from       Sender address (`poh...`).
     * @param to         Recipient address.
     * @param amountPoh  Amount in POH (e.g. 1.5 → 1_500_000_000 μPOH).
     * @param nonce      Sender's current nonce + 1. Fetch via [POHClient.getNonce].
     * @param fee        Miner fee in μPOH (default 0).
     * @param memo       Optional memo string.
     */
    fun buildTransfer(
        from: String,
        to: String,
        amountPoh: Double,
        nonce: Long,
        fee: Long = 0L,
        memo: String = "",
    ): PohTx {
        val amount    = (amountPoh * 1_000_000_000).toLong()
        val timestamp = System.currentTimeMillis()
        val txHash    = computeTxHash(from, to, amount, fee, nonce, timestamp, memo)
        return PohTx(
            from      = from,
            to        = to,
            amount    = amount,
            fee       = fee,
            nonce     = nonce,
            timestamp = timestamp,
            memo      = memo,
            txHash    = txHash,
        )
    }

    /**
     * Sign a transaction built with [buildTransfer].
     *
     * After signing, submit via [POHClient.submitTransaction].
     *
     * @param tx      Unsigned transaction from [buildTransfer].
     * @param keyPair Ed25519 keypair from [generateKeyPair] (both keys required).
     */
    fun signTransaction(tx: PohTx, keyPair: KeyPair): PohTx {
        require(tx.txHash != null) { "tx.txHash is null — call buildTransfer() first" }
        val signature = signData(tx.txHash, keyPair.signingPrivateKey)
        return tx.copy(
            signature        = signature,
            signingPublicKey = keyPair.signingPublicKey,
        )
    }

    /**
     * Sign a transaction using explicit PEM keys.
     *
     * @param tx            Unsigned transaction from [buildTransfer].
     * @param privateKeyPem PKCS8 PEM private key.
     * @param publicKeyPem  SPKI PEM public key (must match the private key).
     */
    fun signTransaction(tx: PohTx, privateKeyPem: String, publicKeyPem: String): PohTx {
        require(tx.txHash != null) { "tx.txHash is null — call buildTransfer() first" }
        val signature = signData(tx.txHash, privateKeyPem)
        return tx.copy(
            signature        = signature,
            signingPublicKey = publicKeyPem,
        )
    }
}

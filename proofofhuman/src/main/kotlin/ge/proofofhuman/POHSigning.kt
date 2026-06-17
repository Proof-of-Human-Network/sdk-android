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
        val canonical = """{"from":"$from","to":"$to","amount":$amount,"fee":$fee,"nonce":$nonce,"timestamp":$timestamp,"memo":"$memo"}"""
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
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

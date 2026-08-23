package ge.proofofhuman

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.Base64
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters

/**
 * chat-crypto — portable public-job chat encryption for the DAI Android/JVM SDK.
 *
 * Public compute jobs are raced by miners the requester doesn't control, so the on-chain
 * record of the prompt/reply is sealed to the requester's X25519 key:
 *
 *     X25519 (ECDH) -> HKDF-SHA256 -> AES-256-GCM
 *
 * Byte-identical to the node reference (dai-miner src/security/chat-crypto.js, verified
 * round-trip) and the JS/Python/Rust SDKs. See CHAT-CRYPTO.md for the wire format.
 */
object ChatCrypto {
    private val SEAL_INFO = "dai-chat-seal-v1".toByteArray()
    private val SCALAR_INFO = "dai-x25519-v1".toByteArray()
    private val rng = SecureRandom()

    /** A wallet's raw 32-byte X25519 encryption keypair (base64). */
    data class EncryptionKeypair(val publicKeyB64: String, val privateKeyB64: String)

    /** A sealed chat envelope (all fields base64). */
    data class SealedEnvelope(
        val v: Int,
        val alg: String,
        val epk: String,
        val iv: String,
        val ct: String,
    )

    private fun b64e(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
    private fun b64d(s: String): ByteArray = Base64.getDecoder().decode(s)

    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, len: Int): ByteArray {
        val gen = HKDFBytesGenerator(SHA256Digest())
        gen.init(HKDFParameters(ikm, salt, info))
        val out = ByteArray(len)
        gen.generateBytes(out, 0, len)
        return out
    }

    private fun deriveKey(shared: ByteArray, recipientPub: ByteArray, epk: ByteArray): ByteArray =
        hkdf(shared, recipientPub + epk, SEAL_INFO, 32)

    /**
     * Deterministically derive the wallet's X25519 keypair from a stable secret (its
     * ed25519 signing private key PEM), matching the node.
     */
    fun deriveEncryptionKeypair(stableSecret: ByteArray): EncryptionKeypair {
        val scalar = hkdf(stableSecret, ByteArray(0), SCALAR_INFO, 32)
        val priv = X25519PrivateKeyParameters(scalar, 0)
        return EncryptionKeypair(b64e(priv.generatePublicKey().encoded), b64e(scalar))
    }

    fun deriveEncryptionKeypair(stableSecret: String): EncryptionKeypair =
        deriveEncryptionKeypair(stableSecret.toByteArray())

    /** Seal a plaintext to a recipient's raw X25519 public key (base64). */
    fun seal(recipientPubB64: String, plaintext: ByteArray): SealedEnvelope {
        val recipientRaw = b64d(recipientPubB64)
        require(recipientRaw.size == 32) { "recipient X25519 pubkey must be 32 bytes" }
        val recipientPub = X25519PublicKeyParameters(recipientRaw, 0)

        val eskBytes = ByteArray(32).also { rng.nextBytes(it) }
        val esk = X25519PrivateKeyParameters(eskBytes, 0)
        val epk = esk.generatePublicKey().encoded

        val shared = ByteArray(32)
        X25519Agreement().apply { init(esk); calculateAgreement(recipientPub, shared, 0) }
        val key = deriveKey(shared, recipientRaw, epk)

        val iv = ByteArray(12).also { rng.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(plaintext) // ciphertext || 16-byte tag
        return SealedEnvelope(1, "x25519-hkdf-sha256-aes256gcm", b64e(epk), b64e(iv), b64e(ct))
    }

    fun seal(recipientPubB64: String, plaintext: String): SealedEnvelope =
        seal(recipientPubB64, plaintext.toByteArray())

    /** Open an envelope with the recipient's raw X25519 private scalar (base64). */
    fun open(env: SealedEnvelope, privateScalarB64: String): String {
        require(env.v == 1) { "unsupported chat-crypto envelope" }
        val priv = X25519PrivateKeyParameters(b64d(privateScalarB64), 0)
        val recipientPub = priv.generatePublicKey().encoded
        val epkRaw = b64d(env.epk)

        val shared = ByteArray(32)
        X25519Agreement().apply { init(priv); calculateAgreement(X25519PublicKeyParameters(epkRaw, 0), shared, 0) }
        val key = deriveKey(shared, recipientPub, epkRaw)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, b64d(env.iv)))
        return String(cipher.doFinal(b64d(env.ct)), Charsets.UTF_8)
    }

    fun isEnvelope(v: Int, epk: String?, ct: String?): Boolean = v == 1 && epk != null && ct != null
}

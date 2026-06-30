package ge.proofofhuman

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class POHSigningTest {

    // ── computeTxHash ────────────────────────────────────────────────────────

    @Test
    fun `computeTxHash returns 64-char hex`() {
        val h = POHSigning.computeTxHash("pohA", "pohB", 1_000_000_000L, 0L, 1L, 1_700_000_000_000L, "")
        assertEquals(64, h.length)
        assertTrue(h.all { it.isDigit() || it in 'a'..'f' })
    }

    @Test
    fun `computeTxHash is deterministic`() {
        val h1 = POHSigning.computeTxHash("pohA", "pohB", 1_000_000_000L, 0L, 1L, 1_700_000_000_000L, "")
        val h2 = POHSigning.computeTxHash("pohA", "pohB", 1_000_000_000L, 0L, 1L, 1_700_000_000_000L, "")
        assertEquals(h1, h2)
    }

    @Test
    fun `computeTxHash differs for different amounts`() {
        val h1 = POHSigning.computeTxHash("pohA", "pohB", 1_000_000_000L, 0L, 1L, 1_700_000_000_000L, "")
        val h2 = POHSigning.computeTxHash("pohA", "pohB", 2_000_000_000L, 0L, 1L, 1_700_000_000_000L, "")
        assertNotEquals(h1, h2)
    }

    /**
     * Fixed value computed by the node's own algorithm — `crypto.createHash('sha256')
     * .update(JSON.stringify({from,to,amount,fee,nonce,timestamp,memo})).digest('hex')` —
     * for these exact inputs. The node recomputes and verifies this hash server-side
     * (WalletManager.applyTransaction), so any mismatch here means real transactions
     * built by this library would be silently rejected. Same fixture used in the Rust
     * and iOS SDKs.
     */
    @Test
    fun `computeTxHash matches node reference value`() {
        val h = POHSigning.computeTxHash("pohA", "pohB", 1_000_000_000L, 5L, 3L, 1_700_000_000_000L, "hello")
        assertEquals("e309a41e0c088876f2763f8d01ae434ff060bd4391202d555be1d96ee0f14c8a", h)
    }

    /**
     * A memo containing JSON-special characters must be escaped the same way
     * JavaScript's `JSON.stringify` would escape it, or the hash silently diverges
     * from what the node (re)computes and the transaction is rejected.
     */
    @Test
    fun `computeTxHash escapes special characters in memo`() {
        val memo = "say \"hi\"\\new\nline"
        val h = POHSigning.computeTxHash("pohA", "pohB", 1L, 0L, 1L, 1L, memo)
        assertEquals(64, h.length)

        // The unescaped-interpolation bug this guards against would produce a *different*
        // hash than the properly-escaped one — assert it doesn't equal the hash of the
        // naively-interpolated (broken) payload.
        val naive = """{"from":"pohA","to":"pohB","amount":1,"fee":0,"nonce":1,"timestamp":1,"memo":"$memo"}"""
        val naiveHash = MessageDigest.getInstance("SHA-256")
            .digest(naive.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertNotEquals(naiveHash, h, "expected properly-escaped JSON to differ from the naive/unescaped interpolation for a memo containing special characters")
    }

    // ── computeJobPaymentHash ────────────────────────────────────────────────

    @Test
    fun `computeJobPaymentHash returns 64-char hex`() {
        val h = POHSigning.computeJobPaymentHash("job-1", "pohA", "pohMiner", 500_000_000L, 0L)
        assertEquals(64, h.length)
        assertTrue(h.all { it.isDigit() || it in 'a'..'f' })
    }

    @Test
    fun `computeJobPaymentHash is deterministic`() {
        val h1 = POHSigning.computeJobPaymentHash("job-1", "pohA", "pohMiner", 500_000_000L, 0L)
        val h2 = POHSigning.computeJobPaymentHash("job-1", "pohA", "pohMiner", 500_000_000L, 0L)
        assertEquals(h1, h2)
    }

    /**
     * Fixed value computed by the node's own algorithm for these exact inputs — see
     * `computeJobPaymentHash` in miner-node.js. The node recomputes and verifies this
     * hash server-side before debiting the requester, so any mismatch here means real
     * jobs submitted by this library would be rejected outright. Same fixture used in
     * the JS, Python, Rust, and iOS SDKs.
     */
    @Test
    fun `computeJobPaymentHash matches node reference value`() {
        val h = POHSigning.computeJobPaymentHash("job-abc", "pohAlice", "pohMiner", 500_000_000L, 3L)
        assertEquals("1ed86280c1ab64d60d55a232a1c339299d32d8bd45e5f2bf26ff72b26d8908c0", h)
    }

    @Test
    fun `signJobPayment returns txHash and signature`() {
        val kp = POHSigning.generateKeyPair()
        val (txHash, signature) = POHSigning.signJobPayment("job-1", "pohA", "pohMiner", 500_000_000L, 0L, kp.signingPrivateKey)
        assertEquals(POHSigning.computeJobPaymentHash("job-1", "pohA", "pohMiner", 500_000_000L, 0L), txHash)
        assertTrue(signature.isNotEmpty())
    }
}

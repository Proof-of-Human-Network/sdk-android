package ge.proofofhuman

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertFailsWith

class ChatCryptoTest {
    @Test
    fun deterministicKeypair() {
        val a = ChatCrypto.deriveEncryptionKeypair("same")
        val b = ChatCrypto.deriveEncryptionKeypair("same")
        assertEquals(a.publicKeyB64, b.publicKeyB64)
        assertNotEquals(ChatCrypto.deriveEncryptionKeypair("diff").publicKeyB64, a.publicKeyB64)
    }

    @Test
    fun roundTrip() {
        val kp = ChatCrypto.deriveEncryptionKeypair("kotlin-secret")
        val env = ChatCrypto.seal(kp.publicKeyB64, "hello kotlin")
        assertEquals("hello kotlin", ChatCrypto.open(env, kp.privateKeyB64))
    }

    // Byte-compat with the node reference: this envelope was produced by the node's
    // src/security/chat-crypto.js for deriveEncryptionKeypair("rust-interop").
    @Test
    fun opensNodeSealedEnvelope() {
        val kp = ChatCrypto.deriveEncryptionKeypair("rust-interop")
        assertEquals("KEuWmZUz5CWxn2QsMVq2ViPk6AQw5ZpFP7KYwiraiRs=", kp.publicKeyB64)
        val env = ChatCrypto.SealedEnvelope(
            1, "x25519-hkdf-sha256-aes256gcm",
            "iEPANh2KxCPlu4HC29mjejV2w9WWRZQMKLv/jaWWX2Q=",
            "ulySPK2YUEhQsL2X",
            "eUxra8/2d5RYGWoBwCCM6C7o5SjZPmtiVHislyZRzhMqRc73eERb",
        )
        assertEquals("hello from node to rust", ChatCrypto.open(env, kp.privateKeyB64))
    }

    @Test
    fun wrongKeyFails() {
        val kp = ChatCrypto.deriveEncryptionKeypair("a")
        val other = ChatCrypto.deriveEncryptionKeypair("b")
        val env = ChatCrypto.seal(kp.publicKeyB64, "x")
        assertFailsWith<Exception> { ChatCrypto.open(env, other.privateKeyB64) }
    }
}

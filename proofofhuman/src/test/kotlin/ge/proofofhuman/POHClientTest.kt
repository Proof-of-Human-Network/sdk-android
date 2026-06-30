package ge.proofofhuman

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class POHClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: POHClient

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = POHClient(baseUrl = server.url("/").toString().trimEnd('/'))
    }

    @AfterTest
    fun tearDown() = server.shutdown()

    // ── scan ──────────────────────────────────────────────────────────────────

    @Test
    fun `scan parses ScanResponse`() = runTest {
        server.enqueue(MockResponse().setBody("""
            {
              "result": [
                {"input":"0xabc","methodId":"m1","description":"Check","result":true,"error":null}
              ],
              "count": 1,
              "source": "processed",
              "brainKey": "0xabc",
              "freeScansLeft": 9
            }
        """.trimIndent()).setResponseCode(200))

        val resp = client.scan("0xabc")
        assertEquals(1, resp.count)
        assertEquals("0xabc", resp.brainKey)
        assertEquals(9, resp.freeScansLeft)
        assertTrue(resp.result.first().result == true)
    }

    @Test
    fun `scan throws HttpException on 401`() = runTest {
        server.enqueue(MockResponse().setBody("""{"error":"Invalid API key"}""").setResponseCode(401))
        val ex = assertFailsWith<POHException.HttpException> { client.scan("0xabc") }
        assertEquals(401, ex.statusCode)
        assertTrue("Invalid API key" in ex.body)
    }

    // ── scanBulk ──────────────────────────────────────────────────────────────

    @Test
    fun `scanBulk parses BulkScanRef`() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"jobId":"job-1","status":"queued","total":2,"pollUrl":"/checker/job/job-1","freeScansLeft":8}
        """.trimIndent()).setResponseCode(200))

        val ref = client.scanBulk(listOf("0xaaa", "0xbbb"))
        assertEquals("job-1", ref.jobId)
        assertEquals("queued", ref.status)
        assertEquals(2, ref.total)
    }

    @Test
    fun `scanBulk throws EmptyInputsException on empty list`() = runTest {
        assertFailsWith<POHException.EmptyInputsException> {
            client.scanBulk(emptyList())
        }
    }

    // ── getJob ────────────────────────────────────────────────────────────────

    @Test
    fun `getJob deserialises JobStatus`() = runTest {
        server.enqueue(MockResponse().setBody("""
            {
              "jobId":"job-1","status":"done","total":2,"done":2,"percent":100,
              "results":[
                {"input":"0xaaa","methodId":"m1","description":"D","result":true,"error":null},
                {"input":"0xbbb","methodId":"m1","description":"D","result":false,"error":null}
              ],
              "errors":[],"createdAt":"2024-01-01T00:00:00Z","completedAt":"2024-01-01T00:00:05Z"
            }
        """.trimIndent()).setResponseCode(200))

        val job = client.getJob("job-1")
        assertTrue(job.isTerminal)
        assertEquals(100, job.percent)
        assertEquals(2, job.results.size)
    }

    // ── pollJob ───────────────────────────────────────────────────────────────

    @Test
    fun `pollJob returns when job is done`() = runTest {
        // First call: processing, second call: done
        server.enqueue(MockResponse().setBody("""
            {"jobId":"j","status":"processing","total":1,"done":0,"percent":0,
             "results":[],"errors":[],"createdAt":"2024-01-01T00:00:00Z","completedAt":null}
        """.trimIndent()))
        server.enqueue(MockResponse().setBody("""
            {"jobId":"j","status":"done","total":1,"done":1,"percent":100,
             "results":[{"input":"0xabc","methodId":"m1","description":"D","result":true,"error":null}],
             "errors":[],"createdAt":"2024-01-01T00:00:00Z","completedAt":"2024-01-01T00:00:02Z"}
        """.trimIndent()))

        val options = PollOptions(intervalMs = 0L, timeoutMs = 10_000L)
        val final = client.pollJob("j", options)
        assertEquals("done", final.status)
    }

    @Test
    fun `pollJob throws JobTimedOutException on timeout`() = runTest {
        repeat(10) {
            server.enqueue(MockResponse().setBody("""
                {"jobId":"j","status":"processing","total":1,"done":0,"percent":0,
                 "results":[],"errors":[],"createdAt":"2024-01-01T00:00:00Z","completedAt":null}
            """.trimIndent()))
        }
        val ex = assertFailsWith<POHException.JobTimedOutException> {
            client.pollJob("j", PollOptions(intervalMs = 0L, timeoutMs = 1L))
        }
        assertEquals("j", ex.jobId)
    }

    // ── watchJob ──────────────────────────────────────────────────────────────

    @Test
    fun `watchJob emits snapshots and terminates`() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"jobId":"j","status":"processing","total":1,"done":0,"percent":50,
             "results":[],"errors":[],"createdAt":"2024-01-01T00:00:00Z","completedAt":null}
        """.trimIndent()))
        server.enqueue(MockResponse().setBody("""
            {"jobId":"j","status":"done","total":1,"done":1,"percent":100,
             "results":[{"input":"0xabc","methodId":"m1","description":"D","result":true,"error":null}],
             "errors":[],"createdAt":"2024-01-01T00:00:00Z","completedAt":"2024-01-01T00:00:01Z"}
        """.trimIndent()))

        val snapshots = client.watchJob("j", PollOptions(intervalMs = 0L)).toList()
        assertEquals(2, snapshots.size)
        assertFalse(snapshots.first().isTerminal)
        assertTrue(snapshots.last().isTerminal)
    }

    // ── getBrainVerdict ───────────────────────────────────────────────────────

    @Test
    fun `getBrainVerdict parses pending status`() = runTest {
        server.enqueue(MockResponse().setBody("""{"status":"pending"}"""))
        val v = client.getBrainVerdict("0xabc")
        assertEquals("pending", v.status)
    }

    @Test
    fun `getBrainVerdict parses done verdict`() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"status":"done","verdict":"HUMAN","confidence":0.87,"reasoning":"Strong on-chain activity.","signals":[]}
        """.trimIndent()))
        val v = client.getBrainVerdict("0xabc")
        assertEquals("done", v.status)
        assertEquals("HUMAN", v.verdict)
        assertEquals(0.87, v.confidence)
        assertNotNull(v.reasoning)
    }

    // ── getMethods ────────────────────────────────────────────────────────────

    @Test
    fun `getMethods parses list of methods`() = runTest {
        server.enqueue(MockResponse().setBody("""
            [{"id":"m1","type":"evm","description":"ERC-20 balance","address":"0xtoken",
              "method":"balanceOf","score":1.0,"voteCount":5,"chainId":"1","expression":"result[0] > 0"}]
        """.trimIndent()))

        val methods = client.getMethods()
        assertEquals(1, methods.size)
        assertEquals("evm", methods.first().type)
        assertEquals("1", methods.first().chainId)
    }

    // ── getNodeInfo ───────────────────────────────────────────────────────────

    @Test
    fun `getNodeInfo parses node metadata`() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"status":"ok","nodeId":"node-42","version":"1.2.0","walletAddress":"poh123","peers":3}
        """.trimIndent()))

        val info = client.getNodeInfo()
        assertEquals("node-42", info.nodeId)
        assertEquals("1.2.0", info.version)
        assertEquals(3, info.peers)
    }

    // ── listSkills ────────────────────────────────────────────────────────────

    @Test
    fun `listSkills parses skill array`() = runTest {
        server.enqueue(MockResponse().setBody("""
            [{"id":"sk-1","description":"Summariser","triggers":["summarise"],"version":"1.0"}]
        """.trimIndent()))

        val skills = client.listSkills()
        assertEquals(1, skills.size)
        assertEquals("sk-1", skills.first().id)
        assertEquals("Summariser", skills.first().description)
    }

    // ── getMinerInfo ──────────────────────────────────────────────────────────

    @Test
    fun `getMinerInfo parses miner metadata`() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"minerAddress":"poh-miner-1","gasPrice":1000,"model":"llama-3","queueLength":2,"reputation":4.5}
        """.trimIndent()))

        val info = client.getMinerInfo()
        assertEquals("poh-miner-1", info.minerAddress)
        assertEquals("llama-3", info.model)
        assertEquals(2, info.queueLength)
    }

    // ── getBalance ────────────────────────────────────────────────────────────

    @Test
    fun `getBalance returns address and μPOH balance`() = runTest {
        server.enqueue(MockResponse().setBody("""{"address":"poh123","balance":5000000000}"""))

        val bal = client.getBalance("poh123")
        assertEquals("poh123", bal.address)
        assertEquals(5_000_000_000L, bal.balance)
    }

    // ── getNonce ──────────────────────────────────────────────────────────────

    @Test
    fun `getNonce returns current nonce`() = runTest {
        server.enqueue(MockResponse().setBody("""{"address":"poh123","nonce":7}"""))

        val n = client.getNonce("poh123")
        assertEquals("poh123", n.address)
        assertEquals(7L, n.nonce)
    }

    // ── getTransactionHistory ─────────────────────────────────────────────────

    @Test
    fun `getTransactionHistory returns entries`() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"address":"poh123","entries":[
                {"height":100,"delta":1000000000,"txHash":"abc","ts":1700000000,"label":"transfer"}
            ]}
        """.trimIndent()))

        val hist = client.getTransactionHistory("poh123")
        assertEquals("poh123", hist.address)
        assertEquals(1, hist.entries.size)
        assertEquals(1_000_000_000L, hist.entries.first().delta)
        assertEquals("transfer", hist.entries.first().label)
    }

    // ── getPendingTransactions ────────────────────────────────────────────────

    @Test
    fun `getPendingTransactions returns count`() = runTest {
        server.enqueue(MockResponse().setBody("""{"pending":[],"count":0}"""))

        val p = client.getPendingTransactions()
        assertEquals(0, p.count)
    }

    // ── submitTransaction ─────────────────────────────────────────────────────

    @Test
    fun `submitTransaction posts signed tx and returns txHash`() = runTest {
        server.enqueue(MockResponse().setBody("""{"ok":true,"txHash":"cafebabe","queueSize":1}"""))

        val tx = PohTx(
            from = "pohA", to = "pohB", amount = 1_000_000_000L, fee = 0L,
            nonce = 1L, timestamp = System.currentTimeMillis(), memo = "",
            txHash = "cafebabe", signature = "sig", signingPublicKey = "pub",
        )
        val result = client.submitTransaction(tx)
        assertEquals("cafebabe", result.txHash)
        assertTrue(result.ok)
    }

    // ── registerSigningKey ────────────────────────────────────────────────────

    @Test
    fun `registerSigningKey posts key and proof`() = runTest {
        server.enqueue(MockResponse().setBody("""{"success":true}"""))

        val body = client.registerSigningKey("pohA", "pubkey-pem", "proof-b64")
        assertNotNull(body)
        val req = server.takeRequest()
        assertEquals("/api/wallet/register-key", req.path)
        assertTrue(req.body.readUtf8().contains("signingPublicKey"))
    }

    // ── submitJob ─────────────────────────────────────────────────────────────

    @Test
    fun `submitJob routes to skill then creates job`() = runTest {
        server.enqueue(MockResponse().setBody("""{"type":"skill","skillId":"sk-sum","input":{}}"""))
        server.enqueue(MockResponse().setBody("""{"jobId":"jnl-1","status":"queued","statusUrl":null,"resultUrl":null}"""))

        val ref = client.submitJob("Summarise this")
        assertEquals("jnl-1", ref.jobId)
        assertEquals("queued", ref.status)
    }

    @Test
    fun `submitJob throws POHException when no skill matched`() = runTest {
        server.enqueue(MockResponse().setBody("""{"type":"chat","reason":"No skill matched"}"""))

        val ex = assertFailsWith<POHException.HttpException> {
            client.submitJob("random question")
        }
        assertEquals(422, ex.statusCode)
    }

    @Test
    fun `submitJob throws when budget positive without private key`() = runTest {
        server.enqueue(MockResponse().setBody("""{"type":"skill","skillId":"sk-sum","input":{}}"""))

        val ex = assertFailsWith<POHException.HttpException> {
            client.submitJob("Summarise this", AskOptions(budget = 0.5, walletAddress = "pohAlice"))
        }
        assertEquals(402, ex.statusCode)
    }

    @Test
    fun `submitJob signs a nonce-bound payment proof when budget positive`() = runTest {
        val kp = POHSigning.generateKeyPair()
        server.enqueue(MockResponse().setBody("""{"type":"skill","skillId":"sk-sum","input":{}}"""))
        server.enqueue(MockResponse().setBody("""{"minerAddress":"pohMiner","gasPrice":1,"model":"qwen2.5:1.5b","queueLength":0,"reputation":1.0}"""))
        server.enqueue(MockResponse().setBody("""{"address":"pohAlice","nonce":3}"""))
        server.enqueue(MockResponse().setBody("""{"jobId":"jnl-1","status":"queued","statusUrl":null,"resultUrl":null}"""))

        val ref = client.submitJob("Summarise this", AskOptions(
            budget = 0.5, walletAddress = "pohAlice", privateKeyPem = kp.signingPrivateKey,
        ))
        assertEquals("jnl-1", ref.jobId)

        server.takeRequest()  // /chat/route
        server.takeRequest()            // /api/miner/info
        server.takeRequest()            // /api/wallet/nonce
        val jobReq = server.takeRequest()
        val body = jobReq.body.readUtf8()
        assertTrue(body.contains("\"maxBudget\":500000000"))
        assertTrue(body.contains("\"requesterAddress\":\"pohAlice\""))
        assertTrue(body.contains("\"paymentTx\""))
        assertTrue(body.contains("\"txHash\""))
        assertTrue(body.contains("\"signature\""))
    }

    // ── runCompute ────────────────────────────────────────────────────────────

    @Test
    fun `runCompute throws when budget not positive`() = runTest {
        val kp = POHSigning.generateKeyPair()
        val ex = assertFailsWith<POHException.HttpException> {
            client.runCompute("hi", ComputeOptions(
                model = "qwen2.5:1.5b", budget = 0.0, walletAddress = "pohAlice", privateKeyPem = kp.signingPrivateKey,
            ))
        }
        assertEquals(402, ex.statusCode)
    }

    @Test
    fun `runCompute signs payment and posts model and dataset`() = runTest {
        val kp = POHSigning.generateKeyPair()
        server.enqueue(MockResponse().setBody("""{"minerAddress":"pohMiner","gasPrice":1,"model":"qwen2.5:1.5b","queueLength":0,"reputation":1.0}"""))
        server.enqueue(MockResponse().setBody("""{"address":"pohAlice","nonce":7}"""))
        server.enqueue(MockResponse().setBody("""{"jobId":"jc-1","status":"queued","statusUrl":null,"resultUrl":null}"""))

        val ref = client.runCompute("Summarize the top rows", ComputeOptions(
            model = "llama3.1:8b", dataset = "some-org/some-dataset",
            budget = 0.5, walletAddress = "pohAlice", privateKeyPem = kp.signingPrivateKey,
        ))
        assertEquals("jc-1", ref.jobId)

        server.takeRequest()  // /api/miner/info
        server.takeRequest()  // /api/wallet/nonce
        val jobReq = server.takeRequest()
        val body = jobReq.body.readUtf8()
        assertTrue(body.contains("\"model\":\"llama3.1:8b\""))
        assertTrue(body.contains("\"dataset\":\"some-org/some-dataset\""))
        assertTrue(body.contains("\"maxBudget\":500000000"))
        assertTrue(body.contains("\"prompt\":\"Summarize the top rows\""))
    }

    // ── getJobStatus ──────────────────────────────────────────────────────────

    @Test
    fun `getJobStatus returns status for NL job`() = runTest {
        server.enqueue(MockResponse().setBody("""{"jobId":"jnl-1","status":"computing","error":null}"""))

        val s = client.getJobStatus("jnl-1")
        assertEquals("jnl-1", s.jobId)
        assertEquals("computing", s.status)
    }

    // ── getJobResult ──────────────────────────────────────────────────────────

    @Test
    fun `getJobResult parses completed result`() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"jobId":"jnl-1","status":"done","profile":{"skillOutput":{"answer":42},"skillId":"sk-1","tokensUsed":10,"nlResponse":"The answer is 42."}}
        """.trimIndent()))

        val r = client.getJobResult("jnl-1")
        assertEquals("jnl-1", r.jobId)
        assertEquals("done", r.status)
        assertEquals("The answer is 42.", r.nlResponse)
        assertEquals(10, r.tokensUsed)
    }

    // ── pollJobResult ─────────────────────────────────────────────────────────

    @Test
    fun `pollJobResult polls status then fetches result when done`() = runTest {
        server.enqueue(MockResponse().setBody("""{"jobId":"jnl-2","status":"done","error":null}"""))
        server.enqueue(MockResponse().setBody("""
            {"jobId":"jnl-2","profile":{"nlResponse":"Done!","skillId":"sk-1","tokensUsed":5,"skillOutput":null}}
        """.trimIndent()))

        val r = client.pollJobResult("jnl-2", PollOptions(intervalMs = 0L, timeoutMs = 5_000L))
        assertEquals("Done!", r.nlResponse)
    }

    // ── askAndWait ────────────────────────────────────────────────────────────

    @Test
    fun `askAndWait routes, submits and polls to completion`() = runTest {
        server.enqueue(MockResponse().setBody("""{"type":"skill","skillId":"sk-1","input":{}}"""))
        server.enqueue(MockResponse().setBody("""{"jobId":"jnl-3","status":"queued","statusUrl":null,"resultUrl":null}"""))
        server.enqueue(MockResponse().setBody("""{"jobId":"jnl-3","status":"done","error":null}"""))
        server.enqueue(MockResponse().setBody("""
            {"jobId":"jnl-3","profile":{"nlResponse":"Answer","skillId":"sk-1","tokensUsed":8,"skillOutput":null}}
        """.trimIndent()))

        val r = client.askAndWait("What is 2+2?", askOptions = AskOptions(), pollOptions = PollOptions(intervalMs = 0L))
        assertEquals("Answer", r.nlResponse)
    }
}

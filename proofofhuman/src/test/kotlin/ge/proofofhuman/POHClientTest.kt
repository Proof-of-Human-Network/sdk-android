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
}

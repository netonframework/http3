package neton.http.h3

import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import neton.http.Request
import neton.http.StatusCode
import kotlinx.coroutines.CoroutineScope
import neton.io.bytes.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource
import neton.http.h3.client.newClient
import neton.http.h3.server.builder as serverBuilder

/**
 * ⚖️ The request-head time limit of a server (`Builder.requestHeaderTimeout`, SPEC §4, time limits; the reference has
 * none): a request stream whose HEADERS frame does not arrive in time is rejected both ways with H3_REQUEST_REJECTED,
 * and only that request; the connection and later requests carry on.
 */
abstract class RequestHeaderTimeoutTest {
    abstract val quic: QuicPairFactory

    private suspend fun CoroutineScope.quicPair() = with(quic) { pair(null, null) }
    @Test
    fun aRequestHeadThatNeverCompletesIsRejected() = h3Test {
        val (cq, sq) = quicPair()
        val incoming = serverBuilder().requestHeaderTimeout(200).build(sq)
        launch { incoming.run() }
        val (driver, send) = newClient(cq)
        launch { driver.run() }
        // The type of a HEADERS frame (0x01), and nothing more.
        val raw = cq.openBi()
        raw.send(Buffer().varint(0x01L))
        val resolver = incoming.accept()!!
        val start = TimeSource.Monotonic.markNow()
        val e = runCatching { resolver.resolveRequest() }.exceptionOrNull()
        val took = start.elapsedNow()
        assertIs<StreamError.Stream>(e, "got $e")
        assertEquals(Code.H3_REQUEST_REJECTED, e.code)
        assertTrue(took.inWholeMilliseconds in 150..3000, "rejected after $took")
        assertEquals(Code.H3_REQUEST_REJECTED.value, raw.stopped(), "the server stopped the request stream")
        // The connection carries on: the next request is served.
        val serve = launch {
            val (_, s) = getStreamBlocking(incoming)!!
            response(s)
        }
        assertEquals(StatusCode.IM_A_TEAPOT, send.sendRequest(Request.get("http://a/").body(Unit)).recvResponse().status)
        serve.join()
    }

    @Test
    fun noLimitWithZero() = h3Test {
        val (cq, sq) = quicPair()
        val incoming = serverBuilder().requestHeaderTimeout(0).build(sq)
        launch { incoming.run() }
        val raw = cq.openBi()
        raw.send(Buffer().varint(0x01L))
        val resolver = incoming.accept()!!
        val pending = async { resolver.resolveRequest() }
        assertNull(withTimeoutOrNull(500) { pending.await() }, "a head without a limit is still awaited")
        pending.cancel()
    }

    @Test
    fun theDefaultIsTenSeconds() {
        assertEquals(10_000L, DEFAULT_REQUEST_HEADER_TIMEOUT_MILLIS)
    }
}

/** On the in-memory QUIC double. */
class RequestHeaderTimeoutTestMemory : RequestHeaderTimeoutTest() {
    override val quic = MEMORY_QUIC
}

/** On neton.quic over loopback UDP. */
class RequestHeaderTimeoutTestQuic : RequestHeaderTimeoutTest() {
    override val quic = NETON_QUIC
}

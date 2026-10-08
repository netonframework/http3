package neton.http.h3

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import neton.http.Request
import neton.http.StatusCode
import neton.http.h3.quic.asH3
import neton.io.net.SocketAddress
import neton.io.net.bindUdp
import neton.quic.Endpoint
import neton.quic.proto.ClientConfig
import neton.quic.proto.EndpointConfig
import neton.quic.proto.ServerConfig
import neton.quic.proto.TlsServerConfig
import neton.quic.proto.VarInt
import neton.quic.proto.default
import neton.quic.proto.withCrypto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import neton.http.h3.client.newClient
import neton.http.h3.server.newConnection

/**
 * HTTP/3 over a 0-RTT QUIC connection (RFC 9114 §7.2.4.2, SPEC §4 "0-RTT"): a client with a session ticket from an earlier
 * connection sends its request before the handshake completes, on real TLS (the test double has no 0-RTT).
 */
class ZeroRttTest {
    private class Endpoints(val server: Endpoint, val client: Endpoint, val clientConfig: ClientConfig) {
        fun shutdown() {
            for (e in listOf(client, server)) {
                e.close(VarInt(0), ByteArray(0))
                e.close()
            }
        }
    }

    private suspend fun endpoints(serverTls: TlsServerConfig = TestCerts.serverConfig()): Endpoints {
        val server = Endpoint.create(EndpointConfig.default(), ServerConfig.withCrypto(serverTls), bindUdp(SocketAddress.IPV4_LOCALHOST_ANY_PORT))
        val client = Endpoint.create(EndpointConfig.default(), null, bindUdp(SocketAddress.IPV4_LOCALHOST_ANY_PORT))
        return Endpoints(server, client, ClientConfig(TestCerts.clientConfig()))
    }

    /**
     * Serve one connection: answer each request with its path; the paths of the requests that came in 0-RTT. The
     * connection is accepted [holdBack] after it arrives, so that on loopback the client's 0-RTT request is sent
     * before the handshake can complete (until accepted, the server endpoint keeps the client's packets).
     */
    private fun CoroutineScope.serveOne(e: Endpoints, holdBack: kotlin.time.Duration = kotlin.time.Duration.ZERO) = async {
        val incoming = checkNotNull(e.server.accept())
        delay(holdBack)
        val conn = incoming.await()
        val h3 = newConnection(conn.asH3())
        val drive = async { h3.run() }
        val early = ArrayList<String>()
        while (true) {
            val resolver = try { h3.accept() } catch (x: Exception) { null } ?: break
            val (req, stream) = resolver.resolveRequest()
            if (stream.isEarlyData()) early += req.uri.path
            stream.sendResponse(ok())
            stream.sendData(bytes(req.uri.path))
            stream.finish()
        }
        drive.cancel()
        early
    }

    /** A first connection that makes one request, so the client holds a session ticket afterwards. */
    private suspend fun CoroutineScope.firstConnection(e: Endpoints) {
        val served = serveOne(e)
        val conn = e.client.connectWith(e.clientConfig, e.server.localAddr(), "localhost").await()
        val (driver, send) = newClient(conn.asH3())
        val drive = async { driver.run() }
        val stream = send.sendRequest(Request.get("https://localhost/first").body(Unit))
        stream.finish()
        assertEquals(StatusCode.OK, stream.recvResponse().status)
        assertEquals("/first", stream.recvData()!!.decodeToString())
        // The tickets come right after the handshake; one more round trip makes sure they are in
        delay(50.milliseconds)
        send.close()
        drive.await()
        conn.close(VarInt(0), ByteArray(0))
        assertEquals(emptyList(), served.await(), "the first connection had no 0-RTT")
    }

    @Test
    fun requestInZeroRtt() = h3Test {
        val e = endpoints()
        firstConnection(e)
        val served = serveOne(e, holdBack = 200.milliseconds)
        val connecting = e.client.connectWith(e.clientConfig, e.server.localAddr(), "localhost")
        val (conn, accepted) = assertNotNull(connecting.into0Rtt(), "0-RTT with the ticket of the first connection")
        // The request goes out before the handshake completes
        val (driver, send) = newClient(conn.asH3())
        val drive = async { driver.run() }
        val stream = send.sendRequest(Request.get("https://localhost/early").body(Unit))
        stream.finish()
        assertEquals(StatusCode.OK, stream.recvResponse().status)
        assertEquals("/early", stream.recvData()!!.decodeToString())
        assertTrue(accepted.await(), "the server accepted the 0-RTT data")
        send.close()
        drive.await()
        conn.close(VarInt(0), ByteArray(0))
        assertEquals(listOf("/early"), served.await(), "the request came as early data")
        e.shutdown()
    }

    /**
     * The server takes the ticket but refuses early data: everything the client sent in 0-RTT is discarded, its HTTP/3
     * connection (whose control stream was opened in 0-RTT) is unusable, and the request fails. The QUIC connection
     * continues in 1-RTT: a new HTTP/3 client on it makes the request again (as quinn's 0-RTT streams must be reopened).
     */
    @Test
    fun rejectedZeroRttIsRetriedOnTheSameConnection() = h3Test {
        val e = endpoints()
        firstConnection(e)
        // A new server configuration with early data refused (its ticket keys differ too): the 0-RTT data is rejected
        e.server.setServerConfig(ServerConfig.withCrypto(TlsServerConfig(TestCerts.server.chainWith(TestCerts.ca.identity),
            TestCerts.server.privateKey, alpnProtocols = listOf(neton.http.h3.quic.ALPN_H3), earlyData = false)))
        val served = serveOne(e, holdBack = 200.milliseconds)
        val connecting = e.client.connectWith(e.clientConfig, e.server.localAddr(), "localhost")
        val (conn, accepted) = assertNotNull(connecting.into0Rtt(), "0-RTT with the ticket of the first connection")
        val (driver, send) = newClient(conn.asH3())
        val drive = async { driver.run() }
        val first = send.sendRequest(Request.get("https://localhost/early").body(Unit))
        first.finish()
        assertFalse(accepted.await(), "the server refused the 0-RTT data")
        try {
            first.recvResponse()
            fail("a request sent in rejected 0-RTT got a response")
        } catch (x: Exception) {
            // expected: the stream was discarded with the 0-RTT data
        }
        // The first HTTP/3 client is given up without closing its sender (closing the last one closes the QUIC
        // connection, h3's rule)
        drive.cancel()
        // Again, in 1-RTT, with a new HTTP/3 client on the same QUIC connection
        val (driver2, send2) = newClient(conn.asH3())
        val drive2 = async { driver2.run() }
        val second = send2.sendRequest(Request.get("https://localhost/again").body(Unit))
        second.finish()
        assertEquals(StatusCode.OK, second.recvResponse().status)
        assertEquals("/again", second.recvData()!!.decodeToString())
        send2.close()
        drive2.await()
        conn.close(VarInt(0), ByteArray(0))
        assertEquals(emptyList(), served.await(), "nothing was accepted as early data")
        e.shutdown()
    }
}

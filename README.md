# http3

HTTP/3 (RFC 9114) and QPACK (RFC 9204) for Kotlin/Native over `com.netonstream:quic`. The first version replicates
`h3` 0.0.8 (and `h3-quinn` for the QUIC adapter). Artifact `com.netonstream:http3`, package `neton.http.h3`. Common
HTTP types (`Request`, `Response`, `HeaderMap`, ...) come from `com.netonstream:http`.

This repository was split out of `http` on 2026-09-29, with its history. Specification, deliberate differences from
the reference (marked ⚖️) and the implementation record: [SPEC.md](SPEC.md).

## Status

Accepted for v1; version 0.1.0, not yet on Maven Central. Built against `com.netonstream:http:0.1.1` and
`com.netonstream:quic:0.1.0` / `quic-testkit:0.1.0`, taken from the sibling repo (`includeBuild("../quic")`) until quic 0.1.0
is on Maven Central. Kotlin 2.4.0, native targets shared by `http` and `quic` (no 32-bit Android, no Windows).

- h3's tests ported (frames, QPACK static + literals with a zero-capacity dynamic table, connection and request tests),
  run on an in-memory QUIC double, on neton.quic with the TLS test double, and on neton.quic with real TLS 1.3.
- Interop over real TLS 1.3 in both directions with h3 0.0.8 + h3-quinn (its own examples and a peer on the same
  crates: GET, POST, 16 MiB bodies, 150 requests per connection, trailers, GOAWAY, close) and with aioquic 1.2.0
  (GREASE on).
- h3spec 0.1.13: 49 / 49, including the five items the reference skips.
- ⚖️ GOAWAY rejects stream IDs greater than or equal to the GOAWAY ID (RFC 9114 §5.2; the reference uses `>`); the
  connection's one GREASE frame on a request stream follows the first head instead of preceding FIN (aioquic needs it).
- Not in v1: 0-RTT, server push, WebTransport, HTTP Datagrams, dynamic QPACK.
- Not yet: quiche / curl `--http3` interop, loss and reordering runs over real TLS, performance comparison.

## Usage

HTTP/3 (`neton.http.h3`) runs on a `neton.quic` connection whose TLS handshake negotiated ALPN "h3". The TLS
configuration is `neton.quic.proto`'s TLS 1.3 session (OpenSSL): the server gives its certificate chain and key, the
client gives its trust anchors explicitly (there is no system trust store), and both offer `ALPN_H3`:

```kotlin
// Server
val tls = TlsServerConfig(Certificates.pem(chainPem), PrivateKey.pem(keyPem), alpnProtocols = listOf(ALPN_H3))
val endpoint = Endpoint.create(EndpointConfig.default(), ServerConfig.withCrypto(tls), bindUdp(address))
while (true) {
    val quic = endpoint.accept()?.await() ?: break
    launch {
        val conn = neton.http.h3.server.newConnection(quic.asH3())
        while (true) {
            val (request, stream) = conn.accept()?.resolveRequest() ?: break
            stream.use {   // close() releases the QUIC stream, as dropping it does in h3
                it.sendResponse(Response.builder().status(200).body(Unit))
                it.sendData(Bytes.copyOf("hello".encodeToByteArray()))
                it.finish()
            }
        }
    }
}

// Client
val tls = TlsClientConfig(trustAnchors = Certificates.pem(caPem), alpnProtocols = listOf(ALPN_H3))
val quic = clientEndpoint.connectWith(ClientConfig(tls), serverAddress, "example.com").await()
val (driver, sender) = neton.http.h3.client.newClient(quic.asH3())
launch { driver.run() }
val stream = sender.sendRequest(Request.get("https://example.com/").body(Unit))
stream.finish()
val response = stream.recvResponse()
while (true) stream.recvData() ?: break
```

Close each server `RequestStream` when done with it (`use { }`): it is h3's `Drop`. Until then a request whose body
was not read to its end keeps its QUIC stream, and with it one of the client's stream credits (100 concurrent
bidirectional streams by default), so a server that never closes them stops accepting requests on that connection.

A server certificate that does not chain to the client's trust anchors or does not match the server name fails the
handshake, as does a peer without "h3". The KDoc of `neton.http.h3.quic.ALPN_H3` has the details.

## Building and testing

```
./gradlew :http3:macosArm64Test          # or linuxX64Test (NETON_IO_DRIVER=epoll|iouring)
```

Interop peers and scripts (h3 examples, an h3 peer, aioquic, h3spec): `http3-interop/` (not published).

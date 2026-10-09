package neton.http.h3

import neton.http.h3.proto.Frame
import neton.http.h3.proto.FrameException
import neton.http.h3.proto.Settings
import neton.http.h3.proto.SettingId
import neton.http.h3.proto.StreamType
import neton.http.h3.proto.StreamTypeDecoder
import neton.http.h3.proto.VarInt
import neton.http.h3.qpack.Decoder
import neton.http.h3.qpack.DecoderException
import neton.http.h3.qpack.DecoderStreamException
import neton.http.h3.qpack.DecoderStreamReceiver
import neton.http.h3.qpack.Encoder
import neton.http.h3.qpack.EncoderStreamException
import neton.http.h3.qpack.EncoderStreamReceiver
import neton.http.h3.qpack.HeaderField
import neton.io.bytes.Buffer
import neton.io.bytes.Bytes
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Fuzzing of the parsers that read peer bytes, beyond VarIntFuzzTest: QPACK field sections (Decoder), the QPACK
// encoder and decoder streams (EncoderStreamReceiver, DecoderStreamReceiver), frames (FrameStream over FrameDecoder)
// and unidirectional stream headers (StreamTypeDecoder). Inputs are valid encodings mutated byte-wise, and random
// bytes, from fixed seeds (reproducible). Each parser must either succeed or throw its declared exception, never
// another exception, never loop, and never read outside the input; unmutated inputs must round-trip.

private const val CASES = 20_000

private val NAMES = listOf(":method", ":path", ":authority", ":scheme", ":status", "content-type", "x-custom", "")
private val VALUES = listOf("GET", "/", "https", "200", "text/html; charset=utf-8", "", "ünïcødé", "v".repeat(300))

private fun Random.fields(): List<HeaderField> = List(nextInt(0, 12)) {
    val name = if (nextInt(4) == 0) "x-" + List(nextInt(1, 20)) { 'a' + nextInt(26) }.joinToString("") else NAMES.random(this)
    HeaderField(name.encodeToByteArray(), VALUES.random(this).encodeToByteArray())
}

/** One of the usual mutations of [seed]: bit flips, overwrites, insertions, deletions, truncation, duplication. */
private fun Random.mutate(seed: ByteArray): ByteArray {
    var b = seed.copyOf()
    repeat(nextInt(1, 4)) {
        b = when (nextInt(6)) {
            0 -> b.also { if (it.isNotEmpty()) { val i = nextInt(it.size); it[i] = (it[i].toInt() xor (1 shl nextInt(8))).toByte() } }
            1 -> b.also { if (it.isNotEmpty()) it[nextInt(it.size)] = nextInt(256).toByte() }
            2 -> { val i = nextInt(b.size + 1); b.copyOfRange(0, i) + nextBytes(nextInt(1, 9)) + b.copyOfRange(i, b.size) }
            3 -> if (b.isEmpty()) b else { val i = nextInt(b.size); val n = nextInt(1, minOf(8, b.size - i) + 1); b.copyOfRange(0, i) + b.copyOfRange(i + n, b.size) }
            4 -> b.copyOfRange(0, nextInt(b.size + 1))
            else -> if (b.isEmpty()) b else { val i = nextInt(b.size); val n = nextInt(1, b.size - i + 1); b.copyOfRange(0, i + n) + b.copyOfRange(i, b.size) }
        }
    }
    return b
}

/** Splits [input] into random chunks, as a transport would deliver it. */
private fun Random.chunks(input: ByteArray): List<ByteArray> {
    val out = ArrayList<ByteArray>()
    var i = 0
    while (i < input.size) {
        val n = nextInt(1, minOf(input.size - i, 64) + 1)
        out += input.copyOfRange(i, i + n)
        i += n
    }
    return out
}

private fun Buffer.bytes(): ByteArray = readBytes(readableBytes)

class FuzzTest {
    // ---- QPACK field sections ----

    private fun decodeSection(decoder: Decoder, input: ByteArray): List<Pair<String, String>>? {
        // Placed inside a larger array: the decoder must stay within [off, off + len).
        val padded = byteArrayOf(0x7f, -1) + input + byteArrayOf(-1, 0x7f)
        val out = ArrayList<Pair<String, String>>()
        return try {
            decoder.decode(padded, 2, input.size) { n, no, nl, v, vo, vl ->
                assertTrue(nl >= 0 && vl >= 0 && no >= 0 && vo >= 0 && no + nl <= n.size && vo + vl <= v.size)
                out += n.decodeToString(no, no + nl) to v.decodeToString(vo, vo + vl)
            }
            assertTrue(decoder.fieldCount <= decoder.maxFieldCount)
            assertTrue(decoder.memSize <= decoder.maxFieldSectionSize)
            out
        } catch (e: DecoderException) {
            null
        }
    }

    @Test
    fun qpackSections() {
        val random = Random(0x9e3779b9)
        val encoder = Encoder()
        val decoders = listOf(Decoder(), Decoder(maxFieldSectionSize = 200, maxFieldCount = 4))
        var ok = 0
        repeat(CASES) {
            val fields = random.fields()
            val block = Buffer()
            encoder.encodeStateless(block, fields)
            val seed = block.bytes()
            val decoded = decodeSection(decoders[0], seed)
            assertEquals(fields.map { it.name.decodeToString() to it.value.decodeToString() }, decoded, "round trip")
            for (d in decoders) {
                if (decodeSection(d, random.mutate(seed)) != null) ok++
                if (decodeSection(d, random.nextBytes(random.nextInt(0, 64))) != null) ok++
            }
        }
        assertTrue(ok > 0, "some mutated sections still decode")
    }

    // ---- QPACK encoder and decoder streams ----

    private fun encoderStreamSeed(random: Random): ByteArray {
        val b = Buffer()
        repeat(random.nextInt(1, 6)) {
            when (random.nextInt(4)) {
                0 -> b.writeByte(0x20.toByte()) // Set Dynamic Table Capacity 0: valid
                1 -> { b.writeByte((0xc0 or random.nextInt(64)).toByte()); b.writeByte(0x03.toByte()); b.writeBytes("abc".encodeToByteArray()) }
                2 -> { b.writeByte(0x43.toByte()); b.writeBytes("abc".encodeToByteArray()); b.writeByte(0x01.toByte()); b.writeByte('v'.code.toByte()) }
                else -> b.writeByte(random.nextInt(32).toByte()) // Duplicate
            }
        }
        return b.bytes()
    }

    private fun decoderStreamSeed(random: Random): ByteArray {
        val b = Buffer()
        repeat(random.nextInt(1, 6)) {
            when (random.nextInt(3)) {
                0 -> b.writeByte((0x40 or random.nextInt(64)).toByte()) // Stream Cancellation: valid
                1 -> b.writeByte((0x80 or random.nextInt(128)).toByte()) // Section Acknowledgment
                else -> b.writeByte(random.nextInt(64).toByte()) // Insert Count Increment
            }
        }
        return b.bytes()
    }

    /** Feeds [input] in chunks; returns whether it was accepted, or false at the first declared error. */
    private inline fun <reified E : H3Exception> feed(random: Random, input: ByteArray, receive: (Buffer) -> Unit): Boolean {
        val buf = Buffer()
        for (chunk in random.chunks(input)) {
            buf.writeBytes(chunk)
            val before = buf.readableBytes
            try {
                receive(buf)
            } catch (e: H3Exception) {
                assertTrue(e is E, "unexpected ${e::class.simpleName}: $e")
                return false
            }
            assertTrue(buf.readableBytes <= before)
        }
        return true
    }

    @Test
    fun qpackEncoderStream() {
        val random = Random(0x51ed)
        repeat(CASES) {
            val receiver = EncoderStreamReceiver()
            val input = when (random.nextInt(3)) {
                0 -> encoderStreamSeed(random)
                1 -> random.mutate(encoderStreamSeed(random))
                else -> random.nextBytes(random.nextInt(0, 64))
            }
            feed<EncoderStreamException>(random, input) { receiver.receive(it) }
            assertEquals(0L, receiver.capacity)
        }
        // A capacity of 0, alone, is all a peer may send.
        assertTrue(feed<EncoderStreamException>(random, ByteArray(100) { 0x20 }) { EncoderStreamReceiver().receive(it) })
    }

    @Test
    fun qpackDecoderStream() {
        val random = Random(0xdec0de)
        repeat(CASES) {
            val receiver = DecoderStreamReceiver()
            val input = when (random.nextInt(3)) {
                0 -> decoderStreamSeed(random)
                1 -> random.mutate(decoderStreamSeed(random))
                else -> random.nextBytes(random.nextInt(0, 64))
            }
            feed<DecoderStreamException>(random, input) { receiver.receive(it) }
        }
        val cancellations = ByteArray(50) { (0x40 or it).toByte() }
        val receiver = DecoderStreamReceiver()
        assertTrue(feed<DecoderStreamException>(random, cancellations) { receiver.receive(it) })
        assertEquals(50L, receiver.streamCancellations)
    }

    // ---- frames ----

    private fun randomFrames(random: Random, request: Boolean): List<Frame> = List(random.nextInt(1, 6)) {
        when (random.nextInt(if (request) 3 else 7)) {
            0 -> Frame.Data(Bytes.wrap(random.nextBytes(random.nextInt(0, 200))))
            1 -> Frame.Headers(Bytes.wrap(random.nextBytes(random.nextInt(0, 100))))
            2 -> Frame.Grease
            3 -> Settings().also { it.insert(SettingId.MAX_HEADER_LIST_SIZE, random.nextLong(0, 1L shl 40)) }
            4 -> Frame.CancelPush(random.nextLong(0, VarInt.MAX))
            5 -> Frame.Goaway(random.nextLong(0, VarInt.MAX))
            else -> Frame.MaxPushId(random.nextLong(0, VarInt.MAX))
        }
    }

    /** Runs [input] through a FrameStream in random chunks; returns the frames (DATA with its payload) or null. */
    private fun readFrames(random: Random, input: ByteArray, request: Boolean, maxHeaders: Int): List<Any>? {
        val stream = FrameStream(maxHeaders, request)
        val out = ArrayList<Any>()
        var data: Buffer? = null
        fun drain() {
            while (true) {
                if (stream.hasData) {
                    val piece = stream.nextData() ?: return
                    data!!.writeBytes(piece)
                    if (!stream.hasData) { out.add(data!!.bytes().toList()); data = null }
                    continue
                }
                val frame = stream.nextFrame() ?: return
                if (frame is Frame.Data) {
                    assertTrue(frame.length >= 0)
                    data = Buffer()
                    if (frame.length == 0L) { out.add(emptyList<Byte>()); data = null }
                } else {
                    out += frame
                }
            }
        }
        try {
            for (chunk in random.chunks(input)) {
                stream.onData(chunk)
                drain()
            }
            stream.onEnd()
            drain()
            assertTrue(stream.isFinished, "the stream ends consumed")
            return out
        } catch (e: H3Exception) {
            assertTrue(e is FrameException, "unexpected ${e::class.simpleName}: $e")
            return null
        }
    }

    @Test
    fun frames() {
        val random = Random(0xf4a3e5)
        repeat(CASES) {
            val request = random.nextBoolean()
            val frames = randomFrames(random, request)
            val buf = Buffer()
            for (f in frames) f.encodeWithPayload(buf)
            val seed = buf.bytes()
            val expected = frames.filter { it !== Frame.Grease }.map { if (it is Frame.Data) it.payload!!.toByteArray().toList() else it }
            val got = readFrames(random, seed, request, DEFAULT_MAX_HEADERS_FRAME_SIZE)!!
            assertEquals(expected.size, got.size, "round trip")
            for ((e, g) in expected.zip(got)) {
                if (e is Settings) assertEquals(e.toString(), g.toString()) else assertEquals(e, g)
            }
            readFrames(random, random.mutate(seed), request, random.nextInt(0, 128))
            readFrames(random, random.nextBytes(random.nextInt(0, 64)), request, DEFAULT_MAX_HEADERS_FRAME_SIZE)
        }
    }

    // ---- stream headers ----

    @Test
    fun streamTypes() {
        val random = Random(0x57e)
        repeat(CASES) {
            val seed = Buffer().also {
                val type = listOf(StreamType.CONTROL, StreamType.PUSH, StreamType.ENCODER, StreamType.DECODER,
                    StreamType.grease(random)).random(random)
                VarInt.encode(type, it)
                if (type == StreamType.PUSH) VarInt.encode(random.nextLong(0, VarInt.MAX), it)
            }.bytes()
            val input = if (random.nextBoolean()) random.mutate(seed) else seed
            val decoder = StreamTypeDecoder()
            val buf = Buffer()
            var done = false
            for (chunk in random.chunks(input)) {
                buf.writeBytes(chunk)
                done = decoder.decode(buf)
                if (done) break
            }
            assertEquals(decoder.isResolved, done)
            if (input === seed) {
                assertTrue(done)
                assertContentEquals(ByteArray(0), buf.bytes())
            }
        }
    }
}

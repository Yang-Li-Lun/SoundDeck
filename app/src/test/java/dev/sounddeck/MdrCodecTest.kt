package dev.sounddeck

import dev.sounddeck.protocol.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class MdrCodecTest {
    @Test fun protocolInfoGoldenFrame() {
        assertArrayEquals(bytes(0x3e, 0x0c, 0, 0, 0, 0, 2, 0, 0, 0x0e, 0x3c),
            MdrCodec.encode(MdrFrame(12, 0, SonyProtocol.protocol)))
    }
    @Test fun ambientGoldenFrame() {
        val command = SonyProtocol.setNoise(NoiseState(NoiseMode.ANC, 20, false, 0), NoiseMode.AMBIENT)
        assertArrayEquals(bytes(0x3e, 12, 0, 0, 0, 0, 7, 0x68, 0x17, 1, 1, 1, 0, 20, 0xa9, 0x3c),
            MdrCodec.encode(MdrFrame(12, 0, command)))
    }
    @Test fun everySplitAndConcatenatedFrameDecodes() {
        val payload = bytes(0x3c, 0x3d, 0x3e, 0xff, 0)
        val encoded = MdrCodec.encode(MdrFrame(12, 1, payload))
        for (split in 0..encoded.size) {
            val decoder = MdrCodec.Decoder()
            val result = decoder.feed(encoded.copyOfRange(0, split)) + decoder.feed(encoded.copyOfRange(split, encoded.size))
            assertEquals(1, result.size); assertArrayEquals(payload, result.single().payload)
        }
        assertEquals(2, MdrCodec.Decoder().feed(encoded + encoded).size)
    }
    @Test fun corruptChecksumAndBadEscapeAreRejected() {
        val frame = MdrCodec.encode(MdrFrame(12, 0, bytes(1, 2)))
        frame[frame.lastIndex - 1] = 0
        assertTrue(MdrCodec.Decoder().feed(frame).isEmpty())
        val d = MdrCodec.Decoder()
        assertTrue(d.feed(bytes(0x3e, 0x3d, 0x20, 0x3c)).isEmpty())
        assertEquals(1, d.rejected)
    }
    @Test fun mismatchedLengthAndInvalidSequenceCannotReachPayloadParser() {
        assertTrue(MdrCodec.Decoder().feed(bytes(0x3e, 12, 0, 0, 0, 0, 2, 1, 15, 0x3c)).isEmpty())
        assertTrue(MdrCodec.Decoder().feed(bytes(0x3e, 1, 2, 0, 0, 0, 0, 3, 0x3c)).isEmpty())
    }
    @Test fun rejectsAckWithPayload() {
        assertTrue(MdrCodec.Decoder().feed(MdrCodec.encode(MdrFrame(1, 0, bytes(1)))).isEmpty())
    }
    @Test fun resynchronizesAfterNoiseAndOversizedInput() {
        val d = MdrCodec.Decoder()
        val valid = MdrCodec.encode(MdrFrame(12, 0, bytes(0, 0)))
        assertEquals(1, d.feed(bytes(0x22, 0x3e) + ByteArray(6000) + valid).size)
        assertEquals(1, d.rejected)
    }
    @Test fun randomizedFragmentationAndArbitraryDataStayBounded() {
        val random = Random(721)
        repeat(300) {
            val payload = random.nextBytes(random.nextInt(1, 1024))
            val encoded = MdrCodec.encode(MdrFrame(12, it % 2, payload))
            val decoder = MdrCodec.Decoder()
            val result = encoded.toList().chunked(7).flatMap { part -> decoder.feed(part.toByteArray()) }
            assertEquals(1, result.size); assertArrayEquals(payload, result.single().payload)
            decoder.feed(random.nextBytes(2000))
        }
    }
}

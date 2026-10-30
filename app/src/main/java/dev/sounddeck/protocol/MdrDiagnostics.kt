package dev.sounddeck.protocol

import java.util.concurrent.atomic.AtomicLong

/** Passive metadata only. No commands, waits, or changes to transaction matching. */
internal class MdrDiagnostics(private val sink: (String) -> Unit) {
    private val session = sessions.incrementAndGet()
    private val started = System.nanoTime()
    private var event = 0L

    @Synchronized fun record(operation: Long, request: Long?, text: String) {
        val line = "MDR sid=$session op=$operation req=${request ?: "-"} event=${++event} " +
            "elapsedMs=${(System.nanoTime() - started) / 1_000_000} $text"
        runCatching { sink(line) }
    }

    companion object {
        private val sessions = AtomicLong()

        /** Exact command/subtype/length allowlist; unknown and malformed payloads stay redacted. */
        fun frame(frame: MdrFrame): String {
            val p = frame.payload
            val command = p.getOrNull(0)?.u()
            val subtype = p.getOrNull(1)?.u()
            val length = when {
                frame.type != MdrCodec.DATA -> null
                subtype == 0x17 && command == 0x66 -> 2
                subtype == 0x17 && command in listOf(0x67, 0x68, 0x69) -> 7
                subtype == 1 && command in listOf(0xe2, 0xe6) -> 2
                subtype == 1 && command in listOf(0xe3, 0xe5, 0xe7, 0xe8, 0xe9) -> 3
                subtype == 0x0c && command in listOf(0xf2, 0xf6, 0xfa) -> 2
                subtype == 0x0c && command in listOf(0xf3, 0xf5, 0xf7, 0xf8, 0xf9, 0xfb, 0xfc, 0xfd) -> 4
                subtype in listOf(2, 3) && command == 0x12 -> 2
                subtype == 2 && command in listOf(0x13, 0x15) -> 3
                subtype == 3 && command in listOf(0x13, 0x15) -> 4
                subtype == 0 && command == 0x99 -> 4 // Fixed-message alert; no automatic response.
                command in listOf(0x23, 0x25) -> when (subtype) { 1 -> 6; 2 -> 4; 9 -> 8; 10 -> 5; else -> null }
                else -> null
            }
            val payload = if (length != null && p.size == length) p.joinToString(" ") { hex(it.u()) } else "redacted"
            return "type=${hex(frame.type)} seq=${frame.sequence} len=${p.size} cmd=${command?.let(::hex) ?: "-"} " +
                "sub=${subtype?.let(::hex) ?: "-"} payload=[$payload]"
        }

        fun parsed(event: SonyEvent?): String = when (event) {
            is SonyEvent.Noise, is SonyEvent.Dsee, is SonyEvent.DseeAvailable,
            is SonyEvent.Speak, is SonyEvent.SpeakConfiguration, is SonyEvent.Codec -> event.toString()
            else -> event?.javaClass?.simpleName ?: "unsupported"
        }

        fun hex(value: Int) = value.toString(16).padStart(2, '0')
    }
}

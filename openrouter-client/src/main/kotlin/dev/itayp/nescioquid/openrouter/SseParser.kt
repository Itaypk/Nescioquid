package dev.itayp.nescioquid.openrouter

import java.io.BufferedReader

/** The sentinel payload OpenRouter sends to close a stream cleanly. */
internal const val SSE_DONE = "[DONE]"

/**
 * Lazily reads a `text/event-stream` body and yields the `data` payload of each event.
 *
 * Just enough of the SSE wire format for OpenRouter, hand-rolled because the library deliberately
 * carries no WebFlux/Reactor dependency (which is where Spring's SSE codecs live):
 * - `:`-prefixed lines are comments and are skipped — OpenRouter sends `: OPENROUTER PROCESSING`
 *   keepalives while an upstream provider is still thinking, and they must not be parsed as JSON,
 * - a blank line dispatches the accumulated event; multiple `data:` lines in one event are joined
 *   with `\n` per the spec (OpenRouter sends one JSON object per line, but a provider need not),
 * - other fields (`event:`, `id:`, `retry:`) are ignored — nothing here dispatches on them,
 * - a payload of [SSE_DONE] terminates the sequence,
 * - a final event not followed by a blank line is still flushed at end of stream.
 *
 * The sequence is lazy and single-pass: [reader] must stay open for as long as it is being consumed.
 */
internal fun sseDataLines(reader: BufferedReader): Sequence<String> = sequence {
    val data = StringBuilder()
    while (true) {
        val line = reader.readLine() ?: break
        when {
            // Blank line: dispatch whatever has accumulated.
            line.isEmpty() -> {
                if (data.isNotEmpty()) {
                    val payload = data.toString()
                    data.setLength(0)
                    if (payload == SSE_DONE) return@sequence
                    yield(payload)
                }
            }
            // Comment / keepalive.
            line.startsWith(":") -> Unit
            line.startsWith("data:") -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.removePrefix("data:").removePrefix(" "))
            }
            // Any other field of the event; not used.
            else -> Unit
        }
    }
    // Stream ended mid-event (no trailing blank line) — dispatch what we have.
    if (data.isNotEmpty()) {
        val payload = data.toString()
        if (payload != SSE_DONE) yield(payload)
    }
}

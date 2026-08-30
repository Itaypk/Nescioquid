package dev.itayp.nescioquid.openrouter

/**
 * Anything that can be sent as one accounted OpenRouter call.
 *
 * Sealed so the seams ([AiCallGate], [AiCallListener]) can be written once against every modality
 * and still narrow exhaustively — a consumer's gate does `when (request) { is ChatRequest -> …;
 * is ImageRequest -> … }` and the compiler tells it when a new modality lands.
 */
sealed interface AiRequest {
    val model: String
}

/**
 * Usage/cost accounting common to every endpoint. [Usage] (`/chat/completions`, `/images`) and
 * [TranscriptionUsage] (`/audio/transcriptions`) are the two shapes OpenRouter reports — the latter
 * bills by audio duration rather than prompt/completion tokens, so it isn't a [Usage] with different
 * field names, it's a genuinely different breakdown. [cost] is the one thing both report and the one
 * thing a generic budget gate needs; narrow to the concrete type for token/duration detail.
 */
sealed interface CallUsage {
    val cost: Double?
}

/**
 * What every OpenRouter response exposes to the accounting seams, regardless of endpoint.
 *
 * Deliberately narrow: only the fields usage accounting actually needs. Notably absent is an id —
 * `/chat/completions` returns one, `/images` returns only a `created` timestamp, and
 * `/audio/transcriptions` returns neither — so a listener that wants it narrows to the concrete type.
 */
interface AiResponse {
    /** The model OpenRouter resolved the request to. Absent on non-OpenRouter backends. */
    val model: String?

    /** The upstream provider OpenRouter routed to. Absent on non-OpenRouter backends. */
    val provider: String?

    val usage: CallUsage?
}

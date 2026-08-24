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
 * What every OpenRouter response exposes to the accounting seams, regardless of endpoint.
 *
 * Deliberately narrow: only the fields usage accounting actually needs. Notably absent is an id —
 * `/chat/completions` returns one, `/images` returns only a `created` timestamp — so a listener that
 * wants it narrows to the concrete type.
 */
interface AiResponse {
    /** The model OpenRouter resolved the request to. Absent on non-OpenRouter backends. */
    val model: String?

    /** The upstream provider OpenRouter routed to. Absent on non-OpenRouter backends. */
    val provider: String?

    val usage: Usage?
}

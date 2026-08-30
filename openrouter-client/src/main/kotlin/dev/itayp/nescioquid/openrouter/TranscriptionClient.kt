package dev.itayp.nescioquid.openrouter

import org.springframework.stereotype.Component

/**
 * The speech-to-text client, speaking OpenRouter's dedicated `POST /audio/transcriptions` endpoint.
 *
 * Kept separate from [AiClient] for the same reason as [ImageClient]: `/audio/transcriptions` is its
 * own endpoint with its own request and response shapes. Everything the clients genuinely share —
 * auth, timeouts, the retry policy, and the gate/listener seams — lives in [OpenRouterTransport], so a
 * call here is accounted exactly as a chat or image call is.
 *
 * ```kotlin
 * val response = transcriptionClient.transcribe(
 *     TranscriptionRequest.ofBytes(model = "openai/whisper-large-v3", bytes = audioBytes, format = "wav"),
 *     AiCallContext(userId = userId, conversationType = "voice-input"),
 * )
 * val transcript = response.text
 * ```
 */
@Component
class TranscriptionClient(private val transport: OpenRouterTransport) {

    /**
     * Transcribes one audio clip. The gate runs first and may throw to refuse the call; the listener
     * is then notified exactly once, for success or failure, as on the chat and image paths.
     *
     * Blocking, and runs against its own [AiClientProperties.transcriptionReadTimeout] rather than the
     * chat read timeout, since a long recording can take a while to transcribe.
     */
    fun transcribe(request: TranscriptionRequest, context: AiCallContext): TranscriptionResponse =
        transport.call(context, request, AUDIO_TRANSCRIPTIONS_PATH, TranscriptionResponse::class.java)
}

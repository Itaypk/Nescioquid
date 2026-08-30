package dev.itayp.nescioquid.openrouter

import org.springframework.stereotype.Component

/**
 * The image-generation client, speaking OpenRouter's dedicated `POST /images` endpoint.
 *
 * Kept separate from [AiClient] rather than added to it: `/images` is its own endpoint with its own
 * request and response shapes, same as [TranscriptionClient]'s `/audio/transcriptions`, and the
 * modality endpoints still to come (`/videos`, `/embeddings`, `/audio/speech`) are each another one.
 * Everything the clients genuinely share — auth, timeouts, the retry policy, and the gate/listener
 * seams — lives in [OpenRouterTransport], so a call here is accounted exactly as a chat call is.
 *
 * Which parameters a given model accepts, and their legal values, are reported per model by
 * [ImageModelCapabilityService]; OpenRouter rejects an unsupported parameter rather than ignoring it.
 *
 * ```kotlin
 * val response = imageClient.generate(
 *     ImageRequest(model = "…", prompt = "a red panda astronaut", aspectRatio = "16:9"),
 *     AiCallContext(userId = userId, conversationType = "illustration"),
 * )
 * val png = response.data.first().bytes
 * ```
 */
@Component
class ImageClient(private val transport: OpenRouterTransport) {

    /**
     * Generates one or more images. The gate runs first and may throw to refuse the call; the
     * listener is then notified exactly once, for success or failure, as on the chat path.
     *
     * Blocking: SSE streaming of partial renders is not implemented yet. Generation regularly takes
     * longer than a text completion, which is why it runs against its own
     * [AiClientProperties.imageReadTimeout] rather than the chat read timeout.
     */
    fun generate(request: ImageRequest, context: AiCallContext): ImageResponse =
        transport.call(context, request, IMAGES_PATH, ImageResponse::class.java)
}

package dev.itayp.nescioquid.openrouter

import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.RestClient

@Component
class AiClient(
    properties: AiClientProperties,
    private val callGate: AiCallGate,
    private val callListener: AiCallListener,
) {
    private val log = LoggerFactory.getLogger(AiClient::class.java)

    private val client = RestClient.builder()
        .baseUrl(properties.baseUrl)
        .defaultHeader("Authorization", "Bearer ${properties.apiKey.trim()}")
        .build()

    /**
     * Sends a chat request to the AI API. The [request] is the source of truth for everything on the
     * wire, including [ChatRequest.reasoning] — the caller sets it (the library applies no central
     * reasoning policy). [context] attributes the call for usage accounting and is the carrier for
     * the pre-call gate. The gate runs first and may throw to refuse the call; otherwise usage is
     * recorded for both success and failure.
     */
    fun chat(request: ChatRequest, context: AiCallContext): ChatResponse {
        callGate.beforeCall(context, request)
        val response = try {
            withRetry {
                log.debug("Sending chat request to AI API: model=${request.model}, messages=${request.messages.size}")
                client.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(ChatResponse::class.java)!!
            }
        } catch (e: RuntimeException) {
            callListener.recordFailure(context, request)
            throw e
        }
        callListener.recordSuccess(context, request, response)
        return response
    }

    private fun <T> withRetry(maxAttempts: Int = 3, block: () -> T): T {
        var delayMs = 2000L
        var lastException: RuntimeException? = null
        repeat(maxAttempts) { attempt ->
            try {
                return block()
            } catch (e: HttpServerErrorException) {
                log.warn("AI API server error (attempt ${attempt + 1}/$maxAttempts): ${e.statusCode}")
                lastException = e
                if (attempt < maxAttempts - 1) Thread.sleep(delayMs)
                delayMs *= 2
            } catch (e: HttpClientErrorException) {
                if (e.statusCode.value() == 429) {
                    log.warn("AI API rate limited (attempt ${attempt + 1}/$maxAttempts)")
                    lastException = e
                    if (attempt < maxAttempts - 1) Thread.sleep(delayMs)
                    delayMs *= 2
                } else {
                    throw e
                }
            }
        }
        throw lastException!!
    }
}

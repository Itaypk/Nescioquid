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
    private val reasoningResolver: ReasoningResolver,
) {
    private val log = LoggerFactory.getLogger(AiClient::class.java)

    private val client = RestClient.builder()
        .baseUrl(properties.baseUrl)
        .defaultHeader("Authorization", "Bearer ${properties.apiKey.trim()}")
        .build()

    /**
     * Sends a chat request to the AI API. [context] attributes the call for usage accounting and
     * is the carrier for the pre-call gate. The gate runs first and may throw to refuse the call;
     * otherwise usage is recorded for both success and failure.
     */
    fun chat(request: ChatRequest, context: AiCallContext): ChatResponse {
        // Apply per-functionality reasoning centrally (keyed on the call's conversation type), unless
        // the caller already set it explicitly. Guarded by the model's fetched capabilities.
        val effectiveRequest =
            if (request.reasoning == null) {
                request.copy(reasoning = reasoningResolver.resolve(context.conversationType, request.model))
            } else {
                request
            }
        callGate.beforeCall(context, effectiveRequest)
        val response = try {
            withRetry {
                log.debug("Sending chat request to AI API: model=${effectiveRequest.model}, messages=${effectiveRequest.messages.size}")
                client.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(effectiveRequest)
                    .retrieve()
                    .body(ChatResponse::class.java)!!
            }
        } catch (e: RuntimeException) {
            callListener.recordFailure(context, effectiveRequest)
            throw e
        }
        callListener.recordSuccess(context, effectiveRequest, response)
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

package dev.itayp.nescioquid.openrouter

import org.springframework.core.io.ByteArrayResource
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import java.util.Base64

/**
 * The speech-to-text client, speaking the dedicated `POST /audio/transcriptions` endpoint.
 *
 * Kept separate from [AiClient] for the same reason as [ImageClient]: `/audio/transcriptions` is its
 * own endpoint with its own request and response shapes. Everything the clients genuinely share —
 * auth, timeouts, the retry policy, and the gate/listener seams — lives in [AiTransport], so a
 * call here is accounted exactly as a chat or image call is.
 *
 * On OpenRouter the request goes as JSON with base64 `input_audio`. Under
 * [ApiDialect.OPENAI_COMPATIBLE] the same [TranscriptionRequest] goes as OpenAI's multipart form
 * (`file`, `model`, `language`, `temperature`, `response_format=json`), which is what OpenAI and the
 * self-hosted Whisper servers take; [TranscriptionRequest.provider] has no counterpart there and is
 * dropped.
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
class TranscriptionClient(private val transport: AiTransport) {

    /**
     * Transcribes one audio clip. The gate runs first and may throw to refuse the call; the listener
     * is then notified exactly once, for success or failure, as on the chat and image paths.
     *
     * Blocking, and runs against its own [AiClientProperties.transcriptionReadTimeout] rather than the
     * chat read timeout, since a long recording can take a while to transcribe.
     */
    fun transcribe(request: TranscriptionRequest, context: AiCallContext): TranscriptionResponse =
        when (transport.dialect) {
            ApiDialect.OPENROUTER ->
                transport.call(context, request, AUDIO_TRANSCRIPTIONS_PATH, TranscriptionResponse::class.java)
            ApiDialect.OPENAI_COMPATIBLE ->
                transport.call(
                    context, request, AUDIO_TRANSCRIPTIONS_PATH, TranscriptionResponse::class.java,
                    body = request.toMultipartForm(), contentType = MediaType.MULTIPART_FORM_DATA,
                )
        }
}

/**
 * OpenAI's multipart shape of this request. The file part needs a filename: servers infer the audio
 * container from its extension rather than from a content type.
 */
internal fun TranscriptionRequest.toMultipartForm(): MultiValueMap<String, Any> {
    val bytes = Base64.getDecoder().decode(inputAudio.data)
    val file = object : ByteArrayResource(bytes) {
        override fun getFilename() = "audio.${inputAudio.format}"
    }
    return LinkedMultiValueMap<String, Any>().apply {
        add("file", file)
        add("model", model)
        add("response_format", "json")
        language?.let { add("language", it) }
        temperature?.let { add("temperature", it.toString()) }
    }
}

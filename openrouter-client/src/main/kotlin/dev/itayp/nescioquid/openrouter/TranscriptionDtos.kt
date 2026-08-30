package dev.itayp.nescioquid.openrouter

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import java.util.Base64

// ── Request ──────────────────────────────────────────────────────────────────

/**
 * A transcription request for OpenRouter's dedicated speech-to-text endpoint
 * (`POST /audio/transcriptions`).
 *
 * This is the path for giving voice input to a model that has no audio input modality of its own —
 * transcribe here first, then send the resulting [TranscriptionResponse.text] as an ordinary
 * [ChatMessage], rather than attaching a [ContentPart.InputAudio] the target model would reject. It
 * also works for a model that *does* accept audio input directly, if a dedicated transcription model
 * is preferred for cost or accuracy. Check `ModelCapabilityService.get(model)?.inputModalities` to
 * decide which path a given target model needs.
 *
 * [inputAudio] carries raw base64 bytes in the same [InputAudioData] shape chat's `input_audio`
 * content part uses — build a request with [ofBytes] rather than assembling [InputAudioData] by hand.
 *
 * OpenRouter also accepts this endpoint as multipart form-data (for OpenAI-SDK compatibility) and a
 * `response_format: verbose_json` mode for segment/word-level timestamps on OpenAI-compatible
 * providers only; neither is modeled here — this client only ever needs the plain transcript text.
 *
 * @param language ISO-639-1 hint for the source language; omitted lets the model auto-detect.
 * @param temperature 0–1 sampling parameter; lower is more deterministic.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class TranscriptionRequest(
    override val model: String,
    @JsonProperty("input_audio") val inputAudio: InputAudioData,
    val language: String? = null,
    val temperature: Double? = null,
    val provider: ProviderPreferences = ProviderPreferences(),
) : AiRequest {
    companion object {
        fun ofBytes(
            model: String,
            bytes: ByteArray,
            format: String,
            language: String? = null,
            temperature: Double? = null,
        ): TranscriptionRequest = TranscriptionRequest(
            model = model,
            inputAudio = InputAudioData(data = Base64.getEncoder().encodeToString(bytes), format = format),
            language = language,
            temperature = temperature,
        )
    }
}

// ── Response ─────────────────────────────────────────────────────────────────

/**
 * The result of a transcription.
 *
 * Unlike [ChatResponse] and [ImageResponse], OpenRouter's transcription endpoint echoes neither an id
 * nor [model]/[provider] — the latter two are captured only for interface parity with [AiResponse]
 * and stay null in practice.
 */
data class TranscriptionResponse(
    val text: String,
    override val usage: TranscriptionUsage? = null,
    override val model: String? = null,
    override val provider: String? = null,
) : AiResponse

/**
 * Transcription usage/cost accounting — a different breakdown from [Usage]'s prompt/completion
 * tokens: OpenRouter bills transcription primarily by audio [seconds], alongside whatever token
 * counts the provider additionally reports.
 */
data class TranscriptionUsage(
    val seconds: Double? = null,
    @JsonProperty("total_tokens") val totalTokens: Int? = null,
    @JsonProperty("input_tokens") val inputTokens: Int? = null,
    @JsonProperty("output_tokens") val outputTokens: Int? = null,
    override val cost: Double? = null,
) : CallUsage

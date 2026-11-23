package dev.itayp.nescioquid.openrouter

import dev.itayp.nescioquid.openrouter.OpenRouterIntegrationTest.Companion.resolveConfig
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.assertNotNull

/**
 * A live check that an OpenAI-compatible speech-to-text server accepts [TranscriptionClient]'s
 * multipart form and that its answer parses.
 *
 * Skipped unless `OPENAI_COMPATIBLE_TEST_TRANSCRIPTION_MODEL` is set, with the server at
 * `OPENAI_COMPATIBLE_TEST_TRANSCRIPTION_BASE_URL` (falling back to `OPENAI_COMPATIBLE_TEST_BASE_URL`)
 * and an optional `OPENAI_COMPATIBLE_TEST_API_KEY`. A local speaches server is the cheapest target
 * (`http://localhost:8000/v1`, `Systran/faster-whisper-tiny`).
 */
@Tag("integration")
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class OpenAiCompatibleTranscriptionIntegrationTest {

    private val baseUrl = resolveConfig("OPENAI_COMPATIBLE_TEST_TRANSCRIPTION_BASE_URL")
        ?: resolveConfig("OPENAI_COMPATIBLE_TEST_BASE_URL")
    private val model = resolveConfig("OPENAI_COMPATIBLE_TEST_TRANSCRIPTION_MODEL")

    @BeforeEach
    fun requireServer() = assumeTrue(baseUrl != null && model != null, "no OpenAI-compatible transcription server configured")

    @Test
    fun `a clip is accepted as multipart and the transcript parses`() {
        val client = TranscriptionClient(
            AiTransport(
                properties = AiClientProperties(
                    apiKey = resolveConfig("OPENAI_COMPATIBLE_TEST_API_KEY").orEmpty(),
                    baseUrl = baseUrl!!,
                    configuredModels = setOf(model!!),
                    dialect = ApiDialect.OPENAI_COMPATIBLE,
                ),
                callGate = { _, _ -> },
                callListener = object : AiCallListener {
                    override fun recordSuccess(context: AiCallContext, request: AiRequest, response: AiResponse) = Unit
                    override fun recordFailure(context: AiCallContext, request: AiRequest) = Unit
                },
            ),
        )

        // A tone rather than speech keeps the test fixture-free: what it proves is the wire shape,
        // so any transcript (often empty) will do.
        val response = client.transcribe(
            TranscriptionRequest.ofBytes(model = model, bytes = toneWav(), format = "wav", language = "en"),
            AiCallContext(userId = "integration", conversationType = "integration-test"),
        )

        assertNotNull(response.text)
    }

    /** One second of a 440 Hz tone as 16 kHz mono 16-bit PCM WAV. */
    private fun toneWav(sampleRate: Int = 16_000): ByteArray {
        val pcm = ByteBuffer.allocate(sampleRate * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(sampleRate) { pcm.putShort((sin(2 * PI * 440 * it / sampleRate) * 8_000).toInt().toShort()) }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.capacity()); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(sampleRate)
            putInt(sampleRate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(pcm.capacity())
        }
        return ByteArrayOutputStream().apply { write(header.array()); write(pcm.array()) }.toByteArray()
    }
}

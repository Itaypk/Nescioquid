package dev.itayp.nescioquid.openrouter

import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.web.client.RestClient

const val TEST_BASE_URL = "https://openrouter.ai/api/v1"
const val COMPLETIONS_URL = "$TEST_BASE_URL/chat/completions"
const val IMAGES_URL = "$TEST_BASE_URL/images"

val testContext = AiCallContext(userId = "00000000-0000-0000-0000-000000000001", conversationType = "test")

fun testProperties() = AiClientProperties(apiKey = "k", baseUrl = TEST_BASE_URL, configuredModels = emptySet())

fun testRequest(model: String = "openai/gpt-oss-20b:free") =
    ChatRequest(model = model, messages = listOf(ChatMessage("user", "Hi")))

fun testImageRequest(model: String = "bytedance-seed/seedream-4.5") =
    ImageRequest(model = model, prompt = "a red panda astronaut")

/** Records gate invocations so tests can assert *when* the pre-call check runs, not just that it did. */
class RecordingGate(private val onCall: (AiCallContext, AiRequest) -> Unit = { _, _ -> }) : AiCallGate {
    var calls = 0
        private set

    override fun beforeCall(context: AiCallContext, request: AiRequest) {
        calls++
        onCall(context, request)
    }
}

class RecordingListener : AiCallListener {
    val successes = mutableListOf<AiResponse>()
    var failures = 0
        private set

    /** The chat responses recorded, for the majority of tests that only send chat requests. */
    val chatSuccesses: List<ChatResponse> get() = successes.filterIsInstance<ChatResponse>()

    val imageSuccesses: List<ImageResponse> get() = successes.filterIsInstance<ImageResponse>()

    override fun recordSuccess(context: AiCallContext, request: AiRequest, response: AiResponse) {
        successes += response
    }

    override fun recordFailure(context: AiCallContext, request: AiRequest) {
        failures++
    }
}

class TestClient(
    val client: AiClient,
    val server: MockRestServiceServer,
    val gate: RecordingGate,
    val listener: RecordingListener,
)

class TestImageClient(
    val client: ImageClient,
    val server: MockRestServiceServer,
    val gate: RecordingGate,
    val listener: RecordingListener,
)

/** A transport bound to a [MockRestServiceServer], which all three of its clients then share. */
private fun testTransport(gate: RecordingGate, listener: RecordingListener): Pair<OpenRouterTransport, MockRestServiceServer> {
    val builder = RestClient.builder()
    val server = MockRestServiceServer.bindTo(builder).build()
    return OpenRouterTransport(testProperties(), gate, listener, builder) to server
}

fun testClient(gate: RecordingGate = RecordingGate(), listener: RecordingListener = RecordingListener()): TestClient {
    val (transport, server) = testTransport(gate, listener)
    return TestClient(AiClient(transport), server, gate, listener)
}

fun testImageClient(
    gate: RecordingGate = RecordingGate(),
    listener: RecordingListener = RecordingListener(),
): TestImageClient {
    val (transport, server) = testTransport(gate, listener)
    return TestImageClient(ImageClient(transport), server, gate, listener)
}

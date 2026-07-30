package dev.itayp.nescioquid.openrouter

import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.web.client.RestClient

const val TEST_BASE_URL = "https://openrouter.ai/api/v1"
const val COMPLETIONS_URL = "$TEST_BASE_URL/chat/completions"

val testContext = AiCallContext(userId = "00000000-0000-0000-0000-000000000001", conversationType = "test")

fun testRequest(model: String = "openai/gpt-oss-20b:free") =
    ChatRequest(model = model, messages = listOf(ChatMessage("user", "Hi")))

/** Records gate invocations so tests can assert *when* the pre-call check runs, not just that it did. */
class RecordingGate(private val onCall: (AiCallContext, ChatRequest) -> Unit = { _, _ -> }) : AiCallGate {
    var calls = 0
        private set

    override fun beforeCall(context: AiCallContext, request: ChatRequest) {
        calls++
        onCall(context, request)
    }
}

class RecordingListener : AiCallListener {
    val successes = mutableListOf<ChatResponse>()
    var failures = 0
        private set

    override fun recordSuccess(context: AiCallContext, request: ChatRequest, response: ChatResponse) {
        successes += response
    }

    override fun recordFailure(context: AiCallContext, request: ChatRequest) {
        failures++
    }
}

class TestClient(
    val client: AiClient,
    val server: MockRestServiceServer,
    val gate: RecordingGate,
    val listener: RecordingListener,
)

fun testClient(gate: RecordingGate = RecordingGate(), listener: RecordingListener = RecordingListener()): TestClient {
    val builder = RestClient.builder()
    val server = MockRestServiceServer.bindTo(builder).build()
    val properties = AiClientProperties(apiKey = "k", baseUrl = TEST_BASE_URL, configuredModels = emptySet())
    return TestClient(AiClient(properties, gate, listener, builder), server, gate, listener)
}

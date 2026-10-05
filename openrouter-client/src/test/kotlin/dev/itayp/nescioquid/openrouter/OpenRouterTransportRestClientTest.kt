package dev.itayp.nescioquid.openrouter

import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import kotlin.test.assertEquals

class OpenRouterTransportRestClientTest {

    @Test
    fun `restClient reaches unwrapped endpoints with the base URL and API key, unaccounted`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val gate = RecordingGate()
        val listener = RecordingListener()
        val transport = OpenRouterTransport(testProperties(), gate, listener, builder)
        server.expect(requestTo("$TEST_BASE_URL/credits"))
            .andExpect(header("Authorization", "Bearer k"))
            .andRespond(withSuccess("""{"data":{"total_credits":10}}""", MediaType.APPLICATION_JSON))

        val body = transport.restClient.get().uri("/credits").retrieve().body(String::class.java)

        server.verify()
        assertEquals("""{"data":{"total_credits":10}}""", body)
        assertEquals(0, gate.calls)
        assertEquals(0, listener.successes.size + listener.failures)
    }
}

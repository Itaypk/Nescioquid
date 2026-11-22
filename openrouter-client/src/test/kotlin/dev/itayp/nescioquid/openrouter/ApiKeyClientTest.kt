package dev.itayp.nescioquid.openrouter

import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ApiKeyClientTest {

    private fun client(): Pair<ApiKeyClient, MockRestServiceServer> {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val transport = AiTransport(testProperties(), RecordingGate(), RecordingListener(), builder)
        return ApiKeyClient(transport) to server
    }

    @Test
    fun `reads usage, limit and the effective budget`() {
        val (client, server) = client()
        // Trimmed from the example in OpenRouter's API reference; the fields we don't map stay in to
        // prove they're ignored rather than rejected.
        server.expect(requestTo("https://openrouter.ai/api/v1/key"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer k"))
            .andRespond(
                withSuccess(
                    """
                    {"data":{
                      "label":"sk-or-v1-au7...890","limit":100,"limit_remaining":74.5,"limit_reset":"monthly",
                      "usage":25.5,"usage_daily":1.5,"usage_weekly":5.5,"usage_monthly":20.5,
                      "byok_usage":17.38,"include_byok_in_limit":false,"is_free_tier":false,
                      "effective_budget":{"scope":"member","guardrail_id":"g","budget_id":null,"limit_usd":50,
                        "spend_usd":32.5,"remaining_usd":17.5,"reset_interval":"weekly",
                        "resets_at":"2026-10-12T00:00:00.000Z","include_byok":false},
                      "rate_limit":{"interval":"1h","note":"deprecated","requests":1000},
                      "expires_at":"2027-12-31T23:59:59Z"
                    }}
                    """.trimIndent(),
                    MediaType.APPLICATION_JSON,
                ),
            )

        val key = client.currentKey()

        server.verify()
        assertEquals(100.0, key.limit)
        assertEquals(74.5, key.limitRemaining)
        assertEquals("monthly", key.limitReset)
        assertEquals(25.5, key.usage)
        assertEquals(1.5, key.usageDaily)
        assertEquals(5.5, key.usageWeekly)
        assertEquals(20.5, key.usageMonthly)
        assertEquals(17.5, key.effectiveBudget?.remainingUsd)
        assertEquals(Instant.parse("2026-10-12T00:00:00Z"), key.effectiveBudget?.resetsAt)
        assertEquals(Instant.parse("2027-12-31T23:59:59Z"), key.expiresAt)
    }

    @Test
    fun `a key with no cap has a null limit and remainder`() {
        val (client, server) = client()
        server.expect(requestTo("https://openrouter.ai/api/v1/key"))
            .andRespond(
                withSuccess(
                    """{"data":{"label":"k","limit":null,"limit_remaining":null,"limit_reset":null,"usage":3.25,"effective_budget":null}}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        val key = client.currentKey()

        assertNull(key.limit)
        assertNull(key.limitRemaining)
        assertNull(key.effectiveBudget)
        assertNull(key.expiresAt)
        assertEquals(3.25, key.usage)
    }

    @Test
    fun `a rejected key throws rather than reading as zero`() {
        val (client, server) = client()
        server.expect(requestTo("https://openrouter.ai/api/v1/key"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED))

        val e = assertFailsWith<HttpClientErrorException> { client.currentKey() }
        assertEquals(401, e.statusCode.value())
        server.verify()
    }
}

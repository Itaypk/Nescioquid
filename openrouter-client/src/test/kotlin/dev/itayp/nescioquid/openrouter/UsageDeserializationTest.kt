package dev.itayp.nescioquid.openrouter

import tools.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UsageDeserializationTest {

    private val objectMapper = jacksonObjectMapper()

    @Test
    fun `parses prompt_tokens_details when present`() {
        val json = """
            {
              "prompt_tokens": 10339,
              "completion_tokens": 60,
              "total_tokens": 10399,
              "prompt_tokens_details": {
                "cached_tokens": 10318,
                "cache_write_tokens": 21,
                "audio_tokens": 0
              }
            }
        """.trimIndent()

        val usage = objectMapper.readValue(json, Usage::class.java)

        assertEquals(10339, usage.promptTokens)
        assertEquals(10318, usage.promptTokensDetails?.cachedTokens)
        assertEquals(21, usage.promptTokensDetails?.cacheWriteTokens)
        assertEquals(0, usage.promptTokensDetails?.audioTokens)
    }

    @Test
    fun `prompt_tokens_details is null when the field is absent`() {
        val json = """{"prompt_tokens": 100, "completion_tokens": 40, "total_tokens": 140}"""

        val usage = objectMapper.readValue(json, Usage::class.java)

        assertNull(usage.promptTokensDetails)
    }

    @Test
    fun `tolerates a partial prompt_tokens_details object`() {
        val json = """
            {
              "prompt_tokens": 100,
              "completion_tokens": 40,
              "prompt_tokens_details": { "cached_tokens": 64 }
            }
        """.trimIndent()

        val usage = objectMapper.readValue(json, Usage::class.java)

        assertEquals(64, usage.promptTokensDetails?.cachedTokens)
        assertNull(usage.promptTokensDetails?.cacheWriteTokens)
        assertNull(usage.promptTokensDetails?.audioTokens)
    }

    @Test
    fun `reads the cost reported alongside token counts`() {
        val usage = objectMapper.readValue(
            """{"prompt_tokens":0,"completion_tokens":4175,"total_tokens":4175,"cost":0.04}""",
            Usage::class.java,
        )

        assertEquals(0.04, usage.cost)
        assertEquals(4175, usage.completionTokens)
    }

    @Test
    fun `cost is null when the response omits it`() {
        val usage = objectMapper.readValue(
            """{"prompt_tokens":7,"completion_tokens":1}""",
            Usage::class.java,
        )

        assertNull(usage.cost)
    }
}

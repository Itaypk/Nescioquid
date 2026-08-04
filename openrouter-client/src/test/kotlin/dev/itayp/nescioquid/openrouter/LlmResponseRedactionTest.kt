package dev.itayp.nescioquid.openrouter

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LlmResponseRedactionTest {

    @Test
    fun `preserves object keys and structure but redacts long leaf values`() {
        val redacted = redactLlmResponse("""{"title":"Call the dentist about a checkup","category_id":"abc123"}""")

        assertTrue(redacted.contains("\"title\""))
        assertTrue(redacted.contains("\"category_id\""))
        assertTrue(redacted.contains("Cal...kup\""))
        assertFalse(redacted.contains("Call the dentist"))
    }

    @Test
    fun `fully masks values at or under six characters`() {
        val redacted = redactLlmResponse("""{"priority":"high"}""")

        assertTrue(redacted.contains("\"***\""))
        assertFalse(redacted.contains("high"))
    }

    @Test
    fun `redacts nested objects and arrays without leaking values`() {
        val redacted = redactLlmResponse(
            """{"items":[{"title":"Buy milk and eggs","tags":["groceries household"]}]}""",
        )

        assertTrue(redacted.contains("\"items\""))
        assertTrue(redacted.contains("\"title\""))
        assertTrue(redacted.contains("\"tags\""))
        assertFalse(redacted.contains("Buy milk"))
        assertFalse(redacted.contains("groceries household"))
    }

    @Test
    fun `preserves null values as-is`() {
        val redacted = redactLlmResponse("""{"deadline":null}""")

        assertTrue(redacted.contains("\"deadline\" : null"))
    }

    @Test
    fun `strips markdown code fences before parsing`() {
        val redacted = redactLlmResponse("```json\n{\"title\":\"Prep quarterly report\"}\n```")

        assertTrue(redacted.contains("\"title\""))
        assertFalse(redacted.contains("Prep quarterly report"))
    }

    @Test
    fun `tolerates common LLM JSON quirks - unquoted keys, single quotes, comments`() {
        val redacted = redactLlmResponse(
            """
            {
              // a trailing comment the strict parser would reject
              title: 'Renew passport before the trip'
            }
            """.trimIndent(),
        )

        assertTrue(redacted.contains("\"title\""))
        assertFalse(redacted.contains("Renew passport"))
    }

    @Test
    fun `falls back to redacting the whole string when it is not JSON at all`() {
        val redacted = redactLlmResponse("Sorry, I can't help with that request right now.")

        assertTrue(redacted.startsWith("(unparseable)"))
        assertFalse(redacted.contains("Sorry, I can't help"))
    }

    @Test
    fun `short unparseable input is fully masked, not just truncated`() {
        val redacted = redactLlmResponse("oops")

        assertEquals("(unparseable) ***", redacted)
    }

    @Test
    fun `a custom redactValue can truncate instead of mask, for content that is long but not sensitive`() {
        val redacted = redactLlmResponse(
            """{"title":"Call the dentist about a checkup and reschedule for next week"}""",
            redactValue = truncateValue(10),
        )

        assertTrue(redacted.contains("\"Call the d… (61 chars)\""))
    }

    @Test
    fun `truncateValue passes short values through unchanged`() {
        assertEquals("hi", truncateValue(10)("hi"))
    }

    @Test
    fun `a custom redactValue also governs the unparseable fallback`() {
        val redacted = redactLlmResponse("Sorry, I can't help with that request right now.", redactValue = truncateValue(10))

        assertEquals("(unparseable) Sorry, I c… (48 chars)", redacted)
    }
}

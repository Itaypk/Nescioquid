package dev.itayp.nescioquid.openrouter

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class SseParserTest {

    private fun parse(body: String): List<String> = sseDataLines(body.reader().buffered()).toList()

    @Test
    fun `yields the payload of each data line`() {
        val events = parse(
            """
            data: {"a":1}

            data: {"a":2}

            """.trimIndent(),
        )
        assertEquals(listOf("""{"a":1}""", """{"a":2}"""), events)
    }

    @Test
    fun `skips comment keepalives`() {
        val events = parse(
            """
            : OPENROUTER PROCESSING

            data: {"a":1}

            : OPENROUTER PROCESSING
            data: {"a":2}

            """.trimIndent(),
        )
        assertEquals(listOf("""{"a":1}""", """{"a":2}"""), events)
    }

    @Test
    fun `stops at the DONE sentinel and ignores anything after it`() {
        val events = parse(
            """
            data: {"a":1}

            data: [DONE]

            data: {"a":2}

            """.trimIndent(),
        )
        assertEquals(listOf("""{"a":1}"""), events)
    }

    @Test
    fun `joins multiple data lines of one event with a newline`() {
        val events = parse(
            """
            data: {"a":
            data: 1}

            """.trimIndent(),
        )
        assertEquals(listOf("{\"a\":\n1}"), events)
    }

    @Test
    fun `flushes a final event that has no trailing blank line`() {
        assertEquals(listOf("""{"a":1}"""), parse("""data: {"a":1}"""))
    }

    @Test
    fun `tolerates a missing space after the colon`() {
        assertEquals(listOf("""{"a":1}"""), parse("""data:{"a":1}"""))
    }

    @Test
    fun `ignores non-data fields`() {
        val events = parse(
            """
            event: message
            id: 7
            retry: 100
            data: {"a":1}

            """.trimIndent(),
        )
        assertEquals(listOf("""{"a":1}"""), events)
    }

    @Test
    fun `an empty body yields nothing`() {
        assertEquals(emptyList(), parse(""))
    }
}

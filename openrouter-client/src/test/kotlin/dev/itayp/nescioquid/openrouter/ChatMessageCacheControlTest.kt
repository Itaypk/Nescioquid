package dev.itayp.nescioquid.openrouter

import tools.jackson.core.type.TypeReference
import tools.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ChatMessageCacheControlTest {

    private val objectMapper = jacksonObjectMapper()

    private fun toMap(message: ChatMessage): Map<String, Any?> =
        objectMapper.readValue(
            objectMapper.writeValueAsString(message),
            object : TypeReference<Map<String, Any?>>() {},
        )

    @Test
    fun `cacheable message serializes content as a text part with cache_control breakpoint`() {
        val map = toMap(ChatMessage.cacheable(role = "system", text = "large static prompt"))

        assertEquals("system", map["role"])
        @Suppress("UNCHECKED_CAST")
        val parts = map["content"] as List<Map<String, Any?>>
        val part = parts.single()
        assertEquals("text", part["type"])
        assertEquals("large static prompt", part["text"])
        assertEquals(mapOf("type" to "ephemeral"), part["cache_control"])
    }

    @Test
    fun `plain string message serializes content as a bare string with no cache_control`() {
        val message = ChatMessage(role = "user", content = "hi")

        assertEquals("hi", toMap(message)["content"])
        assertFalse("cache_control" in objectMapper.writeValueAsString(message))
    }

    @Test
    fun `contentText reads plain string content and is null for the array form`() {
        assertEquals("hi", ChatMessage(role = "assistant", content = "hi").contentText)
        assertNull(ChatMessage.cacheable(role = "system", text = "x").contentText)
        assertNull(ChatMessage(role = "assistant").contentText)
    }
}

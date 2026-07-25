package dev.itayp.nescioquid.openrouter

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import tools.jackson.core.type.TypeReference
import tools.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResponseFormatSerializationTest {

    private val objectMapper = jacksonObjectMapper()

    data class Recipe(
        val title: String,
        @JsonPropertyDescription("Ordered preparation steps") val steps: List<String>,
    )

    private fun toMap(request: ChatRequest): Map<String, Any?> =
        objectMapper.readValue(
            objectMapper.writeValueAsString(request),
            object : TypeReference<Map<String, Any?>>() {},
        )

    @Test
    fun `response_format is omitted when unset`() {
        val json = objectMapper.writeValueAsString(
            ChatRequest(model = "openai/gpt-4o", messages = listOf(ChatMessage(role = "user", content = "hi"))),
        )
        assertFalse("response_format" in json)
    }

    @Test
    fun `structuredOutput builds the exact OpenRouter response_format shape`() {
        val request = ChatRequest(
            model = "openai/gpt-4o",
            messages = listOf(ChatMessage(role = "user", content = "give me a recipe")),
            responseFormat = structuredOutput<Recipe>("recipe"),
        )

        val map = toMap(request)
        @Suppress("UNCHECKED_CAST")
        val responseFormat = map["response_format"] as Map<String, Any?>
        assertEquals("json_schema", responseFormat["type"])

        @Suppress("UNCHECKED_CAST")
        val jsonSchema = responseFormat["json_schema"] as Map<String, Any?>
        assertEquals("recipe", jsonSchema["name"])
        assertEquals(true, jsonSchema["strict"])

        @Suppress("UNCHECKED_CAST")
        val schema = jsonSchema["schema"] as Map<String, Any?>
        assertEquals("object", schema["type"])
        assertEquals(false, schema["additionalProperties"])
        @Suppress("UNCHECKED_CAST")
        val required = schema["required"] as List<String>
        assertEquals(setOf("title", "steps"), required.toSet())
    }

    @Test
    fun `strict flag is overridable`() {
        val format = structuredOutput<Recipe>("recipe", strict = false)
        assertEquals(false, format.jsonSchema.strict)
        assertTrue(format.jsonSchema.schema.isNotEmpty())
    }
}

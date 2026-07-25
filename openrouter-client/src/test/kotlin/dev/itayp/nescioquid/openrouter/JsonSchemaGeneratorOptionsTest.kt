package dev.itayp.nescioquid.openrouter

import com.fasterxml.jackson.annotation.JsonValue
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class JsonSchemaGeneratorOptionsTest {

    @Suppress("UNCHECKED_CAST")
    private fun props(schema: Map<String, Any>): Map<String, Map<String, Any>> =
        schema["properties"] as Map<String, Map<String, Any>>

    @Suppress("UNCHECKED_CAST")
    private fun required(schema: Map<String, Any>): List<String> = schema["required"] as List<String>

    data class Optionals(
        val title: String,
        val note: String?,
        val count: Int?,
    )

    @Test
    fun `non-strict mode omits nullables from required, leaves plain types, and no additionalProperties`() {
        val schema = JsonSchemaGenerator.generate(Optionals::class, strict = false)

        assertEquals(listOf("title"), required(schema))
        assertFalse("additionalProperties" in schema)
        // Nullable properties keep a plain type (no ["string","null"] union) in non-strict mode.
        assertEquals("string", props(schema)["note"]!!["type"])
        assertEquals("integer", props(schema)["count"]!!["type"])
    }

    enum class Color(@get:JsonValue val wire: String) { RED("red"), BLUE("blue") }

    data class Painted(val color: Color)

    @Test
    fun `enum values come from a JsonValue member when present`() {
        val color = props(JsonSchemaGenerator.generate(Painted::class))["color"]!!
        assertEquals("string", color["type"])
        assertEquals(listOf("red", "blue"), color["enum"])
    }

    data class TagArg(val id: String?, val label: String, val colorId: String?)

    data class CreateArgs(
        val title: String,
        val priority: String?,
        val tags: List<TagArg>,
    )

    @Test
    fun `customize overrides enum and description on top-level and nested-array-item properties`() {
        val schema = jsonSchema<CreateArgs>(strict = false) {
            property("priority").enum(listOf("low", "medium", "high")).description("Optional priority.")
            property("tags").items().property("colorId").enum(listOf("red", "green"))
        }

        val priority = props(schema)["priority"]!!
        assertEquals(listOf("low", "medium", "high"), priority["enum"])
        assertEquals("Optional priority.", priority["description"])

        @Suppress("UNCHECKED_CAST")
        val tagItem = (props(schema)["tags"]!!["items"] as Map<String, Any>)
        @Suppress("UNCHECKED_CAST")
        val colorId = (tagItem["properties"] as Map<String, Map<String, Any>>)["colorId"]!!
        assertEquals(listOf("red", "green"), colorId["enum"])

        // Non-strict semantics carry through the nested object: only non-null fields are required.
        assertEquals(setOf("title", "tags"), required(schema).toSet())
        @Suppress("UNCHECKED_CAST")
        assertEquals(listOf("label"), tagItem["required"] as List<String>)
    }

    @Test
    fun `customize navigation to a missing property fails clearly`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            jsonSchema<CreateArgs> { property("nope") }
        }
        assertContains(ex.message!!, "nope")
    }

    @Test
    fun `structuredOutput threads strict into both the wire flag and the schema`() {
        val strictFmt = structuredOutput<Optionals>("opt")
        assertEquals(true, strictFmt.jsonSchema.strict)
        assertEquals(false, strictFmt.jsonSchema.schema["additionalProperties"])

        val looseFmt = structuredOutput<Optionals>("opt", strict = false)
        assertEquals(false, looseFmt.jsonSchema.strict)
        assertFalse("additionalProperties" in looseFmt.jsonSchema.schema)
        @Suppress("UNCHECKED_CAST")
        assertEquals(listOf("title"), looseFmt.jsonSchema.schema["required"] as List<String>)
    }
}

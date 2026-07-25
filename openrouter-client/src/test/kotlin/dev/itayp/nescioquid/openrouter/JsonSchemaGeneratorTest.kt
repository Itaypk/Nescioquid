package dev.itayp.nescioquid.openrouter

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class JsonSchemaGeneratorTest {

    enum class Priority { LOW, HIGH }

    data class Address(val city: String, val zip: String?)

    data class Person(
        val name: String,
        val age: Int,
        val active: Boolean,
        val score: Double,
        val priority: Priority,
        @JsonProperty("nick_name") val nickname: String,
        @JsonPropertyDescription("Free-form notes") val notes: String?,
        val tags: List<String>,
        val addresses: List<Address>,
        val home: Address,
    )

    private val schema = JsonSchemaGenerator.generate(Person::class)

    @Suppress("UNCHECKED_CAST")
    private fun props(objectSchema: Map<String, Any>): Map<String, Map<String, Any>> =
        objectSchema["properties"] as Map<String, Map<String, Any>>

    @Suppress("UNCHECKED_CAST")
    private fun required(objectSchema: Map<String, Any>): List<String> =
        objectSchema["required"] as List<String>

    @Test
    fun `top level is a closed object with all properties required`() {
        assertEquals("object", schema["type"])
        assertEquals(false, schema["additionalProperties"])
        // Every property — including the nullable `notes` — is listed in required (strict semantics).
        assertEquals(
            setOf("name", "age", "active", "score", "priority", "nick_name", "notes", "tags", "addresses", "home"),
            required(schema).toSet(),
        )
    }

    @Test
    fun `primitives map to the expected JSON types`() {
        val p = props(schema)
        assertEquals("string", p["name"]!!["type"])
        assertEquals("integer", p["age"]!!["type"])
        assertEquals("boolean", p["active"]!!["type"])
        assertEquals("number", p["score"]!!["type"])
    }

    @Test
    fun `nullable property gets a type union including null but stays required`() {
        val notes = props(schema)["notes"]!!
        assertEquals(listOf("string", "null"), notes["type"])
        assertContains(required(schema), "notes")
    }

    @Test
    fun `JsonProperty renames the property and JsonPropertyDescription becomes description`() {
        val p = props(schema)
        assertFalse("nickname" in p)
        assertEquals("string", p["nick_name"]!!["type"])
        assertEquals("Free-form notes", p["notes"]!!["description"])
    }

    @Test
    fun `enum serializes as a string with an enum constant list`() {
        val priority = props(schema)["priority"]!!
        assertEquals("string", priority["type"])
        assertEquals(listOf("LOW", "HIGH"), priority["enum"])
    }

    @Test
    fun `collection of primitives becomes an array with typed items`() {
        val tags = props(schema)["tags"]!!
        assertEquals("array", tags["type"])
        @Suppress("UNCHECKED_CAST")
        val items = tags["items"] as Map<String, Any>
        assertEquals("string", items["type"])
    }

    @Test
    fun `nested data class recurses into a closed object`() {
        val home = props(schema)["home"]!!
        assertEquals("object", home["type"])
        assertEquals(false, home["additionalProperties"])
        assertEquals(setOf("city", "zip"), required(home).toSet())
        // The nested nullable field also gets the null union.
        assertEquals(listOf("string", "null"), props(home)["zip"]!!["type"])
    }

    @Test
    fun `collection of nested objects recurses into item schema`() {
        val addresses = props(schema)["addresses"]!!
        assertEquals("array", addresses["type"])
        @Suppress("UNCHECKED_CAST")
        val items = addresses["items"] as Map<String, Any>
        assertEquals("object", items["type"])
        assertEquals(setOf("city", "zip"), required(items).toSet())
    }

    data class Node(val value: String, val child: Node)

    @Test
    fun `recursive type is rejected`() {
        val ex = assertFailsWith<IllegalArgumentException> { JsonSchemaGenerator.generate(Node::class) }
        assertContains(ex.message!!, "recursive")
    }

    data class WithUnsupported(val when_: java.time.Instant)

    @Test
    fun `unsupported property type is rejected with a clear message`() {
        val ex = assertFailsWith<IllegalArgumentException> { JsonSchemaGenerator.generate(WithUnsupported::class) }
        assertContains(ex.message!!, "unsupported")
    }

    @Test
    fun `reified helper delegates to the generator`() {
        assertEquals(schema, jsonSchema<Person>())
    }
}

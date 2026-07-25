package dev.itayp.nescioquid.openrouter

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.fasterxml.jackson.annotation.JsonValue
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.KType
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

/**
 * Generates a JSON Schema object for a Kotlin data class, suitable for OpenRouter's structured
 * outputs (`response_format.json_schema.schema`) and function-tool parameters
 * ([dev.itayp.nescioquid.openrouter.tool.AiTool.parameters]).
 *
 * The output is a plain insertion-ordered [Map] so it drops straight into the existing wire DTOs
 * ([FunctionDefinition.parameters], [JsonSchemaSpec.schema]) without any serialization coupling.
 *
 * **Strict vs. non-strict** ([generate]'s `strict` flag, default `true`):
 * - `strict = true` (as recommended by OpenRouter's `strict: true`): every object sets
 *   `additionalProperties: false` and lists *all* of its properties in `required`; a nullable Kotlin
 *   property is still `required`, but its `type` becomes a `["<base>", "null"]` union so the model
 *   may legitimately emit `null`.
 * - `strict = false`: only non-nullable properties are `required`, nullable ones keep a plain type
 *   and no object forces `additionalProperties`. Use this for function-tool parameters that mirror a
 *   hand-written non-strict schema.
 *
 * Property names honor `@JsonProperty("wire_name")`; a `@JsonPropertyDescription` becomes the
 * schema `description`. Enum wire values come from a `@JsonValue`-annotated enum member when present,
 * else the constant names. Both `@JsonProperty`/`@JsonValue` are the standard Jackson annotations
 * (which remain in the `com.fasterxml.jackson.annotation` package even under Jackson 3).
 *
 * For constraints not derivable from the DTO — e.g. an enum whose allowed values are a runtime list,
 * or a `String` property that should carry an `enum` — pass a `customize` block ([SchemaBuilder]) to
 * patch specific (possibly nested) subschemas after generation.
 *
 * Supported property types: [String]/[CharSequence]/[Char], [Boolean], integer types
 * ([Int]/[Long]/[Short]/[Byte]/[BigInteger]), number types ([Float]/[Double]/[BigDecimal]),
 * enums (→ `enum`), [Collection]/arrays (→ `array`), nested data classes (→ recursed object), and
 * `Map<String, V>` (→ open object via `additionalProperties`; note this is *not* strict-mode
 * compatible — avoid it in strict schemas). Anything else throws [IllegalArgumentException].
 */
object JsonSchemaGenerator {

    fun generate(
        type: KClass<*>,
        strict: Boolean = true,
        customize: SchemaBuilder.() -> Unit = {},
    ): Map<String, Any> {
        val schema = objectSchema(type, emptyList(), strict)
        SchemaBuilder(schema).customize()
        return schema
    }

    private fun objectSchema(type: KClass<*>, path: List<KClass<*>>, strict: Boolean): MutableMap<String, Any> {
        require(type !in path) {
            "Cannot generate a JSON schema for the recursive type ${type.qualifiedName}: " +
                "self-referential DTOs have no finite schema."
        }
        val constructor = type.primaryConstructor
            ?: throw IllegalArgumentException(
                "Cannot generate a JSON schema for ${type.qualifiedName}: it has no primary constructor " +
                    "(only Kotlin classes with a primary constructor, e.g. data classes, are supported).",
            )
        val propertiesByName: Map<String, KProperty1<out Any, *>> =
            type.memberProperties.associateBy { it.name }

        val nextPath = path + type
        val properties = LinkedHashMap<String, Any>()
        val required = ArrayList<String>()

        for (param in constructor.parameters) {
            val kotlinName = param.name
                ?: throw IllegalArgumentException("Cannot generate a schema for a synthetic constructor parameter of ${type.qualifiedName}")
            val property = propertiesByName[kotlinName]
            val wireName = param.findAnnotation<JsonProperty>()?.value?.takeIf { it.isNotBlank() }
                ?: property?.findAnnotation<JsonProperty>()?.value?.takeIf { it.isNotBlank() }
                ?: kotlinName
            val description = param.findAnnotation<JsonPropertyDescription>()?.value
                ?: property?.findAnnotation<JsonPropertyDescription>()?.value

            properties[wireName] = schemaForType(param.type, wireName, type, nextPath, description, strict)
            // Strict schemas require every property; non-strict ones require only the non-nullable ones.
            if (strict || !param.type.isMarkedNullable) {
                required += wireName
            }
        }

        return linkedMapOf<String, Any>(
            "type" to "object",
            "properties" to properties,
            "required" to required,
        ).apply {
            if (strict) this["additionalProperties"] = false
        }
    }

    private fun schemaForType(
        kType: KType,
        propertyName: String,
        owner: KClass<*>,
        path: List<KClass<*>>,
        description: String?,
        strict: Boolean,
    ): MutableMap<String, Any> {
        val schema = LinkedHashMap<String, Any>(baseSchemaForType(kType, propertyName, owner, path, strict))
        // In strict mode a nullable property stays required, so it must admit null via a type union.
        // In non-strict mode it is simply omitted from `required`, so the plain type is enough.
        if (strict && kType.isMarkedNullable) {
            schema["type"] = nullableType(schema["type"], propertyName, owner)
        }
        if (description != null) {
            schema["description"] = description
        }
        return schema
    }

    /** Turns a non-null `type` value into a `["<base>", "null"]` union for a nullable property. */
    private fun nullableType(current: Any?, propertyName: String, owner: KClass<*>): List<String> {
        val base = current as? String
            ?: throw IllegalArgumentException(
                "Cannot make property '$propertyName' of ${owner.qualifiedName} nullable in the schema: " +
                    "its type is not a simple JSON type.",
            )
        return listOf(base, "null")
    }

    private fun baseSchemaForType(
        kType: KType,
        propertyName: String,
        owner: KClass<*>,
        path: List<KClass<*>>,
        strict: Boolean,
    ): Map<String, Any> {
        val klass = kType.classifier as? KClass<*>
            ?: throw IllegalArgumentException(
                "Cannot generate a schema for property '$propertyName' of ${owner.qualifiedName}: " +
                    "its type $kType is not a concrete class (generic type parameters are unsupported).",
            )

        return when {
            klass == String::class || klass == CharSequence::class || klass == Char::class ->
                mapOf("type" to "string")

            klass == Boolean::class -> mapOf("type" to "boolean")

            klass == Int::class || klass == Long::class || klass == Short::class ||
                klass == Byte::class || klass == BigInteger::class ->
                mapOf("type" to "integer")

            klass == Float::class || klass == Double::class || klass == BigDecimal::class ->
                mapOf("type" to "number")

            klass.java.isEnum -> enumSchema(klass)

            klass.java.isArray || klass.isSubclassOf(Collection::class) ->
                arraySchema(kType, propertyName, owner, path, strict)

            klass.isSubclassOf(Map::class) ->
                mapSchema(kType, propertyName, owner, path, strict)

            klass.isData -> objectSchema(klass, path, strict)

            else -> throw IllegalArgumentException(
                "Cannot generate a schema for property '$propertyName' of ${owner.qualifiedName}: " +
                    "type ${klass.qualifiedName} is unsupported. Supported types are primitives, String, " +
                    "enums, collections, Map<String, V>, and data classes.",
            )
        }
    }

    /** Enum → string with an `enum` list; values come from a `@JsonValue` member if present, else names. */
    private fun enumSchema(klass: KClass<*>): Map<String, Any> {
        val jsonValueProp = klass.declaredMemberProperties.firstOrNull {
            it.findAnnotation<JsonValue>() != null || it.getter.findAnnotation<JsonValue>() != null
        }
        val values: List<String> = klass.java.enumConstants.map { constant ->
            if (jsonValueProp != null) {
                @Suppress("UNCHECKED_CAST")
                (jsonValueProp as KProperty1<Any, *>).get(constant).toString()
            } else {
                (constant as Enum<*>).name
            }
        }
        return linkedMapOf("type" to "string", "enum" to values)
    }

    private fun arraySchema(
        kType: KType,
        propertyName: String,
        owner: KClass<*>,
        path: List<KClass<*>>,
        strict: Boolean,
    ): Map<String, Any> {
        val elementType = kType.arguments.firstOrNull()?.type
            ?: throw IllegalArgumentException(
                "Cannot generate a schema for the array/collection property '$propertyName' of " +
                    "${owner.qualifiedName}: its element type is unknown (raw or star-projected).",
            )
        return linkedMapOf(
            "type" to "array",
            "items" to schemaForType(elementType, propertyName, owner, path, description = null, strict = strict),
        )
    }

    private fun mapSchema(
        kType: KType,
        propertyName: String,
        owner: KClass<*>,
        path: List<KClass<*>>,
        strict: Boolean,
    ): Map<String, Any> {
        val keyType = kType.arguments.getOrNull(0)?.type
        require(keyType != null && (keyType.classifier == String::class)) {
            "Cannot generate a schema for the map property '$propertyName' of ${owner.qualifiedName}: " +
                "only Map<String, V> is supported."
        }
        val valueType = kType.arguments.getOrNull(1)?.type
            ?: throw IllegalArgumentException(
                "Cannot generate a schema for the map property '$propertyName' of ${owner.qualifiedName}: " +
                    "its value type is unknown (raw or star-projected).",
            )
        return linkedMapOf(
            "type" to "object",
            "additionalProperties" to schemaForType(valueType, propertyName, owner, path, description = null, strict = strict),
        )
    }
}

/**
 * A cursor over a generated schema tree for targeted post-generation edits (see the `customize`
 * block of [JsonSchemaGenerator.generate]). Navigate with [property] / [items], then set constraints
 * the generator can't derive from the DTO. All methods return `this` (or the child cursor) for
 * chaining, and mutate the underlying schema in place.
 */
class SchemaBuilder internal constructor(private val node: MutableMap<String, Any>) {

    /** Navigate to the subschema of object property [name]. */
    fun property(name: String): SchemaBuilder {
        @Suppress("UNCHECKED_CAST")
        val props = node["properties"] as? MutableMap<String, Any>
            ?: throw IllegalArgumentException("Schema node has no 'properties'; cannot navigate to '$name'.")
        @Suppress("UNCHECKED_CAST")
        val child = props[name] as? MutableMap<String, Any>
            ?: throw IllegalArgumentException("No property '$name' in schema (present: ${props.keys}).")
        return SchemaBuilder(child)
    }

    /** Navigate into an array's `items` subschema. */
    fun items(): SchemaBuilder {
        @Suppress("UNCHECKED_CAST")
        val child = node["items"] as? MutableMap<String, Any>
            ?: throw IllegalArgumentException("Schema node has no array 'items' to navigate into.")
        return SchemaBuilder(child)
    }

    /** Set the `enum` constraint on the current subschema (replacing any existing one). */
    fun enum(values: Collection<String>): SchemaBuilder = put("enum", values.toList())

    /** Set/override the `description` of the current subschema. */
    fun description(text: String): SchemaBuilder = put("description", text)

    /** Escape hatch: set an arbitrary key on the current subschema. */
    fun put(key: String, value: Any): SchemaBuilder {
        node[key] = value
        return this
    }
}

/** Reified convenience: `jsonSchema<MyDto>()`. See [JsonSchemaGenerator]. */
inline fun <reified T : Any> jsonSchema(
    strict: Boolean = true,
    noinline customize: SchemaBuilder.() -> Unit = {},
): Map<String, Any> = JsonSchemaGenerator.generate(T::class, strict, customize)

/**
 * Builds a [ResponseFormat] for OpenRouter structured outputs from a Kotlin DTO, e.g.
 * `ChatRequest(model, messages, responseFormat = structuredOutput<Recipe>("recipe"))`. The `strict`
 * flag governs both the generated schema and the `json_schema.strict` wire flag, so they stay
 * consistent.
 */
inline fun <reified T : Any> structuredOutput(
    name: String,
    strict: Boolean = true,
    noinline customize: SchemaBuilder.() -> Unit = {},
): ResponseFormat =
    ResponseFormat(jsonSchema = JsonSchemaSpec(name = name, strict = strict, schema = jsonSchema<T>(strict = strict, customize = customize)))

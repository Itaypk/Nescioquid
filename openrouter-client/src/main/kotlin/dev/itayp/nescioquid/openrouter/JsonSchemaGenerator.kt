package dev.itayp.nescioquid.openrouter

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KProperty1
import kotlin.reflect.KType
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
 * The schema is **strict-mode compatible** (as recommended by OpenRouter's `strict: true`): every
 * object sets `additionalProperties: false` and lists *all* of its properties in `required`.
 * A nullable Kotlin property is still `required`, but its `type` becomes a `["<base>", "null"]`
 * union so the model may legitimately emit `null` — matching OpenAI/OpenRouter strict semantics.
 *
 * Property names honor `@JsonProperty("wire_name")`; a `@JsonPropertyDescription` becomes the
 * schema `description`. Both are the standard Jackson annotations (which remain in the
 * `com.fasterxml.jackson.annotation` package even under Jackson 3).
 *
 * Supported property types: [String]/[CharSequence]/[Char], [Boolean], integer types
 * ([Int]/[Long]/[Short]/[Byte]/[BigInteger]), number types ([Float]/[Double]/[BigDecimal]),
 * enums (→ `enum` of constant names), [Collection]/arrays (→ `array`), nested data classes
 * (→ recursed object), and `Map<String, V>` (→ open object via `additionalProperties`; note this
 * is *not* strict-mode compatible — avoid it in strict schemas). Anything else throws
 * [IllegalArgumentException].
 */
object JsonSchemaGenerator {

    fun generate(type: KClass<*>): Map<String, Any> = objectSchema(type, emptyList())

    private fun objectSchema(type: KClass<*>, path: List<KClass<*>>): Map<String, Any> {
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

            properties[wireName] = schemaForType(param.type, wireName, type, nextPath, description)
            required += wireName
        }

        return linkedMapOf(
            "type" to "object",
            "properties" to properties,
            "required" to required,
            "additionalProperties" to false,
        )
    }

    private fun schemaForType(
        kType: KType,
        propertyName: String,
        owner: KClass<*>,
        path: List<KClass<*>>,
        description: String?,
    ): Map<String, Any> {
        val schema = LinkedHashMap<String, Any>(baseSchemaForType(kType, propertyName, owner, path))
        if (kType.isMarkedNullable) {
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

            klass.java.isEnum ->
                linkedMapOf(
                    "type" to "string",
                    "enum" to klass.java.enumConstants.map { (it as Enum<*>).name },
                )

            klass.java.isArray || klass.isSubclassOf(Collection::class) ->
                arraySchema(kType, propertyName, owner, path)

            klass.isSubclassOf(Map::class) ->
                mapSchema(kType, propertyName, owner, path)

            klass.isData -> objectSchema(klass, path)

            else -> throw IllegalArgumentException(
                "Cannot generate a schema for property '$propertyName' of ${owner.qualifiedName}: " +
                    "type ${klass.qualifiedName} is unsupported. Supported types are primitives, String, " +
                    "enums, collections, Map<String, V>, and data classes.",
            )
        }
    }

    private fun arraySchema(
        kType: KType,
        propertyName: String,
        owner: KClass<*>,
        path: List<KClass<*>>,
    ): Map<String, Any> {
        val elementType = kType.arguments.firstOrNull()?.type
            ?: throw IllegalArgumentException(
                "Cannot generate a schema for the array/collection property '$propertyName' of " +
                    "${owner.qualifiedName}: its element type is unknown (raw or star-projected).",
            )
        return linkedMapOf(
            "type" to "array",
            "items" to schemaForType(elementType, propertyName, owner, path, description = null),
        )
    }

    private fun mapSchema(
        kType: KType,
        propertyName: String,
        owner: KClass<*>,
        path: List<KClass<*>>,
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
            "additionalProperties" to schemaForType(valueType, propertyName, owner, path, description = null),
        )
    }
}

/** Reified convenience: `jsonSchema<MyDto>()`. See [JsonSchemaGenerator]. */
inline fun <reified T : Any> jsonSchema(): Map<String, Any> = JsonSchemaGenerator.generate(T::class)

/**
 * Builds a [ResponseFormat] for OpenRouter structured outputs from a Kotlin DTO, e.g.
 * `ChatRequest(model, messages, responseFormat = structuredOutput<Recipe>("recipe"))`.
 */
inline fun <reified T : Any> structuredOutput(name: String, strict: Boolean = true): ResponseFormat =
    ResponseFormat(jsonSchema = JsonSchemaSpec(name = name, strict = strict, schema = jsonSchema<T>()))

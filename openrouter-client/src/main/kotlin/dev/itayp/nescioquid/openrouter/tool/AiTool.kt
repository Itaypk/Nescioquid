package dev.itayp.nescioquid.openrouter.tool

interface AiTool {
    val name: String
    val description: String

    /**
     * JSON Schema object describing the function parameters. The recommended way to produce it is
     * `jsonSchema<MyParamsDto>()` (see `dev.itayp.nescioquid.openrouter.JsonSchemaGenerator`), which
     * derives the schema from the Kotlin DTO the arguments are parsed into. It can also be written by
     * hand: `mapOf("type" to "object", "properties" to mapOf(...), "required" to listOf(...))`.
     */
    val parameters: Map<String, Any>

    /** How the orchestrator should dispatch this tool. See [ToolKind]. */
    val kind: ToolKind get() = ToolKind.DATA_LOOKUP

    /** Receives the arguments JSON string from the model; returns the result as a string. */
    fun execute(arguments: String): String
}

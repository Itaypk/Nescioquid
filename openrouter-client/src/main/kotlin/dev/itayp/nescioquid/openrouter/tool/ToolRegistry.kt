package dev.itayp.nescioquid.openrouter.tool

import dev.itayp.nescioquid.openrouter.FunctionDefinition
import dev.itayp.nescioquid.openrouter.ToolDefinition
import org.springframework.stereotype.Component

@Component
class ToolRegistry {
    private val tools = mutableMapOf<String, AiTool>()

    fun register(tool: AiTool) {
        tools[tool.name] = tool
    }

    fun get(name: String): AiTool? = tools[name]

    fun toDefinitions(): List<ToolDefinition> = tools.values.map { tool ->
        ToolDefinition(
            function = FunctionDefinition(
                name = tool.name,
                description = tool.description,
                parameters = tool.parameters,
            )
        )
    }

    fun isEmpty(): Boolean = tools.isEmpty()
}

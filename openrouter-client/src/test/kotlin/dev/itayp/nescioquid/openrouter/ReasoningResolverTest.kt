package dev.itayp.nescioquid.openrouter

import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReasoningResolverTest {

    private val capabilities: ModelCapabilityService = mock()

    /** Builds a resolver whose effort source is a fixed conversationType → effort map. */
    private fun resolver(efforts: Map<String, String?>) =
        ReasoningResolver(ReasoningEffortSource { efforts[it] }, capabilities)

    private fun caps(supportsReasoning: Boolean, supportedEfforts: List<String>? = null) =
        ModelCapabilities(supportsReasoning = supportsReasoning, supportedEfforts = supportedEfforts)

    @Test
    fun `returns effort config when configured and model supports reasoning`() {
        whenever(capabilities.get("some/model")).thenReturn(caps(supportsReasoning = true))
        val resolver = resolver(mapOf("task_search" to "high"))

        val result = resolver.resolve("task_search", "some/model")

        assertEquals(ReasoningConfig(effort = "high"), result)
    }

    @Test
    fun `validates against the model's advertised supported efforts`() {
        whenever(capabilities.get("some/model"))
            .thenReturn(caps(supportsReasoning = true, supportedEfforts = listOf("low", "medium")))
        val resolver = resolver(mapOf("task_search" to "high"))

        // "high" is a valid OpenRouter effort but not advertised by this model → rejected.
        assertNull(resolver.resolve("task_search", "some/model"))
    }

    @Test
    fun `accepts an effort within the model's advertised supported efforts`() {
        whenever(capabilities.get("some/model"))
            .thenReturn(caps(supportsReasoning = true, supportedEfforts = listOf("low", "medium")))
        val resolver = resolver(mapOf("task_search" to "medium"))

        assertEquals(
            ReasoningConfig(effort = "medium"),
            resolver.resolve("task_search", "some/model"),
        )
    }

    @Test
    fun `returns null when the functionality has no configured effort`() {
        val resolver = resolver(mapOf("task_search" to null))

        assertNull(resolver.resolve("task_search", "some/model"))
    }

    @Test
    fun `returns null when the model does not support reasoning`() {
        whenever(capabilities.get("some/model")).thenReturn(caps(supportsReasoning = false))
        val resolver = resolver(mapOf("weekly_planning" to "low"))

        assertNull(resolver.resolve("weekly_planning", "some/model"))
    }

    @Test
    fun `returns null when the model is unknown`() {
        whenever(capabilities.get("some/model")).thenReturn(null)
        val resolver = resolver(mapOf("weekly_planning" to "low"))

        assertNull(resolver.resolve("weekly_planning", "some/model"))
    }

    @Test
    fun `returns null for an invalid effort value when the model advertises no efforts`() {
        whenever(capabilities.get("some/model")).thenReturn(caps(supportsReasoning = true))
        val resolver = resolver(mapOf("slot_reminder" to "turbo"))

        assertNull(resolver.resolve("slot_reminder", "some/model"))
    }

    @Test
    fun `normalizes and trims the configured effort`() {
        whenever(capabilities.get("some/model")).thenReturn(caps(supportsReasoning = true))
        val resolver = resolver(mapOf("task_suggestion" to " Medium "))

        assertEquals(
            ReasoningConfig(effort = "medium"),
            resolver.resolve("task_suggestion", "some/model"),
        )
    }

    @Test
    fun `returns null for an unknown conversation type`() {
        val resolver = resolver(mapOf("task_search" to "high"))

        assertNull(resolver.resolve("unknown_type", "some/model"))
    }
}

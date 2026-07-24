# openrouter-client

A minimal, Spring-native client for the [OpenRouter](https://openrouter.ai) chat-completions API.
Deliberately narrow: request/response DTOs, a retrying transport, model-capability fetching,
reasoning-effort resolution, and a small function-tool abstraction — no framework of its own.

Consumers are expected to be **Spring Boot apps** (the client uses `RestClient` and component beans).

## What's in it

| Class | Role |
| --- | --- |
| `AiClient` | The transport. `chat(request, context)` sends a request with bearer auth and 3-attempt exponential backoff on 5xx/429, applying reasoning centrally and notifying the gate/listener seams. |
| `AiDtos.kt` | Request/response DTOs — `ChatRequest`/`ChatResponse`, the string↔parts `MessageContent` union, prompt-caching `cache_control`, `ReasoningConfig`, usage/token details. Jackson-only. |
| `ModelCapabilityService` | Fetches `/model/{slug}` capabilities (reasoning support, supported efforts, input modalities). Prefetches configured models at startup; caches in memory. |
| `ReasoningResolver` | Validates a configured reasoning effort against a model's advertised capabilities. |
| `AiClientProperties` | The minimal config contract (`apiKey` / `baseUrl` / `configuredModels`) you supply as a bean. |
| `AiCallContext` | Per-call attribution carrier (user, conversation type, optional session/conversation ids). |
| `tool/*` | `AiTool` / `ToolKind` / `ToolRegistry` — a function-tool abstraction and registry. |
| `AssistantJson.kt` | `extractJsonObjectSpan` / `parseAssistantJsonResponse` — pull a JSON object out of an LLM response, tolerating surrounding prose or code fences. |
| `LlmResponseRedaction.kt` | `redactLlmResponse` — render a malformed LLM response safe to log (keeps structure, masks leaf values). |

## Seams you implement

The client core carries no dependency on your persistence, metrics, or config. Provide these as
Spring beans:

| Seam | Purpose |
| --- | --- |
| `AiCallGate` (`fun interface`) | Pre-call veto — throw to refuse a call (opt-out toggle, per-user rate/budget limit). No default is shipped. |
| `AiCallListener` | Post-call `recordSuccess` / `recordFailure` for usage accounting / metrics. Must be best-effort. |
| `ReasoningEffortSource` (`fun interface`) | `effortFor(conversationType)` — supplies the configured effort per conversation type from your own config. |

`conversationType` is an opaque string; you assign your own vocabulary.

## Usage

```kotlin
@Bean
fun aiClientProperties() = AiClientProperties(
    apiKey = env.openRouterKey,
    baseUrl = "https://openrouter.ai/api/v1",
    configuredModels = setOf("openai/gpt-oss-20b:free"),
)

@Bean
fun aiCallGate(): AiCallGate = AiCallGate { ctx, _ -> myLimiter.check(ctx.userId) }

@Bean
fun reasoningEffortSource(): ReasoningEffortSource =
    ReasoningEffortSource { type -> myConfig.effortFor(type) }

// AiClient, ModelCapabilityService, ReasoningResolver, ToolRegistry are @Component beans —
// component-scan the package dev.itayp.nescioquid.openrouter.

val response = aiClient.chat(
    ChatRequest(model = "openai/gpt-oss-20b:free", messages = listOf(ChatMessage("user", "Hi"))),
    AiCallContext(userId = userId, conversationType = "chat"),
)
val text = response.choices.first().message.contentText
```

Component-scan `dev.itayp.nescioquid.openrouter` (and provide the three seams + `AiClientProperties`)
and the client wires itself.

## Coordinates

```kotlin
implementation("com.github.Itaypk.Nescioquid:openrouter-client:0.1.0")
```

Requires JVM 25+ and a Spring Boot 4.x runtime. Apache-2.0.

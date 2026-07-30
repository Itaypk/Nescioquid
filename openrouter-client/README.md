# openrouter-client

A minimal, Spring-native client for the [OpenRouter](https://openrouter.ai) chat-completions API.
Deliberately narrow: request/response DTOs, a retrying transport, model-capability fetching,
and a small function-tool abstraction — no framework of its own.

Consumers are expected to be **Spring Boot apps** (the client uses `RestClient` and component beans).

## What's in it

| Class | Role |
| --- | --- |
| `AiClient` | The transport. `chat(request, context)` sends a request with bearer auth and 3-attempt exponential backoff on 5xx/429, notifying the gate/listener seams. `chatStream(request, context)` is the streaming counterpart, returning a cold `Flow<ChatStreamEvent>`. The `request` is the source of truth for the wire, including `reasoning`. |
| `AiDtos.kt` | Request/response DTOs — `ChatRequest`/`ChatResponse`, the string↔parts `MessageContent` union, prompt-caching `cache_control`, `ReasoningConfig`, usage/token details. Jackson-only. |
| `AiStreamEvents.kt` | `ChatStreamEvent` — the `ContentDelta` / `ReasoningDelta` / `ToolCallReady` / `Completed` union a `chatStream` collector sees — and `OpenRouterStreamException`. |
| `AiStreamDtos.kt` | The SSE chunk/delta wire shapes. Internal plumbing for `chatStream`; you work with `ChatStreamEvent` instead. |
| `ModelCapabilityService` | Fetches `/model/{slug}` capabilities (reasoning support, supported efforts, input modalities). Prefetches configured models at startup; caches in memory. A utility for callers deciding what to put on a request. |
| `AiClientProperties` | The minimal config contract (`apiKey` / `baseUrl` / `configuredModels`) you supply as a bean. |
| `AiCallContext` | Per-call attribution carrier (user, conversation type, optional session/conversation ids). |
| `tool/*` | `AiTool` / `ToolKind` / `ToolRegistry` — a function-tool abstraction and registry. |
| `AssistantJson.kt` | `extractJsonObjectSpan` / `parseAssistantJsonResponse` — pull a JSON object out of an LLM response, tolerating surrounding prose or code fences. |
| `LlmResponseRedaction.kt` | `redactLlmResponse` — render a malformed LLM response safe to log (keeps structure, masks leaf values). |
| `JsonSchemaGenerator.kt` | `jsonSchema<T>()` — derive a JSON Schema (strict or non-strict, with a `customize` hook) from a Kotlin data class, for structured outputs and tool parameters. |

## Seams you implement

The client core carries no dependency on your persistence, metrics, or config. Provide these as
Spring beans:

| Seam | Purpose |
| --- | --- |
| `AiCallGate` (`fun interface`) | Pre-call veto — throw to refuse a call (opt-out toggle, per-user rate/budget limit). No default is shipped. |
| `AiCallListener` | Post-call `recordSuccess` / `recordFailure` for usage accounting / metrics. Must be best-effort. |

Every field on `AiCallContext` — `userId`, `sessionId`, `conversationId` and `conversationType` — is an
opaque string the library only hands back to your gate and listener; you assign the vocabulary and the id
scheme. On UUID keys, pass `uuid.toString()`. (They were `UUID` up to 0.5.0, which made a consumer whose
keys are `bigint` invent a mapping for an attribution parameter.)

Reasoning is **not** a seam: the client applies no central reasoning policy. Set
`ChatRequest.reasoning` yourself (optionally guided by `ModelCapabilityService`) before calling
`chat`.

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

// AiClient, ModelCapabilityService, ToolRegistry are @Component beans —
// component-scan the package dev.itayp.nescioquid.openrouter.

val response = aiClient.chat(
    ChatRequest(
        model = "openai/gpt-oss-20b:free",
        messages = listOf(ChatMessage("user", "Hi")),
        reasoning = ReasoningConfig(effort = "low"), // optional; caller-controlled
    ),
    AiCallContext(userId = userId, conversationType = "chat"),
)
val text = response.choices.first().message.contentText
```

Component-scan `dev.itayp.nescioquid.openrouter` (and provide the two seams + `AiClientProperties`)
and the client wires itself.

## Streaming

`chatStream` returns a **cold** `Flow<ChatStreamEvent>`: nothing is sent until you collect it, and
each collection is one independent call — one gate check, one listener notification. It ends with a
`Completed` carrying the same aggregated `ChatResponse` `chat` would have returned, so usage
accounting is identical on both paths.

```kotlin
aiClient.chatStream(request, AiCallContext(userId = userId, conversationType = "chat"))
    .collect { event ->
        when (event) {
            is ChatStreamEvent.ContentDelta -> sink.send(event.text)
            is ChatStreamEvent.ReasoningDelta -> sink.sendThinking(event.text)
            is ChatStreamEvent.ToolCallReady -> dispatch(event.toolCall)   // arguments are complete
            is ChatStreamEvent.Completed -> log.info("used {}", event.response.usage)
        }
    }
```

Notes:

- **You don't set `stream` or `usage`.** `chatStream` applies both to its own copy of the request —
  `usage.include` is what makes OpenRouter emit the terminal usage chunk. Everything else on your
  `ChatRequest` is sent as given. The blocking `chat` path sends neither field.
- **Tool calls are reassembled for you.** OpenRouter streams `arguments` as JSON fragments; a
  `ToolCallReady` is emitted only once a call is fully assembled, so `function.arguments` always
  parses.
- **Reasoning is stream-only.** `ReasoningDelta` is kept out of the assistant message, so it does
  not appear in `Completed.response` — capture it as it arrives if you need it.
- **Errors.** A non-2xx response throws the same `HttpClientErrorException` / `HttpServerErrorException`
  as `chat`, after the same backoff; only connection establishment is retried, never a stream that
  has already delivered events. An `error` object arriving *inside* a 200 stream throws
  `OpenRouterStreamException`. Both fire `recordFailure`.
- **Cancellation** closes the connection and notifies neither seam — the flow unwinds after your
  collector has moved on, so a notification from there would race with whatever you do next. Account
  for abandoned generations at your own cancellation point. Cancellation takes effect at the next
  event or keepalive, since a read already parked in the socket is not interrupted.
- The flow runs on `Dispatchers.IO` (the reads are blocking) and hands events over without buffering.

## Structured outputs & DTO-driven schemas

`jsonSchema<T>()` derives a JSON Schema from a Kotlin data class. By default (`strict = true`) the
schema is **strict-mode compatible**: every object sets `additionalProperties: false` and lists all
properties in `required`; nullable properties keep a `["<type>", "null"]` union so the model may
legitimately emit `null`. Pass `strict = false` for a looser schema (only non-nullable properties
`required`, no forced `additionalProperties`) — the shape you want for hand-written-style function
tool parameters. `@JsonProperty` renames a property, `@JsonPropertyDescription` becomes its
`description`, and an enum's wire values come from a `@JsonValue` member when present (else the
constant names).

For constraints the generator can't derive from the DTO — an enum whose allowed values are a runtime
list, or a `String` property that should carry an `enum` — pass a `customize` block to patch specific
(possibly nested) subschemas:

```kotlin
val params = jsonSchema<CreateTaskArgs>(strict = false) {
    property("priority").enum(TaskPriority.allowedValues)
    property("tags").items().property("color_id").enum(TagColorOptions.ALLOWED)
}
```

Constrain a reply to a schema with `structuredOutput<T>(name)`:

```kotlin
data class Recipe(
    val title: String,
    @JsonPropertyDescription("Ordered preparation steps") val steps: List<String>,
)

val response = aiClient.chat(
    ChatRequest(
        model = "openai/gpt-4o",
        messages = listOf(ChatMessage("user", "Give me a recipe")),
        responseFormat = structuredOutput<Recipe>("recipe"), // strict = true by default
    ),
    AiCallContext(userId = userId, conversationType = "recipe"),
)
val recipe = parseAssistantJsonResponse(objectMapper, response.choices.first().message.contentText!!, Recipe::class.java)
```

The same generator produces function-tool parameters — an `AiTool` can return
`jsonSchema<MyParamsDto>()` from `parameters` instead of hand-building the map.

Supported property types: `String`/`CharSequence`/`Char`, `Boolean`, integer types
(`Int`/`Long`/`Short`/`Byte`/`BigInteger`), number types (`Float`/`Double`/`BigDecimal`), enums
(→ `enum`), collections/arrays (→ `array`), nested data classes, and `Map<String, V>`
(open object — *not* strict-compatible, avoid in strict schemas). Other types and self-referential
DTOs throw `IllegalArgumentException`.

### Verifying against the real API

`OpenRouterIntegrationTest` sends live structured-output and tool-call requests to OpenRouter to
confirm a generated schema is accepted exactly as-is. It's gated on an API key and **skipped**
(not failed) when none is set, so it stays dormant in normal CI:

```bash
export OPENROUTER_API_KEY=sk-or-...            # or put it in a git-ignored .env at the repo root
# OPENROUTER_TEST_MODEL overrides the default openai/gpt-5-nano (needs structured-output + tool support)
./gradlew :openrouter-client:test --tests '*OpenRouterIntegrationTest'
```

## Coordinates

```kotlin
implementation("com.github.Itaypk.Nescioquid:openrouter-client:0.4.0")
```

Requires JVM 25+ and a Spring Boot 4.x runtime. Apache-2.0.

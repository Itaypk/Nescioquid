package dev.itayp.nescioquid.openrouter.tool

/**
 * Determines how an orchestrator dispatches a tool call and whether the model is
 * re-invoked after the call completes. The library defines the vocabulary; the
 * consumer's conversation loop acts on it.
 */
enum class ToolKind {
    /**
     * The model speaks to the user via this tool (e.g. a `say` tool) or signals an
     * end-of-session side effect (e.g. `submit`). The orchestrator handles any
     * channel rendering / persistence; the tool's `execute` records a short ack as
     * the `tool_result`. The model is NOT re-invoked solely because of one-way calls.
     */
    ONE_WAY_OUTPUT,

    /**
     * The model is asking the user a question (e.g. an `ask_choice` tool). The
     * orchestrator queues the question, dispatches it through the channel, and fills
     * in the `tool_result` once the user replies. Multiple interactive calls in one
     * turn are answered serially without re-invoking the model in between. When the
     * queue drains (or the user picks an escape option), the model is re-invoked
     * exactly once with all collected `tool_result`s in the transcript.
     */
    INTERACTIVE_INPUT,

    /**
     * Synchronous backend call (e.g. a `lookup` tool). `execute` runs immediately and
     * produces a real result string; the model is re-invoked so it can reason about
     * the data.
     */
    DATA_LOOKUP,
}

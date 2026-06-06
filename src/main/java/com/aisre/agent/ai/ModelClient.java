package com.aisre.agent.ai;

import java.util.List;

/**
 * The thing the reasoning loop talks to for "what should I do next?".
 *
 * The loop depends on this small interface, NOT on the concrete Foundry HTTP
 * client. That separation is what lets the Phase-2 tests drive the whole loop
 * with a fake in-memory model (no network, no API key) while production uses
 * {@link FoundryModelClient}.
 */
public interface ModelClient {

    /** True when real Foundry credentials/config are present and we should use the model. */
    boolean isEnabled();

    /**
     * Run one turn: send the conversation so far plus the available tools, and get
     * back the model's decision (either tool calls to run, or a final answer).
     *
     * @param messages the conversation so far (system, user, assistant, tool messages).
     * @param tools    the tools the model is allowed to call this run.
     * @return the model's reply for this turn.
     */
    ModelTurn nextTurn(List<ChatMessage> messages, List<ToolSpec> tools);
}

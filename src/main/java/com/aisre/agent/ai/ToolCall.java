package com.aisre.agent.ai;

/**
 * The model asking us to run a tool.
 *
 * When the model wants evidence, it doesn't run code itself — it replies with one
 * or more of these, and OUR orchestrator runs the matching tool and feeds the
 * result back. This is the core of "function/tool calling".
 *
 * @param id            an id the model assigns; the tool result message must quote
 *                      it back so the model knows which call the result answers.
 * @param name          which tool to run, e.g. "get_logs".
 * @param argumentsJson the call arguments as a JSON object string, e.g.
 *                      {@code {"service":"order-service","time_window":"last_15m"}}.
 *                      The model generates this; we parse it before calling the tool.
 */
public record ToolCall(
        String id,
        String name,
        String argumentsJson
) {
}

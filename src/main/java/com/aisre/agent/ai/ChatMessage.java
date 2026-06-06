package com.aisre.agent.ai;

import java.util.List;

/**
 * One message in the running conversation with the model.
 *
 * The whole conversation is just a growing list of these. Each turn we send the
 * full list, the model replies, we append its reply and any tool results, and
 * send again. The four roles we use:
 *   - "system"    : the standing instructions (the system prompt).
 *   - "user"      : the incident details.
 *   - "assistant" : the model's replies (text, and/or tool-call requests).
 *   - "tool"      : the result of running a tool, fed back to the model.
 *
 * Most messages only use {@code role} + {@code content}. The two tool-calling
 * cases use the extra fields:
 *   - an assistant message that requests tools fills {@code toolCalls};
 *   - a tool-result message fills {@code toolCallId} (which call it answers) and
 *     {@code name} (which tool produced it).
 *
 * Static factory methods below make each kind readable at the call site.
 */
public record ChatMessage(
        String role,
        String content,
        List<ToolCall> toolCalls,
        String toolCallId,
        String name
) {

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content, null, null, null);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content, null, null, null);
    }

    /** The model's plain-text reply (no tool calls). */
    public static ChatMessage assistantText(String content) {
        return new ChatMessage("assistant", content, null, null, null);
    }

    /** The model's reply that requests one or more tool calls. */
    public static ChatMessage assistantToolCalls(List<ToolCall> toolCalls) {
        return new ChatMessage("assistant", null, toolCalls, null, null);
    }

    /** The result of running a tool, fed back so the model can use it next turn. */
    public static ChatMessage toolResult(String toolCallId, String toolName, String content) {
        return new ChatMessage("tool", content, null, toolCallId, toolName);
    }
}

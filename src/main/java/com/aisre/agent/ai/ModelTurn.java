package com.aisre.agent.ai;

import java.util.List;

/**
 * Our parsed view of ONE reply from the model.
 *
 * A turn is exactly one of two things:
 *   - the model wants to run tools  -> {@code toolCalls} is non-empty; or
 *   - the model is done            -> {@code finalContent} holds its conclusion.
 *
 * The orchestrator looks at {@link #wantsToolCalls()} to decide whether to run
 * tools and loop again, or to stop and parse the final answer.
 *
 * @param toolCalls    tools the model wants run this turn (may be empty/null).
 * @param finalContent the model's final text answer when it is NOT calling tools.
 */
public record ModelTurn(
        List<ToolCall> toolCalls,
        String finalContent
) {

    /** True if the model asked to run at least one tool this turn. */
    public boolean wantsToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    /** Convenience constructor for a turn that only contains tool calls. */
    public static ModelTurn ofToolCalls(List<ToolCall> calls) {
        return new ModelTurn(calls, null);
    }

    /** Convenience constructor for a final-answer turn. */
    public static ModelTurn ofFinal(String content) {
        return new ModelTurn(List.of(), content);
    }
}

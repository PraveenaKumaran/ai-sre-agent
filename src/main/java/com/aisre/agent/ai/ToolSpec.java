package com.aisre.agent.ai;

/**
 * Our description of a tool, handed TO the model so it knows what it may call.
 *
 * This is the mirror image of {@link ToolCall}: ToolSpec is "here are the tools
 * you can use"; ToolCall is the model replying "call this one".
 *
 * @param name              the tool name the model will use, e.g. "get_logs".
 * @param description       what the tool does / when to use it (helps the model choose).
 * @param parametersJsonSchema a JSON-Schema object (as a string) describing the
 *                          tool's arguments, so the model knows what to fill in.
 */
public record ToolSpec(
        String name,
        String description,
        String parametersJsonSchema
) {
}

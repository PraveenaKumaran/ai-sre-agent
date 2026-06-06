package com.aisre.agent.tools;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Holds all the tools and lets callers run one by name.
 *
 * Spring finds every {@code @Component} that implements {@link Tool} and injects
 * them here as a list (constructor injection). We index them by name so the
 * reasoning loop — and, in Phase 2, the model's tool calls — can dispatch by name.
 */
@Component
public class ToolRegistry {

    private final Map<String, Tool> toolsByName;

    public ToolRegistry(List<Tool> tools) {
        // Build a name -> tool lookup. If two tools ever share a name, fail fast.
        this.toolsByName = tools.stream()
                .collect(Collectors.toMap(Tool::name, Function.identity()));
    }

    /** All registered tool names (handy for logging and, later, describing tools to the model). */
    public List<String> names() {
        return toolsByName.keySet().stream().sorted().toList();
    }

    /**
     * Run a tool by name.
     *
     * @param name tool name, e.g. "get_logs"
     * @param args named arguments for the tool
     * @return the tool's text result
     * @throws IllegalArgumentException if no tool has that name
     */
    public String execute(String name, Map<String, String> args) {
        Tool tool = toolsByName.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("Unknown tool: " + name + " (known: " + names() + ")");
        }
        return tool.execute(args);
    }
}

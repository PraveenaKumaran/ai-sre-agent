package com.aisre.agent.tools;

import java.util.Map;

/**
 * A single capability the agent is allowed to use.
 *
 * Phase 1: each tool just returns canned data.
 * Phase 2: these same tools will be exposed to the Foundry model as callable
 *          "functions", and the model will decide which to call with which args.
 *
 * Keeping a uniform interface now means Phase 2 can hand the model a list of
 * tools and dispatch its calls without rewriting any tool.
 *
 * @param name        the tool's identifier the model will call by (e.g. "get_logs")
 * @param description one line telling the model what the tool does / when to use it
 */
public interface Tool {

    /** The name the model uses to call this tool, e.g. {@code get_logs}. */
    String name();

    /** A short description of what the tool does (used when describing tools to the model). */
    String description();

    /**
     * A JSON-Schema object (as a string) describing this tool's arguments, so the
     * model knows what to fill in when it calls the tool. Each tool owns and
     * declares its own argument contract here.
     */
    String parametersJsonSchema();

    /**
     * Run the tool.
     *
     * @param args named arguments (e.g. {@code service}, {@code time_window}).
     *             In Phase 1 the stubs mostly ignore these and return canned data.
     * @return the tool's result as plain text the agent can read.
     */
    String execute(Map<String, String> args);
}

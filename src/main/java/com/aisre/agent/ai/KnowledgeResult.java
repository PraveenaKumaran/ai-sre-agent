package com.aisre.agent.ai;

import java.util.List;

/**
 * The result of a Foundry IQ retrieval: cited snippets of grounded knowledge.
 *
 * This is the heart of the IQ requirement — every snippet carries its source, so
 * the agent can reason from cited institutional knowledge rather than guessing.
 *
 * @param snippets the retrieved pieces of knowledge, each with its citation.
 */
public record KnowledgeResult(List<Snippet> snippets) {

    /**
     * One retrieved passage and where it came from.
     *
     * @param sourceId   stable citation id, e.g. "RB-NPE-001".
     * @param sourcePath human-readable location, e.g. "knowledge/runbook-null-pointer.md".
     * @param text       the retrieved passage.
     */
    public record Snippet(String sourceId, String sourcePath, String text) {
    }

    /** Just the citation ids, for recording in the trace and the result. */
    public List<String> citationIds() {
        return snippets.stream().map(Snippet::sourceId).toList();
    }

    /**
     * Render the snippets as the text we hand back to the model as a tool result.
     * Each snippet is prefixed with an explicit [SOURCE: ...] marker so the model
     * can cite it in its conclusion.
     */
    public String toToolResultText() {
        StringBuilder sb = new StringBuilder();
        for (Snippet s : snippets) {
            sb.append("[SOURCE: ").append(s.sourceId())
              .append(" | ").append(s.sourcePath()).append("]\n")
              .append(s.text()).append("\n\n");
        }
        return sb.toString().strip();
    }
}

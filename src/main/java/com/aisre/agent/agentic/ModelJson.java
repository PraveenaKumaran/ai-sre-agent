package com.aisre.agent.agentic;

/**
 * One tiny shared step every agent needs: pull the {...} JSON object out of a
 * model's reply, tolerating code fences or stray prose around it.
 *
 * Parsing (and what to do when parsing fails) stays in each agent, because the
 * graceful-degrade behaviour is agent-specific: Critic degrades to WEAK, Judge
 * degrades to ESCALATE_TO_HUMAN, Triage/RootCause degrade to an empty list.
 */
final class ModelJson {

    private ModelJson() { } // utility class, no instances

    /**
     * @return the substring from the first '{' to the last '}', or null if the text
     *         contains no JSON object at all.
     */
    static String extractObject(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return (start >= 0 && end > start) ? text.substring(start, end + 1) : null;
    }
}

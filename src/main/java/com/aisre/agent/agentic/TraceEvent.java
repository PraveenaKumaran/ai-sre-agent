package com.aisre.agent.agentic;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One entry in the glass-box trace.
 *
 * Every event carries TWO representations on purpose:
 *  - {@code summary}: a human-readable one-liner for reading the trace.
 *  - {@code payload}: machine-readable structured data. The evaluation runner (and
 *    any tooling) reads the payload fields directly and NEVER parses the summary text.
 *
 * The payload is stored as an insertion-ordered, unmodifiable map so the JSON reads
 * in a sensible order and callers can't mutate a recorded event after the fact.
 *
 * @param seq     1-based position in the trace (ordering).
 * @param type    what kind of step this is.
 * @param agent   which stage emitted it, e.g. "TriageAgent" / "Orchestrator".
 * @param summary human-readable description.
 * @param payload machine-readable structured data (may be empty, never null).
 */
public record TraceEvent(
        int seq,
        TraceEventType type,
        String agent,
        String summary,
        Map<String, Object> payload
) {
    public TraceEvent {
        // Defensive, order-preserving copy. LinkedHashMap (not Map.copyOf) so the
        // field order we built is kept and null values don't blow up.
        payload = payload == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }
}

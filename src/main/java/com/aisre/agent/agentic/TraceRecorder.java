package com.aisre.agent.agentic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Accumulates the glass-box {@link TraceEvent}s for one incident, in order.
 *
 * The orchestrator and agents call these methods as the pipeline runs. The recorder
 * assigns each event a 1-based sequence number automatically. A generic {@link #add}
 * covers ordinary handoffs; two specialized factories build the self-contained
 * discard/retry payloads that must survive hypotheses being replaced each round.
 */
public class TraceRecorder {

    private final List<TraceEvent> events = new ArrayList<>();

    /** Record an event with a structured payload. Returns the stored event. */
    public TraceEvent add(TraceEventType type, String agent, String summary, Map<String, Object> payload) {
        TraceEvent event = new TraceEvent(events.size() + 1, type, agent, summary, payload);
        events.add(event);
        return event;
    }

    /** Record an event that has no extra structured data. */
    public TraceEvent add(TraceEventType type, String agent, String summary) {
        return add(type, agent, summary, Map.of());
    }

    /**
     * Record that ONE hypothesis was killed. The payload snapshots the FULL hypothesis
     * plus the Critic's verdict/reasons and the retry number, so it stands alone after
     * the live hypothesis set is replaced.
     */
    public TraceEvent hypothesisDiscarded(int retryNumber, int maxRetries,
                                          Hypothesis hypothesis, CritiqueStatus status, List<String> reasons) {
        DiscardedHypothesis snapshot = new DiscardedHypothesis(hypothesis, status, reasons);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("retryNumber", retryNumber);
        payload.put("maxRetries", maxRetries);
        payload.put("discarded", snapshot);

        String firstReason = reasons == null || reasons.isEmpty() ? "no reason given" : reasons.get(0);
        String summary = "Discarded " + hypothesis.id() + " (" + status + ") at retry "
                + retryNumber + "/" + maxRetries + " — " + firstReason;
        return add(TraceEventType.HYPOTHESIS_DISCARDED, "CriticAgent", summary, payload);
    }

    /**
     * Record that a retry to the RootCauseAgent fired (no hypothesis was SUPPORTED).
     * The payload carries the retry number and the FULL list of rejected hypotheses
     * (each a self-contained snapshot) that are being sent back for re-thinking.
     */
    public TraceEvent retryTriggered(int retryNumber, int maxRetries, List<DiscardedHypothesis> rejected) {
        List<DiscardedHypothesis> snapshot = rejected == null ? List.of() : List.copyOf(rejected);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("retryNumber", retryNumber);
        payload.put("maxRetries", maxRetries);
        payload.put("rejectedHypotheses", snapshot);

        String summary = "Retry " + retryNumber + "/" + maxRetries
                + " — no SUPPORTED hypothesis; sending " + snapshot.size()
                + " back to RootCauseAgent for re-thinking";
        return add(TraceEventType.RETRY_TRIGGERED, "Orchestrator", summary, payload);
    }

    /** The recorded events so far, in order (read-only). */
    public List<TraceEvent> events() {
        return Collections.unmodifiableList(events);
    }
}

package com.aisre.agent.agentic;

/**
 * One structured fact extracted from the raw incident by the TriageAgent.
 *
 * Evidence is deliberately diagnosis-free: it states WHAT was observed, never WHY.
 * Each item carries a stable id (E1, E2, ...) so later agents can cite it by id
 * ("supported by E4") and the trace can show exactly which facts a hypothesis rests on.
 *
 * @param id        stable reference id, e.g. "E1".
 * @param type      kind of fact: "symptom", "timeline", "metric", "code", or "log".
 * @param statement the one-line factual observation.
 * @param source    where it came from, e.g. "get_logs" / "get_metrics" (provenance).
 */
public record Evidence(
        String id,
        String type,
        String statement,
        String source
) {
}

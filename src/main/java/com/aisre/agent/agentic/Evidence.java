package com.aisre.agent.agentic;

/**
 * One structured fact extracted from the raw incident by the TriageAgent.
 *
 * Evidence is deliberately diagnosis-free: it states WHAT was observed, never WHY.
 * Each item carries a stable id (E1, E2, ...) so later agents can cite it by id
 * ("supported by E4") and the trace can show exactly which facts a hypothesis rests on.
 *
 * PROVENANCE IS STRUCTURAL: {@code source} is assigned by CODE, never inferred by
 * the model. The TriageAgent extracts the incident report and the observability
 * data in separate calls, and stamps each item with the source of the call that
 * produced it. The reported-symptom guard keys on this field, so it stays valid
 * whether incidents arrive as stack traces, alerts, or plain-text descriptions.
 *
 * @param id        stable reference id, e.g. "E1" (assigned by code).
 * @param type      kind of fact: "symptom", "timeline", "metric", "code", or "log".
 * @param statement the one-line factual observation.
 * @param source    {@link #SOURCE_INCIDENT_REPORT} or {@link #SOURCE_OBSERVABILITY}
 *                  (code-assigned provenance).
 */
public record Evidence(
        String id,
        String type,
        String statement,
        String source
) {
    /** The item derives from the submitted incident report (the reporter's claims). */
    public static final String SOURCE_INCIDENT_REPORT = "incident_report";

    /** The item derives from tool-gathered observability data (logs/metrics). */
    public static final String SOURCE_OBSERVABILITY = "observability";

    /** True if this item came from the submitted incident report. */
    public boolean isReportDerived() {
        return SOURCE_INCIDENT_REPORT.equals(source);
    }
}

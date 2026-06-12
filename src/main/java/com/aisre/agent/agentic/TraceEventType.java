package com.aisre.agent.agentic;

/**
 * The kinds of step recorded in the glass-box trace.
 *
 * Events are emitted at every agent handoff and every hypothesis kill, so a reader
 * can follow the whole chain: incident -> evidence ids -> citation ids -> hypotheses
 * -> critic verdict -> (discard + retry)* -> decision -> approval gate.
 */
public enum TraceEventType {
    INCIDENT_RECEIVED,
    REDACTION,
    AGENT_HANDOFF,
    EVIDENCE_EXTRACTED,
    IQ_QUERY,
    CITATIONS_RETRIEVED,
    HYPOTHESES_PROPOSED,
    /** The deterministic "model proposes, code verifies" pass changed a hypothesis's citation ids. */
    PROVENANCE_NORMALIZED,
    CRITIQUE,
    HYPOTHESIS_DISCARDED,
    RETRY_TRIGGERED,
    DECISION,
    /** A deterministic safety guard overrode the Judge's decision (model proposes, code verifies). */
    GUARD_OVERRIDE,
    FIX_DRAFTED,
    APPROVAL_GATE,
    ESCALATION
}

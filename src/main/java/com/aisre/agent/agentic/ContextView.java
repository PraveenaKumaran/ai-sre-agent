package com.aisre.agent.agentic;

import java.util.List;
import java.util.Optional;

/**
 * WRITE DISCIPLINE — the read-only view of the shared incident context.
 *
 * Every agent can READ everything here, but this interface exposes NO section
 * mutators. Each agent instead receives its own narrow sub-interface (e.g.
 * {@link TriageView}) that adds exactly ONE write method — its own output section.
 * So "TriageAgent writes only evidence" is enforced by the compiler, not by
 * convention: code that calls a foreign mutator does not compile.
 *
 * Workflow state (retry count, rejection feedback, redacted-signal setters) has no
 * view at all — those mutators live only on the concrete {@link IncidentContext},
 * which only the orchestrator holds.
 *
 * Two honest limits of this guarantee (acceptable for this project):
 * - trace() is shared: agents append their own progress events; the trace is an
 *   append-only audit log, not section state, so cross-agent writes can't corrupt it.
 * - a malicious downcast `(IncidentContext) view` would bypass the views. This is
 *   compile-time API discipline against mistakes, not a security sandbox.
 */
public interface ContextView {

    String incidentId();

    String service();

    String stackTrace();

    String redactedLogs();

    String redactedMetrics();

    List<Evidence> evidence();

    Optional<Evidence> findEvidence(String id);

    List<Citation> citations();

    Optional<Citation> findCitation(String id);

    List<Hypothesis> hypotheses();

    Optional<Hypothesis> findHypothesis(String id);

    List<Critique> critiques();

    List<DiscardedHypothesis> lastRejections();

    int retryCount();

    /** Append-only trace; agents record their own progress events here. */
    TraceRecorder trace();
}

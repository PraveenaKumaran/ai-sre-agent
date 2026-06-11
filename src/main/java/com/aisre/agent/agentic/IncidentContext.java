package com.aisre.agent.agentic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * The single shared state object that flows through the whole agent pipeline.
 *
 * The deterministic orchestrator (Planner-Executor) creates one of these per
 * incident and hands it to each agent in turn. Each agent reads what it needs and
 * writes its contribution back:
 *   TriageAgent    -> evidence
 *   KnowledgeAgent -> citations
 *   RootCauseAgent -> hypotheses (current round)
 *   CriticAgent    -> critiques (current round)
 *   JudgeAgent     -> decision
 *
 * Evidence and citations ACCUMULATE across the run. Hypotheses and critiques are
 * replaced each round (a retry produces a fresh set); the full history of proposed
 * and discarded hypotheses lives in the trace (added in step 2), which is also what
 * the evaluation runner reads.
 *
 * This class is intentionally a plain mutable holder, not a record, because its
 * whole job is to be progressively filled in as the pipeline runs.
 *
 * WRITE DISCIPLINE: this class implements all five per-agent views, but each agent
 * only ever sees its own view type, so the compiler limits each agent to its own
 * output section. The mutators with NO view (setRedactedLogs/Metrics,
 * setLastRejections, incrementRetryCount, decision()) are orchestrator-only state.
 */
public class IncidentContext implements TriageView, KnowledgeView, RootCauseView, CriticView, JudgeView {

    private final String incidentId;
    private final String service;
    private final String stackTrace;

    // Raw signals gathered by the orchestrator, ALREADY REDACTED at the boundary
    // before being stored here. Nothing un-redacted ever enters this context.
    private String redactedLogs = "";
    private String redactedMetrics = "";

    // Feedback for the self-reflection retry (step 4): the hypotheses the Critic
    // killed last round, with reasons, handed back to the RootCauseAgent so it does
    // not blindly regenerate the same theory.
    private List<DiscardedHypothesis> lastRejections = List.of();

    // Monotonic counter so hypothesis ids (H1, H2, ...) stay unique ACROSS retry
    // rounds even though the hypothesis list itself is replaced each round.
    private int hypothesisSeq;

    private final List<Evidence> evidence = new ArrayList<>();
    private final List<Citation> citations = new ArrayList<>();
    private final List<Hypothesis> hypotheses = new ArrayList<>();
    private final List<Critique> critiques = new ArrayList<>();

    // The glass-box trace for this incident. Agents/orchestrator append to it as they
    // run; because hypotheses/critiques are replaced each round, the trace is the only
    // durable record of killed hypotheses (HYPOTHESIS_DISCARDED / RETRY_TRIGGERED).
    private final TraceRecorder trace = new TraceRecorder();

    private Decision decision;
    private int retryCount;

    public IncidentContext(String incidentId, String service, String stackTrace) {
        this.incidentId = incidentId;
        this.service = service;
        this.stackTrace = stackTrace;
    }

    // ---- immutable inputs --------------------------------------------------

    public String incidentId() {
        return incidentId;
    }

    public String service() {
        return service;
    }

    public String stackTrace() {
        return stackTrace;
    }

    // ---- redacted raw signals (set once by the orchestrator at the boundary) ----

    public void setRedactedLogs(String redactedLogs) {
        this.redactedLogs = redactedLogs == null ? "" : redactedLogs;
    }

    public String redactedLogs() {
        return redactedLogs;
    }

    public void setRedactedMetrics(String redactedMetrics) {
        this.redactedMetrics = redactedMetrics == null ? "" : redactedMetrics;
    }

    public String redactedMetrics() {
        return redactedMetrics;
    }

    // ---- retry feedback (set by the orchestrator before a retry round) ----------

    public void setLastRejections(List<DiscardedHypothesis> rejections) {
        this.lastRejections = rejections == null ? List.of() : List.copyOf(rejections);
    }

    public List<DiscardedHypothesis> lastRejections() {
        return lastRejections;
    }

    // ---- hypothesis id sequence --------------------------------------------------

    /** Next unique hypothesis id (H1, H2, ...), monotonic across retry rounds. */
    public String nextHypothesisId() {
        return "H" + (++hypothesisSeq);
    }

    // ---- evidence (accumulates) -------------------------------------------

    public void addEvidence(List<Evidence> items) {
        if (items != null) {
            evidence.addAll(items);
        }
    }

    public List<Evidence> evidence() {
        return Collections.unmodifiableList(evidence);
    }

    public Optional<Evidence> findEvidence(String id) {
        return evidence.stream().filter(e -> e.id().equals(id)).findFirst();
    }

    // ---- citations (accumulate) -------------------------------------------

    public void addCitations(List<Citation> items) {
        if (items != null) {
            citations.addAll(items);
        }
    }

    public List<Citation> citations() {
        return Collections.unmodifiableList(citations);
    }

    public Optional<Citation> findCitation(String id) {
        return citations.stream().filter(c -> c.id().equals(id)).findFirst();
    }

    // ---- hypotheses (replaced each round) ---------------------------------

    /** Replace the current-round hypotheses with a freshly generated set. */
    public void setHypotheses(List<Hypothesis> items) {
        hypotheses.clear();
        if (items != null) {
            hypotheses.addAll(items);
        }
    }

    public List<Hypothesis> hypotheses() {
        return Collections.unmodifiableList(hypotheses);
    }

    public Optional<Hypothesis> findHypothesis(String id) {
        return hypotheses.stream().filter(h -> h.id().equals(id)).findFirst();
    }

    // ---- critiques (replaced each round) ----------------------------------

    /** Replace the current-round critiques with the Critic's latest verdicts. */
    public void setCritiques(List<Critique> items) {
        critiques.clear();
        if (items != null) {
            critiques.addAll(items);
        }
    }

    public List<Critique> critiques() {
        return Collections.unmodifiableList(critiques);
    }

    /**
     * The deterministic retry signal: true if at least one current hypothesis was
     * rated SUPPORTED. The orchestrator retries to the RootCauseAgent whenever this
     * is false (a set of only WEAK/REJECTED hypotheses still triggers a retry).
     */
    public boolean hasSupportedHypothesis() {
        return critiques.stream().anyMatch(c -> c.status() == CritiqueStatus.SUPPORTED);
    }

    // ---- retry bookkeeping ------------------------------------------------

    public int retryCount() {
        return retryCount;
    }

    public void incrementRetryCount() {
        retryCount++;
    }

    // ---- decision ---------------------------------------------------------

    public void setDecision(Decision decision) {
        this.decision = decision;
    }

    public Decision decision() {
        return decision;
    }

    // ---- glass-box trace --------------------------------------------------

    /** The trace recorder for this incident; agents/orchestrator append events to it. */
    public TraceRecorder trace() {
        return trace;
    }
}

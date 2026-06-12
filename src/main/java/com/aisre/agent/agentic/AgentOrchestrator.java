package com.aisre.agent.agentic;

import com.aisre.agent.ai.ModelClient;
import com.aisre.agent.model.IncidentRequest;
import com.aisre.agent.safety.SecretRedactor;
import com.aisre.agent.tools.DraftFixTool;
import com.aisre.agent.tools.GetLogsTool;
import com.aisre.agent.tools.GetMetricsTool;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Planner-Executor: a DETERMINISTIC Java orchestrator that runs the five
 * agents in a fixed, explicit order. Model output influences the CONTENT of each
 * step but can never redirect the control flow — the plan is this Java method,
 * not a model decision. (That determinism is itself one of the prompt-injection
 * mitigations: untrusted log input cannot talk the pipeline into a new shape.)
 *
 * Pipeline (single pass; the RootCause<->Critic retry loop is added in step 4):
 *   redact at boundary -> TriageAgent -> KnowledgeAgent
 *     -> RootCauseAgent -> CriticAgent -> JudgeAgent -> approval gate / escalation
 *
 * SECURITY BOUNDARY: all raw incident input (stack trace, logs, metrics) is
 * redacted HERE, ONCE, before any agent — and therefore any model — sees it.
 * The context, trace, and response are clean by construction; nothing downstream
 * needs to re-redact (anything downstream that does is defense-in-depth, not the
 * guarantee).
 */
@Service
public class AgentOrchestrator {

    /**
     * Hard cap on RootCause<->Critic retry rounds (locked by the spec). After two
     * retries the Judge decides regardless, reading the exhausted count as a
     * signal of diagnostic difficulty.
     */
    static final int MAX_RETRIES = 2;

    /** A recommendation must rest on at least this many real, substantive evidence items. */
    static final int MIN_SUBSTANTIVE_EVIDENCE = 2;

    /** Evidence that merely reports missing data is not substantive support. */
    private static final java.util.regex.Pattern NO_DATA_STATEMENT = java.util.regex.Pattern.compile(
            "(?i)\\bno\\s+(logs?|metrics?|data)\\b.*\\b(found|available|exist)");

    private final TriageAgent triageAgent;
    private final KnowledgeAgent knowledgeAgent;
    private final RootCauseAgent rootCauseAgent;
    private final CriticAgent criticAgent;
    private final JudgeAgent judgeAgent;
    private final GetLogsTool getLogsTool;
    private final GetMetricsTool getMetricsTool;
    private final DraftFixTool draftFixTool;
    private final SecretRedactor redactor;
    private final ModelClient model;

    public AgentOrchestrator(TriageAgent triageAgent,
                             KnowledgeAgent knowledgeAgent,
                             RootCauseAgent rootCauseAgent,
                             CriticAgent criticAgent,
                             JudgeAgent judgeAgent,
                             GetLogsTool getLogsTool,
                             GetMetricsTool getMetricsTool,
                             DraftFixTool draftFixTool,
                             SecretRedactor redactor,
                             ModelClient model) {
        this.triageAgent = triageAgent;
        this.knowledgeAgent = knowledgeAgent;
        this.rootCauseAgent = rootCauseAgent;
        this.criticAgent = criticAgent;
        this.judgeAgent = judgeAgent;
        this.getLogsTool = getLogsTool;
        this.getMetricsTool = getMetricsTool;
        this.draftFixTool = draftFixTool;
        this.redactor = redactor;
        this.model = model;
    }

    /** Run the full multi-agent pipeline for one incident and return the filled context. */
    public IncidentContext run(IncidentRequest incident) {
        String incidentId = "INC-" + UUID.randomUUID().toString().substring(0, 8);
        String service = incident.service() == null ? "unknown-service" : incident.service();

        // No model credentials -> serve the deterministic offline stub (same shape,
        // canned content) so the baseline runs end-to-end without keys.
        if (!model.isEnabled()) {
            return stubContext(incidentId, service, incident);
        }

        // ---- SECURITY BOUNDARY: redact ALL raw input before anything else sees it ----
        String rawStackTrace = incident.stackTrace() == null ? "" : incident.stackTrace();
        String rawLogs = getLogsTool.execute(Map.of("service", service, "time_window", "last_15m"));
        String rawMetrics = getMetricsTool.execute(Map.of("service", service, "metric", "error_rate"));

        String stackTrace = redactor.redact(rawStackTrace);
        IncidentContext ctx = new IncidentContext(incidentId, service, stackTrace);
        ctx.setRedactedLogs(redactor.redact(rawLogs));
        ctx.setRedactedMetrics(redactor.redact(rawMetrics));

        // INCIDENT_RECEIVED records METADATA ONLY — never the input itself
        // (trace data minimization: no raw log lines or stack-trace blobs in payloads).
        Map<String, Object> received = new LinkedHashMap<>();
        received.put("service", service);
        received.put("stackTraceChars", rawStackTrace.length());
        received.put("logChars", rawLogs.length());
        received.put("metricChars", rawMetrics.length());
        received.put("timestamp", Instant.now().toString());
        ctx.trace().add(TraceEventType.INCIDENT_RECEIVED, "Orchestrator",
                "Incident received for " + service, received);

        Map<String, Object> redaction = new LinkedHashMap<>();
        redaction.put("stackTraceChanged", !stackTrace.equals(rawStackTrace));
        redaction.put("logsChanged", !ctx.redactedLogs().equals(rawLogs));
        redaction.put("metricsChanged", !ctx.redactedMetrics().equals(rawMetrics));
        ctx.trace().add(TraceEventType.REDACTION, "Orchestrator",
                "Secret redaction applied at the boundary (before any model sees the input)",
                redaction);

        // ---- The fixed plan (Planner-Executor): deterministic order, in Java. -------
        handOffTo(ctx, triageAgent);
        handOffTo(ctx, knowledgeAgent);
        handOffTo(ctx, rootCauseAgent);
        normalizeCitationProvenance(ctx);
        handOffTo(ctx, criticAgent);

        // ---- Self-reflection retry loop (Critic-Verifier feedback). -----------------
        // DETERMINISTIC trigger: retry whenever NO hypothesis is rated SUPPORTED — a
        // round of only WEAK and REJECTED still retries. The killed hypotheses and
        // the Critic's reasons go back to the RootCauseAgent (true re-think, not a
        // blind retry). Max 2 retries, then the Judge decides regardless — and the
        // Judge reads the retry count as a difficulty signal.
        while (!ctx.hasSupportedHypothesis() && ctx.retryCount() < MAX_RETRIES) {
            int retryNumber = ctx.retryCount() + 1;
            List<DiscardedHypothesis> killed = snapshotKilledHypotheses(ctx);

            // Trace the kills + the retry, each event self-contained (the live
            // hypothesis set is about to be replaced; these snapshots are the only
            // surviving record of the killed round).
            for (DiscardedHypothesis d : killed) {
                ctx.trace().hypothesisDiscarded(retryNumber, MAX_RETRIES,
                        d.hypothesis(), d.status(), d.reasons());
            }
            ctx.trace().retryTriggered(retryNumber, MAX_RETRIES, killed);

            ctx.setLastRejections(killed);
            ctx.incrementRetryCount();

            handOffTo(ctx, rootCauseAgent);
            normalizeCitationProvenance(ctx);
            handOffTo(ctx, criticAgent);
        }

        handOffTo(ctx, judgeAgent);
        enforceEvidenceGuard(ctx);
        enforceReportedSymptomGuard(ctx);

        // ---- Terminal safety event: every run ends at the gate or an escalation. ----
        Decision decision = ctx.decision();
        if (decision.type() == DecisionType.RECOMMEND_REMEDIATION) {
            // Payload minimization: record THAT a fix was drafted and its size, not
            // its text (the fix itself travels in the decision/response).
            ctx.trace().add(TraceEventType.FIX_DRAFTED, "JudgeAgent",
                    "Drafted a proposed fix for " + decision.selectedHypothesisId() + " (draft only, not applied)",
                    Map.of("selectedHypothesisId", decision.selectedHypothesisId(),
                           "proposedFixChars", decision.proposedFix() == null ? 0 : decision.proposedFix().length()));
            ctx.trace().add(TraceEventType.APPROVAL_GATE, "Orchestrator",
                    "HARD STOP: fix is a draft awaiting human approval — the agent takes no action",
                    Map.of("decisionType", decision.type().name(),
                           "selectedHypothesisId", decision.selectedHypothesisId()));
        } else {
            ctx.trace().add(TraceEventType.ESCALATION, "Orchestrator",
                    "Escalated to a human: " + decision.type(),
                    Map.of("decisionType", decision.type().name()));
        }
        return ctx;
    }

    /**
     * Record the handoff, then run the agent. The generic signature is the write
     * discipline in action: each agent receives the SAME context object, but typed
     * as its own narrow view (V), so it can only write its own section.
     */
    private <V extends ContextView> void handOffTo(V ctx, Agent<V> agent) {
        ctx.trace().add(TraceEventType.AGENT_HANDOFF, "Orchestrator",
                "Orchestrator -> " + agent.name(), Map.of("agent", agent.name()));
        agent.execute(ctx);
    }

    /**
     * Model proposes, code verifies: the RootCauseAgent is INSTRUCTED to reference
     * citations by C-id, but a model may still write a document name (e.g.
     * "runbook-null-pointer.md") or an id that appears inside a document's text
     * (e.g. "RB-NPE-001"). This deterministic pass maps every such entry back to
     * its real C-id and DROPS anything unresolvable, so the Critic, the Judge, and
     * the trace all reason over one consistent id space.
     */
    private void normalizeCitationProvenance(IncidentContext ctx) {
        Map<String, Map<String, Object>> changes = new LinkedHashMap<>();

        List<Hypothesis> normalized = ctx.hypotheses().stream()
                .map(h -> {
                    List<String> resolved = h.supportingCitationIds().stream()
                            .map(ref -> resolveCitationId(ctx, ref))
                            .filter(java.util.Objects::nonNull)
                            .distinct()
                            .toList();
                    if (!resolved.equals(h.supportingCitationIds())) {
                        changes.put(h.id(), Map.of(
                                "before", h.supportingCitationIds(), "after", resolved));
                    }
                    return new Hypothesis(h.id(), h.statement(), h.confidence(),
                            h.supportingEvidenceIds(), resolved);
                })
                .toList();
        ctx.setHypotheses(normalized);

        // Glass box: a deterministic correction is itself a traceable step. Only
        // emitted when something actually changed; payload is pure ids.
        if (!changes.isEmpty()) {
            ctx.trace().add(TraceEventType.PROVENANCE_NORMALIZED, "Orchestrator",
                    "Normalized citation references to C-ids for " + changes.keySet()
                            + " (model proposes, code verifies)",
                    Map.of("changes", changes));
        }
    }

    /**
     * Deterministic evidence guard (model proposes, code verifies): a RECOMMEND
     * decision must rest on at least {@link #MIN_SUBSTANTIVE_EVIDENCE} evidence
     * items that (a) actually exist in the context and (b) are substantive — an
     * item that merely reports missing data ("no logs found") is not support.
     * Anything thinner is overridden to INSUFFICIENT_EVIDENCE, with a
     * GUARD_OVERRIDE trace event so the correction is visible in the glass box.
     *
     * This is the code backstop behind the Critic/Judge prompt rules: even if both
     * models bless a theory built on thin air, the recommendation cannot pass.
     */
    private void enforceEvidenceGuard(IncidentContext ctx) {
        Decision decision = ctx.decision();
        if (decision.type() != DecisionType.RECOMMEND_REMEDIATION) {
            return; // guard only constrains recommendations
        }
        List<String> citedIds = ctx.findHypothesis(decision.selectedHypothesisId())
                .map(Hypothesis::supportingEvidenceIds)
                .orElse(List.of());
        List<String> substantive = citedIds.stream()
                .filter(id -> ctx.findEvidence(id)
                        .map(e -> !NO_DATA_STATEMENT.matcher(e.statement()).find())
                        .orElse(false)) // ids that don't resolve to real evidence don't count
                .toList();

        if (substantive.size() >= MIN_SUBSTANTIVE_EVIDENCE) {
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("from", decision.type().name());
        payload.put("to", DecisionType.INSUFFICIENT_EVIDENCE.name());
        payload.put("selectedHypothesisId", decision.selectedHypothesisId());
        payload.put("citedEvidenceIds", citedIds);
        payload.put("substantiveEvidenceIds", substantive);
        payload.put("requiredMinimum", MIN_SUBSTANTIVE_EVIDENCE);
        ctx.trace().add(TraceEventType.GUARD_OVERRIDE, "Orchestrator",
                "Deterministic guard overrode RECOMMEND_REMEDIATION: only " + substantive.size()
                        + " substantive evidence item(s) behind " + decision.selectedHypothesisId()
                        + " (minimum " + MIN_SUBSTANTIVE_EVIDENCE + ")", payload);

        ctx.setDecision(Decision.escalate(DecisionType.INSUFFICIENT_EVIDENCE,
                "Deterministic guard: the recommended hypothesis " + decision.selectedHypothesisId()
                        + " cites only " + substantive.size() + " substantive evidence item(s); a "
                        + "remediation requires at least " + MIN_SUBSTANTIVE_EVIDENCE
                        + ". Escalating instead of acting on thin evidence."));
    }

    /**
     * Deterministic reported-symptom guard (model proposes, code verifies): a
     * RECOMMEND decision must rest on at least one evidence item derived from the
     * INCIDENT REPORT itself — provenance is code-assigned by the TriageAgent's
     * per-channel extraction, never model-inferred. A root cause that explains the
     * observability data but not what was actually reported (EVAL-10's failure
     * mode: NPE theory recommended for an OOM report) is an answer to a different
     * incident, so it is overridden to ESCALATE_TO_HUMAN with a GUARD_OVERRIDE
     * trace event.
     */
    private void enforceReportedSymptomGuard(IncidentContext ctx) {
        Decision decision = ctx.decision();
        if (decision.type() != DecisionType.RECOMMEND_REMEDIATION) {
            return; // guard only constrains recommendations
        }
        List<String> citedIds = ctx.findHypothesis(decision.selectedHypothesisId())
                .map(Hypothesis::supportingEvidenceIds)
                .orElse(List.of());

        // id -> source for everything the winner cites (for the trace payload).
        Map<String, String> citedSources = new LinkedHashMap<>();
        for (String id : citedIds) {
            citedSources.put(id, ctx.findEvidence(id).map(Evidence::source).orElse("unknown"));
        }
        boolean citesReportedSymptom = citedIds.stream()
                .anyMatch(id -> ctx.findEvidence(id).map(Evidence::isReportDerived).orElse(false));
        if (citesReportedSymptom) {
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("from", decision.type().name());
        payload.put("to", DecisionType.ESCALATE_TO_HUMAN.name());
        payload.put("selectedHypothesisId", decision.selectedHypothesisId());
        payload.put("citedEvidenceSources", citedSources);
        payload.put("missingRequirement",
                ">=1 supporting evidence item with source=" + Evidence.SOURCE_INCIDENT_REPORT);
        ctx.trace().add(TraceEventType.GUARD_OVERRIDE, "Orchestrator",
                "Deterministic guard overrode RECOMMEND_REMEDIATION: "
                        + decision.selectedHypothesisId() + " cites no incident-report-derived evidence"
                        + " — the theory does not cover the reported symptom", payload);

        ctx.setDecision(Decision.escalate(DecisionType.ESCALATE_TO_HUMAN,
                "Deterministic guard: the recommended hypothesis " + decision.selectedHypothesisId()
                        + " cites no evidence derived from the incident report, so it does not "
                        + "address the reported symptom. Escalating to a human."));
    }

    /**
     * Bundle each current hypothesis with the Critic's verdict and reasons into a
     * self-contained snapshot. Called only when NO hypothesis is SUPPORTED, so the
     * whole round is being killed — every hypothesis gets snapshotted with its
     * WEAK/REJECTED status for the trace and for the RootCauseAgent's re-think.
     */
    private List<DiscardedHypothesis> snapshotKilledHypotheses(IncidentContext ctx) {
        return ctx.hypotheses().stream()
                .map(h -> ctx.critiques().stream()
                        .filter(c -> c.hypothesisId().equals(h.id()))
                        .findFirst()
                        .map(c -> new DiscardedHypothesis(h, c.status(), c.reasons()))
                        .orElse(new DiscardedHypothesis(h, CritiqueStatus.WEAK,
                                List.of("No critic verdict recorded for this hypothesis"))))
                .toList();
    }

    /** Resolve a model-written citation reference to a real C-id, or null if unknown. */
    private String resolveCitationId(IncidentContext ctx, String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        String wanted = ref.strip();
        // Case 1: already a real C-id.
        if (ctx.findCitation(wanted).isPresent()) {
            return wanted;
        }
        for (Citation c : ctx.citations()) {
            // Case 2: the citation's source name (file name), e.g. "runbook-null-pointer.md".
            if (c.sourceName().equalsIgnoreCase(wanted)) {
                return c.id();
            }
            // Case 3: an id that appears inside the document text, e.g. "RB-NPE-001".
            // Require some length so a short token can't accidentally match everything.
            if (wanted.length() >= 4 && c.snippet().contains(wanted)) {
                return c.id();
            }
        }
        return null; // unresolvable -> dropped by the caller
    }

    /**
     * OFFLINE STUB (no model credentials): a deterministic, canned walk-through of
     * the same pipeline shape — canned evidence, the two bundled knowledge docs as
     * citations, one canned hypothesis, a SUPPORTED critique, and a RECOMMEND
     * decision with the canned draft fix. No model calls, no network. Every summary
     * is tagged [OFFLINE STUB] so a demo can never be mistaken for a real run.
     */
    private IncidentContext stubContext(String incidentId, String service, IncidentRequest incident) {
        IncidentContext ctx = new IncidentContext(
                incidentId, service, redactor.redact(incident.stackTrace() == null ? "" : incident.stackTrace()));
        ctx.setRedactedLogs(redactor.redact(
                getLogsTool.execute(Map.of("service", service, "time_window", "last_15m"))));
        ctx.setRedactedMetrics(redactor.redact(
                getMetricsTool.execute(Map.of("service", service, "metric", "error_rate"))));

        ctx.trace().add(TraceEventType.INCIDENT_RECEIVED, "Orchestrator",
                "[OFFLINE STUB] Incident received for " + service + " (foundry.enabled=false; canned pipeline)",
                Map.of("service", service, "timestamp", Instant.now().toString()));
        ctx.trace().add(TraceEventType.REDACTION, "Orchestrator",
                "[OFFLINE STUB] Secret redaction applied at the boundary");

        ctx.addEvidence(List.of(
                new Evidence("E1", "symptom",
                        "NullPointerException thrown at OrderService.java:42 when applying loyalty discount",
                        Evidence.SOURCE_INCIDENT_REPORT),
                new Evidence("E2", "metric",
                        "error_rate rose from 0.3% to 19% at 09:14", Evidence.SOURCE_OBSERVABILITY),
                new Evidence("E3", "timeline",
                        "Deploy v2.4.0 (legacy customer import) went out at 09:13, one minute before the spike",
                        Evidence.SOURCE_OBSERVABILITY)));
        ctx.trace().add(TraceEventType.EVIDENCE_EXTRACTED, "TriageAgent",
                "[OFFLINE STUB] Extracted 3 canned evidence items",
                Map.of("count", 3, "evidence", ctx.evidence()));

        ctx.addCitations(List.of(
                new Citation("C1", "runbook-null-pointer.md",
                        "Null-guard the value at the point of use, or default it where the data enters the system."),
                new Citation("C2", "postmortem-2025-11-order-service-npe.md",
                        "Legacy-imported customers had a null loyaltyTier; defaulted missing tier to STANDARD.")));
        ctx.trace().add(TraceEventType.CITATIONS_RETRIEVED, "KnowledgeAgent",
                "[OFFLINE STUB] 2 canned citations (Foundry IQ disabled)",
                Map.of("count", 2, "citationIds", List.of("C1", "C2"),
                       "sourceNames", List.of("runbook-null-pointer.md", "postmortem-2025-11-order-service-npe.md")));

        Hypothesis h1 = new Hypothesis(ctx.nextHypothesisId(),
                "Legacy-imported customers have a null loyaltyTier and applyLoyaltyDiscount dereferences it",
                0.85, List.of("E1", "E3"), List.of("C1", "C2"));
        ctx.setHypotheses(List.of(h1));
        ctx.trace().add(TraceEventType.HYPOTHESES_PROPOSED, "RootCauseAgent",
                "[OFFLINE STUB] 1 canned hypothesis", Map.of("count", 1, "hypotheses", ctx.hypotheses()));

        ctx.setCritiques(List.of(new Critique(h1.id(), CritiqueStatus.SUPPORTED,
                List.of("E1 shows the NPE at the discount call site", "C2 documents the identical prior incident"))));
        ctx.trace().add(TraceEventType.CRITIQUE, "CriticAgent",
                "[OFFLINE STUB] " + h1.id() + "=SUPPORTED", Map.of("critiques", ctx.critiques()));

        String fix = draftFixTool.execute(Map.of("file_path", "OrderService.java", "change", "null-guard tier"));
        ctx.setDecision(new Decision(DecisionType.RECOMMEND_REMEDIATION, h1.id(),
                "[OFFLINE STUB] H1 is SUPPORTED and explains E1-E3, backed by C1/C2.",
                fix,
                "Root cause: null loyaltyTier from the legacy import (matches runbook-null-pointer.md "
                        + "and postmortem-2025-11-order-service-npe.md). Fix: default missing tier to STANDARD."));
        ctx.trace().add(TraceEventType.DECISION, "JudgeAgent",
                "[OFFLINE STUB] Decision: RECOMMEND_REMEDIATION (selected " + h1.id() + ")",
                Map.of("type", "RECOMMEND_REMEDIATION", "selectedHypothesisId", h1.id()));
        ctx.trace().add(TraceEventType.FIX_DRAFTED, "JudgeAgent",
                "[OFFLINE STUB] Drafted a proposed fix for " + h1.id() + " (draft only, not applied)",
                Map.of("selectedHypothesisId", h1.id(), "proposedFixChars", fix.length()));
        ctx.trace().add(TraceEventType.APPROVAL_GATE, "Orchestrator",
                "HARD STOP: fix is a draft awaiting human approval — the agent takes no action",
                Map.of("decisionType", "RECOMMEND_REMEDIATION", "selectedHypothesisId", h1.id()));
        return ctx;
    }
}

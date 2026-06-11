package com.aisre.agent.model;

import com.aisre.agent.agentic.Citation;
import com.aisre.agent.agentic.Critique;
import com.aisre.agent.agentic.Decision;
import com.aisre.agent.agentic.DecisionType;
import com.aisre.agent.agentic.Evidence;
import com.aisre.agent.agentic.Hypothesis;
import com.aisre.agent.agentic.IncidentContext;
import com.aisre.agent.agentic.TraceEvent;

import java.util.List;

/**
 * What /triage returns: the multi-agent pipeline's full result.
 *
 * PRESERVED FIELDS (the stable API contract — do not rename):
 *   incidentId, service, citedSources, proposedFix, postmortem, confidence,
 *   status (with "AWAITING_APPROVAL" as the human-gate status).
 *
 * STRUCTURED SECTIONS (new with the multi-agent architecture):
 *   evidence (E#), hypotheses (H# with E/C provenance), critiques (the Critic's
 *   structured verdicts), decision (the Judge's outcome), and trace (the glass-box
 *   event log, which absorbs the old classification/reasoningSteps fields).
 *
 * @param incidentId   generated id for this triage run.
 * @param service      echoes back the failing service.
 * @param citedSources readable knowledge source names supporting the conclusion.
 * @param proposedFix  drafted fix (RECOMMEND_REMEDIATION only), else "".
 * @param postmortem   write-up citing sources (RECOMMEND_REMEDIATION only), else "".
 * @param confidence   the selected hypothesis's confidence, or 0.0 when escalating.
 * @param status       "AWAITING_APPROVAL" (gate) or an escalation status.
 * @param evidence     the TriageAgent's structured facts.
 * @param hypotheses   the FINAL round of hypotheses (discarded ones live in the trace).
 * @param critiques    the Critic's verdicts on that final round.
 * @param decision     the Judge's decision object.
 * @param trace        the ordered glass-box trace events.
 */
public record TriageResult(
        String incidentId,
        String service,
        List<String> citedSources,
        String proposedFix,
        String postmortem,
        double confidence,
        String status,
        List<Evidence> evidence,
        List<Hypothesis> hypotheses,
        List<Critique> critiques,
        Decision decision,
        List<TraceEvent> trace
) {

    /** Map a completed pipeline run onto the API shape. */
    public static TriageResult from(IncidentContext ctx) {
        Decision decision = ctx.decision();
        Hypothesis selected = decision.selectedHypothesisId() == null
                ? null
                : ctx.findHypothesis(decision.selectedHypothesisId()).orElse(null);

        return new TriageResult(
                ctx.incidentId(),
                ctx.service(),
                citedSources(ctx, selected),
                decision.proposedFix() == null ? "" : decision.proposedFix(),
                decision.postmortem() == null ? "" : decision.postmortem(),
                selected == null ? 0.0 : selected.confidence(),
                status(decision.type()),
                ctx.evidence(),
                ctx.hypotheses(),
                ctx.critiques(),
                decision,
                ctx.trace().events());
    }

    /** The human-gate status is AWAITING_APPROVAL; escalations carry their own names. */
    private static String status(DecisionType type) {
        return switch (type) {
            case RECOMMEND_REMEDIATION -> "AWAITING_APPROVAL";
            case ESCALATE_TO_HUMAN -> "ESCALATED_TO_HUMAN";
            case INSUFFICIENT_EVIDENCE -> "INSUFFICIENT_EVIDENCE";
        };
    }

    /**
     * citedSources stays a list of READABLE source names: the ones backing the
     * selected hypothesis when there is one, otherwise every source that was
     * consulted (so an escalation still shows what knowledge was checked).
     */
    private static List<String> citedSources(IncidentContext ctx, Hypothesis selected) {
        if (selected != null && !selected.supportingCitationIds().isEmpty()) {
            return selected.supportingCitationIds().stream()
                    .map(id -> ctx.findCitation(id).map(Citation::sourceName).orElse(id))
                    .distinct()
                    .toList();
        }
        return ctx.citations().stream().map(Citation::sourceName).distinct().toList();
    }
}

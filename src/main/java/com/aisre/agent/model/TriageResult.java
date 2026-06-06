package com.aisre.agent.model;

import java.util.List;

/**
 * What /triage returns: the agent's triage of the incident.
 *
 * In Phase 1 every field is filled with canned/fake values by {@code ReasoningLoop}
 * — NO AI is involved yet. The shape is intentionally the same shape we will fill
 * for real in later phases, so the contract the caller sees does not change.
 *
 * @param incidentId        a generated id for this triage run
 * @param service           echoes back the failing service
 * @param classification    what kind of failure this is (Phase 1: hardcoded)
 * @param rootCauseHypothesis the agent's best theory of the cause (Phase 1: hardcoded)
 * @param citedSources      knowledge sources backing the hypothesis (Phase 1: from stub search_knowledge)
 * @param proposedFix       a drafted diff / PR description (Phase 1: from stub draft_fix)
 * @param postmortem        a short write-up citing the sources (Phase 1: hardcoded)
 * @param confidence        0.0–1.0 confidence (Phase 1: fixed placeholder)
 * @param reasoningSteps    human-readable trail of what the agent "did" (precursor to the Phase 3 trace)
 * @param status            workflow state; always AWAITING_APPROVAL — the agent never acts on its own
 * @param phaseNote         a banner reminding us this output is a Phase 1 stub
 */
public record TriageResult(
        String incidentId,
        String service,
        String classification,
        String rootCauseHypothesis,
        List<String> citedSources,
        String proposedFix,
        String postmortem,
        double confidence,
        List<String> reasoningSteps,
        String status,
        String phaseNote
) {
}

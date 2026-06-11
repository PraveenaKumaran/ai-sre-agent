package com.aisre.agent.agentic;

import java.util.List;

/**
 * A candidate root-cause theory produced by the RootCauseAgent.
 *
 * The RootCauseAgent proposes 2-3 of these as COMPETING explanations. Every
 * hypothesis must declare which evidence and which citations back it (by id), so
 * the Critic can check those exact ids and the trace can show the provenance chain
 * hypothesis -> evidence ids -> citation ids.
 *
 * The list fields are defensively copied and never null, so downstream code can
 * iterate them safely.
 *
 * @param id                    stable reference id, e.g. "H1".
 * @param statement             the proposed root cause, one or two sentences.
 * @param confidence            the agent's own 0.0-1.0 confidence (a SIGNAL only;
 *                              by design confidence alone can never justify selection).
 * @param supportingEvidenceIds evidence ids that back this hypothesis, e.g. ["E1","E4"].
 * @param supportingCitationIds citation ids that back this hypothesis, e.g. ["C2"].
 */
public record Hypothesis(
        String id,
        String statement,
        double confidence,
        List<String> supportingEvidenceIds,
        List<String> supportingCitationIds
) {
    // Compact constructor: copy the lists and turn nulls into empty lists so the
    // record is immutable and safe to read without null checks.
    public Hypothesis {
        supportingEvidenceIds = supportingEvidenceIds == null ? List.of() : List.copyOf(supportingEvidenceIds);
        supportingCitationIds = supportingCitationIds == null ? List.of() : List.copyOf(supportingCitationIds);
    }
}

package com.aisre.agent.agentic;

/**
 * The JudgeAgent's final, recorded decision for an incident.
 *
 * The Judge selects using a STRICT priority order — (1) Critic support status,
 * (2) evidence coverage, (3) citation support, (4) confidence — where confidence
 * alone can never justify a selection. The {@code rationale} captures that reasoning.
 *
 * A fix + postmortem are only present for RECOMMEND_REMEDIATION; for the two
 * escalation outcomes they are null and nothing is drafted.
 *
 * @param type                 the outcome (recommend / escalate / insufficient).
 * @param selectedHypothesisId the chosen hypothesis id (e.g. "H4"), or null if none.
 * @param rationale            the Judge's explanation, framed by the priority order.
 * @param proposedFix          the drafted fix (RECOMMEND_REMEDIATION only), else null.
 * @param postmortem           the postmortem citing sources (RECOMMEND_REMEDIATION only), else null.
 */
public record Decision(
        DecisionType type,
        String selectedHypothesisId,
        String rationale,
        String proposedFix,
        String postmortem
) {
    /** Convenience for the two non-acting outcomes (no fix, no selected hypothesis). */
    public static Decision escalate(DecisionType type, String rationale) {
        return new Decision(type, null, rationale, null, null);
    }
}

package com.aisre.agent.agentic;

import java.util.List;

/**
 * A self-contained snapshot of a hypothesis that was killed, bundled with WHY.
 *
 * Why this exists: hypotheses and critiques are REPLACED each retry round, so by the
 * time anyone reads the trace, the live {@code H#} ids no longer resolve. This record
 * captures the FULL hypothesis (statement, confidence, supporting evidence/citation
 * ids) together with the Critic's verdict and structured reasons, so a
 * HYPOTHESIS_DISCARDED / RETRY_TRIGGERED event stands on its own — no dangling id.
 *
 * @param hypothesis the complete killed hypothesis (immutable record).
 * @param status     the Critic's verdict that killed it (WEAK or REJECTED).
 * @param reasons    the Critic's structured reasons, each ideally citing an E/C id.
 */
public record DiscardedHypothesis(
        Hypothesis hypothesis,
        CritiqueStatus status,
        List<String> reasons
) {
    public DiscardedHypothesis {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }
}

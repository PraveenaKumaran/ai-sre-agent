package com.aisre.agent.agentic;

import java.util.List;

/**
 * The CriticAgent's structured verdict on one hypothesis.
 *
 * This is the machine-readable shape the Critic must emit (one per hypothesis):
 * <pre>
 * { "hypothesisId": "H2",
 *   "status": "SUPPORTED" | "WEAK" | "REJECTED",
 *   "reasons": ["E4 contradicts theory", "No supporting citation found", ...] }
 * </pre>
 * Every reason should reference the specific evidence (E#) or citation (C#) id it
 * relies on — no rejections "on vibes". This same structure flows back to the
 * RootCauseAgent on retry, becomes the HYPOTHESIS_DISCARDED trace payloads, and
 * feeds the evaluation runner's rejection counts.
 *
 * @param hypothesisId the hypothesis this verdict is about, e.g. "H2".
 * @param status       SUPPORTED / WEAK / REJECTED.
 * @param reasons      the supporting argument lines, each ideally citing an E/C id.
 */
public record Critique(
        String hypothesisId,
        CritiqueStatus status,
        List<String> reasons
) {
    public Critique {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }
}

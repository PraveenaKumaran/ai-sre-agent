package com.aisre.agent.agentic;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step 1 tests: lock in the behaviour the rest of the pipeline will depend on —
 * graceful-degrade parsing, the deterministic retry signal, accumulate-vs-replace
 * semantics on the shared context, and null-safe value records.
 */
class DomainModelsTest {

    @Test
    void critiqueStatusParsesAndDegradesToWeak() {
        assertThat(CritiqueStatus.fromString("SUPPORTED")).isEqualTo(CritiqueStatus.SUPPORTED);
        assertThat(CritiqueStatus.fromString("rejected")).isEqualTo(CritiqueStatus.REJECTED); // case-insensitive
        assertThat(CritiqueStatus.fromString("  weak ")).isEqualTo(CritiqueStatus.WEAK);
        assertThat(CritiqueStatus.fromString("banana")).isEqualTo(CritiqueStatus.WEAK);   // unknown -> WEAK
        assertThat(CritiqueStatus.fromString(null)).isEqualTo(CritiqueStatus.WEAK);        // null -> WEAK
    }

    @Test
    void decisionTypeDegradesToEscalate() {
        assertThat(DecisionType.fromString("RECOMMEND_REMEDIATION")).isEqualTo(DecisionType.RECOMMEND_REMEDIATION);
        assertThat(DecisionType.fromString("nonsense")).isEqualTo(DecisionType.ESCALATE_TO_HUMAN); // safe default
        assertThat(DecisionType.fromString(null)).isEqualTo(DecisionType.ESCALATE_TO_HUMAN);
    }

    @Test
    void hypothesisListsAreNeverNull() {
        Hypothesis h = new Hypothesis("H1", "some cause", 0.7, null, null);
        assertThat(h.supportingEvidenceIds()).isEmpty();
        assertThat(h.supportingCitationIds()).isEmpty();
    }

    @Test
    void contextAccumulatesEvidenceButReplacesHypotheses() {
        IncidentContext ctx = new IncidentContext("INC-1", "order-service", "boom");

        ctx.addEvidence(List.of(new Evidence("E1", "symptom", "NPE at line 42", "get_logs")));
        ctx.addEvidence(List.of(new Evidence("E2", "metric", "error_rate 19%", "get_metrics")));
        assertThat(ctx.evidence()).hasSize(2); // accumulates
        assertThat(ctx.findEvidence("E2")).isPresent();

        ctx.setHypotheses(List.of(new Hypothesis("H1", "first", 0.5, List.of("E1"), List.of())));
        ctx.setHypotheses(List.of(new Hypothesis("H2", "second", 0.6, List.of("E2"), List.of("C1"))));
        assertThat(ctx.hypotheses()).hasSize(1); // replaced, not appended
        assertThat(ctx.findHypothesis("H2")).isPresent();
        assertThat(ctx.findHypothesis("H1")).isEmpty();
    }

    @Test
    void hasSupportedHypothesisDrivesTheRetrySignal() {
        IncidentContext ctx = new IncidentContext("INC-2", "payment-service", "timeout");

        // Only WEAK + REJECTED -> no SUPPORTED -> retry signal is false.
        ctx.setCritiques(List.of(
                new Critique("H1", CritiqueStatus.REJECTED, List.of("E4 contradicts")),
                new Critique("H2", CritiqueStatus.WEAK, List.of("No supporting citation"))));
        assertThat(ctx.hasSupportedHypothesis()).isFalse();

        // One SUPPORTED -> retry signal is true (orchestrator proceeds to Judge).
        ctx.setCritiques(List.of(
                new Critique("H3", CritiqueStatus.SUPPORTED, List.of("E1 and C2 both confirm"))));
        assertThat(ctx.hasSupportedHypothesis()).isTrue();

        ctx.incrementRetryCount();
        ctx.incrementRetryCount();
        assertThat(ctx.retryCount()).isEqualTo(2);
    }

    @Test
    void decisionEscalateHelperHasNoFix() {
        Decision d = Decision.escalate(DecisionType.INSUFFICIENT_EVIDENCE, "not enough grounded evidence");
        assertThat(d.proposedFix()).isNull();
        assertThat(d.selectedHypothesisId()).isNull();
        assertThat(d.type()).isEqualTo(DecisionType.INSUFFICIENT_EVIDENCE);
    }
}

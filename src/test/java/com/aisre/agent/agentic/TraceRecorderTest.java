package com.aisre.agent.agentic;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Step 2 tests: the trace records ordered events with a machine-readable payload,
 * and — critically — the discard/retry snapshots are SELF-CONTAINED, so they still
 * hold the full killed hypothesis after the live hypothesis set has been replaced.
 */
class TraceRecorderTest {

    private Hypothesis h(String id, String stmt, double conf) {
        return new Hypothesis(id, stmt, conf, List.of("E1", "E4"), List.of("C2"));
    }

    @Test
    void assignsIncreasingSequenceNumbers() {
        TraceRecorder t = new TraceRecorder();
        TraceEvent a = t.add(TraceEventType.INCIDENT_RECEIVED, "Orchestrator", "got incident");
        TraceEvent b = t.add(TraceEventType.EVIDENCE_EXTRACTED, "TriageAgent", "3 facts");

        assertThat(a.seq()).isEqualTo(1);
        assertThat(b.seq()).isEqualTo(2);
        assertThat(t.events()).containsExactly(a, b);
    }

    @Test
    void payloadCarriesStructuredDataSeparateFromSummary() {
        TraceRecorder t = new TraceRecorder();
        TraceEvent e = t.add(TraceEventType.CITATIONS_RETRIEVED, "KnowledgeAgent",
                "2 citations retrieved",
                Map.of("citationIds", List.of("C1", "C2")));

        // The machine-readable data lives in payload, not in the summary text.
        assertThat(e.summary()).isEqualTo("2 citations retrieved");
        assertThat(e.payload()).containsEntry("citationIds", List.of("C1", "C2"));
    }

    @Test
    void recordedPayloadIsImmutable() {
        TraceRecorder t = new TraceRecorder();
        TraceEvent e = t.add(TraceEventType.DECISION, "JudgeAgent", "decided",
                Map.of("type", "ESCALATE_TO_HUMAN"));
        assertThatThrownBy(() -> e.payload().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void discardedSnapshotSurvivesHypothesisReplacement() {
        IncidentContext ctx = new IncidentContext("INC-1", "payment-service", "timeout");
        Hypothesis h2 = h("H2", "bank-gateway is down", 0.55);
        ctx.setHypotheses(List.of(h2));

        // Critic kills H2 on retry 1; we snapshot it into the trace.
        ctx.trace().hypothesisDiscarded(1, 2, h2, CritiqueStatus.REJECTED,
                List.of("E4 shows downstream latency normal", "C2 recommends checking own pool"));

        // Now the round is replaced with a brand-new hypothesis set (H2 no longer exists live).
        ctx.setHypotheses(List.of(h("H4", "connection pool exhausted by 09:05 config change", 0.8)));
        assertThat(ctx.findHypothesis("H2")).isEmpty(); // gone from the live context

        // ...but the trace still holds the FULL killed hypothesis, self-contained.
        TraceEvent discarded = ctx.trace().events().get(0);
        assertThat(discarded.type()).isEqualTo(TraceEventType.HYPOTHESIS_DISCARDED);
        assertThat(discarded.payload()).containsEntry("retryNumber", 1);

        DiscardedHypothesis snap = (DiscardedHypothesis) discarded.payload().get("discarded");
        assertThat(snap.hypothesis().id()).isEqualTo("H2");
        assertThat(snap.hypothesis().statement()).isEqualTo("bank-gateway is down");
        assertThat(snap.hypothesis().confidence()).isEqualTo(0.55);
        assertThat(snap.hypothesis().supportingEvidenceIds()).containsExactly("E1", "E4");
        assertThat(snap.hypothesis().supportingCitationIds()).containsExactly("C2");
        assertThat(snap.status()).isEqualTo(CritiqueStatus.REJECTED);
        assertThat(snap.reasons()).hasSize(2).anyMatch(r -> r.contains("E4"));
    }

    @Test
    void retryTriggeredCarriesRetryNumberAndAllRejectedHypotheses() {
        TraceRecorder t = new TraceRecorder();
        DiscardedHypothesis r1 = new DiscardedHypothesis(h("H1", "theory one", 0.4),
                CritiqueStatus.REJECTED, List.of("E2 contradicts"));
        DiscardedHypothesis r2 = new DiscardedHypothesis(h("H2", "theory two", 0.5),
                CritiqueStatus.WEAK, List.of("No supporting citation"));

        TraceEvent e = t.retryTriggered(1, 2, List.of(r1, r2));

        assertThat(e.type()).isEqualTo(TraceEventType.RETRY_TRIGGERED);
        assertThat(e.payload()).containsEntry("retryNumber", 1).containsEntry("maxRetries", 2);
        @SuppressWarnings("unchecked")
        List<DiscardedHypothesis> rejected = (List<DiscardedHypothesis>) e.payload().get("rejectedHypotheses");
        assertThat(rejected).hasSize(2);
        assertThat(rejected.get(0).hypothesis().id()).isEqualTo("H1");
        assertThat(rejected.get(1).status()).isEqualTo(CritiqueStatus.WEAK);
    }
}

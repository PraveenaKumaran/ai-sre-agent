package com.aisre.agent.agentic;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

import com.aisre.agent.config.FoundryProperties;

/**
 * Unit tests for the eval runner's scoring rules — no model, no keys. The live run
 * exercises the pipeline; these pin how its output is judged.
 */
class EvalScoringTest {

    @Test
    void decisionScoring() {
        assertThat(EvalRunner.decisionPass("RECOMMEND_REMEDIATION", DecisionType.RECOMMEND_REMEDIATION)).isTrue();
        assertThat(EvalRunner.decisionPass("RECOMMEND_REMEDIATION", DecisionType.ESCALATE_TO_HUMAN)).isFalse();
        // Expected ESCALATE accepts EITHER escalation outcome…
        assertThat(EvalRunner.decisionPass("ESCALATE", DecisionType.ESCALATE_TO_HUMAN)).isTrue();
        assertThat(EvalRunner.decisionPass("ESCALATE", DecisionType.INSUFFICIENT_EVIDENCE)).isTrue();
        // …but a recommendation when escalation was expected is a fail.
        assertThat(EvalRunner.decisionPass("ESCALATE", DecisionType.RECOMMEND_REMEDIATION)).isFalse();
    }

    @Test
    void keywordScoringRequiresAllKeywordsCaseInsensitive() {
        String text = "Root cause: the connection POOL was shrunk by a Config change.";
        assertThat(EvalRunner.keywordsPass(List.of("pool", "config"), text)).isTrue();
        assertThat(EvalRunner.keywordsPass(List.of("pool", "loyaltyTier"), text)).isFalse();
    }

    @Test
    void citationScoringPassesOnAnyMatch() {
        List<String> cited = List.of("runbook-connection-pool-exhaustion.md");
        assertThat(EvalRunner.citationPass(
                List.of("runbook-connection-pool-exhaustion.md", "postmortem-2025-09-payment-pool.md"),
                cited)).isTrue();
        assertThat(EvalRunner.citationPass(List.of("runbook-null-pointer.md"), cited)).isFalse();
    }

    @Test
    void totalsLineCarriesAverageLatencyAndModelCalls() {
        // toMarkdown only needs FoundryProperties for its header line.
        FoundryProperties.Iq iqOff = new FoundryProperties.Iq(false, "", "", "", "", "", 4);
        EvalRunner runner = new EvalRunner(null, null,
                new FoundryProperties(true, "", "gpt-5.4", "", "", "", "api-key", "", "low", 4000, iqOff),
                null, null);

        String md = runner.toMarkdown(List.of(
                new EvalRunner.Row("EVAL-01", "a", "RECOMMEND_REMEDIATION", "AWAITING_APPROVAL",
                        true, true, true, 0, 30.0, 5, null),
                new EvalRunner.Row("EVAL-02", "b", "RECOMMEND_REMEDIATION", "AWAITING_APPROVAL",
                        true, true, true, 2, 60.0, 9, null)));

        // Per-row columns are structured fields, and the totals carry the averages.
        assertThat(md).contains("| 30.0 | 5 |").contains("| 60.0 | 9 |");
        assertThat(md).contains("Average latency: 45.0 s · Average model calls: 7.0");
    }

    @Test
    void rejectionCountReadsStructuredCritiquePayloadsAcrossRounds() {
        TraceRecorder trace = new TraceRecorder();
        // Round 1: one REJECTED, one WEAK.
        trace.add(TraceEventType.CRITIQUE, "CriticAgent", "round 1", Map.of("critiques", List.of(
                new Critique("H1", CritiqueStatus.REJECTED, List.of("E4 contradicts")),
                new Critique("H2", CritiqueStatus.WEAK, List.of("No citation")))));
        // Noise event that must not be counted.
        trace.add(TraceEventType.DECISION, "JudgeAgent", "decided", Map.of("type", "X"));
        // Round 2: another REJECTED.
        trace.add(TraceEventType.CRITIQUE, "CriticAgent", "round 2", Map.of("critiques", List.of(
                new Critique("H3", CritiqueStatus.REJECTED, List.of("C1 recommends the opposite")))));

        assertThat(EvalRunner.countCriticRejections(trace.events())).isEqualTo(2);
    }
}

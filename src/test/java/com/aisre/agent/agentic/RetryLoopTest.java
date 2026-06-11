package com.aisre.agent.agentic;

import com.aisre.agent.model.IncidentRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step 4: the RootCause<->Critic self-reflection retry loop, plus the
 * "model proposes, code verifies" citation-id normalization.
 *
 * Deterministic trigger under test: retry fires whenever NO hypothesis is rated
 * SUPPORTED (WEAK-only rounds retry too), killed hypotheses flow back to the
 * RootCauseAgent with the Critic's reasons, max 2 retries, then the Judge decides
 * regardless — and is told the retries were exhausted.
 */
class RetryLoopTest {

    private static final String TRIAGE_REPLY = """
            {"evidence":[
              {"id":"E1","type":"symptom","statement":"NPE at OrderService.java:42","source":"stack_trace"},
              {"id":"E2","type":"timeline","statement":"Errors started at 09:14, right after the 09:13 deploy","source":"metrics"}]}
            """;

    private static final String KNOWLEDGE_REPLY = """
            {"query":"NullPointerException loyaltyTier order-service"}
            """;

    private IncidentContext run(List<String> replies, PipelineTestSupport.ScriptedModel[] modelOut) {
        PipelineTestSupport.ScriptedModel model = new PipelineTestSupport.ScriptedModel(replies);
        modelOut[0] = model;
        return PipelineTestSupport.orchestrator(model)
                .run(new IncidentRequest("order-service", "java.lang.NullPointerException at OrderService.java:42"));
    }

    @Test
    void citationReferencesAreNormalizedToRealCIds() {
        // The model "cites" a doc-internal id, a file name, a real C-id, and garbage.
        // Code must resolve the first three to C-ids and drop the garbage.
        String messyRootCause = """
                {"hypotheses":[{"statement":"Null loyaltyTier from legacy import","confidence":0.8,
                  "supportingEvidenceIds":["E1"],
                  "supportingCitationIds":["RB-NPE-001","postmortem-2025-11-order-service-npe.md","C1","totally-bogus"]}]}
                """;
        PipelineTestSupport.ScriptedModel[] m = new PipelineTestSupport.ScriptedModel[1];
        IncidentContext ctx = run(List.of(TRIAGE_REPLY, KNOWLEDGE_REPLY, messyRootCause,
                """
                {"critiques":[{"hypothesisId":"H1","status":"SUPPORTED","reasons":["E1 confirms; C1 documents the pattern"]}]}
                """,
                """
                {"decision":"RECOMMEND_REMEDIATION","selectedHypothesisId":"H1",
                 "rationale":"H1 SUPPORTED.","proposedFix":"Null guard.","postmortem":"Cites runbook-null-pointer.md."}
                """), m);

        // RB-NPE-001 lives inside C1's text; the file name is C2's sourceName; C1 is
        // already real; "totally-bogus" resolves to nothing and is dropped.
        assertThat(ctx.findHypothesis("H1").orElseThrow().supportingCitationIds())
                .containsExactly("C1", "C2");

        // And the correction itself is in the glass box, with before/after ids.
        TraceEvent normalized = ctx.trace().events().stream()
                .filter(e -> e.type() == TraceEventType.PROVENANCE_NORMALIZED)
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        var changes = (java.util.Map<String, java.util.Map<String, Object>>) normalized.payload().get("changes");
        assertThat(changes).containsKey("H1");
        assertThat(changes.get("H1").get("after")).isEqualTo(List.of("C1", "C2"));
    }

    @Test
    void retryFiresWhenNothingIsSupportedAndFeedsRejectionsBack() {
        PipelineTestSupport.ScriptedModel[] m = new PipelineTestSupport.ScriptedModel[1];
        IncidentContext ctx = run(List.of(
                TRIAGE_REPLY, KNOWLEDGE_REPLY,
                // Round 1: two theories...
                """
                {"hypotheses":[
                  {"statement":"bank-gateway outage is causing failures","confidence":0.6,"supportingEvidenceIds":["E1"],"supportingCitationIds":[]},
                  {"statement":"Disk pressure on the order-service host","confidence":0.3,"supportingEvidenceIds":["E2"],"supportingCitationIds":[]}]}
                """,
                // ...both killed: one REJECTED, one WEAK -> NO SUPPORTED -> retry.
                """
                {"critiques":[
                  {"hypothesisId":"H1","status":"REJECTED","reasons":["E2 contradicts an external outage: onset matches our own deploy"]},
                  {"hypothesisId":"H2","status":"WEAK","reasons":["No supporting citation found"]}]}
                """,
                // Round 2 (the re-think): a different theory, id continues as H3.
                """
                {"hypotheses":[{"statement":"Null loyaltyTier introduced by the 09:13 deploy","confidence":0.85,"supportingEvidenceIds":["E1","E2"],"supportingCitationIds":["C1"]}]}
                """,
                """
                {"critiques":[{"hypothesisId":"H3","status":"SUPPORTED","reasons":["E1+E2 fit; C1 documents the fix pattern"]}]}
                """,
                """
                {"decision":"RECOMMEND_REMEDIATION","selectedHypothesisId":"H3",
                 "rationale":"H3 SUPPORTED after re-think.","proposedFix":"Null guard.","postmortem":"Cites runbook-null-pointer.md."}
                """), m);

        // One retry happened; the new round replaced the old (H1/H2 gone, H3 live).
        assertThat(ctx.retryCount()).isEqualTo(1);
        assertThat(ctx.hypotheses()).extracting(Hypothesis::id).containsExactly("H3");
        assertThat(ctx.findHypothesis("H1")).isEmpty();

        // Trace: both kills snapshotted self-contained, then the retry event.
        List<TraceEvent> discarded = ctx.trace().events().stream()
                .filter(e -> e.type() == TraceEventType.HYPOTHESIS_DISCARDED).toList();
        assertThat(discarded).hasSize(2);
        DiscardedHypothesis firstKill = (DiscardedHypothesis) discarded.get(0).payload().get("discarded");
        assertThat(firstKill.hypothesis().statement()).contains("bank-gateway");
        assertThat(firstKill.status()).isEqualTo(CritiqueStatus.REJECTED);
        assertThat(discarded.get(0).payload()).containsEntry("retryNumber", 1);

        TraceEvent retry = ctx.trace().events().stream()
                .filter(e -> e.type() == TraceEventType.RETRY_TRIGGERED).findFirst().orElseThrow();
        assertThat(retry.payload()).containsEntry("retryNumber", 1).containsEntry("maxRetries", 2);
        @SuppressWarnings("unchecked")
        List<DiscardedHypothesis> rejected = (List<DiscardedHypothesis>) retry.payload().get("rejectedHypotheses");
        assertThat(rejected).hasSize(2);

        // Self-reflection: the SECOND RootCause call (model call #5) was shown the
        // killed theories WITH the Critic's reasons.
        String secondRootCauseInput = m[0].userMessages.get(4);
        assertThat(secondRootCauseInput)
                .contains("PREVIOUSLY REJECTED HYPOTHESES")
                .contains("bank-gateway")
                .contains("onset matches our own deploy");

        // The Judge (last call) was told the retry count.
        assertThat(m[0].userMessages.get(m[0].userMessages.size() - 1)).contains("Retry count: 1");

        // And the run still ends at the approval gate.
        assertThat(ctx.decision().type()).isEqualTo(DecisionType.RECOMMEND_REMEDIATION);
        assertThat(ctx.trace().events().get(ctx.trace().events().size() - 1).type())
                .isEqualTo(TraceEventType.APPROVAL_GATE);
    }

    @Test
    void afterTwoRetriesTheJudgeDecidesRegardless() {
        // Three rounds of hypotheses, none ever SUPPORTED -> exactly 2 retries, then Judge.
        String weakRound1 = """
                {"hypotheses":[{"statement":"theory one","confidence":0.4,"supportingEvidenceIds":["E1"],"supportingCitationIds":[]}]}
                """;
        String weakRound2 = """
                {"hypotheses":[{"statement":"theory two","confidence":0.4,"supportingEvidenceIds":["E1"],"supportingCitationIds":[]}]}
                """;
        String weakRound3 = """
                {"hypotheses":[{"statement":"theory three","confidence":0.4,"supportingEvidenceIds":["E2"],"supportingCitationIds":[]}]}
                """;
        PipelineTestSupport.ScriptedModel[] m = new PipelineTestSupport.ScriptedModel[1];
        IncidentContext ctx = run(List.of(
                TRIAGE_REPLY, KNOWLEDGE_REPLY,
                weakRound1,
                """
                {"critiques":[{"hypothesisId":"H1","status":"WEAK","reasons":["No supporting citation found"]}]}
                """,
                weakRound2,
                """
                {"critiques":[{"hypothesisId":"H2","status":"REJECTED","reasons":["E2 contradicts theory two"]}]}
                """,
                weakRound3,
                """
                {"critiques":[{"hypothesisId":"H3","status":"WEAK","reasons":["Rests on an unverified assumption"]}]}
                """,
                """
                {"decision":"ESCALATE_TO_HUMAN","selectedHypothesisId":null,
                 "rationale":"Retries exhausted and nothing SUPPORTED; a human should investigate.",
                 "proposedFix":"","postmortem":""}
                """), m);

        // Exactly 2 retries (the cap), 9 model calls total, then the Judge ran anyway.
        assertThat(ctx.retryCount()).isEqualTo(2);
        assertThat(m[0].calls).isEqualTo(9);
        assertThat(ctx.trace().events().stream()
                .filter(e -> e.type() == TraceEventType.RETRY_TRIGGERED).count()).isEqualTo(2);

        // The Judge was explicitly told the retries were exhausted, and escalated.
        assertThat(m[0].userMessages.get(m[0].userMessages.size() - 1)).contains("RETRIES EXHAUSTED");
        assertThat(ctx.decision().type()).isEqualTo(DecisionType.ESCALATE_TO_HUMAN);
        assertThat(ctx.decision().proposedFix()).isNull();
        assertThat(ctx.trace().events().get(ctx.trace().events().size() - 1).type())
                .isEqualTo(TraceEventType.ESCALATION);
    }
}

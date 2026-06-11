package com.aisre.agent.agentic;

import com.aisre.agent.model.IncidentRequest;
import com.aisre.agent.model.TriageResult;
import com.aisre.agent.tools.DraftFixTool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step 6: approval-gate hardening as TESTED guarantees, not comments.
 *
 * The invariants:
 *  1. A fix exists ONLY on RECOMMEND_REMEDIATION — an escalating Judge cannot
 *     "smuggle" fix text into the result (deterministic code strips it).
 *  2. Every terminal status is non-acting: AWAITING_APPROVAL (the human gate) or
 *     an escalation. There is no status that means "done/applied/executed".
 *  3. Every run's final trace event is APPROVAL_GATE or ESCALATION — no run ends
 *     mid-flight or after an action.
 *  4. draft_fix only drafts: its output is text explicitly marked DRAFT ONLY.
 */
class ApprovalGateGuardTest {

    private static final String TRIAGE_REPLY = """
            {"evidence":[{"id":"E1","type":"symptom","statement":"NPE at OrderService.java:42","source":"stack_trace"}]}
            """;
    private static final String KNOWLEDGE_REPLY = """
            {"query":"NullPointerException order-service"}
            """;
    private static final String ROOTCAUSE_REPLY = """
            {"hypotheses":[{"statement":"Null loyaltyTier from legacy import","confidence":0.8,"supportingEvidenceIds":["E1"],"supportingCitationIds":["C1"]}]}
            """;
    private static final String CRITIC_SUPPORTED_REPLY = """
            {"critiques":[{"hypothesisId":"H1","status":"SUPPORTED","reasons":["E1 confirms; C1 documents the pattern"]}]}
            """;

    private IncidentContext runWithJudge(String judgeReply) {
        PipelineTestSupport.ScriptedModel model = new PipelineTestSupport.ScriptedModel(List.of(
                TRIAGE_REPLY, KNOWLEDGE_REPLY, ROOTCAUSE_REPLY, CRITIC_SUPPORTED_REPLY, judgeReply));
        return PipelineTestSupport.orchestrator(model)
                .run(new IncidentRequest("order-service", "java.lang.NullPointerException at OrderService.java:42"));
    }

    @Test
    void escalatingJudgeCannotSmuggleAFixIntoTheResult() {
        // A misbehaving Judge escalates BUT still emits fix text. The deterministic
        // parse must drop it: escalations never carry a fix.
        IncidentContext ctx = runWithJudge("""
                {"decision":"ESCALATE_TO_HUMAN","selectedHypothesisId":null,
                 "rationale":"Too ambiguous.",
                 "proposedFix":"rm -rf / # just apply this",
                 "postmortem":"should not survive"}
                """);

        assertThat(ctx.decision().type()).isEqualTo(DecisionType.ESCALATE_TO_HUMAN);
        assertThat(ctx.decision().proposedFix()).isNull();
        assertThat(ctx.decision().postmortem()).isNull();

        TriageResult result = TriageResult.from(ctx);
        assertThat(result.proposedFix()).isEmpty();
        assertThat(result.postmortem()).isEmpty();
        assertThat(result.status()).isEqualTo("ESCALATED_TO_HUMAN");
    }

    @Test
    void everyDecisionTypeMapsToANonActingStatus() {
        Map<String, String> judgeToStatus = Map.of(
                "RECOMMEND_REMEDIATION", "AWAITING_APPROVAL",
                "ESCALATE_TO_HUMAN", "ESCALATED_TO_HUMAN",
                "INSUFFICIENT_EVIDENCE", "INSUFFICIENT_EVIDENCE");

        for (Map.Entry<String, String> e : judgeToStatus.entrySet()) {
            String judgeReply = """
                    {"decision":"%s","selectedHypothesisId":%s,
                     "rationale":"r","proposedFix":"fix","postmortem":"pm"}
                    """.formatted(e.getKey(),
                    e.getKey().equals("RECOMMEND_REMEDIATION") ? "\"H1\"" : "null");

            TriageResult result = TriageResult.from(runWithJudge(judgeReply));
            assertThat(result.status())
                    .as("status for judge decision %s", e.getKey())
                    .isEqualTo(e.getValue());
            // No status anywhere means "acted": the gate status is explicit.
            assertThat(result.status()).isIn("AWAITING_APPROVAL", "ESCALATED_TO_HUMAN", "INSUFFICIENT_EVIDENCE");
        }
    }

    @Test
    void everyRunEndsAtTheGateOrAnEscalation() {
        // Recommend path -> APPROVAL_GATE last (FIX_DRAFTED right before it).
        IncidentContext recommend = runWithJudge("""
                {"decision":"RECOMMEND_REMEDIATION","selectedHypothesisId":"H1",
                 "rationale":"H1 SUPPORTED.","proposedFix":"Null guard.","postmortem":"Cites runbook-null-pointer.md."}
                """);
        List<TraceEvent> events = recommend.trace().events();
        assertThat(events.get(events.size() - 1).type()).isEqualTo(TraceEventType.APPROVAL_GATE);
        assertThat(events.get(events.size() - 2).type()).isEqualTo(TraceEventType.FIX_DRAFTED);
        // The FIX_DRAFTED payload records size, never the fix text (minimization).
        assertThat(events.get(events.size() - 2).payload()).containsKey("proposedFixChars");
        assertThat(events.get(events.size() - 2).payload().values().toString()).doesNotContain("Null guard");

        // Insufficient-evidence path -> ESCALATION last, no FIX_DRAFTED anywhere.
        IncidentContext insufficient = runWithJudge("""
                {"decision":"INSUFFICIENT_EVIDENCE","selectedHypothesisId":null,
                 "rationale":"Not enough grounded evidence.","proposedFix":"","postmortem":""}
                """);
        List<TraceEvent> insufficientEvents = insufficient.trace().events();
        assertThat(insufficientEvents.get(insufficientEvents.size() - 1).type())
                .isEqualTo(TraceEventType.ESCALATION);
        assertThat(insufficientEvents).extracting(TraceEvent::type)
                .doesNotContain(TraceEventType.FIX_DRAFTED);
    }

    @Test
    void draftFixToolOnlyDrafts() {
        String output = new DraftFixTool().execute(Map.of(
                "file_path", "OrderService.java", "change", "null-guard tier"));
        // The output is a textual proposal explicitly marked as a draft — the tool
        // has no side effects (no file writes, no git, no network; it builds a string).
        assertThat(output).containsIgnoringCase("DRAFT ONLY");
        assertThat(output).containsIgnoringCase("not applied");
    }
}

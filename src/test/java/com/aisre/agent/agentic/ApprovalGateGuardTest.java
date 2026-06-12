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

    // Per-channel triage replies: E1 derives from the incident report, E2 from
    // observability (provenance stamped by code, which the symptom guard reads).
    private static final String TRIAGE_REPORT_REPLY = """
            {"evidence":[{"type":"symptom","statement":"NPE at OrderService.java:42"}]}
            """;
    private static final String TRIAGE_OBS_REPLY = """
            {"evidence":[{"type":"timeline","statement":"Errors started at 09:14, right after the 09:13 deploy"}]}
            """;
    private static final String KNOWLEDGE_REPLY = """
            {"query":"NullPointerException order-service"}
            """;
    // Two substantive evidence ids, so a legitimate recommendation passes the evidence guard.
    private static final String ROOTCAUSE_REPLY = """
            {"hypotheses":[{"statement":"Null loyaltyTier from legacy import","confidence":0.8,"supportingEvidenceIds":["E1","E2"],"supportingCitationIds":["C1"]}]}
            """;
    private static final String CRITIC_SUPPORTED_REPLY = """
            {"critiques":[{"hypothesisId":"H1","status":"SUPPORTED","reasons":["E1 confirms; C1 documents the pattern"]}]}
            """;

    private IncidentContext runWithJudge(String judgeReply) {
        PipelineTestSupport.ScriptedModel model = new PipelineTestSupport.ScriptedModel(List.of(
                TRIAGE_REPORT_REPLY, TRIAGE_OBS_REPLY, KNOWLEDGE_REPLY,
                ROOTCAUSE_REPLY, CRITIC_SUPPORTED_REPLY, judgeReply));
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
    void guardOverridesRecommendationBuiltOnTooFewEvidenceItems() {
        // Hypothesis cites only ONE evidence item; Critic and Judge both bless it
        // anyway. The deterministic guard must override to INSUFFICIENT_EVIDENCE.
        PipelineTestSupport.ScriptedModel model = new PipelineTestSupport.ScriptedModel(List.of(
                TRIAGE_REPORT_REPLY, TRIAGE_OBS_REPLY, KNOWLEDGE_REPLY,
                """
                {"hypotheses":[{"statement":"Some theory","confidence":0.9,"supportingEvidenceIds":["E1"],"supportingCitationIds":["C1"]}]}
                """,
                CRITIC_SUPPORTED_REPLY,
                """
                {"decision":"RECOMMEND_REMEDIATION","selectedHypothesisId":"H1",
                 "rationale":"Looks fine.","proposedFix":"Apply the theory.","postmortem":"pm"}
                """));
        IncidentContext ctx = PipelineTestSupport.orchestrator(model)
                .run(new IncidentRequest("order-service", "boom"));

        assertThat(ctx.decision().type()).isEqualTo(DecisionType.INSUFFICIENT_EVIDENCE);
        assertThat(ctx.decision().proposedFix()).isNull();
        assertThat(TriageResult.from(ctx).status()).isEqualTo("INSUFFICIENT_EVIDENCE");

        // The override is visible in the glass box, with structured before/after.
        TraceEvent guard = ctx.trace().events().stream()
                .filter(e -> e.type() == TraceEventType.GUARD_OVERRIDE).findFirst().orElseThrow();
        assertThat(guard.payload())
                .containsEntry("from", "RECOMMEND_REMEDIATION")
                .containsEntry("to", "INSUFFICIENT_EVIDENCE")
                .containsEntry("requiredMinimum", 2);
        // And the run terminates as an escalation, with no FIX_DRAFTED anywhere.
        List<TraceEvent> events = ctx.trace().events();
        assertThat(events.get(events.size() - 1).type()).isEqualTo(TraceEventType.ESCALATION);
        assertThat(events).extracting(TraceEvent::type).doesNotContain(TraceEventType.FIX_DRAFTED);
    }

    @Test
    void guardDoesNotCountNoDataEvidenceAsSubstantive() {
        // Two cited ids, but one is a "no logs found" placeholder — only one
        // SUBSTANTIVE item remains, so the guard still overrides.
        PipelineTestSupport.ScriptedModel model = new PipelineTestSupport.ScriptedModel(List.of(
                """
                {"evidence":[{"type":"symptom","statement":"NPE reported at CartService.applyPromo(CartService.java:88)"}]}
                """,
                """
                {"evidence":[{"type":"log","statement":"No logs found for service 'checkout-service' in the requested window"}]}
                """,
                KNOWLEDGE_REPLY,
                """
                {"hypotheses":[{"statement":"Promo code dereferences a null cart","confidence":0.8,"supportingEvidenceIds":["E1","E2"],"supportingCitationIds":[]}]}
                """,
                CRITIC_SUPPORTED_REPLY,
                """
                {"decision":"RECOMMEND_REMEDIATION","selectedHypothesisId":"H1",
                 "rationale":"Uncontradicted.","proposedFix":"Null guard the cart.","postmortem":"pm"}
                """));
        IncidentContext ctx = PipelineTestSupport.orchestrator(model)
                .run(new IncidentRequest("checkout-service", "NPE at CartService.applyPromo"));

        assertThat(ctx.decision().type()).isEqualTo(DecisionType.INSUFFICIENT_EVIDENCE);
        TraceEvent guard = ctx.trace().events().stream()
                .filter(e -> e.type() == TraceEventType.GUARD_OVERRIDE).findFirst().orElseThrow();
        assertThat(guard.payload()).containsEntry("substantiveEvidenceIds", List.of("E1"));
    }

    @Test
    void symptomGuardOverridesWinnerThatIgnoresTheReportedSymptom() {
        // EVAL-10's failure mode: the report says OOM, the observability data shows
        // an NPE. The winning theory explains the DATA (cites only observability
        // evidence E2/E3) and ignores the REPORT (E1). Critic and Judge bless it
        // anyway — the deterministic symptom guard must override to ESCALATE.
        PipelineTestSupport.ScriptedModel model = new PipelineTestSupport.ScriptedModel(List.of(
                """
                {"evidence":[{"type":"symptom","statement":"OutOfMemoryError reported in ReportGenerator.buildDailyReport; host rebooted twice"}]}
                """,
                """
                {"evidence":[
                  {"type":"log","statement":"Logs show repeated NullPointerException at OrderService.java:42"},
                  {"type":"timeline","statement":"error_rate spiked at 09:14, right after the 09:13 deploy"}]}
                """,
                KNOWLEDGE_REPLY,
                """
                {"hypotheses":[{"statement":"Null loyaltyTier from legacy import causes the failures","confidence":0.85,"supportingEvidenceIds":["E2","E3"],"supportingCitationIds":["C1"]}]}
                """,
                CRITIC_SUPPORTED_REPLY,
                """
                {"decision":"RECOMMEND_REMEDIATION","selectedHypothesisId":"H1",
                 "rationale":"Explains the logs.","proposedFix":"Null guard.","postmortem":"pm"}
                """));
        IncidentContext ctx = PipelineTestSupport.orchestrator(model)
                .run(new IncidentRequest("order-service", "java.lang.OutOfMemoryError: Java heap space"));

        // Overridden: no fix survives, status is the escalation.
        assertThat(ctx.decision().type()).isEqualTo(DecisionType.ESCALATE_TO_HUMAN);
        assertThat(ctx.decision().proposedFix()).isNull();
        assertThat(TriageResult.from(ctx).status()).isEqualTo("ESCALATED_TO_HUMAN");

        // The override is in the glass box with the structured payload spec'd:
        // selected id, the cited sources, and the missing requirement.
        TraceEvent guard = ctx.trace().events().stream()
                .filter(e -> e.type() == TraceEventType.GUARD_OVERRIDE).findFirst().orElseThrow();
        assertThat(guard.payload())
                .containsEntry("selectedHypothesisId", "H1")
                .containsEntry("citedEvidenceSources",
                        Map.of("E2", "observability", "E3", "observability"));
        assertThat(guard.payload().get("missingRequirement").toString()).contains("incident_report");

        List<TraceEvent> events = ctx.trace().events();
        assertThat(events.get(events.size() - 1).type()).isEqualTo(TraceEventType.ESCALATION);
        assertThat(events).extracting(TraceEvent::type).doesNotContain(TraceEventType.FIX_DRAFTED);
    }

    @Test
    void symptomGuardStaysSilentWhenWinnerCitesReportEvidence() {
        // Direction 2: the legitimate winner cites E1 (incident_report) + E2
        // (observability) — the guard must not fire and the gate is reached.
        IncidentContext ctx = runWithJudge("""
                {"decision":"RECOMMEND_REMEDIATION","selectedHypothesisId":"H1",
                 "rationale":"H1 SUPPORTED.","proposedFix":"Null guard.","postmortem":"Cites runbook-null-pointer.md."}
                """);

        assertThat(ctx.decision().type()).isEqualTo(DecisionType.RECOMMEND_REMEDIATION);
        assertThat(ctx.trace().events()).extracting(TraceEvent::type)
                .doesNotContain(TraceEventType.GUARD_OVERRIDE);
        assertThat(TriageResult.from(ctx).status()).isEqualTo("AWAITING_APPROVAL");
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

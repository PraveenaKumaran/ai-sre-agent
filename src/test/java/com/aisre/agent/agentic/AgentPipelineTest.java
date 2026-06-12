package com.aisre.agent.agentic;

import com.aisre.agent.model.IncidentRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step 3a: the five agents wired through the deterministic orchestrator, driven by
 * a scripted model (one reply per agent, no network). Proves the happy path:
 * evidence -> citations -> hypotheses (with provenance) -> structured critiques ->
 * decision -> approval gate, with trace events at every handoff.
 */
class AgentPipelineTest {

    // TriageAgent now extracts per channel: one reply for the incident report,
    // one for observability. Code assigns ids sequentially: E1 then E2, E3.
    private static final String TRIAGE_REPORT_REPLY = """
            {"evidence":[
              {"type":"symptom","statement":"NullPointerException thrown at OrderService.java:42 when applying loyalty discount"}]}
            """;
    private static final String TRIAGE_OBS_REPLY = """
            {"evidence":[
              {"type":"metric","statement":"error_rate rose from 0.3% to 19% at 09:14"},
              {"type":"timeline","statement":"Deploy v2.4.0 with legacy customer import went out at 09:13, one minute before the spike"}]}
            """;

    private static final String KNOWLEDGE_REPLY = """
            {"query":"NullPointerException null loyaltyTier legacy import order-service"}
            """;

    private static final String ROOTCAUSE_REPLY = """
            {"hypotheses":[
              {"statement":"Legacy-imported customers have a null loyaltyTier and the discount code dereferences it","confidence":0.8,"supportingEvidenceIds":["E1","E3"],"supportingCitationIds":["C1","C2"]},
              {"statement":"A database outage is causing customer profile loads to fail","confidence":0.4,"supportingEvidenceIds":["E2"],"supportingCitationIds":[]}]}
            """;

    private static final String CRITIC_REPLY = """
            {"critiques":[
              {"hypothesisId":"H1","status":"SUPPORTED","reasons":["E1 shows the NPE at the discount call site","C2 documents the identical prior incident","E3 ties onset to the legacy-import deploy"]},
              {"hypothesisId":"H2","status":"REJECTED","reasons":["E1 shows a code-level NPE, not connection failures","No citation supports a database outage"]}]}
            """;

    private static final String JUDGE_REPLY = """
            {"decision":"RECOMMEND_REMEDIATION","selectedHypothesisId":"H1",
             "rationale":"H1 is SUPPORTED by the Critic, explains E1-E3, and is backed by C1/C2; H2 was REJECTED on E1.",
             "proposedFix":"Default missing loyaltyTier to STANDARD and add a null guard in applyLoyaltyDiscount.",
             "postmortem":"NPE caused by null loyaltyTier from legacy import; matches runbook-null-pointer.md and postmortem-2025-11-order-service-npe.md."}
            """;

    @Test
    void fullPipelineProducesGroundedDecisionWithProvenanceAndTrace() {
        PipelineTestSupport.ScriptedModel model = new PipelineTestSupport.ScriptedModel(
                List.of(TRIAGE_REPORT_REPLY, TRIAGE_OBS_REPLY, KNOWLEDGE_REPLY,
                        ROOTCAUSE_REPLY, CRITIC_REPLY, JUDGE_REPLY));

        IncidentContext ctx = PipelineTestSupport.orchestrator(model).run(new IncidentRequest(
                "order-service",
                "java.lang.NullPointerException at OrderService.applyLoyaltyDiscount(OrderService.java:42)"));

        // One model call per agent, except TriageAgent's two per-channel calls.
        assertThat(model.calls).isEqualTo(6);

        // TriageAgent: structured evidence with code-assigned ids AND code-assigned
        // provenance (report vs observability — never model-inferred).
        assertThat(ctx.evidence()).hasSize(3);
        assertThat(ctx.findEvidence("E1").orElseThrow().source())
                .isEqualTo(Evidence.SOURCE_INCIDENT_REPORT);
        assertThat(ctx.findEvidence("E3").orElseThrow().source())
                .isEqualTo(Evidence.SOURCE_OBSERVABILITY);

        // KnowledgeAgent (IQ off -> bundled fallback docs): citations C1/C2 with real names.
        assertThat(ctx.citations()).extracting(Citation::id).containsExactly("C1", "C2");
        assertThat(ctx.citations()).extracting(Citation::sourceName)
                .containsExactly("runbook-null-pointer.md", "postmortem-2025-11-order-service-npe.md");

        // RootCauseAgent: competing hypotheses, ids assigned by the context, provenance kept.
        assertThat(ctx.hypotheses()).extracting(Hypothesis::id).containsExactly("H1", "H2");
        assertThat(ctx.findHypothesis("H1").orElseThrow().supportingEvidenceIds())
                .containsExactly("E1", "E3");

        // CriticAgent: structured verdicts.
        assertThat(ctx.critiques()).hasSize(2);
        assertThat(ctx.critiques().get(0).status()).isEqualTo(CritiqueStatus.SUPPORTED);
        assertThat(ctx.critiques().get(1).status()).isEqualTo(CritiqueStatus.REJECTED);
        assertThat(ctx.hasSupportedHypothesis()).isTrue();

        // JudgeAgent: recommendation with fix + postmortem, selected hypothesis resolves.
        assertThat(ctx.decision().type()).isEqualTo(DecisionType.RECOMMEND_REMEDIATION);
        assertThat(ctx.decision().selectedHypothesisId()).isEqualTo("H1");
        assertThat(ctx.decision().proposedFix()).contains("null guard");
        assertThat(ctx.decision().postmortem()).contains("runbook-null-pointer.md");

        // The glass-box trace: every handoff and step, in deterministic order,
        // ending at the approval gate (the only terminal for a recommendation).
        assertThat(ctx.trace().events()).extracting(TraceEvent::type).containsExactly(
                TraceEventType.INCIDENT_RECEIVED,
                TraceEventType.REDACTION,
                TraceEventType.AGENT_HANDOFF,      // -> TriageAgent
                TraceEventType.EVIDENCE_EXTRACTED,
                TraceEventType.AGENT_HANDOFF,      // -> KnowledgeAgent
                TraceEventType.IQ_QUERY,
                TraceEventType.CITATIONS_RETRIEVED,
                TraceEventType.AGENT_HANDOFF,      // -> RootCauseAgent
                TraceEventType.HYPOTHESES_PROPOSED,
                TraceEventType.AGENT_HANDOFF,      // -> CriticAgent
                TraceEventType.CRITIQUE,
                TraceEventType.AGENT_HANDOFF,      // -> JudgeAgent
                TraceEventType.DECISION,
                TraceEventType.FIX_DRAFTED,
                TraceEventType.APPROVAL_GATE);
    }

    @Test
    void malformedJudgeReplyEscalatesInsteadOfCrashing() {
        PipelineTestSupport.ScriptedModel model = new PipelineTestSupport.ScriptedModel(
                List.of(TRIAGE_REPORT_REPLY, TRIAGE_OBS_REPLY, KNOWLEDGE_REPLY, ROOTCAUSE_REPLY, CRITIC_REPLY,
                        "I think we should probably restart something?")); // no JSON

        IncidentContext ctx = PipelineTestSupport.orchestrator(model).run(
                new IncidentRequest("order-service", "boom"));

        // Fail-safe: unparseable judge -> ESCALATE_TO_HUMAN, no fix, ESCALATION terminal event.
        assertThat(ctx.decision().type()).isEqualTo(DecisionType.ESCALATE_TO_HUMAN);
        assertThat(ctx.decision().proposedFix()).isNull();
        assertThat(ctx.trace().events().get(ctx.trace().events().size() - 1).type())
                .isEqualTo(TraceEventType.ESCALATION);
    }
}

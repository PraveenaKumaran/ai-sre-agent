package com.aisre.agent.agentic;

import com.aisre.agent.model.IncidentRequest;
import com.aisre.agent.safety.SecretRedactor;
import com.aisre.agent.tools.GetLogsTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The leak guard: runs an incident whose input contains planted secrets (one in
 * the submitted stack trace, one in the canned logs) through the full pipeline,
 * then asserts three invariants over EVERYTHING that leaves the system:
 *
 *  (i)  the planted secret values appear NOWHERE in the serialized context or trace;
 *  (ii) no trace payload value contains the raw incident input verbatim
 *       (trace data minimization — payloads hold ids/counters/redacted statements);
 *  (iii) running the SecretRedactor's own patterns over every serialized payload
 *        value yields ZERO matches — "payloads are redacted" is a tested invariant,
 *        not a comment.
 */
class LeakGuardTest {

    private static final String PLANTED_IN_STACKTRACE = "TOPSECRET_VALUE_12345";

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void plantedSecretsNeverLeaveTheBoundary() throws Exception {
        // Incident input carries a secret; the canned order-service log carries the
        // planted apiKey=EXAMPLE_FAKE_KEY_DO_NOT_USE line.
        String rawStackTrace = "java.lang.NullPointerException at OrderService.java:42\n"
                + "context: apiKey=" + PLANTED_IN_STACKTRACE + " Bearer abc.def.ghi";
        String rawLogs = new GetLogsTool().execute(Map.of()); // exactly what the orchestrator gathers

        PipelineTestSupport.ScriptedModel model = new PipelineTestSupport.ScriptedModel(List.of(
                """
                {"evidence":[{"id":"E1","type":"symptom","statement":"NPE at OrderService.java:42","source":"stack_trace"}]}
                """,
                """
                {"query":"NullPointerException order-service"}
                """,
                """
                {"hypotheses":[{"statement":"Null loyaltyTier from legacy import","confidence":0.7,"supportingEvidenceIds":["E1"],"supportingCitationIds":["C1"]}]}
                """,
                """
                {"critiques":[{"hypothesisId":"H1","status":"SUPPORTED","reasons":["E1 confirms the NPE; C1 documents the pattern"]}]}
                """,
                """
                {"decision":"RECOMMEND_REMEDIATION","selectedHypothesisId":"H1",
                 "rationale":"H1 SUPPORTED per E1 and C1.","proposedFix":"Add null guard.","postmortem":"Cites runbook-null-pointer.md."}
                """));

        IncidentContext ctx = PipelineTestSupport.orchestrator(model)
                .run(new IncidentRequest("order-service", rawStackTrace));

        // Serialize everything a caller (or trace reader) could ever see.
        String serializedTrace = mapper.writeValueAsString(ctx.trace().events());
        String serializedContext = mapper.writeValueAsString(Map.of(
                "stackTrace", ctx.stackTrace(),
                "logs", ctx.redactedLogs(),
                "metrics", ctx.redactedMetrics(),
                "evidence", ctx.evidence(),
                "citations", ctx.citations(),
                "hypotheses", ctx.hypotheses(),
                "critiques", ctx.critiques(),
                "decision", ctx.decision()));
        String everything = serializedTrace + serializedContext;

        // (i) The planted secret values are gone — from the trace AND the context.
        assertThat(everything)
                .doesNotContain(PLANTED_IN_STACKTRACE)
                .doesNotContain("EXAMPLE_FAKE_KEY_DO_NOT_USE")
                .doesNotContain("abc.def.ghi");

        // (ii) Trace minimization: no payload value carries the raw input verbatim.
        // We probe with distinctive verbatim markers: the first raw log line and the
        // raw stack trace text (payloads may hold ids/counters/REDACTED statements only).
        String firstRawLogLine = rawLogs.lines().findFirst().orElseThrow();
        for (TraceEvent event : ctx.trace().events()) {
            for (Map.Entry<String, Object> entry : event.payload().entrySet()) {
                String value = mapper.writeValueAsString(entry.getValue());
                assertThat(value)
                        .as("payload %s of %s must not contain raw incident input", entry.getKey(), event.type())
                        .doesNotContain(firstRawLogLine)
                        .doesNotContain(rawStackTrace);
            }
        }

        // (iii) The redactor's own patterns find NOTHING in any serialized payload value.
        for (TraceEvent event : ctx.trace().events()) {
            for (Map.Entry<String, Object> entry : event.payload().entrySet()) {
                String value = mapper.writeValueAsString(entry.getValue());
                for (Pattern p : SecretRedactor.patterns()) {
                    assertThat(p.matcher(value).find())
                            .as("pattern %s matched payload %s of event %s: %s",
                                    p, entry.getKey(), event.type(), value)
                            .isFalse();
                }
            }
        }
    }
}

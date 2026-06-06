package com.aisre.agent;

import com.aisre.agent.ai.FoundryIqClient;
import com.aisre.agent.ai.ModelClient;
import com.aisre.agent.ai.ModelTurn;
import com.aisre.agent.ai.ToolCall;
import com.aisre.agent.config.AgentProperties;
import com.aisre.agent.config.FoundryProperties;
import com.aisre.agent.model.IncidentRequest;
import com.aisre.agent.model.TriageResult;
import com.aisre.agent.tools.DraftFixTool;
import com.aisre.agent.tools.GetLogsTool;
import com.aisre.agent.tools.GetMetricsTool;
import com.aisre.agent.tools.ReadCodeTool;
import com.aisre.agent.tools.SearchKnowledgeTool;
import com.aisre.agent.tools.Tool;
import com.aisre.agent.tools.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 tests for the REAL model-driven loop — with NO network and NO API key.
 *
 * The trick: we build the loop by hand and hand it a *fake* ModelClient whose
 * replies we script. That lets us prove the orchestrator's mechanics:
 *   - it runs the tools the model asks for, then concludes;
 *   - citations retrieved by search_knowledge flow into the result;
 *   - the hard iteration cap stops a model that never concludes.
 *
 * (The "must ground before concluding" and "discard a wrong hypothesis" behaviours
 * are driven by the SYSTEM PROMPT and the real model; here we verify the loop
 * faithfully executes and traces whatever the model decides.)
 */
class ModelDrivenLoopTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** Build a ReasoningLoop wired to the given fake model, with IQ disabled (canned snippets). */
    private ReasoningLoop loopWith(ModelClient model) {
        // IQ disabled -> search_knowledge returns the canned, cited sample snippets.
        FoundryProperties.Iq iq = new FoundryProperties.Iq(false, "", "", "", "", "", 4);
        FoundryProperties props = new FoundryProperties(true, "", "", "", "", "", "api-key", "", iq);
        FoundryIqClient iqClient = new FoundryIqClient(props, mapper);

        List<Tool> tools = List.of(
                new GetLogsTool(),
                new GetMetricsTool(),
                new SearchKnowledgeTool(iqClient),
                new ReadCodeTool(),
                new DraftFixTool());
        ToolRegistry registry = new ToolRegistry(tools);

        AgentProperties agent = new AgentProperties(6, 0.6);
        return new ReasoningLoop(registry, model, agent, mapper);
    }

    @Test
    void runsToolsThenConcludesAndCitationsFlowThrough() {
        // Script: model gathers logs, grounds via search_knowledge, then concludes with JSON.
        String conclusion = """
                {
                  "classification": "NullPointerException / null-handling bug",
                  "rootCauseHypothesis": "Legacy-imported customers have a null loyaltyTier.",
                  "citedSources": ["RB-NPE-001", "PM-2025-11-ORDER"],
                  "proposedFix": "Null-guard the tier; default to STANDARD.",
                  "postmortem": "Root cause matches RB-NPE-001 and PM-2025-11-ORDER.",
                  "confidence": 0.82
                }
                """;
        ScriptedModel model = new ScriptedModel(List.of(
                ModelTurn.ofToolCalls(List.of(
                        new ToolCall("c1", "get_logs", "{\"service\":\"order-service\",\"time_window\":\"last_15m\"}"))),
                ModelTurn.ofToolCalls(List.of(
                        new ToolCall("c2", "search_knowledge", "{\"query\":\"NullPointerException loyaltyTier\"}"))),
                ModelTurn.ofFinal(conclusion)));

        TriageResult result = loopWith(model).triage(
                new IncidentRequest("order-service", "java.lang.NullPointerException at OrderService.java:42"));

        // The model was asked exactly 3 times (2 tool turns + 1 conclusion).
        assertThat(model.calls).isEqualTo(3);

        // The conclusion was parsed and mapped onto the result.
        assertThat(result.classification()).containsIgnoringCase("NullPointer");
        assertThat(result.confidence()).isEqualTo(0.82);
        assertThat(result.status()).isEqualTo("AWAITING_APPROVAL");

        // Grounding: citations are present, and the trace recorded the IQ sources.
        assertThat(result.citedSources()).contains("RB-NPE-001", "PM-2025-11-ORDER");
        assertThat(result.reasoningSteps())
                .anyMatch(s -> s.contains("FOUNDRY IQ returned sources"))
                .anyMatch(s -> s.contains("model called search_knowledge"));
    }

    @Test
    void iterationCapStopsARunawayModelAndEscalates() {
        // A model that NEVER concludes — it always asks for another tool call.
        ModelClient runaway = new ModelClient() {
            int calls = 0;
            @Override public boolean isEnabled() { return true; }
            @Override public ModelTurn nextTurn(java.util.List<com.aisre.agent.ai.ChatMessage> m,
                                                java.util.List<com.aisre.agent.ai.ToolSpec> t) {
                calls++;
                return ModelTurn.ofToolCalls(List.of(
                        new ToolCall("c" + calls, "get_logs", "{\"service\":\"order-service\"}")));
            }
        };

        TriageResult result = loopWith(runaway).triage(
                new IncidentRequest("order-service", "boom"));

        // The cap (6) kicked in: escalated, zero confidence, no fix drafted.
        assertThat(result.status()).isEqualTo("ESCALATED_ITERATION_CAP");
        assertThat(result.confidence()).isEqualTo(0.0);
        assertThat(result.proposedFix()).isEmpty();
        assertThat(result.reasoningSteps())
                .anyMatch(s -> s.contains("hit max-iterations cap"));
    }

    @Test
    void malformedFinalAnswerDegradesGracefully() {
        // Model "concludes" but returns prose instead of the required JSON.
        ScriptedModel model = new ScriptedModel(List.of(
                ModelTurn.ofFinal("I think it's probably a null pointer somewhere, not sure.")));

        TriageResult result = loopWith(model).triage(new IncidentRequest("order-service", "boom"));

        // We don't crash: we degrade to a low-confidence, clearly-marked result.
        assertThat(result.classification()).isEqualTo("UNPARSED");
        assertThat(result.confidence()).isEqualTo(0.0);
        assertThat(result.status()).isEqualTo("AWAITING_APPROVAL");
    }

    /** A ModelClient that replays a fixed list of turns and counts how many times it was asked. */
    static class ScriptedModel implements ModelClient {
        private final Deque<ModelTurn> script;
        int calls = 0;

        ScriptedModel(List<ModelTurn> turns) {
            this.script = new ArrayDeque<>(turns);
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public ModelTurn nextTurn(java.util.List<com.aisre.agent.ai.ChatMessage> messages,
                                  java.util.List<com.aisre.agent.ai.ToolSpec> tools) {
            calls++;
            if (script.isEmpty()) {
                throw new IllegalStateException("ScriptedModel ran out of scripted turns");
            }
            return script.poll();
        }
    }
}

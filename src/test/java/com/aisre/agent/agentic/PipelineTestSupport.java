package com.aisre.agent.agentic;

import com.aisre.agent.ai.ChatMessage;
import com.aisre.agent.ai.FoundryIqClient;
import com.aisre.agent.ai.ModelClient;
import com.aisre.agent.ai.ModelTurn;
import com.aisre.agent.ai.ToolSpec;
import com.aisre.agent.config.FoundryProperties;
import com.aisre.agent.safety.SecretRedactor;
import com.aisre.agent.tools.DraftFixTool;
import com.aisre.agent.tools.GetLogsTool;
import com.aisre.agent.tools.GetMetricsTool;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Shared fixture for pipeline tests: hand-wires the orchestrator with a SCRIPTED
 * model (no network, no keys) and IQ disabled (so KnowledgeAgent uses the bundled
 * fallback docs). Mirrors exactly how Spring wires it in production.
 */
final class PipelineTestSupport {

    private PipelineTestSupport() { }

    static AgentOrchestrator orchestrator(ModelClient model) {
        ObjectMapper mapper = new ObjectMapper();
        FoundryProperties.Iq iqOff = new FoundryProperties.Iq(false, "", "", "", "", "", 4);
        FoundryProperties props = new FoundryProperties(
                true, "", "", "", "", "", "api-key", "", "low", 4000, iqOff);
        FoundryIqClient iq = new FoundryIqClient(props, mapper);
        SecretRedactor redactor = new SecretRedactor();

        return new AgentOrchestrator(
                new TriageAgent(model, mapper),
                new KnowledgeAgent(model, iq, mapper),
                new RootCauseAgent(model, mapper),
                new CriticAgent(model, mapper),
                new JudgeAgent(model, mapper),
                new GetLogsTool(),
                new GetMetricsTool(),
                new DraftFixTool(),
                redactor,
                model);
    }

    /**
     * Replays a fixed list of final-answer turns, one per agent model call, in
     * order. Also records the user message of every call, so tests can assert what
     * an agent was actually shown (e.g. the rejection feedback on a retry).
     */
    static class ScriptedModel implements ModelClient {
        private final Deque<String> replies;
        final java.util.List<String> userMessages = new java.util.ArrayList<>();
        int calls;

        ScriptedModel(List<String> finalContents) {
            this.replies = new ArrayDeque<>(finalContents);
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public ModelTurn nextTurn(List<ChatMessage> messages, List<ToolSpec> tools) {
            calls++;
            messages.stream()
                    .filter(m -> "user".equals(m.role()))
                    .reduce((a, b) -> b) // last user message of this call
                    .ifPresent(m -> userMessages.add(m.content()));
            if (replies.isEmpty()) {
                throw new IllegalStateException("ScriptedModel ran out of replies at call " + calls);
            }
            return ModelTurn.ofFinal(replies.poll());
        }
    }
}

package com.aisre.agent.agentic;

import com.aisre.agent.ai.ChatMessage;
import com.aisre.agent.ai.ModelClient;
import com.aisre.agent.ai.ModelTurn;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 1: turns the raw incident signals (stack trace, logs, metrics — all
 * redacted at the boundary before reaching this agent) into structured
 * {@link Evidence} items with stable ids (E1, E2, ...).
 *
 * Observation only — the prompt forbids diagnosis. The triage step is also a
 * defense-in-depth MITIGATION against prompt injection from untrusted log input
 * (downstream agents see short structured statements, not raw logs). It is NOT a
 * secret-protection control: secrets are guaranteed gone by the boundary redaction.
 */
@Service
public class TriageAgent implements Agent<TriageView> {

    private final ModelClient model;
    private final ObjectMapper json;
    private final String systemPrompt;

    public TriageAgent(ModelClient model, ObjectMapper json) {
        this.model = model;
        this.json = json;
        this.systemPrompt = PromptLoader.load("triage-agent.txt");
    }

    @Override
    public String name() {
        return "TriageAgent";
    }

    @Override
    public void execute(TriageView ctx) {
        String input = """
                Service: %s

                Stack trace / error:
                %s

                Recent logs:
                %s

                Metrics:
                %s
                """.formatted(ctx.service(), ctx.stackTrace(), ctx.redactedLogs(), ctx.redactedMetrics());

        ModelTurn turn = model.nextTurn(
                List.of(ChatMessage.system(systemPrompt), ChatMessage.user(input)),
                List.of()); // no tools: this agent only structures what it was given

        List<Evidence> evidence = parse(turn.finalContent());
        ctx.addEvidence(evidence);

        // Trace: evidence statements are derived from redacted input, so they are
        // allowed in the payload (the minimization whitelist). Raw logs are NOT.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("count", evidence.size());
        payload.put("evidence", evidence);
        ctx.trace().add(TraceEventType.EVIDENCE_EXTRACTED, name(),
                "Extracted " + evidence.size() + " evidence items", payload);
    }

    /**
     * Parse {"evidence":[{id,type,statement,source}...]}. Graceful degrade: a
     * malformed reply yields an EMPTY list (the pipeline then naturally heads
     * toward escalation for lack of evidence) — never an exception.
     */
    private List<Evidence> parse(String content) {
        List<Evidence> out = new ArrayList<>();
        String jsonPart = ModelJson.extractObject(content);
        if (jsonPart == null) {
            return out;
        }
        try {
            JsonNode items = json.readTree(jsonPart).path("evidence");
            int i = 0;
            for (JsonNode n : items) {
                String statement = n.path("statement").asText("");
                if (statement.isBlank()) {
                    continue; // an evidence item with no fact is useless
                }
                i++;
                out.add(new Evidence(
                        n.path("id").asText("E" + i),
                        n.path("type").asText("log"),
                        statement,
                        n.path("source").asText("incident")));
            }
        } catch (Exception malformed) {
            return List.of(); // degrade to "no evidence extracted"
        }
        return out;
    }
}

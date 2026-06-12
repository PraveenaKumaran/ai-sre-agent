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
 * Agent 1: turns the raw incident signals (all redacted at the boundary) into
 * structured {@link Evidence} items with stable ids (E1, E2, ...).
 *
 * STRUCTURAL PROVENANCE: extraction runs as TWO model calls — one over the
 * submitted incident report, one over the tool-gathered observability data
 * (logs + metrics). The CODE stamps each item's {@code source} from the call
 * that produced it ({@code incident_report} vs {@code observability}) and
 * assigns the E-ids; neither is ever inferred by the model. Downstream, the
 * reported-symptom guard and the Critic's coverage rules key on this field.
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
        List<Evidence> all = new ArrayList<>();

        // Call 1: the incident report (the request payload) -> source stamped by code.
        String reportInput = """
                INCIDENT REPORT (as submitted by the reporter):
                Service: %s

                %s
                """.formatted(ctx.service(), ctx.stackTrace());
        all.addAll(extract(reportInput, Evidence.SOURCE_INCIDENT_REPORT, all.size()));

        // Call 2: tool-gathered observability data -> source stamped by code.
        String observabilityInput = """
                OBSERVABILITY DATA (gathered from monitoring tools):
                Service: %s

                Recent logs:
                %s

                Metrics:
                %s
                """.formatted(ctx.service(), ctx.redactedLogs(), ctx.redactedMetrics());
        all.addAll(extract(observabilityInput, Evidence.SOURCE_OBSERVABILITY, all.size()));

        ctx.addEvidence(all);

        // Trace: statements derive from redacted input, so they are payload-safe.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("count", all.size());
        payload.put("reportDerivedCount", all.stream().filter(Evidence::isReportDerived).count());
        payload.put("evidence", all);
        ctx.trace().add(TraceEventType.EVIDENCE_EXTRACTED, name(),
                "Extracted " + all.size() + " evidence items ("
                        + all.stream().filter(Evidence::isReportDerived).count() + " from the incident report)",
                payload);
    }

    /** One extraction call over one input channel; ids and source are assigned HERE. */
    private List<Evidence> extract(String input, String source, int idOffset) {
        ModelTurn turn = model.nextTurn(
                List.of(ChatMessage.system(systemPrompt), ChatMessage.user(input)),
                List.of()); // no tools: this agent only structures what it was given
        return parse(turn.finalContent(), source, idOffset);
    }

    /**
     * Parse {"evidence":[{type,statement}...]}. The model's id/source fields (if
     * any) are IGNORED — ids continue sequentially across both calls and source is
     * the code-known provenance. Graceful degrade: a malformed reply yields an
     * EMPTY list for that channel — never an exception.
     */
    private List<Evidence> parse(String content, String source, int idOffset) {
        List<Evidence> out = new ArrayList<>();
        String jsonPart = ModelJson.extractObject(content);
        if (jsonPart == null) {
            return out;
        }
        try {
            for (JsonNode n : json.readTree(jsonPart).path("evidence")) {
                String statement = n.path("statement").asText("");
                if (statement.isBlank()) {
                    continue; // an evidence item with no fact is useless
                }
                out.add(new Evidence(
                        "E" + (idOffset + out.size() + 1),
                        n.path("type").asText("log"),
                        statement,
                        source));
            }
        } catch (Exception malformed) {
            return List.of(); // degrade to "no evidence extracted" for this channel
        }
        return out;
    }
}

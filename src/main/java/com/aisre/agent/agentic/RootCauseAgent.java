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
 * Agent 3: proposes 2-3 COMPETING root-cause hypotheses, each carrying its
 * provenance — the evidence ids (E#) and citation ids (C#) that back it.
 *
 * Self-reflection retry: when the orchestrator re-invokes this agent after the
 * Critic killed a round, the killed hypotheses AND the Critic's reasons are
 * rendered into the prompt ("PREVIOUSLY REJECTED HYPOTHESES"). The prompt forbids
 * regenerating the same theory unless new evidence justifies it — a real
 * re-think, not a blind retry.
 *
 * Hypothesis ids are assigned HERE (via the context's monotonic counter), not
 * trusted from the model, so H-ids stay unique across retry rounds and the trace
 * never has two different theories sharing an id.
 */
@Service
public class RootCauseAgent implements Agent<RootCauseView> {

    private final ModelClient model;
    private final ObjectMapper json;
    private final String systemPrompt;

    public RootCauseAgent(ModelClient model, ObjectMapper json) {
        this.model = model;
        this.json = json;
        this.systemPrompt = PromptLoader.load("rootcause-agent.txt");
    }

    @Override
    public String name() {
        return "RootCauseAgent";
    }

    @Override
    public void execute(RootCauseView ctx) {
        ModelTurn turn = model.nextTurn(
                List.of(ChatMessage.system(systemPrompt), ChatMessage.user(renderInput(ctx))),
                List.of());

        List<Hypothesis> hypotheses = parse(ctx, turn.finalContent());
        ctx.setHypotheses(hypotheses); // replaces the previous round (history is in the trace)

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("count", hypotheses.size());
        payload.put("hypotheses", hypotheses);
        payload.put("afterRetry", ctx.retryCount());
        ctx.trace().add(TraceEventType.HYPOTHESES_PROPOSED, name(),
                "Proposed " + hypotheses.size() + " competing hypotheses"
                        + (ctx.lastRejections().isEmpty() ? "" : " (re-think after rejection)"),
                payload);
    }

    /** Render evidence + citations (+ the killed hypotheses with reasons, on retry). */
    private String renderInput(RootCauseView ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("Service: ").append(ctx.service()).append("\n\nEVIDENCE:\n");
        for (Evidence e : ctx.evidence()) {
            sb.append(e.id()).append(" (").append(e.type()).append(", ").append(e.source())
              .append("): ").append(e.statement()).append('\n');
        }
        sb.append("\nCITED KNOWLEDGE:\n");
        if (ctx.citations().isEmpty()) {
            sb.append("(none retrieved — do not invent citations)\n");
        }
        for (Citation c : ctx.citations()) {
            sb.append(c.id()).append(" [").append(c.sourceName()).append("]: ")
              .append(c.snippet()).append("\n\n");
        }
        if (!ctx.lastRejections().isEmpty()) {
            sb.append("\nPREVIOUSLY REJECTED HYPOTHESES (do not repeat without new evidence):\n");
            for (DiscardedHypothesis d : ctx.lastRejections()) {
                sb.append("- ").append(d.hypothesis().id()).append(" \"")
                  .append(d.hypothesis().statement()).append("\" — ").append(d.status())
                  .append(": ").append(String.join("; ", d.reasons())).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * Parse {"hypotheses":[{statement,confidence,supportingEvidenceIds,supportingCitationIds}...]}.
     * Ids are reassigned from the context counter. Graceful degrade: malformed reply
     * yields an empty list, which the orchestrator's retry/Judge logic handles safely.
     */
    private List<Hypothesis> parse(RootCauseView ctx, String content) {
        List<Hypothesis> out = new ArrayList<>();
        String jsonPart = ModelJson.extractObject(content);
        if (jsonPart == null) {
            return out;
        }
        try {
            for (JsonNode n : json.readTree(jsonPart).path("hypotheses")) {
                String statement = n.path("statement").asText("");
                if (statement.isBlank()) {
                    continue;
                }
                out.add(new Hypothesis(
                        ctx.nextHypothesisId(),
                        statement,
                        n.path("confidence").asDouble(0.0),
                        toStringList(n.path("supportingEvidenceIds")),
                        toStringList(n.path("supportingCitationIds"))));
            }
        } catch (Exception malformed) {
            return List.of();
        }
        return out;
    }

    private List<String> toStringList(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array.isArray()) {
            array.forEach(n -> out.add(n.asText()));
        }
        return out;
    }
}

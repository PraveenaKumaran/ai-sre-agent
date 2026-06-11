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
 * Agent 4: the Critic-Verifier. An adversarial "Principal SRE" that tries to
 * DISPROVE each hypothesis using ONLY the evidence (E#) and citations (C#).
 *
 * Output is STRUCTURED, one verdict per hypothesis:
 *   { "hypothesisId": "H2", "status": "SUPPORTED|WEAK|REJECTED", "reasons": [...] }
 * with every reason expected to point at specific E/C ids — no verdicts on vibes.
 * This structure flows back to the RootCauseAgent on retry, into the
 * HYPOTHESIS_DISCARDED trace events, and into the evaluation runner's counts.
 *
 * Graceful degrade (fail-safe): a malformed or missing verdict becomes WEAK —
 * it neither blesses a hypothesis as SUPPORTED nor kills it as REJECTED, and a
 * WEAK-only round still triggers the orchestrator's retry. Never crashes.
 */
@Service
public class CriticAgent implements Agent<CriticView> {

    private final ModelClient model;
    private final ObjectMapper json;
    private final String systemPrompt;

    public CriticAgent(ModelClient model, ObjectMapper json) {
        this.model = model;
        this.json = json;
        this.systemPrompt = PromptLoader.load("critic-agent.txt");
    }

    @Override
    public String name() {
        return "CriticAgent";
    }

    @Override
    public void execute(CriticView ctx) {
        ModelTurn turn = model.nextTurn(
                List.of(ChatMessage.system(systemPrompt), ChatMessage.user(renderInput(ctx))),
                List.of());

        List<Critique> critiques = parse(ctx, turn.finalContent());
        ctx.setCritiques(critiques);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("critiques", critiques);
        payload.put("supportedCount",
                critiques.stream().filter(c -> c.status() == CritiqueStatus.SUPPORTED).count());
        ctx.trace().add(TraceEventType.CRITIQUE, name(),
                "Critiqued " + critiques.size() + " hypotheses: " + summarize(critiques), payload);
    }

    private String renderInput(CriticView ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("EVIDENCE:\n");
        for (Evidence e : ctx.evidence()) {
            sb.append(e.id()).append(" (").append(e.type()).append("): ").append(e.statement()).append('\n');
        }
        sb.append("\nCITED KNOWLEDGE:\n");
        if (ctx.citations().isEmpty()) {
            sb.append("(none retrieved)\n");
        }
        for (Citation c : ctx.citations()) {
            sb.append(c.id()).append(" [").append(c.sourceName()).append("]: ")
              .append(c.snippet()).append("\n\n");
        }
        sb.append("\nHYPOTHESES TO ATTACK:\n");
        for (Hypothesis h : ctx.hypotheses()) {
            sb.append(h.id()).append(" \"").append(h.statement()).append("\"")
              .append(" — claims support from evidence ").append(h.supportingEvidenceIds())
              .append(" and citations ").append(h.supportingCitationIds())
              .append(" (confidence ").append(h.confidence()).append(")\n");
        }
        return sb.toString();
    }

    /**
     * Parse the structured verdicts. Two degrade paths, both fail-safe to WEAK:
     * - whole reply malformed -> every hypothesis gets WEAK;
     * - a single hypothesis missing its verdict -> that one gets WEAK.
     */
    private List<Critique> parse(CriticView ctx, String content) {
        Map<String, Critique> byHypothesis = new LinkedHashMap<>();
        String jsonPart = ModelJson.extractObject(content);
        if (jsonPart != null) {
            try {
                for (JsonNode n : json.readTree(jsonPart).path("critiques")) {
                    String hId = n.path("hypothesisId").asText("");
                    if (hId.isBlank() || ctx.findHypothesis(hId).isEmpty()) {
                        continue; // verdict about a hypothesis we don't have — ignore
                    }
                    List<String> reasons = new ArrayList<>();
                    n.path("reasons").forEach(r -> reasons.add(r.asText()));
                    byHypothesis.put(hId, new Critique(
                            hId, CritiqueStatus.fromString(n.path("status").asText()), reasons));
                }
            } catch (Exception malformed) {
                byHypothesis.clear(); // fall through: everything degrades to WEAK below
            }
        }
        // Ensure exactly one verdict per current hypothesis.
        List<Critique> out = new ArrayList<>();
        for (Hypothesis h : ctx.hypotheses()) {
            out.add(byHypothesis.getOrDefault(h.id(), new Critique(
                    h.id(), CritiqueStatus.WEAK,
                    List.of("Critic returned no parseable verdict for this hypothesis; defaulting to WEAK"))));
        }
        return out;
    }

    private String summarize(List<Critique> critiques) {
        return critiques.stream()
                .map(c -> c.hypothesisId() + "=" + c.status())
                .reduce((a, b) -> a + ", " + b)
                .orElse("none");
    }
}

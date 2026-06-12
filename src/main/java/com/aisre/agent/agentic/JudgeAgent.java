package com.aisre.agent.agentic;

import com.aisre.agent.ai.ChatMessage;
import com.aisre.agent.ai.ModelClient;
import com.aisre.agent.ai.ModelTurn;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 5: the Judge. Selects the outcome using a STRICT priority order —
 * (1) Critic status, (2) evidence coverage, (3) citation support, (4) confidence
 * (tiebreaker only; confidence alone can never justify selection).
 *
 * The Judge also receives the retry count: exhausted retries signal diagnostic
 * difficulty and bias it toward ESCALATE_TO_HUMAN / INSUFFICIENT_EVIDENCE rather
 * than forcing a recommendation from weakly-supported survivors.
 *
 * Safety: the only "acting" outcome is RECOMMEND_REMEDIATION, and even that is a
 * DRAFT that hard-stops at human approval. Graceful degrade: a malformed reply,
 * or a recommendation pointing at a hypothesis that doesn't exist, becomes
 * ESCALATE_TO_HUMAN — when in doubt, hand off to a human.
 */
@Service
public class JudgeAgent implements Agent<JudgeView> {

    private final ModelClient model;
    private final ObjectMapper json;
    private final String systemPrompt;

    public JudgeAgent(ModelClient model, ObjectMapper json) {
        this.model = model;
        this.json = json;
        this.systemPrompt = PromptLoader.load("judge-agent.txt");
    }

    @Override
    public String name() {
        return "JudgeAgent";
    }

    @Override
    public void execute(JudgeView ctx) {
        ModelTurn turn = model.nextTurn(
                List.of(ChatMessage.system(systemPrompt), ChatMessage.user(renderInput(ctx))),
                List.of());

        Decision decision = parse(ctx, turn.finalContent());
        ctx.setDecision(decision);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", decision.type().name());
        payload.put("selectedHypothesisId", decision.selectedHypothesisId());
        payload.put("rationale", decision.rationale());
        payload.put("retryCount", ctx.retryCount());
        ctx.trace().add(TraceEventType.DECISION, name(),
                "Decision: " + decision.type()
                        + (decision.selectedHypothesisId() == null ? "" : " (selected " + decision.selectedHypothesisId() + ")"),
                payload);
    }

    private String renderInput(JudgeView ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("Service: ").append(ctx.service()).append('\n');
        sb.append("Retry count: ").append(ctx.retryCount()).append(" of 2")
          .append(ctx.retryCount() >= 2 ? " (RETRIES EXHAUSTED — diagnosis was hard)" : "").append('\n');

        sb.append("\nEVIDENCE:\n");
        for (Evidence e : ctx.evidence()) {
            sb.append(e.id()).append(" (").append(e.type()).append(", ").append(e.source())
              .append("): ").append(e.statement()).append('\n');
        }
        sb.append("\nCITATIONS:\n");
        if (ctx.citations().isEmpty()) {
            sb.append("(none retrieved)\n");
        }
        for (Citation c : ctx.citations()) {
            sb.append(c.id()).append(" [").append(c.sourceName()).append("]\n");
        }
        sb.append("\nHYPOTHESES AND THE CRITIC'S VERDICTS:\n");
        for (Hypothesis h : ctx.hypotheses()) {
            sb.append(h.id()).append(" \"").append(h.statement()).append("\"")
              .append(" — evidence ").append(h.supportingEvidenceIds())
              .append(", citations ").append(h.supportingCitationIds())
              .append(", confidence ").append(h.confidence()).append('\n');
            ctx.critiques().stream()
                    .filter(c -> c.hypothesisId().equals(h.id()))
                    .findFirst()
                    .ifPresent(c -> sb.append("   CRITIC: ").append(c.status())
                            .append(" — ").append(String.join("; ", c.reasons())).append('\n'));
        }
        return sb.toString();
    }

    /**
     * Parse the decision JSON. Degrades SAFELY: malformed reply, unknown decision
     * value, or a RECOMMEND that names a non-existent hypothesis all become
     * ESCALATE_TO_HUMAN.
     */
    private Decision parse(JudgeView ctx, String content) {
        String jsonPart = ModelJson.extractObject(content);
        if (jsonPart == null) {
            return Decision.escalate(DecisionType.ESCALATE_TO_HUMAN,
                    "Judge returned no parseable decision; escalating to a human.");
        }
        try {
            JsonNode n = json.readTree(jsonPart);
            DecisionType type = DecisionType.fromString(n.path("decision").asText());
            String selectedId = n.path("selectedHypothesisId").asText("");
            String rationale = n.path("rationale").asText("");

            if (type != DecisionType.RECOMMEND_REMEDIATION) {
                return Decision.escalate(type, rationale.isBlank()
                        ? "No hypothesis survived scrutiny." : rationale);
            }
            // Sanity guard: a recommendation must point at a hypothesis that exists.
            if (selectedId.isBlank() || ctx.findHypothesis(selectedId).isEmpty()) {
                return Decision.escalate(DecisionType.ESCALATE_TO_HUMAN,
                        "Judge recommended remediation without a valid selected hypothesis; escalating.");
            }
            return new Decision(type, selectedId, rationale,
                    n.path("proposedFix").asText(""),
                    n.path("postmortem").asText(""));
        } catch (Exception malformed) {
            return Decision.escalate(DecisionType.ESCALATE_TO_HUMAN,
                    "Judge decision was malformed; escalating to a human.");
        }
    }
}

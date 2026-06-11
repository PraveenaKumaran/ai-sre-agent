package com.aisre.agent.agentic;

import com.aisre.agent.ai.ChatMessage;
import com.aisre.agent.ai.FoundryIqClient;
import com.aisre.agent.ai.KnowledgeResult;
import com.aisre.agent.ai.ModelClient;
import com.aisre.agent.ai.ModelTurn;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Agent 2: grounds the incident in institutional knowledge via Foundry IQ.
 *
 * Two-step job: (1) one model call turns the evidence into ONE focused search
 * query; (2) Foundry IQ retrieves cited snippets, which become {@link Citation}s
 * (C1, C2, ...). When IQ is disabled (no credentials), the two bundled sample
 * knowledge docs are returned instead, so the pipeline runs end-to-end offline.
 *
 * Never guesses: if retrieval returns nothing relevant, this agent reports zero
 * citations explicitly — it does not invent sources.
 */
@Service
public class KnowledgeAgent implements Agent<KnowledgeView> {

    private final ModelClient model;
    private final FoundryIqClient iq;
    private final ObjectMapper json;
    private final String systemPrompt;

    public KnowledgeAgent(ModelClient model, FoundryIqClient iq, ObjectMapper json) {
        this.model = model;
        this.iq = iq;
        this.json = json;
        this.systemPrompt = PromptLoader.load("knowledge-agent.txt");
    }

    @Override
    public String name() {
        return "KnowledgeAgent";
    }

    @Override
    public void execute(KnowledgeView ctx) {
        String query = buildQuery(ctx);
        ctx.trace().add(TraceEventType.IQ_QUERY, name(),
                "Searching knowledge base: \"" + query + "\"", Map.of("query", query));

        List<Citation> citations = iq.isEnabled() ? retrieveFromIq(ctx, query) : fallbackCitations(ctx);
        ctx.addCitations(citations);

        // Payload minimization: ids + source names + count only. Snippet text stays
        // in the context (for the agents) but out of the trace payload.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("count", citations.size());
        payload.put("citationIds", citations.stream().map(Citation::id).toList());
        payload.put("sourceNames", citations.stream().map(Citation::sourceName).toList());
        String summary = citations.isEmpty()
                ? "No relevant knowledge found — reporting zero citations (not guessing)"
                : "Retrieved " + citations.size() + " cited snippets";
        ctx.trace().add(TraceEventType.CITATIONS_RETRIEVED, name(), summary, payload);
    }

    /** One model call: evidence -> {"query": "..."}. Malformed reply -> simple joined fallback. */
    private String buildQuery(KnowledgeView ctx) {
        String evidenceText = ctx.evidence().stream()
                .map(e -> e.id() + " (" + e.type() + "): " + e.statement())
                .collect(Collectors.joining("\n"));

        ModelTurn turn = model.nextTurn(
                List.of(ChatMessage.system(systemPrompt),
                        ChatMessage.user("Service: " + ctx.service() + "\nEvidence:\n" + evidenceText)),
                List.of());

        String jsonPart = ModelJson.extractObject(turn.finalContent());
        if (jsonPart != null) {
            try {
                String q = json.readTree(jsonPart).path("query").asText("");
                if (!q.isBlank()) {
                    return q;
                }
            } catch (Exception malformed) {
                // fall through to the deterministic fallback below
            }
        }
        // Degraded but functional: search on the service plus the first symptom.
        String firstSymptom = ctx.evidence().isEmpty() ? "" : ctx.evidence().get(0).statement();
        return (ctx.service() + " " + firstSymptom).strip();
    }

    /** Real grounding: Foundry IQ retrieval, mapped to citations with continuing C-ids. */
    private List<Citation> retrieveFromIq(KnowledgeView ctx, String query) {
        KnowledgeResult result = iq.retrieve(query);
        List<Citation> out = new ArrayList<>();
        int offset = ctx.citations().size(); // C-ids continue across retry rounds
        int i = 0;
        for (KnowledgeResult.Snippet s : result.snippets()) {
            i++;
            out.add(new Citation("C" + (offset + i), s.sourceId(), s.text()));
        }
        return out;
    }

    /** Offline fallback (IQ disabled): the two bundled sample knowledge docs. */
    private List<Citation> fallbackCitations(KnowledgeView ctx) {
        int offset = ctx.citations().size();
        return List.of(
                new Citation("C" + (offset + 1), "runbook-null-pointer.md",
                        readResource("knowledge/runbook-null-pointer.md")),
                new Citation("C" + (offset + 2), "postmortem-2025-11-order-service-npe.md",
                        readResource("knowledge/postmortem-2025-11-order-service-npe.md")));
    }

    private String readResource(String path) {
        try (var in = new ClassPathResource(path).getInputStream()) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Could not read bundled knowledge: " + path, e);
        }
    }
}

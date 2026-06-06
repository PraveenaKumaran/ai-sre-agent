package com.aisre.agent.tools;

import com.aisre.agent.ai.FoundryIqClient;
import com.aisre.agent.ai.KnowledgeResult;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Tool 3: search_knowledge(query) -> cited runbooks / past postmortems.
 *
 * This is THE grounding tool and the mandatory Microsoft Foundry IQ integration.
 *
 * Behaviour now depends on configuration:
 *   - foundry.iq.enabled = true  -> calls Foundry IQ (FoundryIqClient) for REAL
 *     retrieved snippets with their real citations.
 *   - foundry.iq.enabled = false -> returns the two canned sample snippets (still
 *     with [SOURCE: ...] markers) so the baseline works without credentials.
 *
 * Either way the OUTPUT SHAPE is identical: text snippets, each tagged with a
 * [SOURCE: ...] citation. That means the rest of the system (and the model) does
 * not care whether grounding was real or stubbed — only whether it was cited.
 */
@Component
public class SearchKnowledgeTool implements Tool {

    private final FoundryIqClient iqClient;

    public SearchKnowledgeTool(FoundryIqClient iqClient) {
        this.iqClient = iqClient;
    }

    @Override
    public String name() {
        return "search_knowledge";
    }

    @Override
    public String description() {
        return "Search runbooks and past postmortems via Foundry IQ; returns snippets WITH "
                + "source citations. Call this to ground your hypothesis before concluding.";
    }

    @Override
    public String parametersJsonSchema() {
        return """
               {
                 "type": "object",
                 "properties": {
                   "query": { "type": "string", "description": "what to search the knowledge base for" }
                 },
                 "required": ["query"]
               }
               """;
    }

    @Override
    public String execute(Map<String, String> args) {
        String query = args.getOrDefault("query", "");

        // Real grounding path: ask Foundry IQ and return its cited snippets.
        if (iqClient.isEnabled()) {
            KnowledgeResult result = iqClient.retrieve(query);
            if (result.snippets().isEmpty()) {
                return "Foundry IQ returned no relevant sources for query: \"" + query + "\".";
            }
            return result.toToolResultText();
        }

        // Fallback (IQ disabled): canned snippets, still carrying [SOURCE: ...] citations.
        String runbook = Resources.read("knowledge/runbook-null-pointer.md");
        String postmortem = Resources.read("knowledge/postmortem-2025-11-order-service-npe.md");
        return """
               [SOURCE: RB-NPE-001 | knowledge/runbook-null-pointer.md]
               %s

               [SOURCE: PM-2025-11-ORDER | knowledge/postmortem-2025-11-order-service-npe.md]
               %s
               """.formatted(runbook, postmortem);
    }
}

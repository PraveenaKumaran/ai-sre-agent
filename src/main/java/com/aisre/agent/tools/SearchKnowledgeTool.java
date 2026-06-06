package com.aisre.agent.tools;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Tool 3: search_knowledge(query) -> cited runbooks / past postmortems.
 *
 * This is THE grounding tool and the mandatory Microsoft Foundry IQ integration.
 *
 * Phase 1 stub: returns two canned knowledge snippets WITH explicit source ids,
 * so the "cited sources" idea is visible end-to-end before IQ is wired in.
 * Phase 2: this method is replaced by a real call to Foundry IQ (via FoundryIqClient),
 *          returning retrieved snippets with their real citations.
 */
@Component
public class SearchKnowledgeTool implements Tool {

    @Override
    public String name() {
        return "search_knowledge";
    }

    @Override
    public String description() {
        return "Search runbooks and past postmortems; returns snippets WITH source citations "
                + "(grounded via Foundry IQ in Phase 2).";
    }

    @Override
    public String execute(Map<String, String> args) {
        // The query arg is accepted but ignored in the stub; we return the two
        // sample knowledge documents along with their source ids so callers can cite them.
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

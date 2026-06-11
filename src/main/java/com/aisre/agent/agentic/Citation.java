package com.aisre.agent.agentic;

/**
 * One cited knowledge snippet returned by the KnowledgeAgent (grounded via Foundry IQ).
 *
 * Each citation carries a stable id (C1, C2, ...) so hypotheses can reference it
 * ("supported by C2") and the postmortem can cite real sources. Citations only ever
 * come from retrieved content — never invented. If IQ returns nothing relevant, the
 * KnowledgeAgent produces zero citations rather than guessing.
 *
 * @param id         stable reference id, e.g. "C1".
 * @param sourceName the readable document name, e.g. "runbook-null-pointer.md".
 * @param snippet    the retrieved passage text.
 */
public record Citation(
        String id,
        String sourceName,
        String snippet
) {
}

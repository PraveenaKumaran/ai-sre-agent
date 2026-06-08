package com.aisre.agent.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for mapping an Azure AI Search "retrieve" response into our cited snippets.
 *
 * The input mirrors the REAL 2026-05-01-preview response shape observed live:
 *  - grounding lives as a JSON-encoded STRING at response[0].content[0].text;
 *  - each grounding doc has a NUMERIC ref_id and a content field (no inline title);
 *  - the readable document name is references[].docName.
 * No network or key is involved — we feed a canned response.
 */
class FoundryIqClientParseTest {

    private final ObjectMapper m = new ObjectMapper();

    /** Build a response in the observed preview shape (numeric ref_id, content, references.docName). */
    private String buildResponse() throws Exception {
        // Inner grounding array: each element is one retrieved document.
        ArrayNode docs = m.createArrayNode();

        ObjectNode d0 = docs.addObject();
        d0.put("ref_id", 0); // NUMERIC, as the preview API returns it
        d0.put("content", "Null-guard the value at the point of use.");

        ObjectNode d1 = docs.addObject();
        d1.put("ref_id", 1);
        d1.put("content", "Legacy import left loyaltyTier null.");

        // The grounding array is serialized to a STRING and placed in content[0].text.
        String groundingString = m.writeValueAsString(docs);

        ObjectNode root = m.createObjectNode();
        ArrayNode response = root.putArray("response");
        ObjectNode msg = response.addObject(); // preview omits "role"
        ArrayNode content = msg.putArray("content");
        ObjectNode textPart = content.addObject();
        textPart.put("type", "text");
        textPart.put("text", groundingString);

        // Citations: the readable file name is carried as docName (preview).
        ArrayNode refs = root.putArray("references");
        ObjectNode r0 = refs.addObject();
        r0.put("type", "file");
        r0.put("id", "0");
        r0.put("docName", "runbook-null-pointer.md");
        ObjectNode r1 = refs.addObject();
        r1.put("type", "file");
        r1.put("id", "1");
        r1.put("docName", "postmortem-2025-11-order-service-npe.md");

        return m.writeValueAsString(root);
    }

    @Test
    void mapsGroundingDocsToReadableCitations() throws Exception {
        String response = buildResponse();

        KnowledgeResult result = FoundryIqClient.parseRetrieveResponse(m, response, "knowledgebase969");

        assertThat(result.snippets()).hasSize(2);

        // sourceId is now the READABLE document name (docName), not the bare ref_id;
        // path carries the knowledge base as provenance.
        KnowledgeResult.Snippet first = result.snippets().get(0);
        assertThat(first.sourceId()).isEqualTo("runbook-null-pointer.md");
        assertThat(first.sourcePath()).isEqualTo("knowledgebase969");
        assertThat(first.text()).contains("Null-guard");

        KnowledgeResult.Snippet second = result.snippets().get(1);
        assertThat(second.sourceId()).isEqualTo("postmortem-2025-11-order-service-npe.md");
        assertThat(second.text()).contains("Legacy import");

        // Citations flow through as readable names, and the [SOURCE: ...] contract holds.
        assertThat(result.citationIds())
                .containsExactly("runbook-null-pointer.md", "postmortem-2025-11-order-service-npe.md");
        assertThat(result.toToolResultText())
                .contains("[SOURCE: runbook-null-pointer.md | knowledgebase969")
                .contains("[SOURCE: postmortem-2025-11-order-service-npe.md | knowledgebase969");
    }

    @Test
    void fallsBackToRefIdWhenNoReadableNamePresent() throws Exception {
        // A grounding doc whose ref has no docName/title -> citation falls back to the ref id.
        ArrayNode docs = m.createArrayNode();
        ObjectNode d0 = docs.addObject();
        d0.put("ref_id", 0);
        d0.put("content", "Some grounding text.");

        ObjectNode root = m.createObjectNode();
        ArrayNode response = root.putArray("response");
        ObjectNode msg = response.addObject();
        ArrayNode content = msg.putArray("content");
        content.addObject().put("type", "text").put("text", m.writeValueAsString(docs));
        root.putArray("references"); // no references at all

        KnowledgeResult result =
                FoundryIqClient.parseRetrieveResponse(m, m.writeValueAsString(root), "knowledgebase969");

        assertThat(result.snippets()).hasSize(1);
        assertThat(result.snippets().get(0).sourceId()).isEqualTo("0"); // graceful fallback
    }

    @Test
    void emptyResponseYieldsNoSnippets() throws Exception {
        // A well-formed response that retrieved nothing.
        ObjectNode root = m.createObjectNode();
        root.putArray("response"); // empty
        String response = m.writeValueAsString(root);

        KnowledgeResult result = FoundryIqClient.parseRetrieveResponse(m, response, "knowledgebase969");

        assertThat(result.snippets()).isEmpty();
    }
}

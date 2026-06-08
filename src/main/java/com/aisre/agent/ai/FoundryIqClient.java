package com.aisre.agent.ai;

import com.aisre.agent.config.FoundryProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Calls Microsoft Foundry IQ — which runs on Azure AI Search "agentic retrieval"
 * — to fetch grounded, CITED knowledge.
 *
 * This is the mandatory IQ integration. {@code search_knowledge} delegates here
 * (when IQ is enabled) so the agent's hypotheses are backed by retrieved runbooks
 * and past postmortems with real citations instead of guessed.
 *
 * WIRE FORMAT (verified against Microsoft Learn, "Query Knowledge Base via APIs"):
 *   POST {endpoint}/knowledgebases/{name}/retrieve?api-version=...
 *   Header: api-key: <Azure AI Search key>
 *   Body:   { "intents": [ { "type": "semantic", "search": "<query>" } ] }
 * We use the "intents" input and omit the optional "knowledgeSourceParams", so the
 * pipeline queries every knowledge source attached to the knowledge base.
 *
 * RESPONSE: the grounding text is a JSON-ENCODED STRING at
 *   response[0].content[0].text  -> an array of { ref_id, content } documents,
 * and citation metadata is in a separate references[] array. The readable document
 * name is "docName" on the 2026-05-01-preview API (and "docKey" on GA 2026-04-01);
 * we read whichever is present and use it as the citation. Each grounding doc maps
 * to a {@link KnowledgeResult.Snippet} rendered as "[SOURCE: docName | knowledgeBase]",
 * so the rest of the reasoning loop is unchanged.
 */
@Component
public class FoundryIqClient {

    /** Azure AI Search key-auth uses this fixed header name; the value comes from config/env. */
    private static final String SEARCH_KEY_HEADER = "api-key";

    private final FoundryProperties props;
    private final ObjectMapper json;
    private final RestClient http;

    public FoundryIqClient(FoundryProperties props, ObjectMapper json) {
        this.props = props;
        this.json = json;
        this.http = RestClient.create();
    }

    /** True when Foundry IQ is configured; if false, search_knowledge uses canned snippets. */
    public boolean isEnabled() {
        return props.iq() != null && props.iq().enabled();
    }

    /**
     * Retrieve cited knowledge snippets relevant to a query, via the Azure AI Search
     * knowledge-base "retrieve" action.
     *
     * @param query the natural-language search, e.g. "NullPointerException loyaltyTier".
     * @return retrieved snippets, each carrying its source citation.
     */
    public KnowledgeResult retrieve(String query) {
        FoundryProperties.Iq iq = props.iq();

        // URL: {endpoint}/knowledgebases/{name}/retrieve?api-version=...
        String path = iq.retrievePath().replace("{index}", iq.index());
        String url = iq.endpoint() + path;
        if (iq.apiVersion() != null && !iq.apiVersion().isBlank()) {
            url += "?api-version=" + iq.apiVersion();
        }

        // Body: a single semantic intent built from the agent's query.
        ObjectNode body = json.createObjectNode();
        ArrayNode intents = body.putArray("intents");
        ObjectNode intent = intents.addObject();
        intent.put("type", "semantic");
        intent.put("search", query);

        // Azure AI Search key (its own env var); fall back to the model key if unset.
        String key = (iq.apiKey() == null || iq.apiKey().isBlank()) ? props.apiKey() : iq.apiKey();

        String response = http.post()
                .uri(url)
                .header(SEARCH_KEY_HEADER, key)
                .header("Content-Type", "application/json")
                .body(body.toString())
                .retrieve()
                .body(String.class);

        return parseRetrieveResponse(json, response, iq.index());
    }

    /**
     * Map an Azure AI Search retrieve response into cited snippets.
     *
     * Package-private + static so it can be unit-tested against the documented
     * sample response without any network call or key.
     *
     * @param json         a Jackson mapper.
     * @param response     the raw JSON body returned by the retrieve action.
     * @param fallbackPath used as the citation "path" when a doc has no title/docKey.
     */
    static KnowledgeResult parseRetrieveResponse(ObjectMapper json, String response, String fallbackPath) {
        List<KnowledgeResult.Snippet> snippets = new ArrayList<>();
        JsonNode root = readTree(json, response);

        // references[] maps a ref id -> a readable document name, used as the citation.
        // The 2026-05-01-preview API returns "docName"; the GA 2026-04-01 API used
        // "docKey". Read whichever is present so we work on both shapes.
        Map<String, String> refIdToName = new LinkedHashMap<>();
        for (JsonNode ref : root.path("references")) {
            String id = ref.path("id").asText("");
            if (!id.isBlank()) {
                refIdToName.put(id, firstNonBlank(ref, "docName", "docKey"));
            }
        }

        // The grounding data is a JSON string nested at response[0].content[0].text.
        String grounding = root.path("response").path(0).path("content").path(0).path("text").asText("");
        if (grounding.isBlank()) {
            return new KnowledgeResult(snippets); // nothing retrieved
        }

        JsonNode docs;
        try {
            docs = json.readTree(grounding);
        } catch (Exception notJson) {
            // Unexpected: keep the grounding as a single uncited snippet rather than lose it.
            snippets.add(new KnowledgeResult.Snippet("IQ", fallbackPath, grounding));
            return new KnowledgeResult(snippets);
        }

        if (docs.isArray()) {
            int i = 0;
            for (JsonNode doc : docs) {
                String refId = doc.path("ref_id").asText(""); // numeric or string -> "0", "1", ...
                if (refId.isBlank()) {
                    refId = String.valueOf(i);
                }
                // Readable citation: prefer the document name from references[], then an
                // inline title, and only fall back to the bare ref id if neither exists.
                // This is what flows into citedSources, so citations read as file names.
                String docName = refIdToName.getOrDefault(refId, "");
                String inlineTitle = firstNonBlank(doc, "title", "name");
                String citationName = !docName.isBlank() ? docName
                        : (!inlineTitle.isBlank() ? inlineTitle : refId);

                // The grounded passage; never drop content even if field names differ.
                String text = firstNonBlank(doc, "content", "text", "chunk", "terms");
                if (text.isBlank()) {
                    text = doc.toString();
                }
                // [SOURCE: <readable doc name> | <knowledge base>]: id = citation, path = provenance.
                snippets.add(new KnowledgeResult.Snippet(citationName, fallbackPath, text));
                i++;
            }
        }
        return new KnowledgeResult(snippets);
    }

    /** Return the first of the given fields that has a non-blank text value. */
    private static String firstNonBlank(JsonNode node, String... fields) {
        for (String f : fields) {
            String v = node.path(f).asText("");
            if (!v.isBlank()) {
                return v;
            }
        }
        return "";
    }

    private static JsonNode readTree(ObjectMapper json, String s) {
        try {
            return json.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse IQ JSON: " + s, e);
        }
    }
}

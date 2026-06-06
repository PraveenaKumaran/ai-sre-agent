package com.aisre.agent.ai;

import com.aisre.agent.config.FoundryProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Calls Microsoft Foundry IQ to retrieve grounded, CITED knowledge.
 *
 * This is the mandatory IQ integration. {@code search_knowledge} delegates here
 * (when IQ is enabled) so the agent's hypotheses are backed by retrieved runbooks
 * and past postmortems with real source citations, instead of guessed.
 *
 * WIRE FORMAT: as with the model client, the request/response JSON shape for IQ
 * retrieval varies and must be confirmed on Microsoft Learn. The path, index,
 * api-version and key all come from config; the response field names we read are
 * marked "VERIFY ON LEARN" and kept tolerant (we try a few common names).
 */
@Component
public class FoundryIqClient {

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
     * Retrieve cited knowledge snippets relevant to a query.
     *
     * @param query the natural-language search, e.g. "NullPointerException loyaltyTier".
     * @return retrieved snippets, each carrying its source citation.
     */
    public KnowledgeResult retrieve(String query) {
        FoundryProperties.Iq iq = props.iq();

        // Build URL: iq.endpoint + retrieve-path (with {index}) + ?api-version=...
        String path = iq.retrievePath().replace("{index}", iq.index());
        String url = iq.endpoint() + path + "?api-version=" + iq.apiVersion();

        // Build request body. VERIFY ON LEARN: confirm the exact field names IQ expects.
        ObjectNode reqBody = json.createObjectNode();
        reqBody.put("query", query);
        reqBody.put("top", iq.topK());

        // IQ may use its own key; if not set, reuse the model key.
        String key = (iq.apiKey() == null || iq.apiKey().isBlank()) ? props.apiKey() : iq.apiKey();
        String authValue = props.authScheme() + key;

        String response = http.post()
                .uri(url)
                .header(props.authHeader(), authValue)
                .header("Content-Type", "application/json")
                .body(reqBody.toString())
                .retrieve()
                .body(String.class);

        return parse(response);
    }

    /**
     * Parse the IQ response into cited snippets.
     *
     * VERIFY ON LEARN: confirm where the retrieved passages live and what the
     * source/citation fields are called. We tolerantly check a few common names:
     *   - array under "results" or "value"
     *   - text under "content", "text" or "chunk"
     *   - source under "sourceId", "id", "title" or "filepath"
     */
    private KnowledgeResult parse(String response) {
        JsonNode root = readTree(response);
        JsonNode array = root.has("results") ? root.get("results") : root.path("value");

        List<KnowledgeResult.Snippet> snippets = new ArrayList<>();
        if (array.isArray()) {
            for (JsonNode n : array) {
                String text = firstNonBlank(n, "content", "text", "chunk");
                String sourceId = firstNonBlank(n, "sourceId", "id", "title");
                String sourcePath = firstNonBlank(n, "filepath", "url", "title");
                snippets.add(new KnowledgeResult.Snippet(
                        sourceId.isBlank() ? "UNKNOWN" : sourceId,
                        sourcePath.isBlank() ? "unknown" : sourcePath,
                        text));
            }
        }
        return new KnowledgeResult(snippets);
    }

    /** Return the first of the given fields that has a non-blank text value. */
    private String firstNonBlank(JsonNode node, String... fields) {
        for (String f : fields) {
            String v = node.path(f).asText("");
            if (!v.isBlank()) {
                return v;
            }
        }
        return "";
    }

    private JsonNode readTree(String s) {
        try {
            return json.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse IQ JSON: " + s, e);
        }
    }
}

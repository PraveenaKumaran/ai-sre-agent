package com.aisre.agent.ai;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.aisre.agent.config.FoundryProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Calls the Microsoft Foundry model API over HTTPS and turns the raw HTTP reply
 * into our small {@link ModelTurn}.
 *
 * WIRE FORMAT: this implementation uses the OpenAI-compatible Chat Completions
 * shape, which is the most widely supported Foundry pattern. Everything that can
 * differ per resource (URL path, api-version, auth header/scheme) comes from
 * {@link FoundryProperties} (application.yml), so confirming your resource's
 * shape on Microsoft Learn is a config change, not a code change. The few places
 * the JSON *structure* itself is assumed are marked "VERIFY ON LEARN" below.
 */
@Component
public class FoundryModelClient implements ModelClient {

    private final FoundryProperties props;
    private final ObjectMapper json;
    private final RestClient http;

    /** Instrumentation: total model invocations, read by the eval harness per incident. */
    private final java.util.concurrent.atomic.AtomicLong invocations = new java.util.concurrent.atomic.AtomicLong();

    public FoundryModelClient(FoundryProperties props, ObjectMapper json) {
        this.props = props;
        this.json = json;
        // RestClient is Spring's modern synchronous HTTP client.
        this.http = RestClient.create();
    }

    /** Total nextTurn invocations since startup (monotonic; callers diff before/after). */
    public long invocationCount() {
        return invocations.get();
    }

    @Override
    public boolean isEnabled() {
        return props.enabled();
    }

    @Override
    public ModelTurn nextTurn(List<ChatMessage> messages, List<ToolSpec> tools) {
        if (!isEnabled()) {
            // Guard: the loop should check isEnabled() first. If we get here, it's a bug.
            throw new IllegalStateException("FoundryModelClient called while foundry.enabled=false");
        }
        invocations.incrementAndGet(); // count every real model call (attempts included)

        // 1) Build the request URL: endpoint + chat-path.
        //    On the OpenAI v1 endpoint the model goes in the BODY (no {model} in the
        //    path) and there is NO api-version query param, so we only append
        //    api-version when one is actually configured (classic deployments path).
        String path = props.chatPath().replace("{model}", props.model());
        String url = props.endpoint() + path;
        if (props.apiVersion() != null && !props.apiVersion().isBlank()) {
            url += "?api-version=" + props.apiVersion();
        }

        // 2) Build the JSON request body from our messages + tools.
        String body = buildRequestBody(messages, tools);

        // 3) POST it, sending the credential in the configured auth header.
        String authValue = props.authScheme() + props.apiKey();
        String response = http.post()
                .uri(url)
                .header(props.authHeader(), authValue)
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve()
                .body(String.class);

        // 4) Parse the reply into a ModelTurn (tool calls, or a final answer).
        return parseResponse(response);
    }

    // ----------------------------------------------------------------------
    // Request building
    // ----------------------------------------------------------------------

    /**
     * Turn our conversation + tool list into the OpenAI-compatible JSON body:
     * { "model": ..., "messages": [...], "tools": [...], "tool_choice": "auto", "temperature": 0 }
     */
    private String buildRequestBody(List<ChatMessage> messages, List<ToolSpec> tools) {
        ObjectNode root = json.createObjectNode();
        // On the OpenAI v1 endpoint the model is selected here in the body.
        root.put("model", props.model());

        // GPT-5.4 is a REASONING model: it rejects "temperature", and uses
        // "max_completion_tokens" (not "max_tokens"). We send "reasoning_effort"
        // to control how much it thinks. All three come from config so a different
        // (non-reasoning) model can be configured later without code changes.
        if (props.reasoningEffort() != null && !props.reasoningEffort().isBlank()) {
            root.put("reasoning_effort", props.reasoningEffort());
        }
        if (props.maxCompletionTokens() > 0) {
            root.put("max_completion_tokens", props.maxCompletionTokens());
        }

        // messages -> JSON array
        ArrayNode msgs = root.putArray("messages");
        for (ChatMessage m : messages) {
            msgs.add(toMessageNode(m));
        }

        // tools -> JSON array of {"type":"function","function":{name,description,parameters}}
        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolArr = root.putArray("tools");
            for (ToolSpec t : tools) {
                ObjectNode toolNode = toolArr.addObject();
                toolNode.put("type", "function");
                ObjectNode fn = toolNode.putObject("function");
                fn.put("name", t.name());
                fn.put("description", t.description());
                // parametersJsonSchema is itself a JSON string; embed it as parsed JSON.
                fn.set("parameters", readTree(t.parametersJsonSchema()));
            }
            root.put("tool_choice", "auto"); // let the model decide which (if any) tool to call.
        }

        return root.toString();
    }

    /** Convert one ChatMessage to its OpenAI-compatible JSON node. */
    private ObjectNode toMessageNode(ChatMessage m) {
        ObjectNode node = json.createObjectNode();
        node.put("role", m.role());

        if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
            // assistant message that requests tools
            ArrayNode calls = node.putArray("tool_calls");
            for (ToolCall c : m.toolCalls()) {
                ObjectNode call = calls.addObject();
                call.put("id", c.id());
                call.put("type", "function");
                ObjectNode fn = call.putObject("function");
                fn.put("name", c.name());
                fn.put("arguments", c.argumentsJson()); // arguments is a JSON *string* by spec.
            }
            // content stays null for a pure tool-call assistant message.
        } else if ("tool".equals(m.role())) {
            // a tool-result message must reference the call it answers.
            node.put("tool_call_id", m.toolCallId());
            if (m.name() != null) {
                node.put("name", m.name());
            }
            node.put("content", m.content() == null ? "" : m.content());
        } else {
            node.put("content", m.content() == null ? "" : m.content());
        }
        return node;
    }

    // ----------------------------------------------------------------------
    // Response parsing
    // ----------------------------------------------------------------------

    /**
     * Read choices[0].message: if it has tool_calls, return those; otherwise return
     * its text content as the final answer. (VERIFY ON LEARN: confirm your API nests
     * the reply under choices[0].message — the Responses API differs slightly.)
     */
    private ModelTurn parseResponse(String response) {
        JsonNode root = readTree(response);
        JsonNode message = root.path("choices").path(0).path("message");

        JsonNode toolCalls = message.path("tool_calls");
        if (toolCalls.isArray() && !toolCalls.isEmpty()) {
            List<ToolCall> calls = new ArrayList<>();
            for (JsonNode tc : toolCalls) {
                String id = tc.path("id").asText();
                JsonNode fn = tc.path("function");
                String name = fn.path("name").asText();
                String args = fn.path("arguments").asText("{}"); // arguments comes back as a JSON string.
                calls.add(new ToolCall(id, name, args));
            }
            return ModelTurn.ofToolCalls(calls);
        }

        // No tool calls -> this is the model's final answer.
        String content = message.path("content").asText("");
        return ModelTurn.ofFinal(content);
    }

    // Small helpers that wrap Jackson's checked exceptions as runtime ones.
    private JsonNode readTree(String s) {
        try {
            return json.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse JSON: " + s, e);
        }
    }
}

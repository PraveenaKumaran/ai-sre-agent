package com.aisre.agent;

import com.aisre.agent.ai.ChatMessage;
import com.aisre.agent.ai.ModelClient;
import com.aisre.agent.ai.ModelConclusion;
import com.aisre.agent.ai.ModelTurn;
import com.aisre.agent.ai.ToolCall;
import com.aisre.agent.config.AgentProperties;
import com.aisre.agent.model.IncidentRequest;
import com.aisre.agent.model.TriageResult;
import com.aisre.agent.tools.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The orchestrator loop.
 *
 * TWO MODES, chosen by config:
 *  - foundry.enabled = false -> {@link #stubTriage} runs the Phase-1 canned path
 *    (no network), so the baseline always works without credentials.
 *  - foundry.enabled = true  -> {@link #modelDrivenTriage} runs the REAL loop: ask
 *    the Foundry model what to do, run the tool it picks, feed the result back,
 *    repeat (up to a hard iteration cap), until the model returns a conclusion.
 *
 * The orchestrator owns the LOOP; the model owns each DECISION inside a turn.
 */
@Service
public class ReasoningLoop {

    /** Pulls the source id out of a "[SOURCE: RB-NPE-001 | path]" marker for the trace. */
    private static final Pattern SOURCE_MARKER = Pattern.compile("\\[SOURCE:\\s*([^|\\]]+)");

    private final ToolRegistry tools;
    private final ModelClient model;
    private final AgentProperties agentProps;
    private final ObjectMapper json;
    private final String systemPrompt;

    public ReasoningLoop(ToolRegistry tools, ModelClient model, AgentProperties agentProps, ObjectMapper json) {
        this.tools = tools;
        this.model = model;
        this.agentProps = agentProps;
        this.json = json;
        this.systemPrompt = loadSystemPrompt();
    }

    public TriageResult triage(IncidentRequest incident) {
        String incidentId = "INC-" + UUID.randomUUID().toString().substring(0, 8);
        String service = incident.service() == null ? "unknown-service" : incident.service();

        // No real model configured -> keep returning the Phase-1 stub result.
        if (!model.isEnabled()) {
            return stubTriage(incidentId, service);
        }
        return modelDrivenTriage(incidentId, service, incident);
    }

    // ======================================================================
    // PHASE 2: the real, model-driven reasoning loop
    // ======================================================================

    private TriageResult modelDrivenTriage(String incidentId, String service, IncidentRequest incident) {
        List<String> steps = new ArrayList<>();
        List<String> citationsSeen = new ArrayList<>();

        // The conversation starts with our standing instructions + the incident.
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(systemPrompt));
        messages.add(ChatMessage.user(
                "Incident for service '" + service + "'.\nStack trace / error:\n" + incident.stackTrace()));

        int maxIterations = agentProps.maxIterations();

        for (int turn = 1; turn <= maxIterations; turn++) {
            // Ask the model what to do next, giving it the tools it may call.
            ModelTurn reply = model.nextTurn(messages, tools.specs());

            if (reply.wantsToolCalls()) {
                // The model wants evidence. Record its request, run each tool, feed results back.
                messages.add(ChatMessage.assistantToolCalls(reply.toolCalls()));

                for (ToolCall call : reply.toolCalls()) {
                    Map<String, String> args = parseArgs(call.argumentsJson());
                    String result = tools.execute(call.name(), args);

                    // Build a concise, human-readable trace line for this tool call.
                    steps.add("TURN " + turn + " — model called " + call.name() + "(" + args + ")");
                    if ("search_knowledge".equals(call.name())) {
                        List<String> ids = extractSourceIds(result);
                        citationsSeen.addAll(ids);
                        steps.add("    FOUNDRY IQ returned sources: " + ids);
                    }

                    // Feed the tool's output back to the model as a 'tool' message.
                    messages.add(ChatMessage.toolResult(call.id(), call.name(), result));
                }
                // Loop again: the model now sees the tool results and decides the next step.
            } else {
                // No tool calls -> the model is done. Parse its JSON conclusion.
                steps.add("TURN " + turn + " — model concluded");
                steps.add("GATE: stopping for human approval. Agent takes no real action on its own.");
                ModelConclusion c = parseConclusion(reply.finalContent());
                return toResult(incidentId, service, c, steps, dedupe(citationsSeen));
            }
        }

        // We exhausted the iteration cap without a conclusion -> escalate (safety/cost guard).
        steps.add("STOPPED: hit max-iterations cap (" + maxIterations
                + ") without a conclusion. Escalating to a human.");
        return new TriageResult(
                incidentId, service,
                "UNRESOLVED (iteration cap reached)",
                "The agent did not converge on a root cause within " + maxIterations
                        + " turns. A human should investigate.",
                dedupe(citationsSeen),
                "", // no fix drafted
                "Inconclusive: the reasoning loop hit its safety cap of " + maxIterations + " turns.",
                0.0,
                steps,
                "ESCALATED_ITERATION_CAP",
                phaseNote());
    }

    /** Map a parsed model conclusion onto the public TriageResult shape. */
    private TriageResult toResult(String incidentId, String service, ModelConclusion c,
                                  List<String> steps, List<String> citationsSeen) {
        // Prefer the sources the model cited; fall back to what IQ actually returned.
        List<String> cited = (c.citedSources() != null && !c.citedSources().isEmpty())
                ? c.citedSources() : citationsSeen;

        return new TriageResult(
                incidentId,
                service,
                orEmpty(c.classification()),
                orEmpty(c.rootCauseHypothesis()),
                cited,
                orEmpty(c.proposedFix()),
                orEmpty(c.postmortem()),
                c.confidence(),
                steps,
                "AWAITING_APPROVAL",
                phaseNote());
    }

    // ======================================================================
    // PHASE 1: the stubbed path (unchanged behaviour, kept for the no-credentials baseline)
    // ======================================================================

    private TriageResult stubTriage(String incidentId, String service) {
        // Run the stub tools in a fixed order (the MODEL chooses these in Phase 2).
        tools.execute("get_logs", Map.of("service", service, "time_window", "last_15m"));
        tools.execute("get_metrics", Map.of("service", service, "metric", "error_rate"));
        tools.execute("search_knowledge", Map.of("query", "NullPointerException loyaltyTier order-service"));
        tools.execute("read_code", Map.of("file_path", "com/shop/order/OrderService.java"));
        String draft = tools.execute("draft_fix", Map.of("file_path", "OrderService.java", "change", "null-guard tier"));

        List<String> steps = List.of(
                "CLASSIFY: stack trace shows java.lang.NullPointerException -> null-handling bug. [STUB]",
                "GATHER: called get_logs(" + service + ", last_15m) -> repeated NPEs at OrderService.java:42. [STUB]",
                "GATHER: called get_metrics(" + service + ", error_rate) -> error_rate jumps from ~0.3% to ~19% right after the 09:13 deploy. [STUB]",
                "GROUND: called search_knowledge(...) -> matched RB-NPE-001 and PM-2025-11-ORDER (cited). [STUB — Foundry IQ when enabled]",
                "INSPECT: called read_code(OrderService.java) -> tier.toUpperCase() with no null check at line 42. [STUB]",
                "HYPOTHESIZE: legacy-imported customers have null loyaltyTier; .toUpperCase() throws NPE. [STUB]",
                "TEST: hypothesis matches logs (loyaltyTier=null), metrics (spike at deploy) and the runbook/postmortem. Holds. [STUB]",
                "PROPOSE: called draft_fix(...) -> null-guard the tier. Draft only, not applied. [STUB]",
                "GATE: stopping for human approval. Agent takes no real action on its own."
        );

        return new TriageResult(
                incidentId,
                service,
                "NullPointerException / null-handling bug",
                "Legacy-imported customers have a null loyaltyTier. OrderService.applyLoyaltyDiscount "
                        + "calls tier.toUpperCase() with no null check (OrderService.java:42), throwing an NPE "
                        + "and returning HTTP 500. Onset matches the 09:13 deploy that added the loyalty discount.",
                List.of(
                        "RB-NPE-001 (knowledge/runbook-null-pointer.md)",
                        "PM-2025-11-ORDER (knowledge/postmortem-2025-11-order-service-npe.md)"
                ),
                draft,
                "Root cause: a field assumed non-null (loyaltyTier) is null for legacy-imported "
                        + "customers, throwing an NPE in applyLoyaltyDiscount after the 09:13 deploy. "
                        + "This matches runbook RB-NPE-001 and the near-identical prior incident "
                        + "PM-2025-11-ORDER. Proposed fix: default a missing tier to \"STANDARD\" and "
                        + "null-guard the call site. [Cites: RB-NPE-001, PM-2025-11-ORDER]",
                0.0, // Phase 1 placeholder — real confidence scoring comes in Phase 3.
                steps,
                "AWAITING_APPROVAL",
                "PHASE 1 STUB: no AI involved. foundry.enabled=false, so tools return canned data.");
    }

    // ======================================================================
    // Helpers
    // ======================================================================

    /** Turn the model's arguments JSON (e.g. {"service":"x"}) into a String->String map for the tool. */
    private Map<String, String> parseArgs(String argumentsJson) {
        Map<String, String> out = new LinkedHashMap<>();
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return out;
        }
        try {
            JsonNode node = json.readTree(argumentsJson);
            node.fields().forEachRemaining(e ->
                    out.put(e.getKey(), e.getValue().asText()));
        } catch (Exception ignored) {
            // A single malformed tool-arg payload shouldn't abort the whole run;
            // pass no args and let the tool handle the absence.
        }
        return out;
    }

    /**
     * Parse the model's final answer into a ModelConclusion. We strip optional code
     * fences and take the JSON object substring, so a stray ```json wrapper or extra
     * prose doesn't break parsing. If it still isn't valid JSON, we degrade gracefully.
     */
    private ModelConclusion parseConclusion(String content) {
        String text = content == null ? "" : content.strip();

        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            String jsonPart = text.substring(start, end + 1);
            try {
                return json.readValue(jsonPart, ModelConclusion.class);
            } catch (Exception ignored) {
                // fall through to the degraded result below
            }
        }
        // Degraded fallback: keep the raw text as the hypothesis with low confidence.
        return new ModelConclusion(
                "UNPARSED",
                text.isBlank() ? "Model returned no final content." : text,
                List.of(),
                "",
                "Model did not return the expected JSON conclusion.",
                0.0);
    }

    /** Pull source ids out of [SOURCE: id | path] markers in a search_knowledge result. */
    private List<String> extractSourceIds(String toolResult) {
        List<String> ids = new ArrayList<>();
        Matcher m = SOURCE_MARKER.matcher(toolResult == null ? "" : toolResult);
        while (m.find()) {
            ids.add(m.group(1).strip());
        }
        return ids;
    }

    private List<String> dedupe(List<String> in) {
        return in.stream().distinct().toList();
    }

    private String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private String phaseNote() {
        return "PHASE 2: model-driven loop (foundry.enabled=true). Grounding via Foundry IQ.";
    }

    private String loadSystemPrompt() {
        try {
            var in = new ClassPathResource("prompts/system-prompt.txt").getInputStream();
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Could not load prompts/system-prompt.txt", e);
        }
    }
}

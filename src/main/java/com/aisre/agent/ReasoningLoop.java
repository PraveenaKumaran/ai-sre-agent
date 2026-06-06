package com.aisre.agent;

import com.aisre.agent.model.IncidentRequest;
import com.aisre.agent.model.TriageResult;
import com.aisre.agent.tools.ToolRegistry;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The orchestrator loop.
 *
 * PHASE 1: this is a FAKE reasoning loop. There is no AI. It runs the five stub
 * tools in a fixed, sensible order, records a human-readable trail of what it
 * "did", and returns a hardcoded triage result. The point is to prove the whole
 * request -> tools -> response pipeline runs end-to-end before any model is added.
 *
 * PHASE 2 will replace the fixed sequence with a real loop: ask the Foundry
 * model what to do next, run the tool it picks, feed the result back, repeat,
 * discarding hypotheses that the evidence contradicts.
 */
@Service
public class ReasoningLoop {

    private final ToolRegistry tools;

    public ReasoningLoop(ToolRegistry tools) {
        this.tools = tools;
    }

    public TriageResult triage(IncidentRequest incident) {
        String incidentId = "INC-" + UUID.randomUUID().toString().substring(0, 8);
        String service = incident.service() == null ? "unknown-service" : incident.service();

        // ---- Run the stub tools in a fixed order, narrating each step. ----
        // (In Phase 2 the MODEL chooses these calls; here we hardcode the sequence.)

        tools.execute("get_logs", Map.of("service", service, "time_window", "last_15m"));
        tools.execute("get_metrics", Map.of("service", service, "metric", "error_rate"));
        // The grounding step: pull cited knowledge (Foundry IQ in Phase 2).
        tools.execute("search_knowledge", Map.of("query", "NullPointerException loyaltyTier order-service"));
        tools.execute("read_code", Map.of("file_path", "com/shop/order/OrderService.java"));
        String draft = tools.execute("draft_fix", Map.of("file_path", "OrderService.java", "change", "null-guard tier"));

        List<String> steps = List.of(
                "CLASSIFY: stack trace shows java.lang.NullPointerException -> null-handling bug. [STUB]",
                "GATHER: called get_logs(" + service + ", last_15m) -> repeated NPEs at OrderService.java:42. [STUB]",
                "GATHER: called get_metrics(" + service + ", error_rate) -> error_rate jumps from ~0.3% to ~19% right after the 09:13 deploy. [STUB]",
                "GROUND: called search_knowledge(...) -> matched RB-NPE-001 and PM-2025-11-ORDER (cited). [STUB — Foundry IQ in Phase 2]",
                "INSPECT: called read_code(OrderService.java) -> tier.toUpperCase() with no null check at line 42. [STUB]",
                "HYPOTHESIZE: legacy-imported customers have null loyaltyTier; .toUpperCase() throws NPE. [STUB]",
                "TEST: hypothesis matches logs (loyaltyTier=null), metrics (spike at deploy) and the runbook/postmortem. Holds. [STUB]",
                "PROPOSE: called draft_fix(...) -> null-guard the tier. Draft only, not applied. [STUB]",
                "GATE: stopping for human approval. Agent takes no real action on its own."
        );

        // ---- Return a hardcoded triage result (no AI yet). ----
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
                "PHASE 1 STUB: no AI involved. Tools return canned data and this result is hardcoded."
        );
    }
}

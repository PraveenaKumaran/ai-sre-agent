package com.aisre.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed view of the {@code agent.*} guardrail settings.
 *
 * @param maxIterations       hard cap on model turns in one triage run. The loop
 *                            stops once this many turns have happened, so a
 *                            model-driven loop can never run away (safety + cost).
 * @param minConfidenceToPropose below this confidence the agent should escalate
 *                            rather than propose a fix. Defined now; the full
 *                            escalation behavior is wired in Phase 3.
 */
@ConfigurationProperties(prefix = "agent")
public record AgentProperties(
        int maxIterations,
        double minConfidenceToPropose
) {
}

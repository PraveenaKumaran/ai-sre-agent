package com.aisre.agent;

import com.aisre.agent.model.IncidentRequest;
import com.aisre.agent.model.TriageResult;
import com.aisre.agent.tools.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1 tests: prove the request -> tools -> response pipeline runs and that
 * the stubbed result keeps the shape later phases depend on (classification,
 * cited sources, a draft fix, and the always-on approval gate).
 *
 * {@code @SpringBootTest} boots the real Spring context, so the tools are wired
 * exactly as they are at runtime.
 */
@SpringBootTest
// Pin the stub path on, independent of application.yml (which is now live/enabled).
// This keeps the Phase-1 baseline test hermetic — no model/network calls.
@TestPropertySource(properties = {"foundry.enabled=false", "foundry.iq.enabled=false"})
class ReasoningLoopTest {

    @Autowired
    ReasoningLoop reasoningLoop;

    @Autowired
    ToolRegistry toolRegistry;

    @Test
    void allFiveToolsAreRegistered() {
        // The spec says exactly five tools. Lock that in.
        assertThat(toolRegistry.names())
                .containsExactlyInAnyOrder(
                        "get_logs", "get_metrics", "search_knowledge", "read_code", "draft_fix");
    }

    @Test
    void triageReturnsAGroundedStubResult() {
        IncidentRequest incident = new IncidentRequest(
                "order-service",
                "java.lang.NullPointerException at com.shop.order.OrderService.applyLoyaltyDiscount(OrderService.java:42)");

        TriageResult result = reasoningLoop.triage(incident);

        // Echoes the service and classifies the failure.
        assertThat(result.service()).isEqualTo("order-service");
        assertThat(result.classification()).containsIgnoringCase("NullPointer");

        // Grounding: the result must cite knowledge sources (the IQ requirement, stubbed in Phase 1).
        assertThat(result.citedSources()).isNotEmpty();
        assertThat(result.postmortem()).contains("RB-NPE-001");

        // It drafts a fix but does NOT act: the approval gate is always on.
        assertThat(result.proposedFix()).containsIgnoringCase("DRAFT ONLY");
        assertThat(result.status()).isEqualTo("AWAITING_APPROVAL");

        // The reasoning trail is present (precursor to the Phase 3 glass-box trace).
        assertThat(result.reasoningSteps()).isNotEmpty();
    }
}

package com.aisre.agent;

import com.aisre.agent.model.IncidentRequest;
import com.aisre.agent.model.TriageResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the real Spring context (with Foundry/IQ disabled so no network is
 * touched) and exercises /triage end-to-end through the controller. Proves the
 * wiring AND the no-credentials offline baseline: the multi-agent response shape
 * comes back with the preserved contract fields populated.
 */
@SpringBootTest
@TestPropertySource(properties = {"foundry.enabled=false", "foundry.iq.enabled=false"})
class AppContextTest {

    @Autowired
    TriageController controller;

    @Test
    void offlineTriageReturnsTheMultiAgentShapeWithPreservedFields() {
        TriageResult result = controller.triage(new IncidentRequest(
                "order-service",
                "java.lang.NullPointerException at OrderService.applyLoyaltyDiscount(OrderService.java:42)"));

        // Preserved contract fields.
        assertThat(result.incidentId()).startsWith("INC-");
        assertThat(result.service()).isEqualTo("order-service");
        assertThat(result.status()).isEqualTo("AWAITING_APPROVAL");
        assertThat(result.citedSources()).contains("runbook-null-pointer.md");
        assertThat(result.proposedFix()).containsIgnoringCase("DRAFT ONLY");
        assertThat(result.postmortem()).isNotBlank();
        assertThat(result.confidence()).isGreaterThan(0.0);

        // Structured sections present, stub clearly marked.
        assertThat(result.evidence()).isNotEmpty();
        assertThat(result.hypotheses()).isNotEmpty();
        assertThat(result.critiques()).isNotEmpty();
        assertThat(result.decision().rationale()).contains("[OFFLINE STUB]");
        assertThat(result.trace()).isNotEmpty();
    }
}

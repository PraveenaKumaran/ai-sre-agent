package com.aisre.agent.agentic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the evaluation data set: incidents.json must stay well-formed (the step-8
 * eval runner consumes it), with exactly 10 incidents of which exactly 2 are
 * expected to escalate, and every converging incident must name its expected
 * citations so grounding is actually scored.
 */
class EvalDataTest {

    @Test
    void incidentsJsonIsWellFormedWithTenIncidentsAndTwoEscalations() throws Exception {
        JsonNode root = new ObjectMapper().readTree(
                new ClassPathResource("eval/incidents.json").getInputStream());
        JsonNode incidents = root.path("incidents");

        assertThat(incidents.isArray()).isTrue();
        assertThat(incidents.size()).isEqualTo(10);

        int escalations = 0;
        for (JsonNode incident : incidents) {
            // Required fields for the runner.
            for (String field : List.of("id", "name", "service", "stackTrace")) {
                assertThat(incident.path(field).asText())
                        .as("%s of %s", field, incident.path("id").asText()).isNotBlank();
            }
            JsonNode expected = incident.path("expected");
            String decision = expected.path("decision").asText();
            assertThat(decision).isIn("RECOMMEND_REMEDIATION", "ESCALATE");

            if (decision.equals("ESCALATE")) {
                escalations++;
            } else {
                // A converging incident must be scoreable on grounding.
                assertThat(expected.path("citationAnyOf").size())
                        .as("citationAnyOf of %s", incident.path("id").asText())
                        .isGreaterThan(0);
                assertThat(expected.path("rootCauseKeywords").size())
                        .as("rootCauseKeywords of %s", incident.path("id").asText())
                        .isGreaterThan(0);
            }
        }
        assertThat(escalations).isEqualTo(2);
    }
}

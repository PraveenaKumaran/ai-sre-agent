package com.aisre.agent.tools;

import com.aisre.agent.ai.FoundryIqClient;
import com.aisre.agent.config.FoundryProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The spec defines exactly five tools; keep that pinned (coverage carried over
 * from the retired ReasoningLoopTest).
 */
class ToolRegistryTest {

    @Test
    void allFiveToolsAreRegistered() {
        FoundryProperties.Iq iqOff = new FoundryProperties.Iq(false, "", "", "", "", "", 4);
        FoundryProperties props = new FoundryProperties(
                false, "", "", "", "", "", "api-key", "", "low", 4000, iqOff);
        FoundryIqClient iq = new FoundryIqClient(props, new ObjectMapper());

        ToolRegistry registry = new ToolRegistry(List.of(
                new GetLogsTool(), new GetMetricsTool(), new SearchKnowledgeTool(iq),
                new ReadCodeTool(), new DraftFixTool()));

        assertThat(registry.names()).containsExactlyInAnyOrder(
                "get_logs", "get_metrics", "search_knowledge", "read_code", "draft_fix");
    }
}

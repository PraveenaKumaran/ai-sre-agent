package com.aisre.agent.agentic;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * All five agent prompt files must exist and be non-empty — they are repo/demo
 * assets the agents load at construction time, so a missing one is a build break.
 */
class PromptLoaderTest {

    @Test
    void allFiveAgentPromptsExistAndAreNonEmpty() {
        List<String> prompts = List.of(
                "triage-agent.txt",
                "knowledge-agent.txt",
                "rootcause-agent.txt",
                "critic-agent.txt",
                "judge-agent.txt");

        for (String file : prompts) {
            String text = PromptLoader.load(file);
            assertThat(text).as("prompt file %s", file).isNotBlank();
        }
    }
}

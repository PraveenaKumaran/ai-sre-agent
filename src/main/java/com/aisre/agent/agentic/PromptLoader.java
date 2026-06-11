package com.aisre.agent.agentic;

import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Loads agent system prompts from src/main/resources/prompts/.
 *
 * One place for path, charset, and error handling, so every agent loads its prompt
 * the same way. Prompts are part of the repo on purpose: they are demo/repo assets
 * a reviewer should be able to read, not internals buried in Java strings.
 */
public final class PromptLoader {

    private PromptLoader() { } // utility class, no instances

    /**
     * Read a prompt file as UTF-8 text.
     *
     * @param fileName file under resources/prompts/, e.g. "triage-agent.txt".
     * @return the prompt text.
     */
    public static String load(String fileName) {
        try (InputStream in = new ClassPathResource("prompts/" + fileName).getInputStream()) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // A missing prompt is a build mistake — fail loudly at startup, not mid-incident.
            throw new IllegalStateException("Could not load prompt file: prompts/" + fileName, e);
        }
    }
}

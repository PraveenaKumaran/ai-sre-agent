package com.aisre.agent.tools;

import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Tiny helper to read a file bundled under src/main/resources as text.
 *
 * The Phase 1 tools serve canned data from real files (logs, metrics, sample
 * code, knowledge). Reading from files keeps the data realistic and means the
 * later phases inspect the exact same material.
 */
final class Resources {

    private Resources() { } // utility class, no instances

    /**
     * Read a classpath resource into a String.
     *
     * @param path resource path relative to src/main/resources,
     *             e.g. "sample-incident/order-service.log"
     * @return the file contents as text
     */
    static String read(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // In Phase 1 a missing canned file is a developer mistake, so fail loudly.
            throw new IllegalStateException("Could not read bundled resource: " + path, e);
        }
    }
}

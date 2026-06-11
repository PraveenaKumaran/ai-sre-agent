package com.aisre.agent.agentic;

import java.util.Locale;

/**
 * The CriticAgent's verdict on a single hypothesis.
 *
 * - SUPPORTED: evidence and citations clearly back it and nothing contradicts it.
 * - WEAK:      thin or unverified — not contradicted, but not solidly grounded either.
 * - REJECTED:  some evidence or citation directly contradicts it.
 *
 * The orchestrator's retry decision is deterministic and depends on these values
 * (it retries whenever NO hypothesis is SUPPORTED), so we keep them a strict enum.
 */
public enum CritiqueStatus {
    SUPPORTED,
    WEAK,
    REJECTED;

    /**
     * Parse a model-produced status string, degrading gracefully.
     *
     * Anything we don't recognise (including null or malformed JSON values) becomes
     * WEAK — the safe middle ground: it neither blesses an unverified hypothesis as
     * SUPPORTED nor throws it out as REJECTED, and it still triggers a retry.
     */
    public static CritiqueStatus fromString(String s) {
        if (s == null) {
            return WEAK;
        }
        try {
            return valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return WEAK;
        }
    }
}

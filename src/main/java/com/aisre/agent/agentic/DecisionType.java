package com.aisre.agent.agentic;

import java.util.Locale;

/**
 * The JudgeAgent's final verdict — exactly one of three outcomes.
 *
 * - RECOMMEND_REMEDIATION: a hypothesis is solidly supported; draft a fix + postmortem
 *   and HARD STOP at human approval. (There is no auto-remediate path anywhere.)
 * - ESCALATE_TO_HUMAN:     a human should investigate (ambiguous / weakly supported /
 *   retries exhausted).
 * - INSUFFICIENT_EVIDENCE: not enough grounded evidence to decide at all.
 */
public enum DecisionType {
    RECOMMEND_REMEDIATION,
    ESCALATE_TO_HUMAN,
    INSUFFICIENT_EVIDENCE;

    /**
     * Parse a model-produced decision string, degrading SAFELY.
     *
     * Anything we don't recognise becomes ESCALATE_TO_HUMAN — when the Judge's output
     * is unclear, the safe default is to hand off to a human, never to recommend
     * (let alone perform) a remediation.
     */
    public static DecisionType fromString(String s) {
        if (s == null) {
            return ESCALATE_TO_HUMAN;
        }
        try {
            return valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return ESCALATE_TO_HUMAN;
        }
    }
}

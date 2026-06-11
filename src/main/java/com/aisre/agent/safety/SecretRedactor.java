package com.aisre.agent.safety;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Deterministic, regex-based secret redaction.
 *
 * SECURITY FRAMING (the layer model for this project):
 * - THIS class is the security GUARANTEE for secrets. It runs at the BOUNDARY —
 *   raw incident input (stack trace, logs, metrics) is redacted ONCE, before any
 *   model ever sees it. The model cannot leak what it never receives.
 * - Everything downstream (triage summarization, the provenance-constrained Critic,
 *   fail-safe enum parsing, the deterministic orchestrator) is defense-in-depth
 *   MITIGATION for prompt injection from untrusted log input — NOT secret
 *   protection. Model behavior is probabilistic and may repeat input verbatim, so
 *   summarization must never be described as a secret-protection control.
 *
 * The patterns are deliberately idempotent: already-redacted text contains zero
 * matches, so "running the redactor over any output changes nothing" is a testable
 * invariant (see the leak guard test).
 */
@Component
public class SecretRedactor {

    /** Replacement marker. The patterns below refuse to re-match it (idempotence). */
    public static final String MASK = "[REDACTED]";

    /** One redaction rule: what to find and what to replace it with. */
    private record Rule(Pattern pattern, String replacement) { }

    // Each value-matching rule uses (?!\[REDACTED\]) so redacted text never re-matches.
    private static final List<Rule> RULES = List.of(
            // key=value style credentials: apiKey=..., password: ..., token=..., etc.
            new Rule(Pattern.compile(
                    "(?i)\\b(api[_-]?key|apikey|access[_-]?key|password|passwd|pwd|secret|token|credential)s?\\b(\\s*[=:]\\s*)(?!\\[REDACTED])\\S+"),
                    "$1$2" + MASK),
            // HTTP bearer tokens: "Bearer eyJhbGci..."
            new Rule(Pattern.compile(
                    "(?i)\\b(bearer)\\s+(?!\\[REDACTED])[A-Za-z0-9._~+/=-]+"),
                    "$1 " + MASK),
            // OpenAI-style key literals: sk-xxxxxxxx...
            new Rule(Pattern.compile("\\bsk-[A-Za-z0-9_-]{8,}\\b"), MASK),
            // Azure connection-string fragments: AccountKey=...;  SharedAccessSignature=...
            new Rule(Pattern.compile(
                    "(?i)\\b(AccountKey|SharedAccessSignature|sig)(=)(?!\\[REDACTED])[^;\\s&\"]+"),
                    "$1$2" + MASK)
    );

    /**
     * Redact all secret-shaped values in the given text. Null-safe. Idempotent:
     * redact(redact(x)) equals redact(x).
     */
    public String redact(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String out = text;
        for (Rule rule : RULES) {
            out = rule.pattern().matcher(out).replaceAll(rule.replacement());
        }
        return out;
    }

    /**
     * The raw detection patterns, exposed so the leak guard test can assert that
     * serialized trace payloads contain ZERO matches — making "payloads are
     * redacted" a tested invariant rather than a comment.
     */
    public static List<Pattern> patterns() {
        return RULES.stream().map(Rule::pattern).toList();
    }
}

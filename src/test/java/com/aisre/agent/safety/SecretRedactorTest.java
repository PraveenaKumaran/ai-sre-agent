package com.aisre.agent.safety;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The redactor is the security GUARANTEE for secrets: deterministic regex at the
 * boundary. These tests pin the patterns and the idempotence invariant the leak
 * guard test relies on.
 */
class SecretRedactorTest {

    private final SecretRedactor redactor = new SecretRedactor();

    @Test
    void redactsKeyValueStyleCredentials() {
        assertThat(redactor.redact("Connecting with apiKey=EXAMPLE_FAKE_KEY_DO_NOT_USE user=svc"))
                .isEqualTo("Connecting with apiKey=[REDACTED] user=svc");
        assertThat(redactor.redact("password: hunter2!")).isEqualTo("password: [REDACTED]");
        assertThat(redactor.redact("token=abc.def.ghi end")).isEqualTo("token=[REDACTED] end");
    }

    @Test
    void redactsBearerAndKeyLiterals() {
        assertThat(redactor.redact("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig"))
                .contains("Bearer [REDACTED]");
        assertThat(redactor.redact("found sk-live-9f8e7d6c5b4a3210 in log"))
                .isEqualTo("found [REDACTED] in log");
        assertThat(redactor.redact("conn=AccountKey=abc123XYZ;EndpointSuffix=core.windows.net"))
                .contains("AccountKey=[REDACTED]");
    }

    @Test
    void leavesOrdinaryLogTextAlone() {
        String text = "2026-06-06 ERROR OrderService NPE at line 42, error_rate=19%";
        assertThat(redactor.redact(text)).isEqualTo(text);
        assertThat(redactor.redact(null)).isNull();
    }

    @Test
    void redactionIsIdempotentAndRedactedTextHasZeroPatternMatches() {
        String dirty = "apiKey=SUPERSECRET Bearer tok.en.value sk-live-12345678 password=x";
        String clean = redactor.redact(dirty);

        // Idempotent: running it again changes nothing.
        assertThat(redactor.redact(clean)).isEqualTo(clean);

        // And the detection patterns find nothing left — the invariant the
        // leak guard test asserts over every serialized trace payload.
        for (Pattern p : SecretRedactor.patterns()) {
            assertThat(p.matcher(clean).find())
                    .as("pattern %s must not match redacted text: %s", p, clean)
                    .isFalse();
        }
    }
}

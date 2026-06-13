package com.aisre.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed view of the {@code foundry.*} settings in application.yml.
 *
 * Binding config to a small record (instead of sprinkling {@code @Value} strings
 * everywhere) means there is ONE place that describes what the app needs from
 * Foundry, and the rest of the code just reads these getters.
 *
 * NOTE on the "wire format" fields (chatPath, apiVersion, authHeader, authScheme):
 * these describe HOW we talk to the Foundry HTTP API. The defaults below follow
 * the common OpenAI-compatible Chat Completions shape. The EXACT values for your
 * resource must be confirmed on Microsoft Learn — that is exactly why they live in
 * config and not hardcoded in Java.
 *
 * @param enabled    master switch. FALSE = run the Phase-1 stub (no network).
 * @param endpoint   base URL of your Foundry resource/project.
 * @param model      the *deployment* name of the model you deployed in Foundry.
 * @param apiKey     secret, injected from the FOUNDRY_API_KEY env var only.
 * @param chatPath   path template appended to {@code endpoint} for a chat call.
 *                   {model} is substituted with {@link #model}. VERIFY ON LEARN.
 * @param apiVersion the {@code api-version} query parameter value. VERIFY ON LEARN.
 * @param authHeader the HTTP header used to send the credential. VERIFY ON LEARN
 *                   (common values: "api-key" for key auth, "Authorization" for tokens).
 * @param authScheme prefix put before the credential value, e.g. "Bearer " for
 *                   Authorization, or "" (empty) for an api-key header. VERIFY ON LEARN.
 * @param reasoningEffort for reasoning models (e.g. GPT-5.4): "low"/"medium"/"high".
 *                   Sent as the {@code reasoning_effort} body field. Leave blank to omit.
 * @param maxCompletionTokens cap on the reply size, sent as {@code max_completion_tokens}
 *                   (reasoning models reject the older {@code max_tokens}). <=0 omits it.
 * @param iq         nested Foundry IQ settings (the grounding layer).
 */
@ConfigurationProperties(prefix = "foundry")
public record FoundryProperties(
        boolean enabled,
        String endpoint,
        String model,
        String apiKey,
        String chatPath,
        String apiVersion,
        String authHeader,
        String authScheme,
        String reasoningEffort,
        int maxCompletionTokens,
        Iq iq
) {

    /**
     * Foundry IQ (the mandatory grounding integration) settings.
     *
     * @param enabled    FALSE = search_knowledge returns the canned stub snippets.
     * @param endpoint   the IQ retrieval endpoint (may differ from the model endpoint).
     * @param index      name of the IQ knowledge resource/index holding our runbooks+postmortems.
     * @param retrievePath path template for a retrieval call. VERIFY ON LEARN.
     * @param apiVersion the IQ api-version query parameter. VERIFY ON LEARN.
     * @param apiKey     IQ credential from env (FOUNDRY_IQ_API_KEY); if blank we reuse the model key.
     * @param topK       how many snippets to ask IQ to return.
     */
    public record Iq(
            boolean enabled,
            String endpoint,
            String index,
            String retrievePath,
            String apiVersion,
            String apiKey,
            int topK
    ) {
    }
}

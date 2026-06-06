package com.aisre.agent.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * The structured final answer we ask the model to emit (as JSON) when it is done.
 *
 * Instead of letting the model end with free prose, the system prompt tells it to
 * finish with a JSON object having exactly these fields. We parse that JSON into
 * this record, then copy it into the public {@code TriageResult}. This keeps the
 * model's grounded conclusion in a predictable shape.
 *
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} makes parsing tolerant: if
 * the model adds an extra field, we ignore it instead of failing.
 *
 * @param classification      the failure type.
 * @param rootCauseHypothesis the cause the agent settled on (after testing/discarding).
 * @param citedSources        the IQ source ids/paths that support the conclusion.
 * @param proposedFix         the drafted fix text (from the draft_fix tool).
 * @param postmortem          short write-up that cites the supporting sources.
 * @param confidence          the model's 0.0-1.0 confidence in the conclusion.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ModelConclusion(
        String classification,
        String rootCauseHypothesis,
        List<String> citedSources,
        String proposedFix,
        String postmortem,
        double confidence
) {
}

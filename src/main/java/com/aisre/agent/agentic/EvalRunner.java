package com.aisre.agent.agentic;

import com.aisre.agent.ai.FoundryModelClient;
import com.aisre.agent.config.FoundryProperties;
import com.aisre.agent.model.IncidentRequest;
import com.aisre.agent.model.TriageResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The evaluation harness: runs every incident in eval/incidents.json through the
 * SAME pipeline the /triage endpoint uses, scores the outcome against the
 * expectations, and emits a markdown results table (console + docs/eval-results.md).
 *
 * Activated ONLY by --eval.enabled=true (it needs live model + IQ credentials):
 *   mvn spring-boot:run "-Dspring-boot.run.arguments=--eval.enabled=true"
 *
 * Scoring is HONEST against whatever Foundry IQ actually returns: incidents whose
 * knowledge is not indexed are expected to ground poorly and escalate — and that
 * counts as correct behaviour for the expected-ESCALATE incidents.
 *
 * Scores per incident (consuming structured data, never trace summary text):
 *  - decision:   expected RECOMMEND_REMEDIATION or ESCALATE (either escalation type).
 *  - root cause: ALL expected keywords appear in the selected hypothesis /
 *                postmortem / rationale (scored only where keywords are defined).
 *  - citation:   ANY expected source name appears in citedSources (ditto).
 *  - rejections: total hypotheses the Critic rated REJECTED, from CRITIQUE payloads.
 */
@Component
@ConditionalOnProperty(name = "eval.enabled", havingValue = "true")
public class EvalRunner implements CommandLineRunner {

    private final AgentOrchestrator orchestrator;
    private final ObjectMapper json;
    private final FoundryProperties foundry;
    // The CONCRETE client, on purpose: the eval reads its invocation counter to
    // report actual model calls per incident (retry rounds included).
    private final FoundryModelClient modelClient;
    private final ConfigurableApplicationContext context;

    public EvalRunner(AgentOrchestrator orchestrator, ObjectMapper json,
                      FoundryProperties foundry, FoundryModelClient modelClient,
                      ConfigurableApplicationContext context) {
        this.orchestrator = orchestrator;
        this.json = json;
        this.foundry = foundry;
        this.modelClient = modelClient;
        this.context = context;
    }

    @Override
    public void run(String... args) throws Exception {
        JsonNode incidents = json.readTree(new ClassPathResource("eval/incidents.json").getInputStream())
                .path("incidents");

        List<Row> rows = new ArrayList<>();
        int n = 0;
        for (JsonNode incident : incidents) {
            n++;
            String id = incident.path("id").asText();
            System.out.printf("%n[eval] (%d/%d) running %s — %s%n",
                    n, incidents.size(), id, incident.path("name").asText());
            rows.add(runOne(incident));
        }

        String markdown = toMarkdown(rows);
        System.out.println("\n" + markdown);
        Path out = Path.of("docs", "eval-results.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, markdown);
        System.out.println("[eval] results written to " + out.toAbsolutePath());

        context.close(); // eval is a one-shot run, not a server
    }

    private Row runOne(JsonNode incident) {
        String id = incident.path("id").asText();
        String name = incident.path("name").asText();
        JsonNode expected = incident.path("expected");
        String expectedDecision = expected.path("decision").asText();

        // Structured measurements: wall-clock + actual model invocations (diffed
        // around the run, so retry rounds are naturally included).
        long callsBefore = modelClient.invocationCount();
        long startNanos = System.nanoTime();

        try {
            IncidentContext ctx = orchestrator.run(new IncidentRequest(
                    incident.path("service").asText(), incident.path("stackTrace").asText()));
            double seconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;
            long modelCalls = modelClient.invocationCount() - callsBefore;
            TriageResult result = TriageResult.from(ctx);

            List<String> keywords = toStrings(expected.path("rootCauseKeywords"));
            List<String> citationAnyOf = toStrings(expected.path("citationAnyOf"));

            return new Row(id, name, expectedDecision, result.status(),
                    decisionPass(expectedDecision, ctx.decision().type()),
                    keywords.isEmpty() ? null : keywordsPass(keywords, searchableText(ctx, result)),
                    citationAnyOf.isEmpty() ? null : citationPass(citationAnyOf, result.citedSources()),
                    countCriticRejections(ctx.trace().events()),
                    seconds, modelCalls,
                    null);
        } catch (Exception e) {
            double seconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;
            long modelCalls = modelClient.invocationCount() - callsBefore;
            // One failing incident must not sink the whole eval; record it honestly.
            return new Row(id, name, expectedDecision, "ERROR",
                    false, null, null, 0, seconds, modelCalls,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // ----------------------------------------------------------------------
    // Scoring (static + package-private so it is unit-testable without keys)
    // ----------------------------------------------------------------------

    /** Expected RECOMMEND_REMEDIATION matches exactly; ESCALATE accepts either escalation type. */
    static boolean decisionPass(String expected, DecisionType actual) {
        if ("RECOMMEND_REMEDIATION".equals(expected)) {
            return actual == DecisionType.RECOMMEND_REMEDIATION;
        }
        return actual == DecisionType.ESCALATE_TO_HUMAN || actual == DecisionType.INSUFFICIENT_EVIDENCE;
    }

    /** ALL keywords must appear, case-insensitive, in the searchable conclusion text. */
    static boolean keywordsPass(List<String> keywords, String text) {
        String haystack = text.toLowerCase(Locale.ROOT);
        return keywords.stream().allMatch(k -> haystack.contains(k.toLowerCase(Locale.ROOT)));
    }

    /** ANY expected source name must appear among the cited sources. */
    static boolean citationPass(List<String> anyOf, List<String> citedSources) {
        return anyOf.stream().anyMatch(citedSources::contains);
    }

    /** Count REJECTED verdicts across every CRITIQUE event's structured payload. */
    static int countCriticRejections(List<TraceEvent> events) {
        int count = 0;
        for (TraceEvent e : events) {
            if (e.type() != TraceEventType.CRITIQUE) {
                continue;
            }
            Object critiques = e.payload().get("critiques");
            if (critiques instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Critique c && c.status() == CritiqueStatus.REJECTED) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    /** Where the expected root-cause keywords may legitimately appear. */
    static String searchableText(IncidentContext ctx, TriageResult result) {
        String selectedStatement = ctx.decision().selectedHypothesisId() == null ? ""
                : ctx.findHypothesis(ctx.decision().selectedHypothesisId())
                      .map(Hypothesis::statement).orElse("");
        return selectedStatement + "\n" + result.postmortem() + "\n" + ctx.decision().rationale();
    }

    // ----------------------------------------------------------------------
    // Reporting
    // ----------------------------------------------------------------------

    /**
     * One scored incident. Boolean wrappers: null = not applicable for this incident.
     * latencySeconds is wall-clock for the whole pipeline run; modelCalls is the
     * actual FoundryModelClient invocation count (retry rounds included).
     */
    record Row(String id, String name, String expectedDecision, String actualStatus,
               boolean decisionPass, Boolean rootCausePass, Boolean citationPass,
               int criticRejections, double latencySeconds, long modelCalls, String error) {
    }

    String toMarkdown(List<Row> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Evaluation results\n\n");
        sb.append("Run: ").append(Instant.now()).append(" · model: ").append(foundry.model())
          .append(" · Foundry IQ: ").append(foundry.iq().enabled()
                  ? "live (" + foundry.iq().index() + ")" : "OFF (canned fallback)")
          .append("\n\n");
        sb.append("| ID | Incident | Expected | Actual | Decision | Root cause | Citation | Latency (s) | Model calls | Critic rejections |\n");
        sb.append("|----|----------|----------|--------|----------|------------|----------|-------------|-------------|-------------------|\n");

        int decisionPasses = 0;
        int rootCauseScored = 0;
        int rootCausePasses = 0;
        int citationScored = 0;
        int citationPasses = 0;
        int escalationExpected = 0;
        int escalationPasses = 0;
        int totalRejections = 0;
        double totalSeconds = 0;
        long totalModelCalls = 0;

        for (Row r : rows) {
            if (r.decisionPass()) {
                decisionPasses++;
            }
            if (r.rootCausePass() != null) {
                rootCauseScored++;
                if (r.rootCausePass()) {
                    rootCausePasses++;
                }
            }
            if (r.citationPass() != null) {
                citationScored++;
                if (r.citationPass()) {
                    citationPasses++;
                }
            }
            if ("ESCALATE".equals(r.expectedDecision())) {
                escalationExpected++;
                if (r.decisionPass()) {
                    escalationPasses++;
                }
            }
            totalRejections += r.criticRejections();
            totalSeconds += r.latencySeconds();
            totalModelCalls += r.modelCalls();

            sb.append("| ").append(r.id())
              .append(" | ").append(r.error() == null ? r.name() : r.name() + " ⚠ " + r.error())
              .append(" | ").append(r.expectedDecision())
              .append(" | ").append(r.actualStatus())
              .append(" | ").append(mark(r.decisionPass()))
              .append(" | ").append(mark(r.rootCausePass()))
              .append(" | ").append(mark(r.citationPass()))
              .append(" | ").append(String.format(Locale.ROOT, "%.1f", r.latencySeconds()))
              .append(" | ").append(r.modelCalls())
              .append(" | ").append(r.criticRejections())
              .append(" |\n");
        }

        sb.append("\n**Totals:** decision ").append(decisionPasses).append('/').append(rows.size())
          .append(" · root-cause ").append(rootCausePasses).append('/').append(rootCauseScored)
          .append(" · citation ").append(citationPasses).append('/').append(citationScored)
          .append(" · escalation ").append(escalationPasses).append('/').append(escalationExpected)
          .append(" · total hypotheses rejected by Critic: ").append(totalRejections).append('\n');
        if (!rows.isEmpty()) {
            sb.append(String.format(Locale.ROOT,
                    "%n**Average latency: %.1f s · Average model calls: %.1f** (per incident; "
                            + "model calls include retry rounds; Foundry IQ adds 1 retrieval per incident)%n",
                    totalSeconds / rows.size(), (double) totalModelCalls / rows.size()));
        }
        return sb.toString();
    }

    private static String mark(Boolean pass) {
        return pass == null ? "—" : (pass ? "✅" : "❌");
    }

    private List<String> toStrings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }
}

package com.aisre.agent;

import com.aisre.agent.model.IncidentRequest;
import com.aisre.agent.model.TriageResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The REST front door of the agent.
 *
 * {@code @RestController} means each method returns data (serialized to JSON),
 * not an HTML page. Spring converts the incoming JSON body into an
 * {@link IncidentRequest} and the returned {@link TriageResult} back into JSON.
 */
@RestController
public class TriageController {

    private final ReasoningLoop reasoningLoop;

    public TriageController(ReasoningLoop reasoningLoop) {
        this.reasoningLoop = reasoningLoop;
    }

    /**
     * Submit an incident for triage.
     *
     * POST /triage  with JSON body: { "service": "...", "stackTrace": "..." }
     * Returns the agent's triage result (Phase 1: a hardcoded result).
     */
    @PostMapping("/triage")
    public TriageResult triage(@RequestBody IncidentRequest incident) {
        return reasoningLoop.triage(incident);
    }

    /** Simple liveness check so you can confirm the app is up: GET /health. */
    @GetMapping("/health")
    public String health() {
        return "ai-sre-agent is up (Phase 1 stub)";
    }
}

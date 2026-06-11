package com.aisre.agent;

import com.aisre.agent.agentic.AgentOrchestrator;
import com.aisre.agent.agentic.IncidentContext;
import com.aisre.agent.model.IncidentRequest;
import com.aisre.agent.model.TriageResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The REST front door of the agent.
 *
 * Request flow: Controller -> AgentOrchestrator (deterministic Planner-Executor)
 * -> the five agent services -> TriageResult response. The controller itself only
 * translates between HTTP and the pipeline.
 */
@RestController
public class TriageController {

    private final AgentOrchestrator orchestrator;

    public TriageController(AgentOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    /**
     * Submit an incident for triage.
     *
     * POST /triage  with JSON body: { "service": "...", "stackTrace": "..." }
     * Returns the multi-agent triage result: the preserved summary fields plus the
     * structured evidence/hypotheses/critiques/decision/trace sections.
     */
    @PostMapping("/triage")
    public TriageResult triage(@RequestBody IncidentRequest incident) {
        IncidentContext ctx = orchestrator.run(incident);
        return TriageResult.from(ctx);
    }

    /** Simple liveness check so you can confirm the app is up: GET /health. */
    @GetMapping("/health")
    public String health() {
        return "ai-sre-agent is up (multi-agent pipeline)";
    }
}

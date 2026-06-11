package com.aisre.agent.agentic;

/**
 * The uniform shape of all five pipeline agents (role-based specialization).
 *
 * Each agent reads what it needs from the shared incident context, does ONE
 * focused job (usually a single model call with its own system prompt), writes its
 * contribution back, and records trace events as it goes.
 *
 * WRITE DISCIPLINE: {@code V} is the agent's narrowed view of the context — reads
 * plus ONLY its own write method (e.g. TriageAgent is {@code Agent<TriageView>}).
 * An agent that tries to write a foreign section does not compile. The orchestrator
 * holds the full {@link IncidentContext} (which implements every view) and passes
 * it in; the type system narrows what each agent can do with it.
 *
 * The orchestrator calls agents in an explicit, deterministic order — agents never
 * call each other, and model output never decides which agent runs next.
 */
public interface Agent<V extends ContextView> {

    /** The agent's name as it appears in trace events, e.g. "TriageAgent". */
    String name();

    /** Do this agent's one job: read from the view, write its own section + trace. */
    void execute(V context);
}

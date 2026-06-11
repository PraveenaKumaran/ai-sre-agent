package com.aisre.agent.agentic;

import java.util.List;

/**
 * The RootCauseAgent's view: read everything, write ONLY the hypothesis set.
 * Also exposes hypothesis id allocation — handing out a fresh H-id is bookkeeping
 * for this agent's own section, not mutation of someone else's.
 */
public interface RootCauseView extends ContextView {

    void setHypotheses(List<Hypothesis> items);

    String nextHypothesisId();
}

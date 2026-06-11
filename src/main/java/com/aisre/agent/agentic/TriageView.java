package com.aisre.agent.agentic;

import java.util.List;

/** The TriageAgent's view: read everything, write ONLY evidence. */
public interface TriageView extends ContextView {

    void addEvidence(List<Evidence> items);
}

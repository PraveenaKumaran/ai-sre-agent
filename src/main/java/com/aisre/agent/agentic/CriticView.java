package com.aisre.agent.agentic;

import java.util.List;

/** The CriticAgent's view: read everything, write ONLY critiques. */
public interface CriticView extends ContextView {

    void setCritiques(List<Critique> items);
}

package com.aisre.agent.agentic;

import java.util.List;

/** The KnowledgeAgent's view: read everything, write ONLY citations. */
public interface KnowledgeView extends ContextView {

    void addCitations(List<Citation> items);
}

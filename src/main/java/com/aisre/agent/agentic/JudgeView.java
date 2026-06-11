package com.aisre.agent.agentic;

/** The JudgeAgent's view: read everything, write ONLY the decision. */
public interface JudgeView extends ContextView {

    void setDecision(Decision decision);
}

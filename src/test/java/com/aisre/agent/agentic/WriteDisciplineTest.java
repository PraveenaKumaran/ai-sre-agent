package com.aisre.agent.agentic;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WRITE DISCIPLINE guard.
 *
 * The enforcement itself is compile-time: TriageAgent's execute() takes a
 * TriageView, and TriageView simply has no setDecision/setCritiques/... method, so
 * a "misbehaving agent" that writes a foreign section DOES NOT COMPILE. You cannot
 * write that agent in this test for exactly that reason.
 *
 * What a runtime test CAN do is pin the structure so a future refactor can't
 * quietly widen a view: for every view interface we assert that, of all the
 * context mutators, it exposes EXACTLY its own — and that the orchestrator-only
 * workflow state (retry count, rejection feedback, redacted-signal setters,
 * decision read-back) is on NO view at all.
 */
class WriteDisciplineTest {

    /** Every section/workflow mutator that exists on the concrete IncidentContext. */
    private static final Set<String> ALL_MUTATORS = Set.of(
            "addEvidence", "addCitations", "setHypotheses", "setCritiques", "setDecision",
            "nextHypothesisId",
            // orchestrator-only workflow state:
            "setRedactedLogs", "setRedactedMetrics", "setLastRejections", "incrementRetryCount");

    /** What each view is allowed to expose. */
    private static final Map<Class<?>, Set<String>> ALLOWED = Map.of(
            ContextView.class, Set.of(),
            TriageView.class, Set.of("addEvidence"),
            KnowledgeView.class, Set.of("addCitations"),
            RootCauseView.class, Set.of("setHypotheses", "nextHypothesisId"),
            CriticView.class, Set.of("setCritiques"),
            JudgeView.class, Set.of("setDecision"));

    @Test
    void eachViewExposesExactlyItsOwnMutatorAndNothingElse() {
        for (Map.Entry<Class<?>, Set<String>> entry : ALLOWED.entrySet()) {
            Class<?> view = entry.getKey();
            // getMethods() includes everything inherited from ContextView.
            Set<String> exposedMutators = Arrays.stream(view.getMethods())
                    .map(Method::getName)
                    .filter(ALL_MUTATORS::contains)
                    .collect(Collectors.toSet());

            assertThat(exposedMutators)
                    .as("mutators exposed by %s", view.getSimpleName())
                    .isEqualTo(entry.getValue());
        }
    }

    @Test
    void orchestratorOnlyStateIsOnNoView() {
        Set<String> orchestratorOnly = Set.of(
                "setRedactedLogs", "setRedactedMetrics", "setLastRejections",
                "incrementRetryCount", "decision", "hasSupportedHypothesis");

        for (Class<?> view : ALLOWED.keySet()) {
            Set<String> exposed = Arrays.stream(view.getMethods())
                    .map(Method::getName)
                    .collect(Collectors.toSet());
            assertThat(exposed)
                    .as("%s must not expose orchestrator-only state", view.getSimpleName())
                    .doesNotContainAnyElementsOf(orchestratorOnly);
        }
    }

    @Test
    void agentsAreDeclaredAgainstTheirOwnViewOnly() {
        // Each agent's execute() parameter IS its narrow view — the compile-time guarantee,
        // pinned here so a refactor back to the full context would fail this test.
        assertThat(executeParamType(TriageAgent.class)).isEqualTo(TriageView.class);
        assertThat(executeParamType(KnowledgeAgent.class)).isEqualTo(KnowledgeView.class);
        assertThat(executeParamType(RootCauseAgent.class)).isEqualTo(RootCauseView.class);
        assertThat(executeParamType(CriticAgent.class)).isEqualTo(CriticView.class);
        assertThat(executeParamType(JudgeAgent.class)).isEqualTo(JudgeView.class);
    }

    private Class<?> executeParamType(Class<?> agentClass) {
        // The declared (non-bridge) execute method's single parameter type.
        List<Method> executes = Arrays.stream(agentClass.getDeclaredMethods())
                .filter(m -> m.getName().equals("execute") && !m.isBridge())
                .toList();
        assertThat(executes).as("declared execute methods of %s", agentClass.getSimpleName()).hasSize(1);
        return executes.get(0).getParameterTypes()[0];
    }
}

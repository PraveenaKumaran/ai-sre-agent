# CONTEXT TRANSFER DOCUMENT — AI SRE Agent / Microsoft Agents League Hackathon

> For a new Claude session with zero memory of the prior conversation. Read fully before advising.
> Companion docs that may also be pasted: PROJECT.md (original spec, now partially superseded) and DELTA_SUMMARY.md (mid-project handoff, also partially superseded — this document is the most current).

---

## 1. Executive Summary

The user (Praveena Kumaran, software engineer in Coimbatore, India; Java/Spring Boot/DevOps background; currently interning at EPAM with a final interview coming up; learning agentic AI) is competing in the **Microsoft Agents League hackathon @ AI Skills Fest** (June 4–14, 2026), **Reasoning Agents track** (Microsoft Foundry). The project doubles as an interview portfolio piece, so the user wants to *understand* everything built, not just receive code. They build with **Claude Code in VS Code** (Opus 4.8), with this chat acting as advisor/reviewer; the working pattern is: this chat writes prompts → user pastes them to Claude Code → Claude Code plans, pauses for approval, builds, explains in plain English.

**The project:** an "AI SRE" — an incident-triage agent that takes a production incident (stack trace + logs + metrics), reasons to a root cause **grounded in Foundry IQ citations**, drafts a fix and postmortem, and hard-stops at a human approval gate.

**Where things stand (June 8, 2026):** Phases 1–2 are complete, committed, and pushed. The agent runs live end-to-end with real gpt-5-mini driving tool-calling and **real Foundry IQ retrieval with readable citations**. A major mid-course discovery: the track **requires a multi-agent system**, and the current build is single-agent. The final, locked decision is to refactor into a **5-agent architecture** (Triage, Knowledge, RootCause, Critic, Judge) with a deterministic Java orchestrator, folding the planned Phase 3 (trace/safety) into the refactor. The exact refactor prompt has been written and is ready/just sent to Claude Code. Hard deadlines: **registration June 12, 12:00 PM PT; submission June 14, 11:59 PM PT.**

---

## 2. Problem We Are Solving

Two problems, layered:

**Domain problem (the demo story):** when production breaks, an engineer reads logs, correlates metrics, recalls runbooks/past incidents, forms a root-cause theory, tests it, and only then drafts a fix. The agent automates this loop while keeping a human in control.

**Meta problem (the actual goal):** win/place in the Reasoning track and produce an interview-grade portfolio project. Judging rubric (per official rules): Accuracy & Relevance 20%, Reasoning & Multi-step Thinking 20%, Creativity & Originality 15%, UX & Presentation 15%, Reliability & Safety 20%, Community Discord vote 10%. (The track starter kit shows a slightly different 25/25/15/15/20 split with no community vote line — treat reasoning+accuracy as ~45–50% combined either way.) Prizes: $16,468 best overall; $6,468 per track; $6,468 "Best use of IQ tools"; student awards exist (user may qualify if enrolled — unconfirmed).

**Mandatory contest constraints discovered along the way:**
- Must integrate **at least one Microsoft IQ layer** (Foundry IQ / Work IQ / Fabric IQ) — satisfied via Foundry IQ.
- Must be a **multi-agent system** (Reasoning-track submission requirement) — NOT yet satisfied; this drives the current refactor.
- **Synthetic data only**, no PII/credentials/confidential info; public GitHub repo; ≤5-min demo video on YouTube/Vimeo (own work, no third-party copyrighted material); project description; architecture diagram; max 3 entries/person/challenge; CLA acceptance.
- The track's "enterprise learning/certification" scenario was investigated and determined **non-mandatory** (see §6).

---

## 3. Architecture

### Current (as built, Phases 1–2) — single-agent, working live

- **Spring Boot 3.3.5 / Java 17 / Maven** REST service. `POST /triage` accepts `{service, stackTrace}`; `GET /health`.
- **ReasoningLoop (orchestrator):** owns the loop; sends full conversation + 5 tool definitions to the model each turn; executes the tool the model picks; feeds result back; hard cap 6 iterations → `ESCALATED_ITERATION_CAP`. Graceful degrade: malformed final JSON → low-confidence `UNPARSED` result instead of crash.
- **FoundryModelClient:** calls **Microsoft Foundry OpenAI v1 endpoint** — `https://ai-sre-resource-praveen.openai.azure.com/openai/v1/chat/completions` (fallback host `...services.ai.azure.com/openai/v1` if needed), header `api-key: ${FOUNDRY_API_KEY}`, model **in the body** (`"model": "gpt-5-mini"`), **no api-version param on chat**. gpt-5-mini is a **reasoning model**: `temperature` is rejected; uses `reasoning_effort: "low"` and `max_completion_tokens` (not `max_tokens`). `tool_choice: "auto"`.
- **FoundryIqClient:** calls **Azure AI Search agentic-retrieval** ("knowledge base retrieve") API with header key from `FOUNDRY_IQ_API_KEY` (separate key). **api-version `2026-05-01-preview`** (critical — see §5). Knowledge base name: **`ai-sre-kb`** (KB display also appeared as `knowledgebase969` in provenance). Parser maps preview response: `references[].docName` (preview) or `docKey` (GA) → readable citation id; grounding text at `response[0].content[0].text` (JSON-encoded string parsed again); snippets rendered as `[SOURCE: <docName> | <kb>]` + text; regex `\[SOURCE:\s*([^|\]]+)` extracts cited ids.
- **5 tools** (Java methods exposed as functions): `get_logs(service, time_window)`, `get_metrics(service, metric)`, `search_knowledge(query)` (→ Foundry IQ), `read_code(file_path)`, `draft_fix(file_path, change)` (**drafts only; never merges/pushes**).
- **System prompt** (in `system-prompt.txt`): SRE role; never acts autonomously; MUST call search_knowledge before concluding; hypothesize→test→discard-if-contradicted; escalate instead of guess when ambiguous; cite sources, never invent citations; reply with ONLY a fixed JSON shape `{classification, rootCauseHypothesis, citedSources, proposedFix, postmortem, confidence}`; never claim to have applied/merged/deployed anything.
- **Synthetic scenario 1** (committed): order-service NullPointerException — legacy-imported customers have `loyaltyTier=null`; deploy at 09:13 added `tier.toUpperCase()` at OrderService.java:42; logs/metrics/buggy code + two knowledge docs (`runbook-null-pointer.md` = RB-NPE-001, `postmortem-2025-11-order-service-npe.md` = PM-2025-11-ORDER). A deliberately planted fake secret `apiKey=EXAMPLE_FAKE_KEY_DO_NOT_USE` sits in the log as a Phase-3 redaction target.
- **Config:** `application.yml` holds endpoints/model/KB name and flags `foundry.enabled` / `foundry.iq.enabled` (both now true); keys ONLY from env vars; feature flags preserve a fully working Phase-1 stub fallback.
- **Response contract** (`TriageResult`) was final-shaped from Phase 1: incidentId, service, classification, rootCauseHypothesis, citedSources, proposedFix, postmortem, confidence, reasoningSteps, status (`AWAITING_APPROVAL` / `ESCALATED_*`), phaseNote.

### Target (locked, being built now) — 5-agent multi-agent system

Deterministic Java **orchestrator** (Planner-Executor; intentionally NOT an LLM router) passes one shared **`IncidentContext`** (evidence, citations, hypotheses w/ provenance, critiques, decision, plan, escalation flag) through:

1. **TriageAgent** — raw logs/metrics/stack trace → structured Evidence items **each with an ID (E1, E2, …)**: symptoms + timeline. No diagnosis.
2. **KnowledgeAgent** — Foundry IQ retrieval; cited snippets **each with citation IDs (C1, C2, …)**; explicitly says so if nothing relevant. Never guesses.
3. **RootCauseAgent** — **top 2–3 competing hypotheses with confidence scores**; every hypothesis lists `supportingEvidenceIds` + `supportingCitationIds` (provenance). **On retry it RECEIVES rejected hypotheses + rejection reasons** and must not regenerate the same theory without new evidence (true self-reflection).
4. **CriticAgent** — adversarial "Principal SRE": tries to **disprove** each hypothesis using ONLY evidence + citations; outputs supported true/false + issues per hypothesis, referencing specific E/C ids.
5. **JudgeAgent** — selects using strict priority: (1) Critic support status, (2) evidence coverage, (3) citation support, (4) confidence — **confidence alone can never justify selection**. Output exactly one of: `RECOMMEND_REMEDIATION` (draft fix + postmortem → hard stop at human approval), `ESCALATE_TO_HUMAN`, `INSUFFICIENT_EVIDENCE`. Never invents facts. The string "auto-remediate" must not exist anywhere.

**Retry loop:** Critic rejects ALL → back to RootCauseAgent with rejections, max 2 retries → Judge regardless. This makes discard-and-retry **structural** (guaranteed visible), not luck.

**Folded-in Phase 3:** structured glass-box **TraceEvents** at every agent handoff and hypothesis kill (snapshot IncidentContext deltas) so a reader can follow hypothesis → E ids → C ids → critic verdict; **secret redaction** before any data reaches a model; approval-gate hardening + guard tests; patterns named in code/docs: **Planner-Executor, Critic-Verifier, self-reflection retry, role-based specialization**.

**Evaluation harness:** ~10 synthetic incidents in `incidents.json` with expected root causes (1–2 designed to escalate); runner scores correct-root-cause, correct-citation, correct-escalation, **and total hypotheses-rejected-by-critic**; emits a README results table.

**Build order (locked):** (1) models — IncidentContext, Evidence, Hypothesis, Critique, Decision, TraceEvent → (2) refactor loop into 5 agent services → (3) RootCause↔Critic retry with rejection feedback → (4) TraceEvents → (5) approval-gate guard tests → (6) incidents.json → (7) eval runner + results table.

---

## 4. Components and Responsibilities (repo map)

Repo: **github.com/PraveenaKumaran/ai-sre-agent** (public, MIT, README, PROJECT.md, .gitignore — note: was initially committed as `gitignore` without the dot and fixed). Commit history on main: `e60f91c` Phase 1 scaffold → `ea674a2` Phase 2 loop+IQ plumbing → `b70962c` live gpt-5-mini wiring → `b4439f1` Mermaid architecture diagram (`docs/architecture.md`, kept accurate: v1 endpoint, api-key auth, reasoning_effort, flags true) → `47716dc` Foundry IQ live via preview api-version. Tests: 8/8 green pre-refactor. Key paths: `src/main/java/com/aisre/agent/` (TriageController, ReasoningLoop, ai/FoundryModelClient, ai/FoundryIqClient, tools/, model/), `src/main/resources/` (application.yml, system-prompt.txt, sample-incident/, knowledge/), `src/test/java/...`.

**Azure resources (user's personal account — praveenakumaran15@gmail.com, "Default Directory", Azure subscription 1, $200 free credit expiring ~July 6):**
- Foundry project **ai-sre** / resource **ai-sre-resource-praveen** (East US 2; first-choice name was taken — subdomain names are globally unique).
- Model deployment **gpt-5-mini** (Global Standard).
- Azure AI Search **ai-sre-search** (Central US, **Basic tier** — user initially had Standard selected; corrected to Basic pre-creation to save credit; ~$2–3/day, delete after hackathon).
- Knowledge base **ai-sre-kb** with the two scenario-1 docs uploaded via the portal's direct **File** upload.

**Secrets discipline:** `FOUNDRY_API_KEY` + `FOUNDRY_IQ_API_KEY` env vars only; never in files; never pasted into chat; PowerShell env vars are per-window (must set in the SAME window as `mvn spring-boot:run` — this caused a 401 once).

---

## 5. Important Decisions Made (with reasoning)

1. **Track: Reasoning Agents; domain: AI SRE incident triage.** Fits user's Java/DevOps strengths; visible multi-step reasoning matches the heaviest rubric weights; "AI SRE" is demo-friendly. A CVE-remediation agent was the named backup (rejected as less demo-dramatic).
2. **Keep orchestration in Spring Boot calling Foundry over HTTPS, not Python/Agent-Framework.** Reason: 8-day window, user's strength is Java; Foundry is framework-agnostic. Confidence high; tradeoff accepted: most track samples are Python.
3. **gpt-5-mini over gpt-4o-mini and gpt-5.5.** 4o-mini is a generation behind on reasoning; 5.5 is overkill for cost; the real argument: with $200 credit and dozens of test runs, optimize for reasoning quality per the rubric, not pennies. Model is config-swappable (`foundry.model`) if 5.5 is wanted later.
4. **API-key auth, not Entra ID.** Portal's Java samples push Entra/DefaultAzureCredential; rejected for now (extra CLI/SDK setup under deadline). Confirmed api-key works on /openai/v1. Entra noted as a credible "hardening" talking point.
5. **Foundry IQ as the (single) IQ layer.** Satisfies the mandatory requirement, strengthens Accuracy + Reliability scores, and qualifies for the separate "Best use of IQ tools" prize. **Deliberately NOT adding Work IQ/Fabric IQ** — scope discipline beats checkbox-collecting solo under deadline.
6. **api-version `2026-05-01-preview` for AI Search retrieve — and KEEP the `file` knowledge source.** The GA-ish version `2026-04-01` rejected knowledge sources of kind `file` ("supported: searchIndex, azureBlob, web, indexedOneLake") → 400. Original fix plan was migrating to Blob storage (Stages A–C, fully specced in DELTA_SUMMARY); user discovered the preview api-version accepts `file` directly. **Blob migration cancelled.** If the preview version ever breaks, Blob is the fallback plan.
7. **Readable citations via `references[].docName`.** Preview response returns numeric `ref_id` ("0","1") and `docName` instead of GA's `docKey`. Claude Code added temporary logging (never logging headers/keys), captured the real response, then mapped docName→sourceId so `citedSources` reads `["runbook-null-pointer.md", ...]` / `knowledgebase969:RB-NPE-001` style instead of `["0","1"]`. Parser supports both preview and GA fields. Verified live; committed.
8. **Verify each integration in isolation before building on it.** Explicit sequencing principle used twice: run live model before Phase 3; run live IQ before Phase 3. Caught the 401 and the kind-'file' 400 cheaply.
9. **Multi-agent refactor (5 agents) — the pivotal decision.** Trigger: user pasted the track starter kit, which states submissions "must implement a multi-agent system" and prescribes an enterprise-learning scenario. Verified against ~36 linked projects: domains are wildly varied (security, CI/CD, dispatch, transit, health, marketing…), so the scenario is a suggested theme — **keep SRE** (also: the learning/certification lane is the most saturated in the track). But multi-agent is the norm among all serious entries and a stated requirement — **must refactor**. Confidence: scenario-flexibility high (strong empirical evidence); multi-agent-required certainty: treat as hard requirement regardless.
10. **5 agents, not 7.** Initial sketch had 7 (Classifier and Investigator separate); collapsed into TriageAgent per external suggestion. Fewer prompts, crisper roles, same pattern coverage.
11. **Deterministic Java orchestrator, not an LLM router.** Cheaper, debuggable, still legitimately Planner-Executor.
12. **Four refinements adopted from a second external review:** (a) rejected hypotheses + reasons feed back into RootCauseAgent on retry — otherwise retries regenerate the same top-3 and self-reflection is fake; (b) Judge's strict priority order with confidence as tiebreaker only — otherwise a 90%-confident ungrounded theory could win, contradicting the grounding story; (c) hypothesis provenance (E/C id lists) — makes the trace a followable chain a judge can verify in seconds; (d) eval harness also counts hypotheses-rejected-by-critic — measures the differentiator (generate→challenge→discard) directly.
13. **Skip OpenTelemetry/Zipkin/Jaeger.** Suggested externally; rejected: a tracing backend costs a day; the custom structured TraceEvents prove the same thing more visibly. Micrometer annotations only if trivial.
14. **No "AUTO_REMEDIATE" anywhere, even as a label** — contradicts the safety narrative; Judge's best outcome is RECOMMEND_REMEDIATION behind the gate.
15. **Personal Azure/GitHub identity only.** User almost signed up under their EPAM work account (and once landed in the EPAM directory in the portal — fixed by switching to Default Directory). Reasons: portability of the portfolio, corporate tenant policy/IP entanglement, contest originality. Colleague (Ganesamoorthi, .NET/AWS lead) is welcome as an advisor, not as an account.
16. **Maven over Gradle; Java 17 over 21** (only JDK 17 installed; one-line upgrade path documented in pom.xml).
17. **Process/working agreement:** PROJECT.md as spec source-of-truth; phased builds with plan-pause-approve; Claude Code explains everything in plain English (interview prep); secret scan before every commit; tests green before advancing.

---

## 6. Alternative Approaches Considered (and rejected)

- **Pivot to the enterprise-learning scenario:** rejected after evidence-gathering (see Decision 9). Also strategically poor — most crowded lane (a dozen-plus certification/career projects among the 36 reviewed).
- **Other project ideas at kickoff:** CVE remediation agent (backup), generic chatbots — rejected for weaker demo/reasoning visibility.
- **Python / Microsoft Agent Framework / LangGraph / CrewAI / AutoGen:** rejected; plain Spring services + deterministic orchestration is the chosen idiom (also a differentiator among Python entries).
- **Blob-storage knowledge source:** fully planned (storage account + `knowledge` container + repoint KB), then cancelled when the preview api-version made direct `file` upload work. Retained as documented fallback.
- **Entra ID auth:** deferred (see Decision 4).
- **gpt-5.5 as primary model:** rejected for now (cost/scope); flag-swappable.
- **7-agent architecture; LLM-as-orchestrator; OpenTelemetry stack; extra IQ layers; hosted-agent deployment; fancy UI:** all rejected for scope (hosted agents/UI may be mentioned as future work in README).
- **Original "Phase 3 as planned" (single-agent trace/safety):** superseded — a complete Phase 3 plan existed (TraceEvent types; SecretRedactor; three escalation gates incl. ESCALATED_NO_GROUNDING and ESCALATED_LOW_CONFIDENCE at min-confidence 0.6; gate guard tests; a misleading payment-service scenario; a planned third ambiguous incident for live escalation). Its content survives inside the refactor; its single-agent structure does not. The payment-service scenario design remains gold for incidents.json: SocketTimeoutException calling bank-gateway looks like a downstream outage (bait), but bank-gateway p95 is healthy ~120ms while the service's own connection pool is pinned 5/5 after a 09:05 deploy cut maxConnections 50→5 (truth) — plus matching runbook/postmortem docs and a planted fake secret.

---

## 7. Critiques and Improvements Identified

- **Single clean scenario can't show discarding** — the order-service NPE converges immediately; competitive demos need visible hypothesis kills. Fixed structurally by multiple-competing-hypotheses + Critic (every run shows kills) and by misleading/ambiguous incidents in the eval set.
- **Model "thinking" between tool calls was being dropped** (assistant content alongside tool_calls discarded; ModelTurn captured only calls-or-final) — identified by Claude Code as the load-bearing fix for visible reasoning; superseded by the agent refactor where each agent's output IS the visible reasoning, but worth remembering if any single-loop pieces persist.
- **Confidence-led selection is a grounding contradiction** — fixed via Judge priority order.
- **Blind retries fake self-reflection** — fixed via rejection feedback into RootCauseAgent.
- **`citedSources: ["0","1"]` unreadable** — fixed via docName mapping.
- **Quality bar from the field (36 linked projects reviewed):** top tier (PathForward, OmniDispatch, CertPathAI, HELIOS) is formidable — named reasoning patterns, multiple IQ layers, telemetry, accessibility, hosted agents. Many others are thin pitches or have fake/"prepared" IQ (one literally says Foundry IQ is "awaiting provisioning"). The user's real, verified grounding + real approval gate + measured evaluation is genuinely competitive substance; the refactor supplies the missing multi-agent structure.

---

## 8. Current Status (as of June 8, 2026)

- ✅ Phase 1 (scaffold, stub tools, synthetic data, /triage pipeline) — done, committed.
- ✅ Phase 2 (real model loop) — gpt-5-mini live on /openai/v1, tool-calling verified end-to-end; ~18s/run; confidence 0.9 on scenario 1; gate holds (status AWAITING_APPROVAL).
- ✅ Foundry IQ — REAL retrieval verified (raw response inspected: full doc markdown, 1018 + 1227 chars), readable citations like `knowledgebase969:RB-NPE-001`, committed (`47716dc`). Working tree clean.
- ✅ Architecture diagram (Mermaid) committed and accurate to the live single-agent system.
- 🔄 **In flight:** the 5-agent refactor prompt (full content reflected in §3; the user has the exact text) — Claude Code is to respond with a plain-English plan and pause for approval. The new session's likely first job: review that plan against §3.
- ❓ **Unconfirmed:** whether the user has actually registered for the contest (repeatedly urged; deadline June 12 noon PT). ASK FIRST.
- Estimated effort: refactor ≈ 2–3 focused days → done ~June 11, leaving ~3 days for eval runs, demo video, description, submission.

---

## 9. Remaining Work

1. Approve/execute the 5-agent refactor (build order in §3). Keep the 8 existing tests green or consciously replace them.
2. incidents.json (~10 incidents: scenario-1 NPE; the payment-service misleading scenario; 1–2 ambiguous escalation cases; fill the rest with varied patterns — config regressions, cache failures, etc.).
3. Decide whether scenario-2/extra knowledge docs go into the IQ knowledge base (upload via portal `file` source, same as before) or rely on stub grounding for eval-only incidents — live-IQ for the demo scenarios is preferred.
4. Eval runner + README results table (root-cause acc., citation acc., escalation acc., hypotheses-rejected count).
5. README upgrade: agent architecture, named patterns, eval table, synthetic-data statement, how-to-run.
6. Demo video ≤5 min (own work; no third-party copyrighted material/music): suggested arc — incident in → agent handoffs in trace → hypothesis killed by Critic → grounded citations → Judge decision → approval gate; plus an escalation moment ("knows when not to answer").
7. Project description for the submission form; link video; update the Mermaid diagram post-refactor.
8. Submit well before June 14 23:59 PT (aim ~June 13). Up to 3 entries are allowed per challenge — one polished entry is the plan.
9. Post-hackathon: delete `ai-sre-search` (and any storage) to stop credit burn; Entra ID + hosted agents are credible "future work" README items.

---

## 10. Risks and Considerations

- **Registration not confirmed** — single biggest non-technical risk. June 12 noon PT. Ask about this immediately.
- **Refactor scope creep / time** — 5 prompts to tune, JSON contracts between agents to stabilize. Mitigate: locked build order, one step at a time, resist extras.
- **Latency & cost multiply** — 5+ LLM calls per incident, ×10-incident eval runs. Fine within $200 credit, but full eval runs will take minutes; keep `reasoning_effort: low`; don't loop evals carelessly.
- **Preview api-version dependency** (`2026-05-01-preview` + `file` knowledge source) could change behavior; Blob fallback documented.
- **PowerShell env-var-per-window gotcha** recurs; both keys must be set in the run window.
- **Public repo hygiene:** planted fake secrets must stay obviously fake (`EXAMPLE_FAKE_KEY_DO_NOT_USE` pattern); secret scan before every push (habit established); GitHub 2FA + secret scanning enabled.
- **Demo video copyright** (no music/IP) and the "own work" rule.
- **AI Search Basic tier billing** (~$2–3/day) until deleted.
- **User is learning agentic AI** — keep explanations plain-English; they must be able to defend every design choice in a job interview. Preserve the explain-as-you-go working agreement with Claude Code.

---

## 11. Recommended Next Steps (in order)

1. **Confirm registration status**; if not done, register immediately at aka.ms/agentsleague/aisf (Reasoning track; accept CLA when prompted).
2. Review Claude Code's 5-agent plan against §3 — checklist: rejection feedback into RootCauseAgent; Judge's strict priority order; E/C provenance ids on every hypothesis; retry cap (max 2); no "auto-remediate" string anywhere; deterministic Java orchestrator; TraceEvents at every handoff and hypothesis kill. Approve with corrections if needed.
3. Build steps 1–3 (models → agent services → retry loop) and run scenario 1 live through the new pipeline before continuing — same verify-in-isolation principle that caught earlier bugs cheaply.
4. TraceEvents + gate tests → incidents.json → eval runner.
5. Re-run live IQ demo scenarios; update the Mermaid diagram; README with the eval results table.
6. Record the demo video; write the description; submit by ~June 13 to leave buffer.

---

## 12. Context Needed For Continuation

- **People/setup:** user = Praveena Kumaran (machine user PraveenaKumaranR; GitHub PraveenaKumaran), Windows 11, VS Code + Claude Code (Opus 4.8, high effort), PowerShell, Postman (lightweight client), repo at `C:\Users\PraveenaKumaranR\Pictures\ai-sre-agent`. Personal Gmail-based Azure account; do NOT use EPAM identity for anything.
- **Live config cheat-sheet:** model endpoint `https://ai-sre-resource-praveen.openai.azure.com/openai/v1/chat/completions` (api-key header; model `gpt-5-mini` in body; `reasoning_effort: low`; `max_completion_tokens`; NO `temperature`). IQ: Azure AI Search `ai-sre-search` (Central US, Basic tier), knowledge base `ai-sre-kb`, api-version `2026-05-01-preview`, key in `FOUNDRY_IQ_API_KEY`. Flags `foundry.enabled: true`, `foundry.iq.enabled: true`; stub fallback still works with flags off.
- **Test commands:** set both env vars, then `mvn spring-boot:run` (same window); POST `http://localhost:8080/triage` with body `{"service":"order-service","stackTrace":"java.lang.NullPointerException at OrderService.java:42"}`; expect readable citations (e.g. `knowledgebase969:RB-NPE-001`) and status `AWAITING_APPROVAL`. Port-conflict fix if needed: `netstat -ano | findstr :8080` → `taskkill /PID <pid> /F`.
- **Key documents and their status:** PROJECT.md (original spec — single-agent sections superseded by §3 here); DELTA_SUMMARY.md (superseded where it lists the IQ kind-'file' issue as unresolved with Blob as the fix — actually RESOLVED via the preview api-version; no blob storage exists); `docs/architecture.md` Mermaid (accurate pre-refactor; needs post-refactor update).
- **Contest facts:** Microsoft Agents League @ AI Skills Fest; Reasoning Agents track; rules at aka.ms/AgentsLeagueRules; submission via the contest site "Projects" tab; required: public repo + README, project description, ≤5-min YouTube/Vimeo video, architecture diagram, ≥1 Microsoft IQ layer, multi-agent system, synthetic data only. Discord (aka.ms/agentsleague/discord) for support + community vote.
- **Tone/working style that worked:** review external suggestions critically — two external reviews materially improved the design (5-agent consolidation, competing hypotheses, provenance, Judge priority, rejection-feedback, eval metrics) while scope-expanding ideas (OpenTelemetry) were trimmed; write copy-paste-ready prompts for Claude Code; insist on plan-pause-approve; verify integrations in isolation; tie explanations back to the user's interview-prep goal; say the deadlines out loud.

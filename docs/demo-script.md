# AI SRE — Demo Playbook

> A **playbook**, not a read-aloud script. Use it to drive a confident ≤5-minute demo
> and to defend every design choice. All numbers/trace seq references are from the
> committed sample runs in `docs/sample-runs/` and the eval tables in `docs/`.

---

## ✈️ PRE-FLIGHT CHECKLIST (do every item before you hit record)

- [ ] **App running with BOTH keys in the SAME PowerShell window** (env vars are per-window):
      `$env:FOUNDRY_API_KEY=...`, `$env:FOUNDRY_IQ_API_KEY=...`, then `mvn spring-boot:run`.
- [ ] **Hit it once to warm up** before recording (first call is slowest). Confirm `GET /health` returns 200.
- [ ] **Network check**: confirm Azure reachable (one successful `/triage`). If flaky → skip the live run, go 100% saved JSONs.
- [ ] **Open these tabs/files in order**, ready to switch:
      `docs/sample-runs/eval05-payment-pool.json` (scenario B),
      `docs/sample-runs/eval-10-escalation-trace.json` (scenario C),
      `docs/eval-results-before1.md` + `docs/eval-results-after.md` (eval story),
      `README.md` (architecture diagram), `AgentOrchestrator.java`.
- [ ] **⚠️ Save a scenario-A trace as fallback** (there is no saved order-service NPE JSON yet):
      run EVAL-01 once and save the response to `docs/sample-runs/eval01-order-npe.json` so the
      opening hook has a backup if the live run fails on camera.
- [ ] **Font size up** for screen recording (editor ≥ 16pt; pretty-print JSON; collapse to fit).
- [ ] **JSON viewer ready** (browser/VS Code with folding) so you can expand sections live.
- [ ] **Close secrets**: no terminal showing key values; nothing with `FOUNDRY_*_API_KEY=` visible.
- [ ] **Recording rules**: **≤ 5:00 hard cap** (aim 4:00–4:30), **your own work** (filming/editing/graphics),
      **no copyrighted music or third-party trademarks**. Upload YouTube/Vimeo.

---

## 1. DEMO GOAL — what a judge should remember

One sentence to leave in their head: **"It reasons like an SRE, it's grounded in real cited knowledge, and it's engineered to refuse to guess."**

The **3 strongest differentiators** (say each at least once, on screen):

1. **Real grounded Foundry IQ with citations** — not "prepared"/mock retrieval. `search` hits Azure AI Search agentic retrieval live; `citedSources` are real document names (`runbook-connection-pool-exhaustion.md`), and every hypothesis carries the `C-id`s that back it. (Several competing entries fake this; ours is verifiable.)
2. **Four-layer human-approval gate + "model proposes, code verifies."** The agent never acts. A fix is only ever a *draft* behind `AWAITING_APPROVAL`, and deterministic Java guards can override the model (`GUARD_OVERRIDE`, `PROVENANCE_NORMALIZED`) — the LLM is never the last word on safety.
3. **An evaluation harness that CAUGHT the system guessing — and proved the fix.** 10 synthetic incidents, scored automatically. It exposed the agent recommending fixes with no/contradictory evidence (escalation 0/2), which drove a concrete fix to **2/2**. We test our own reasoning, honestly.

---

## 2. DEMO TIMELINE (target 4:00–4:30; hard cap 5:00)

**Principle: show it WORKING before explaining HOW.** Lead with a live run; architecture comes after the hook.

| Time | On screen | You do / say (one line) |
|------|-----------|--------------------------|
| **0:00–0:45** | PowerShell + the `/triage` POST for **Scenario A** (order NPE). Response lands. | "A production NullPointerException comes in. Watch." Fire it live; let the JSON return; scroll to `status: AWAITING_APPROVAL` + `proposedFix`. "Root cause, a drafted fix, cited sources — and it **stopped for human approval**." |
| **0:45–1:05** | Keep the response; highlight `citedSources` + `decision`. | "Three things make this different: it's grounded in **real** retrieved runbooks, it **never acts on its own**, and we have an eval harness that caught it guessing. Here's how." |
| **1:05–2:15** | `README.md` diagram → `AgentOrchestrator.run()`. | "**Five specialized agents**, a **deterministic Java orchestrator**. Triage → Knowledge → RootCause → **Critic** → Judge, with a retry loop." Point at the agent list + the retry `while` loop. (Code walkthrough §3 — keep to ~70s.) |
| **2:15–3:10** | **Scenario B** saved JSON (`eval05-payment-pool.json`). Expand `hypotheses` + `critiques`. | "A timeout that *looks* like a downstream outage. The RootCause agent proposes **two competing theories**. The **Critic rejects the bait** — citing the runbook." Show `H2: REJECTED`. |
| **3:10–3:55** | **Scenario C** saved JSON (`eval-10-escalation-trace.json`). Scroll the `trace`. | "Reported symptom contradicts the data. Watch it **discard, retry, discard again** — then **escalate instead of guessing**." Point at the two `RETRY_TRIGGERED` events and `ESCALATION`. |
| **3:55–4:25** | `eval-results-before1.md` beside `eval-results-after.md`. | "Our eval caught this exact failure — escalation **0 of 2**. After the fix: **2 of 2**, 10/10 overall. We measure our own reasoning." Close. |

If you must cut: drop the live hook to a saved Scenario-A JSON (still lead with the *result*), and trim the code walkthrough to orchestrator + Critic only.

---

## 3. CODE WALKTHROUGH ORDER (target ~70–80s total; this is a tour, not a lecture)

> Rule: **point, say ONE line, move.** Resist reading code. Budgets are generous-but-firm.

| # | File | Point at | Say (one line) | SKIP | ~sec |
|---|------|----------|----------------|------|------|
| 1 | `README.md` diagram | The 5-agent flow + IQ box | "Five roles, one deterministic orchestrator, grounded by Foundry IQ." | the prose under it | 10 |
| 2 | `AgentOrchestrator.run()` | the linear handoffs + the retry `while (!hasSupportedHypothesis() && retryCount < MAX_RETRIES)` | "The plan is **Java**, not an LLM router — reproducible and debuggable. The reasoning is distributed; the control flow is mine." | the stub path, redaction details | 15 |
| 3 | `IncidentContext` + `ContextView`/`TriageView`/… | the read-only base + per-agent write interfaces | "**Write-discipline**: each agent gets a view that can read everything but write only its own section — enforced by the compiler." | the field list | 8 |
| 4 | `TriageAgent` | the **two** extract calls (report vs observability) | "Provenance is **code-assigned**, never model-guessed — which channel produced each fact." | the parse loop | 6 |
| 5 | `KnowledgeAgent` | `iq.retrieve(...)` + "zero citations if nothing relevant" | "Grounding via real Foundry IQ; if nothing's relevant it says so — it doesn't invent." | the fallback reader | 6 |
| 6 | `RootCauseAgent` | `supportingEvidenceIds` / `supportingCitationIds`; the rejection-feedback block | "Competing hypotheses, each with **provenance**. On retry it's shown what was rejected and why." | JSON parsing | 6 |
| 7 | `CriticAgent` | the structured `{status, reasons[]}`; "absence of contradiction is NOT support" | "An **adversarial** Principal-SRE. Every verdict cites E/C ids. Fail-safe: malformed → WEAK." | render method | 8 |
| 8 | `JudgeAgent` | the strict priority order; degrade → ESCALATE | "Selects on Critic status first, **confidence last** — confidence alone can never win." | input rendering | 6 |
| 9 | `SecretRedactor` | the boundary call in the orchestrator | "Secrets are redacted **once, at the boundary** — the model can't leak what it never sees." | the regex list | 5 |
| 10 | Guards in `AgentOrchestrator` | `enforceEvidenceGuard`, `enforceReportedSymptomGuard`, `normalizeCitationProvenance` | "**Model proposes, code verifies**: deterministic guards can override the Judge and re-write bad citations — and each leaves a trace event." | the field-resolution details | 10 |
| 11 | `EvalRunner` | the scoring fields + totals line | "10 incidents scored automatically — including how many hypotheses the Critic killed." | markdown builder | 6 |

---

## 4. LIVE DEMO ORDER (saved JSONs are the primary path)

> Saved runs are reliable on camera. Run **at most one** live (the opening hook). Everything else: open the committed JSON.

### Scenario A — order-service NPE (clean RECOMMEND) — *the live hook*
- **Request:**
  ```json
  { "service": "order-service",
    "stackTrace": "java.lang.NullPointerException: Cannot invoke \"String.toUpperCase()\" because \"loyaltyTier\" is null\n  at com.shop.order.OrderService.applyLoyaltyDiscount(OrderService.java:42)" }
  ```
- **Show, in order:** `status` → `AWAITING_APPROVAL`; `decision.selectedHypothesisId` + `confidence`; `citedSources` (real doc names); `proposedFix` (a *draft*).
- **One line:** "Found it, drafted a fix, cited the runbook — and stopped at the gate."
- **Fallback:** if live fails, open the pre-saved `docs/sample-runs/eval01-order-npe.json` (see pre-flight) and present the *result* the same way.

### Scenario B — payment-service misleading timeout (competing hypotheses; Critic rejects the bait)
- **File:** `docs/sample-runs/eval05-payment-pool.json` (a gpt-5-mini run; the bait is dramatically **REJECTED**).
- **Request it represents:**
  ```json
  { "service": "payment-service",
    "stackTrace": "java.net.SocketTimeoutException: Read timed out\n  at com.shop.payment.BankGatewayClient.charge(BankGatewayClient.java:31)\nPayments to bank-gateway are timing out. Suspect bank-gateway outage." }
  ```
- **Show:** `hypotheses` → **H1** (pool exhaustion, conf 0.8) vs **H2** (bank-gateway latency, conf 0.3) — *competing*. Then `critiques`: **H1 SUPPORTED**, **H2 REJECTED**. Expand H2's reasons — it cites **E4** (gateway health-checks 200 OK ~115–121ms) and **C1** (the runbook: "if downstream p95 is normal, the downstream is NOT the problem").
- **Highlight trace by seq:** `9 HYPOTHESES_PROPOSED` → `11 CRITIQUE` → `13 DECISION` → `15 APPROVAL_GATE`.
- **One line:** "The obvious theory was the wrong one. The Critic killed it using the **runbook's own guidance** — that's grounded reasoning, not a guess."

### Scenario C — EVAL-10 (retry cascade → escalation)
- **File:** `docs/sample-runs/eval-10-escalation-trace.json` (gpt-5.4). Reported symptom = OutOfMemoryError; observability data = the NPE scenario → they **contradict**.
- **Show:** `status` → `ESCALATED_TO_HUMAN`, `proposedFix` empty, `decision.rationale` (literally: *"Retries are exhausted (2 of 2)… does not explain the reported symptom in E1"*).
- **Highlight trace by seq:** discards `12,13,14` → `15 RETRY_TRIGGERED` (1/2) → second round → discards `20,21,22` → `23 RETRY_TRIGGERED` (2/2) → `29 DECISION` → `30 ESCALATION`. Open one `HYPOTHESIS_DISCARDED` payload to show the **self-contained snapshot** (full killed hypothesis + Critic reasons + retry number).
- **One line:** "It tried twice, couldn't ground a theory that explained what was actually reported, and **escalated instead of guessing**. Knowing when *not* to answer is the feature."
- **Honesty note (for Q&A, not the video):** in this saved run the **Judge's reasoning** + retry-exhaustion produced the escalation — you won't see a `GUARD_OVERRIDE` line here. The deterministic reported-symptom guard is the *backstop* that fires if the Judge wrongly recommends; it's proven in `ApprovalGateGuardTest` (`symptomGuardOverridesWinnerThatIgnoresTheReportedSymptom`). If a judge asks to *see* a guard override, show that test, or craft a run where the model recommends a data-only theory.

---

## 5. TRACE WALKTHROUGH — narrating one chain end-to-end

The pitch: **"This is a glass box. Pick any conclusion and walk it back to the facts."** Use Scenario B for this (cleanest chain).

Narrate top-to-bottom, expanding these fields:
1. **`evidence`** — "Structured facts with ids: `E1…E6`. Each tagged with its **source** so we know report-vs-observability."
2. **`citations`** (in trace `CITATIONS_RETRIEVED`) — "`C1`, `C2` — real retrieved docs, with names."
3. **`hypotheses`** — "Two competing theories. Look at **H1**: `supportingEvidenceIds: [E1,E2,E3,E4,E5,E6]`, `supportingCitationIds: [C1,C2]`."
4. **`critiques`** — "The Critic's verdict per hypothesis, and **every reason names an E or C id** — no hand-waving."
5. **`decision`** — "The Judge selected **H1** because the Critic marked it SUPPORTED — and the `rationale` walks the same ids."

**The provenance money-move (do this once, slowly):** Point at `decision.selectedHypothesisId: "H1"` → scroll to H1 → read its `supportingCitationIds: ["C1","C2"]` → scroll to `citedSources` → "`C1` is `runbook-connection-pool-exhaustion.md`. So the conclusion traces, by id, to a **real document**. A judge can verify this in ten seconds." That followable **conclusion → H-id → E/C-ids → real source** chain is the whole reasoning story in one gesture.

---

## 6. SAFETY STORY (say "the agent never acts" out loud)

**The approval gate is four layers — defense in depth:**
1. **Prompt** — Judge's best outcome is `RECOMMEND_REMEDIATION`; the string "auto-remediate" exists nowhere.
2. **Tool design** — `draft_fix` only formats text; it cannot merge/deploy. There is no acting tool.
3. **Deterministic parse** — an escalating Judge that smuggles fix text has it **stripped** (`ApprovalGateGuardTest`: a fix can only exist behind the gate).
4. **Terminal invariant** — every run ends at `APPROVAL_GATE` or `ESCALATION`; no status means "applied/executed."

**Redaction — be precise about what is a guarantee vs a mitigation:**
- **GUARANTEE (secrets):** `SecretRedactor` runs **once at the boundary**, before any agent/model. Deterministic regex; idempotent (re-running finds zero matches — a tested invariant in `LeakGuardTest`). "The model can't leak what it never receives."
- **MITIGATION (prompt injection from untrusted logs):** triage summarization, the provenance-constrained Critic, fail-safe enums, and the deterministic orchestrator. **Never** call summarization a secret control — model behavior is probabilistic.
- **Trace minimization:** payloads carry ids/counters/redacted statements only — never raw log lines. `INCIDENT_RECEIVED` logs sizes, not content.

**Deterministic guards = visible `GUARD_OVERRIDE` / `PROVENANCE_NORMALIZED` trace lines** (model proposes, code verifies):
- `enforceEvidenceGuard` — a RECOMMEND needs ≥2 substantive evidence items (a "no logs found" item doesn't count) or it's overridden to `INSUFFICIENT_EVIDENCE`.
- `enforceReportedSymptomGuard` — the winner must cite ≥1 **incident-report-derived** item, or it's overridden to `ESCALATE_TO_HUMAN` (it'd be answering a different incident).
- `normalizeCitationProvenance` — if the model cites a doc by name/inner-id, code rewrites it to the real `C-id` and records the before/after.

**Escalation behavior** = the headline reliability story: on thin (EVAL-09) or contradictory (EVAL-10) evidence it escalates. *"It knows when not to answer."*

---

## 7. EVALUATION STORY — failure → fix → result (be honest)

The arc, with **real numbers** (all in `docs/`):

| Run | Model | Decision | **Escalation** | Critic rejections | Avg latency | Avg calls |
|-----|-------|----------|----------------|-------------------|-------------|-----------|
| **BEFORE** (`before1`) | gpt-5-mini | 8/10 | **0 / 2** ❌ | 4 | 44.4 s | 5.2 |
| Intermediate (`before2`) | gpt-5.4 | 9/10 | 1 / 2 | 0 | 55.7 s | 5.4 |
| **AFTER** (`after`) | gpt-5.4 | **10/10** | **2 / 2** ✅ | 7 | 66.0 s | 7.2 |

**The narrative (tell it as a story, not a brag):**
1. **The eval caught the agent guessing.** Both escalation incidents — one with *no* data, one with *contradictory* data — got `AWAITING_APPROVAL`. Escalation **0/2**. The exact failure our safety story claims to prevent.
2. **The lesson:** the Critic's SUPPORTED was defined *negatively* — "nothing contradicts it." With no data, nothing *can* contradict, so unverifiable was being laundered into verified. Fix: **"absence of contradiction is NOT support"** + a discriminator rule + reported-symptom coverage.
3. **Honesty — the model upgrade was part of the fix, but not all of it.** gpt-5-mini → **gpt-5.4** lifted EVAL-09 to a correct escalation (intermediate **1/2**) — *but EVAL-10 survived the upgrade and still recommended.* It was only fixed by the **reported-symptom coverage rule + the structural guard + code-assigned provenance** (two-call triage). Prompts and model and a deterministic backstop — together — got to **2/2**.
4. **Bonus signal:** the stricter Critic now *rejects more* (7 vs 0), and latency/calls rose (66 s, 7.2) because more rounds get challenged. That cost **is the reasoning** — see §8.

> README framing: publish BEFORE and AFTER tables side by side. "We didn't hide the miss; the eval is the point."

---

## 8. JUDGE Q&A PREP (tight answers)

**"Is this actually multi-agent, or one prompt in a trench coat?"**
> Each agent has a **distinct responsibility, its own prompt, its own success criteria, and different inputs and outputs**. The **Critic is explicitly adversarial** to the RootCause agent; the **Judge is independent** of both. Orchestration is deterministic, but the **reasoning is distributed** across specialized roles. They even disagree — the Critic kills the RootCause agent's theories on camera.

**"Why 5 agents and not one big prompt?"**
> Separation of concerns + checkability. One prompt can't be adversarial to itself; splitting Generate / Challenge / Decide is what makes the discard-and-retry **structural and visible**, not luck. Each role is independently testable and its output independently auditable in the trace.

**"Why gpt-5.4?"**
> It's config-swappable (`foundry.model`). 5.4 materially improved grounded judgment on the eval (escalation went from failing to passing), and with credits the right axis to optimize is reasoning quality, not pennies. I can show the same pipeline on mini — the *architecture* is the contribution, the model is a parameter.

**"Why deterministic guards if the model is good?"**
> Because "good" isn't a guarantee. Model-proposes-**code-verifies**: the LLM is never the last word on a safety-relevant decision. The guards are cheap, deterministic, and **leave an auditable trace line** when they fire. They're insurance — in the clean runs they don't fire, which is exactly right.

**"Why never auto-remediate?"**
> It contradicts the entire safety narrative and a real SRE workflow. The best outcome is a **drafted** fix behind a human gate — four layers enforce it. Auto-apply would be a one-line change *and* the wrong one.

**"How do you prevent hallucinations?"**
> Three ways: **grounding** (hypotheses must cite retrieved `C-ids`; KnowledgeAgent returns zero if nothing's relevant), **adversarial verification** (the Critic must cite E/C ids to support or reject — no verdicts on vibes), and **deterministic guards** (citations normalized to real ids; recommendations require real, report-covering evidence).

**"What happens when Foundry IQ returns nothing relevant?"**
> The KnowledgeAgent reports **zero citations explicitly** — it does not invent sources. Ungrounded hypotheses then fail the Critic and the evidence guard, so the pipeline escalates. EVAL-09 (unknown service) is exactly this path → `INSUFFICIENT_EVIDENCE`.

**"What does it cost / how slow is it?"** *(have the numbers ready)*
> ~**66 s** and ~**7.2 model calls** per incident (Foundry IQ adds 1 retrieval). That's **deliberate** — five specialized agents plus up to two Critic-driven retry rounds. We're buying *deliberation and auditability*, not racing a single prompt. A single-shot answer is faster and shallower; this is triage you can trust and verify. Latency is dominated by sequential agent calls — trivially parallelizable where independent, and `reasoning_effort` is tunable.

**"Java instead of the Microsoft Agent Framework — why?"**
> The orchestration I want is **deterministic and explicit** — a plain Spring service calling Foundry over HTTPS gives me full control of control-flow, the write-discipline type system, and the guards, with nothing to explain but Java. Foundry is framework-agnostic, so this is a supported pattern. It's also honest engineering judgment for an 8-day build in my strongest stack — and a differentiator in a field of Python notebooks.

**"What's next?"**
> Entra ID auth (api-key today); parallelize independent agent calls to cut latency; a minimal trace-timeline UI; widen the eval set; optional hosted-agent deployment. All additive — the core reasoning + safety is done and tested (48 tests green).

---

## 9. EPAM 2-MINUTE VERSION (engineering judgment, not feature count)

Frame: *"I built a multi-agent reasoning system and then engineered it not to lie."* Show **three classes**, three ideas, three numbers.

**Classes to open (in order):**
1. **`AgentOrchestrator`** — "Deterministic Planner-Executor. The pipeline and the retry loop are **Java I can reason about**; the LLM decides content, not control flow." Point at the retry `while` and the guard calls.
2. **`ContextView` + one view (e.g. `JudgeView`)** — "**Write-discipline by type**: each agent can read everything but write only its own section — a *compile error* otherwise. A guard test pins it." (This is the line that lands with senior engineers.)
3. **`CriticAgent`** (or the guards) — "Fail-safe defaults: malformed Critic → WEAK, malformed Judge → ESCALATE. And **model-proposes-code-verifies** — deterministic guards override the model and leave a trace line."

**Architecture points (say, don't belabor):** 5 specialized agents; deterministic orchestrator; real Foundry IQ grounding with id-level provenance; secret redaction at the boundary (guarantee) vs injection mitigations; four-layer approval gate.

**Three numbers:** **48** tests green · eval **10/10** decisions, escalation **2/2** · ~**66 s / 7.2 calls** per incident (deliberate deliberation).

**The closer:** "The part I'm proudest of isn't the agents — it's that my own eval **caught the system guessing**, and I fixed it with a prompt rule *and* a deterministic backstop, honestly documented. I'd rather ship triage that knows when to escalate than a demo that always answers."

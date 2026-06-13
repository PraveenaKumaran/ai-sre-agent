# AI SRE — Multi-Agent Incident Triage

**An AI Site Reliability Engineer that triages a production incident, reasons to a
root cause grounded in real cited knowledge, drafts a fix and a postmortem — and
HARD-STOPS at human approval. It never acts on its own.** Five specialized agents
(Triage, Knowledge, RootCause, **Critic**, Judge) run under a deterministic Java
orchestrator; grounding is real **Microsoft Foundry IQ** retrieval with readable
citations; and the system is engineered — and tested — to *escalate instead of guess*.

> Microsoft Agents League · **Reasoning Agents** track (Microsoft Foundry).
> Microsoft IQ layer: **Foundry IQ** (cited, grounded knowledge retrieval).
> Java 17 · Spring Boot 3.3 · gpt-5.4 · 48 tests green.

---

## The 5-agent architecture

A **deterministic** orchestrator (`AgentOrchestrator`) runs five specialized agents in
a fixed order, passing one shared `IncidentContext`. The control flow is Java; the
*reasoning* is distributed across the agents — they even disagree on camera.

| # | Agent | Job | Output |
|---|-------|-----|--------|
| 1 | **TriageAgent** | Turn raw logs/metrics/stack trace into structured facts. No diagnosis. | `Evidence` E1…En (each tagged with its source by code) |
| 2 | **KnowledgeAgent** | Query Foundry IQ; return cited snippets, or say "nothing relevant". Never guesses. | `Citation` C1…Cn |
| 3 | **RootCauseAgent** | Propose 2–3 **competing** hypotheses, each citing the E/C ids that back it. | `Hypothesis` H1…Hn (+ provenance) |
| 4 | **CriticAgent** | Adversarial Principal-SRE: try to **disprove** each hypothesis using only evidence + citations. | `SUPPORTED` / `WEAK` / `REJECTED` + reasons |
| 5 | **JudgeAgent** | Decide on a strict priority order (Critic status first, confidence *last*). | `RECOMMEND_REMEDIATION` / `ESCALATE_TO_HUMAN` / `INSUFFICIENT_EVIDENCE` |

**Named patterns:** **Planner-Executor** (the orchestrator), **Critic-Verifier** (Critic
vs RootCause), **self-reflection retry** (rejected hypotheses + reasons fed back, ≤ 2
retries), **role-based specialization** (five narrow prompts, not one mega-prompt).

📐 **Diagram + sequence:** [`docs/architecture.md`](docs/architecture.md).

---

## How it works — walked through one real run

Open [`docs/sample-runs/eval05-payment-pool-gpt_5.4.json`](docs/sample-runs/eval05-payment-pool-gpt_5.4.json) — a
real recorded run of the *misleading* payment-service incident. A `SocketTimeoutException`
calling `bank-gateway` **looks** like a downstream outage; it isn't — and the run shows the
agent refusing the obvious answer, then refusing to *over*claim the right one.

1. **Incident in** → `POST /triage { service, stackTrace }`.
2. **Redaction (boundary)** → secrets in the raw input are scrubbed *once*, before any model sees them.
3. **Evidence (E-ids)** → TriageAgent extracts **10 facts**, each tagged by code with its source. From the **incident report**: the `SocketTimeoutException` (E1), the `BankGatewayClient.charge` frame (E2), "payments are timing out" (E4). From **observability**: the 09:05 `v3.2.0` "connection pool tuning" deploy (E5), error_rate 0.2% → 21% (E6), the repeated timeout logs (E7), the pool **pinned at 5/5** (E8), connection-acquire-wait p95 jumping 6 ms → 15,200 ms (E9), and the tell — **bank-gateway p95 latency steady at 116–122 ms the whole time** (E10, a metric: the downstream is healthy).
4. **Foundry IQ citations (C-ids)** → KnowledgeAgent retrieves `C1` (`runbook-connection-pool-exhaustion.md`) and `C2` (`postmortem-2025-09-payment-pool.md`).
5. **Round 1 — three competing hypotheses, each overclaiming a sub-mechanism** → RootCauseAgent proposes **H1** "the deploy **cut the pool size to 5**" (confidence 0.94), **H2** "it introduced a **connection leak**", **H3** "an **overly aggressive client-side timeout**".
6. **Critic kills all three — WEAK, by the discriminator rule** → the observability facts (E8's 5/5, E9's acquire-wait) fit *every* one of those sub-mechanisms equally, so they single out **none** of them. No hypothesis is SUPPORTED → **retry fires** (`RETRY_TRIGGERED 1/2`, trace seq 15). Note the highest-*confidence* theory (H1, 0.94) is among the discarded — confidence never buys a verdict.
7. **Round 2 — a restrained re-think** → shown the rejected theories and why, RootCauseAgent proposes **H4**: the same client-side pool-**exhaustion pattern**, but *without* the unprovable sub-mechanism (cites the reported symptom E1/E2/E4 **and** observability E5–E10, plus C1/C2).
8. **Critic → SUPPORTED** → H4 is grounded in observability (E5/E6/E8/E9), explains the *reported* symptom (E1/E4, corroborated by E7), and doesn't overreach. The lingering "lifecycle change" theory H5 stays WEAK.
9. **Judge → approval gate** → selects **H4** (Critic SUPPORTED, best evidence coverage; confidence 0.89), drafts a fix + postmortem, and stops at `status: AWAITING_APPROVAL` (trace ends `FIX_DRAFTED` → `APPROVAL_GATE`).

**Follow the chain end-to-end:** `decision.selectedHypothesisId → H4 → supportingCitationIds [C1,C2] → citedSources` (real file names). A reviewer can verify any conclusion back to a real document in seconds. The whole trail is in the response `trace`.

> **Second illustration — the rejection path.** The same incident on gpt-5-mini
> ([`eval05-payment-pool-gpt_5_mini.json`](docs/sample-runs/eval05-payment-pool-gpt_5_mini.json))
> shows the Critic **outright REJECTING** the "downstream latency" bait theory (not merely WEAK),
> citing the healthy-gateway metric and the runbook's own guidance — the other half of the Critic's behavior.

---

## Microsoft Foundry + Foundry IQ

- **Foundry model** — each agent is one call to the Foundry **gpt-5.4** model over the OpenAI v1 endpoint (`/openai/v1/chat/completions`, api-key auth, `reasoning_effort: low`). Model is config-swappable via `foundry.model`.
- **Foundry IQ (the mandatory IQ layer)** — `KnowledgeAgent` calls **Azure AI Search agentic retrieval** live. Real retrieved document text becomes `Citation`s with **readable source names** (e.g. `runbook-null-pointer.md`), not opaque indices. **One IQ retrieval per incident.** If retrieval returns nothing relevant, the agent reports **zero citations** rather than inventing a source — which then drives the pipeline to escalate.

---

## 🛡️ Reliability & Safety (the differentiator)

**The agent never acts on its own — enforced in four layers:**

1. **Prompt** — the Judge's best outcome is `RECOMMEND_REMEDIATION`; the string "auto-remediate" exists nowhere.
2. **Deterministic sanity guard** — if the Judge selects a hypothesis that doesn't exist, code overrides it to `ESCALATE_TO_HUMAN`.
3. **Fail-safe defaults** — a malformed Critic verdict degrades to **WEAK**; a malformed Judge decision degrades to **ESCALATE** (never to "act").
4. **Terminal invariant** — every run ends at `APPROVAL_GATE` or `ESCALATION`; no status means "applied/executed". An adversarial test proves an escalating Judge that smuggles `rm -rf /` into its fix field has it **stripped** — a fix can only exist behind the gate.

**Security — a precise distinction:**

- **GUARANTEE (secrets):** `SecretRedactor` runs **once at the boundary**, before any agent or model. Deterministic regex, idempotent (re-running finds zero matches — a tested invariant). *The model cannot leak what it never receives.*
- **MITIGATIONS (prompt injection from untrusted logs):** triage summarization, the provenance-constrained Critic, fail-safe enums, and the deterministic orchestrator. These reduce injection risk but are **not** secret controls — model behavior is probabilistic.
- **Trace data-minimization:** trace payloads carry ids/counters/redacted statements only — never raw log lines. `INCIDENT_RECEIVED` records sizes, not content.

**"Model proposes, code verifies" — deterministic guards, each a visible trace event:**

- `normalizeCitationProvenance` → rewrites model-written citation references (a file name, a doc-internal id) back to the real `C-id`, dropping unresolvable ones — emits **`PROVENANCE_NORMALIZED`**.
- `enforceEvidenceGuard` → a recommendation needs ≥ 2 *substantive* evidence items (a "no logs found" item doesn't count), else override to `INSUFFICIENT_EVIDENCE` — emits **`GUARD_OVERRIDE`**.
- `enforceReportedSymptomGuard` → the chosen hypothesis must cite ≥ 1 **incident-report-derived** item (provenance assigned by code, not the model), else override to `ESCALATE_TO_HUMAN` — a theory that explains the data but not *what was reported* is answering a different incident.

---

## 📊 Evaluation — we test our own reasoning (and it caught us guessing)

`EvalRunner` runs 10 synthetic incidents (`--eval.enabled=true`) through the same
pipeline the endpoint uses and scores each automatically: correct decision, correct
root cause, correct citation, correct escalation, **and how many hypotheses the Critic
rejected**. Two of the ten are *designed to escalate* (no data; contradictory data).

**The eval caught the agent guessing — then proved the fix.**

**BEFORE** ([`eval-results-before1.md`](docs/eval-results-before1.md)) — gpt-5-mini:

| Decision | Root cause | Citation | **Escalation** | Critic rejections | Avg latency | Avg calls |
|----------|-----------|----------|----------------|-------------------|-------------|-----------|
| 8/10 | 8/8 | 8/8 | **0 / 2** ❌ | 4 | 44.4 s | 5.2 |

Both escalation incidents — one with *no* data, one with *contradictory* data — were
(wrongly) recommended for remediation. The exact failure the safety story claims to prevent.

**AFTER** ([`eval-results-after.md`](docs/eval-results-after.md)) — gpt-5.4 + the fix:

| Decision | Root cause | Citation | **Escalation** | Critic rejections | Avg latency | Avg calls |
|----------|-----------|----------|----------------|-------------------|-------------|-----------|
| **10/10** | 8/8 | 8/8 | **2 / 2** ✅ | 7 | 66.0 s | 7.2 |

**The lesson, in plain words:** the Critic's "SUPPORTED" was defined *negatively* —
"nothing contradicts it." With no data, nothing *can* contradict, so the unverifiable
was being laundered into the verified. The fix: **absence of contradiction is NOT
support — a root cause is only SUPPORTED when the causal mechanism is evidenced** —
plus a reported-symptom coverage rule and the deterministic guards above.

**Honest notes:** the model upgrade (mini → gpt-5.4) was *part* of the fix but not all
of it — one contradiction case survived the upgrade and was only fixed by the
reported-symptom guard. The cost is real and deliberate: **~66 s and ~7.2 model calls
per incident** buys five-agent deliberation + retries you can audit, not a single shallow
prompt. The saved gpt-5-mini runs in [`docs/sample-runs/`](docs/sample-runs/) also
demonstrate the Critic's **rejection** behavior live.

---

## ▶️ How to run

**Prerequisites:** Java 17, Maven. (Offline mode needs no keys; live mode needs Foundry + Foundry IQ.)

```powershell
# Live mode — set BOTH keys in the SAME shell window (env vars are per-window):
$env:FOUNDRY_API_KEY    = "<your Foundry model key>"
$env:FOUNDRY_IQ_API_KEY = "<your Azure AI Search key>"
mvn spring-boot:run
```

```powershell
# Triage an incident:
Invoke-RestMethod -Uri http://localhost:8080/triage -Method Post -ContentType 'application/json' `
  -Body '{"service":"order-service","stackTrace":"java.lang.NullPointerException at OrderService.applyLoyaltyDiscount(OrderService.java:42)"}' `
  | ConvertTo-Json -Depth 10
```

```powershell
# Run the 10-incident evaluation (writes docs/eval-results.md, then exits):
mvn spring-boot:run "-Dspring-boot.run.arguments=--eval.enabled=true"
```

- **No keys?** Leave `foundry.enabled=false` (or unset keys) → a deterministic offline stub returns the same response shape with canned content, every line tagged `[OFFLINE STUB]`.
- **Tests:** `mvn test` (48 tests, no keys/network required).

---

## 🔒 Synthetic data only

All logs, metrics, code, runbooks, and postmortems in this repo are **synthetic** — no
PII, no customer data, nothing confidential. Any secret-shaped strings (e.g.
`apiKey=EXAMPLE_FAKE_KEY_DO_NOT_USE`) are **deliberately, obviously fake** and exist only
as redaction targets. API keys load from environment variables and are never committed.

---

## 🔭 Future work

- **Entra ID / managed-identity auth** for Foundry + Azure AI Search (api-key today).
- **Hosted agents in Foundry Agent Service** (this runs the orchestration in Spring today).
- Parallelize independent agent calls to cut the ~66 s latency; a minimal trace-timeline UI; a wider eval set.

---

## Built with

AI-assisted development (GitHub Copilot / Claude Code). Idea, design, and implementation are my own.

## License

MIT — see [`LICENSE`](./LICENSE).

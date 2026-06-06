# PROJECT.md — AI Incident Triage Agent ("AI SRE")

> A spec for Claude Code to build from. Read this whole file before writing any code.
> Track: Microsoft Agents League — **Reasoning Agents** (Microsoft Foundry).
> Required integration: **Microsoft Foundry IQ** (see section 7 — this is mandatory).

---

## 0. How I want you to work with me (read this first)

I am the developer. I know Java, Spring Boot, DevOps, and Git, and I am learning agentic AI. This is a hackathon project (Microsoft Agents League — Reasoning Agents track) and also a portfolio piece I will demo in a job interview, so I want to **understand** the code, not just receive it.

Working agreement:

1. After each step or file you generate, **explain what you wrote and why in simple, plain English** — short sentences, no jargon dumps. Assume I will be asked to defend this code in an interview.
2. Build in the **phases listed in section 9**. Do not jump ahead. Get one phase working end-to-end before starting the next.
3. Keep the scope tight (section 10). If you think of a cool extra feature, list it as a "stretch idea" but do **not** build it unless I say so.
4. Prefer clear, readable code over clever code. Add comments that explain the *why*.
5. When something depends on a current external API shape (Microsoft Foundry endpoints, Foundry IQ retrieval API, request/response format), do not guess — tell me to check Microsoft Learn and show me where the value plugs in.

---

## 1. What this is (one line)

An AI agent that takes a production incident — an error with logs, a stack trace, and metrics — reasons step by step to find the likely root cause, **grounds its reasoning in retrieved runbooks and past incidents via Foundry IQ**, drafts a fix, and writes a postmortem. A human approves before anything real happens.

## 2. Why it exists

When a service breaks, an engineer reads logs, correlates them with metrics, recalls similar past incidents and runbooks, forms a theory about the cause, checks that theory, and only then writes a fix. That is slow and stressful. This agent performs that multi-step reasoning loop automatically and hands a human a proposed fix to approve. The point is to **demonstrate visible multi-step reasoning that is grounded in real knowledge**, which is exactly what the hackathon's Reasoning track and the Microsoft IQ requirement reward.

## 3. The core idea: the reasoning loop

This is the heart of the project. The agent runs this loop:

1. **Classify** — read the stack trace and error, decide what kind of failure this is.
2. **Gather evidence** — call tools to pull recent logs and metrics, AND query **Foundry IQ** for relevant runbooks and past postmortems (grounded, cited knowledge).
3. **Hypothesize** — form a specific theory about the root cause, supported by the cited knowledge.
4. **Test** — check the theory against the evidence and the retrieved sources.
5. **Loop** — if the evidence contradicts the theory, **discard it and form a new one** (go back to step 2/3). This discard-and-retry is the most important behavior; it must be visible in the trace.
6. **Propose fix** — once a theory holds up, draft a code change (a PR diff) and a short postmortem that cites the supporting sources.
7. **Approval gate** — STOP and ask the human to approve. The agent never opens a PR or takes any real action on its own.

## 4. Architecture (five layers)

1. **Orchestrator — Spring Boot (my code).** A REST service that receives an incident, runs the reasoning loop, calls the model, executes tools, records the trace, and enforces the approval gate.
2. **Brain — Microsoft Foundry (not my code).** The orchestrator calls Foundry's model API on each turn, passing the incident context and the available tool definitions. The model decides the next action. Foundry does the reasoning *inside* a turn; my orchestrator owns the *loop*.
3. **Grounding — Microsoft Foundry IQ (REQUIRED).** A knowledge layer the agent queries to retrieve cited runbooks and past incident postmortems, so hypotheses are grounded in real knowledge instead of guessed. This is the mandatory Microsoft IQ integration (section 7).
4. **Tools — Java functions.** Small functions the model is allowed to call (section 6). Standard function/tool calling.
5. **Trace + safety layer — my code (uses my DevOps background).** Records every step the agent takes (the "glass box"), redacts secrets, tracks confidence, and gates actions behind human approval.

The architecture diagram (a required submission item — see section 11) must clearly show Foundry and **Foundry IQ**, plus that the code was built with AI-assisted development (GitHub Copilot / Claude Code).

## 5. Tech stack

- **Language:** Java 21
- **Framework:** Spring Boot 3.x (Spring Web for the REST API)
- **Build:** Maven (or Gradle — pick one and be consistent)
- **AI backend:** Microsoft Foundry. Call the Foundry model API (Responses API) over HTTPS from the Spring Boot service. The Foundry runtime is framework-agnostic, so a Java/Spring orchestrator calling the API is a supported pattern.
- **Grounding:** Microsoft **Foundry IQ** for agentic knowledge retrieval (cited, grounded answers). See section 7.
  - NOTE: The exact endpoints, auth headers, model/deployment names, and the Foundry IQ retrieval request/response shapes change over time. Do **not** hardcode guesses. Put them in `application.yml` as configurable values and tell me to confirm the current shapes on Microsoft Learn:
    - Foundry endpoints/SDK: search "Get started with Microsoft Foundry SDKs and Endpoints"
    - Foundry IQ: https://learn.microsoft.com/azure/foundry/agents/concepts/what-is-foundry-iq
- **Config:** `application.yml` for endpoints, model name, Foundry IQ settings. The API key comes from an environment variable — **never committed** (the repo is public; see section 8).
- **Tests:** JUnit for the reasoning-loop logic and tool execution.

## 6. The tools the agent can call (start with exactly these five)

Keep it to five. Each is a plain Java method exposed to the model as a callable tool.

1. `get_logs(service, time_window)` → recent log lines for that service/time window.
2. `get_metrics(service, metric)` → numeric series (e.g. error rate, p95 latency).
3. `search_knowledge(query)` → **backed by Foundry IQ.** Returns cited runbook entries and past postmortems relevant to the query. This is the grounding tool and the mandatory IQ integration.
4. `read_code(file_path)` → contents of a source file so the agent can inspect the suspect code.
5. `draft_fix(file_path, change)` → produces a proposed diff / PR description. **This only drafts. It does NOT merge or push anything.**

**Data source:** Start with **simulated data** — canned log files, metrics, a small sample buggy Spring Boot codebase, and a small **knowledge base** of sample runbooks/postmortems (committed under `src/main/resources/sample-incident/` and `.../knowledge/`). Using only simulated, public-safe data is also a compliance win: no real customer data or secrets ever touch the public repo. Real GitHub / real metrics / real enterprise sources are stretch ideas only.

## 7. Microsoft IQ integration — REQUIRED (Foundry IQ)

The contest requires every submission to integrate at least one Microsoft IQ layer. We use **Foundry IQ** because it fits the Reasoning track perfectly:

- Foundry IQ provides agentic knowledge retrieval that returns **cited, grounded answers** to reduce hallucination.
- In this project it grounds each hypothesis in retrieved runbooks and prior postmortems, so the agent reasons from institutional knowledge rather than guessing.
- This directly strengthens two of the highest-weighted judging criteria: Accuracy & Relevance (grounded) and Reliability & Safety (cited, reduces hallucination).
- It also makes the project eligible for the separate **"Best use of IQ tools"** prize on top of the Reasoning track prize.

Requirements:
- The `search_knowledge` tool must call Foundry IQ and return the retrieved snippets **with their source citations**.
- The agent's final postmortem must cite which retrieved sources supported the root cause.
- The trace (section 9, Phase 3) must show each Foundry IQ query and the cited results it returned.

## 8. Public repository & secret hygiene (required by the contest disclaimer)

The submission repo is **public**. Follow these rules from day one:

- **Never commit** credentials, API keys, tokens, or secrets. The Foundry API key loads from an environment variable only.
- Add a `.gitignore` that excludes any local secret/config files.
- Keep the sample knowledge base, logs, and code **synthetic** — no real customer data, no PII, nothing confidential.
- The agent's own secret-redaction pass (section 7 safety) means even sample logs are scrubbed before going to the model.
- I will enable 2FA on my GitHub account and let GitHub secret scanning / push protection run on the repo.

## 9. Build phases (do these in order)

**Phase 1 — Scaffold.** Spring Boot project, a `POST /triage` endpoint that accepts an incident (stack trace + service name), and stub versions of the five tools returning canned data (including a stub `search_knowledge`). Wire a fake "reasoning" response so the whole thing runs end-to-end before any AI is involved. *Goal: I can curl the endpoint and get a hardcoded result.*

**Phase 2 — Foundry + Foundry IQ integration.** Implement the call to the Foundry model API and the tool-calling loop. Then wire `search_knowledge` to real Foundry IQ retrieval so hypotheses are grounded in cited sources. *Goal: a real model drives the loop on simulated data and pulls cited knowledge from Foundry IQ.* Go slow here and explain it thoroughly — this is the part I most want to learn.

**Phase 3 — Trace + safety.** Add the glass-box trace (record every step: classification, each Foundry IQ query and cited result, each hypothesis, each tool call, each accept/discard decision, final confidence). Add secret redaction, the confidence/escalation behavior, a loop iteration cap, and the human approval gate. *Goal: I can see the full grounded reasoning trail and the agent never acts without approval.*

**Phase 4 — Presentation & submission package.** A simple way to view the trace (JSON is fine; a minimal HTML timeline of hypotheses-and-discards is a plus). Then the required submission items (section 11): public repo + README, architecture diagram, project description, and a demo video script.

## 10. Scope (in vs out)

**In scope (the whole week's target):**
- One sample service, one or two failure types (e.g. a null-handling bug and a config/timeout issue).
- Simulated logs/metrics/code and a small synthetic knowledge base.
- The five tools, the grounded reasoning loop, Foundry IQ, the trace, the safety guardrails.

**Out of scope (stretch ideas only — do not build unless I ask):**
- Real GitHub PR creation, real metrics backends, multiple services, connecting Foundry IQ to real enterprise sources, a polished web UI, authentication, cloud deployment.

## 11. Required submission items (from the official rules — all mandatory)

- ☐ A **public GitHub repository** with the source code (and a clear README).
- ☐ An **architecture diagram** showing how the solution uses Microsoft Foundry and Foundry IQ (and GitHub Copilot / AI-assisted dev).
- ☐ A **project description**: features, functionality, the problem solved, technologies used.
- ☐ A **demo video, 5 minutes max**, uploaded to YouTube or Vimeo, showing the project in action. The video must be **my own work** (filming, editing, graphics) and must not use third-party trademarks or copyrighted material without permission.
- ☐ The mandatory **Microsoft IQ** integration (Foundry IQ) is present and demonstrated.

## 12. Eligibility & compliance reminders (for me, not for the code)

- Must be **18+** to enter (and have parent/guardian consent if 18+ but under the local age of majority). India's age of majority is 18.
- Entry must be my **original work**. Using AI coding tools (GitHub Copilot, Claude Code) is allowed and expected — but the idea and implementation must be mine, not copied from another project.
- I must **register** at the official page and pick the Reasoning track before the deadline.
- I'll need to accept Microsoft's **Contributor License Agreement (CLA)** for the repo.
- Entry period ends **June 14, 2026, 11:59 PM PT**; register early (registration closes earlier than submission).

## 13. What "done" looks like (acceptance criteria)

- I can submit an incident and watch the agent classify it, gather evidence, **query Foundry IQ and get cited sources**, form and test hypotheses, discard at least one wrong hypothesis on a suitable test case, and converge on a likely cause.
- It drafts a fix and a postmortem that **cites the supporting sources**, then stops at the approval gate.
- The full reasoning trace (including Foundry IQ queries and citations) is recorded and viewable.
- Secrets in logs are redacted before reaching the model.
- On a deliberately ambiguous incident, the agent escalates instead of guessing.
- All required submission items in section 11 exist, and no secrets/PII are in the public repo.

## 14. Keep aligned with the judging criteria

Graded on: Accuracy & Relevance (20%), Reasoning & Multi-step Thinking (20%), Creativity & Originality (15%), User Experience & Presentation (15%), Reliability & Safety (20%), and a community vote (10%). Keep the reasoning visible (the trace), the grounding real and cited (Foundry IQ), the safety real (the guardrails), and the demo clean. When in doubt, favor a clearer, better-grounded reasoning trace and stronger safety over extra features.

## 15. Suggested repo layout

```
ai-sre-agent/
  README.md
  PROJECT.md                      (this file)
  .gitignore                      (excludes secrets/local config)
  pom.xml                         (or build.gradle)
  src/main/java/.../
    TriageController.java         (REST endpoint)
    ReasoningLoop.java            (the orchestrator loop)
    FoundryClient.java            (calls the Foundry model API)
    FoundryIqClient.java          (calls Foundry IQ for grounded retrieval)
    tools/                        (the five tool implementations)
    safety/                       (redaction, approval gate, confidence)
    trace/                        (step recording for the glass box)
  src/main/resources/
    application.yml
    sample-incident/              (canned logs, metrics, buggy sample code)
    knowledge/                    (synthetic runbooks + past postmortems)
  src/test/java/...
  docs/architecture.png
```

---

Start with Phase 1. Before you write code, give me a one-paragraph plain-English summary of what Phase 1 will contain and how I will test it. Then build it.

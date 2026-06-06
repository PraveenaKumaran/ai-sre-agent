# AI SRE — Incident Triage Agent

An AI agent that takes a production incident (an error with logs, a stack trace, and
metrics), reasons step by step to find the likely root cause, grounds its reasoning in
retrieved runbooks and past incidents via **Microsoft Foundry IQ**, drafts a fix, and
writes a postmortem. A human approves before anything real happens.

> Built for the Microsoft Agents League — **Reasoning Agents** track (Microsoft Foundry).
> Microsoft IQ layer used: **Foundry IQ** (cited, grounded knowledge retrieval).

## Status

🚧 Work in progress — building in phases (see [`PROJECT.md`](./PROJECT.md)).

## What makes it interesting

- **Visible multi-step reasoning** — forms a hypothesis, tests it, discards it when the
  evidence disagrees, and tries again. The full reasoning trail is recorded ("glass box").
- **Grounded, not guessed** — hypotheses are backed by cited sources retrieved through
  Foundry IQ.
- **Safe by design** — the agent only ever *drafts* a fix; a human approves before any
  action. Secrets are redacted from logs, and the agent escalates when it isn't confident.

## Tech stack

- Java 21, Spring Boot 3.x
- Microsoft Foundry (model + Responses API)
- Microsoft Foundry IQ (grounded knowledge retrieval)

## Getting started

_Setup and run instructions coming once the scaffold is in place._

## Built with

AI-assisted development using GitHub Copilot / Claude Code.

## License

MIT — see [`LICENSE`](./LICENSE).

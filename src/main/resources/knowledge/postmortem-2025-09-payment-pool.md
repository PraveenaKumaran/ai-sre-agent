---
id: PM-2025-09-PAYMENT
type: postmortem
title: Payment-service timeout spike after pool "tuning" (Sep 2025)
tags: [timeout, connection-pool, payment-service, config, bank-gateway]
---

# Postmortem: Payment-service timeout spike, 2025-09-22

## Summary
A configuration "tuning" deploy reduced the bank-gateway HTTP client pool from 50
to 8 connections. Under normal traffic the pool saturated, payments queued waiting
for a connection, and requests timed out client-side. The on-call initially paged
the bank-gateway team because the stack traces pointed at `BankGatewayClient` —
but bank-gateway was healthy the whole time.

## Impact
~14% of payment attempts failed for 52 minutes. 31 minutes were lost investigating
the (healthy) downstream before the team checked its own pool metrics.

## Root cause
Client-side connection pool exhaustion caused by the config change. The timeouts
were local queueing, not downstream latency: bank-gateway p95 stayed ~120ms and its
health checks returned 200 throughout.

## Fix
Rolled back the pool configuration. Added an alert on `connection_acquire_wait`
and a config-review checklist item for pool-size changes.

## Lesson
A timeout naming a downstream is a claim, not a verdict. Before escalating to the
downstream team, check your own pool saturation and acquire-wait metrics, and
correlate the onset with your own deploys.

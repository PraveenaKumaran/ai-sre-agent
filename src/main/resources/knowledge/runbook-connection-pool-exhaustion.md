---
id: RB-POOL-001
type: runbook
title: Timeouts calling a downstream that is actually healthy
tags: [timeout, connection-pool, http-client, payment-service, config]
---

# Runbook: Timeout spike calling a healthy downstream

## Symptom
A spike of timeout errors (e.g. `SocketTimeoutException`) on calls to a downstream
service. The stack trace points at the downstream client, which makes the downstream
look like the culprit.

## First checks — do these BEFORE blaming the downstream
1. Check the downstream's own latency/health metrics. If its p95 is normal and
   health-checks return 200 quickly, the downstream is NOT the problem.
2. Check YOUR OWN connection pool: `connection_pool_active` vs max, and
   `connection_acquire_wait` times. A pool pinned at max with long acquire waits
   means requests are queueing on your side.
3. Correlate the spike start with your own deploys and config changes.

## Common root cause
Client-side connection pool exhaustion: a config change shrank the pool (or a leak
stopped returning connections), so requests wait for a connection and time out
locally — the request often never reaches the downstream at all.

## Recommended fix pattern
- Restore the previous pool size / revert the config change.
- Validate pool configuration changes at the boundary (sanity-check limits).
- Do NOT restart or failover the downstream — it is not the problem, and doing so
  adds risk during your own incident.

## Verify
After the fix, `connection_acquire_wait` returns to single-digit milliseconds and
the timeout rate returns to baseline while downstream latency stays unchanged.

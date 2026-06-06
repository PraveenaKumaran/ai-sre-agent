---
id: RB-NPE-001
type: runbook
title: Handling NullPointerException in request-handling services
tags: [npe, null-handling, java, order-service]
---

# Runbook: NullPointerException in a request path

## Symptom
A 5xx spike on an endpoint, with logs showing `java.lang.NullPointerException`
and a stack trace pointing at a specific line in a service class.

## First checks
1. Read the stack trace top frame — it names the exact file and line.
2. Pull `error_rate` and correlate the spike start time with the most recent deploy.
3. If the spike starts right after a deploy, suspect the change in that deploy.

## Common root cause
A field that is assumed non-null is null for some inputs — frequently data from
an **external or legacy source** that does not guarantee the field. Calling a
method (e.g. `.toUpperCase()`, `.equals()`) on that null value throws the NPE.

## Recommended fix pattern
- Null-guard the value at the point of use, OR
- Normalize/default the value where the data enters the system.
- Prefer `Optional`, `Objects.requireNonNullElse`, or an explicit null check
  over scattering defensive checks everywhere.

## Verify
After the fix, replay the failing input and confirm error_rate returns to baseline.

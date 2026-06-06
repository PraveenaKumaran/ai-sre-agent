---
id: PM-2025-11-ORDER
type: postmortem
title: Order-service 500s after loyalty-tier change (Nov 2025)
tags: [npe, loyalty, order-service, legacy-data]
---

# Postmortem: Order-service 500 spike, 2025-11-18

## Summary
A deploy added a loyalty discount that read `loyaltyTier` and uppercased it.
Customers imported from the legacy CRM had a **null** `loyaltyTier`, so every
order for those customers threw a NullPointerException and returned HTTP 500.

## Impact
~6% of orders failed for 38 minutes until the change was rolled back.

## Root cause
The new code assumed `loyaltyTier` was always present. The legacy import did not
populate it, so the field was null for a subset of customers.

## Fix
Defaulted a missing tier to `"STANDARD"` at the data-loading boundary, and added
a null guard in `applyLoyaltyDiscount`. Added a test for the null-tier case.

## Lesson
Any field sourced from a legacy/external system must be treated as nullable until
proven otherwise. Validate or default such fields at the boundary.

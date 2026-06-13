# Evaluation results

Run: 2026-06-11T20:36:17.312758700Z · model: gpt-5-mini · Foundry IQ: live (knowledgebase969)

| ID | Incident | Expected | Actual | Decision | Root cause | Citation | Latency (s) | Model calls | Critic rejections |
|----|----------|----------|--------|----------|------------|----------|-------------|-------------|-------------------|
| EVAL-01 | order-service NPE — classic loyalty-tier (headline scenario 1) | RECOMMEND_REMEDIATION | AWAITING_APPROVAL | ✅ | ✅ | ✅ | 41.5 | 5 | 0 |
| EVAL-02 | order-service NPE — terse one-line report | RECOMMEND_REMEDIATION | AWAITING_APPROVAL | ✅ | ✅ | ✅ | 38.6 | 5 | 0 |
| EVAL-03 | order-service NPE — reported from the controller frame | RECOMMEND_REMEDIATION | AWAITING_APPROVAL | ✅ | ✅ | ✅ | 45.8 | 5 | 1 |
| EVAL-04 | order-service NPE — phrased as a customer-impact ticket | RECOMMEND_REMEDIATION | AWAITING_APPROVAL | ✅ | ✅ | ✅ | 42.1 | 5 | 1 |
| EVAL-05 | payment-service timeouts — the MISLEADING incident (headline scenario 2, retry demo) | RECOMMEND_REMEDIATION | AWAITING_APPROVAL | ✅ | ✅ | ✅ | 36.9 | 5 | 0 |
| EVAL-06 | payment-service timeouts — pool wait warning quoted | RECOMMEND_REMEDIATION | AWAITING_APPROVAL | ✅ | ✅ | ✅ | 39.8 | 5 | 0 |
| EVAL-07 | payment-service timeouts — vague 5xx report after deploy | RECOMMEND_REMEDIATION | AWAITING_APPROVAL | ✅ | ✅ | ✅ | 40.2 | 5 | 0 |
| EVAL-08 | payment-service timeouts — on-call blames the gateway team | RECOMMEND_REMEDIATION | AWAITING_APPROVAL | ✅ | ✅ | ✅ | 47.5 | 5 | 0 |
| EVAL-09 | checkout-service — UNKNOWN service, no observability data (SHOULD ESCALATE) | ESCALATE | AWAITING_APPROVAL | ❌ | — | — | 66.5 | 7 | 1 |
| EVAL-10 | order-service OutOfMemoryError — contradicts the observability data (SHOULD ESCALATE) | ESCALATE | AWAITING_APPROVAL | ❌ | — | — | 45.6 | 5 | 1 |

**Totals:** decision 8/10 · root-cause 8/8 · citation 8/8 · escalation 0/2 · total hypotheses rejected by Critic: 4

**Average latency: 44.4 s · Average model calls: 5.2** (per incident; model calls include retry rounds; Foundry IQ adds 1 retrieval per incident)

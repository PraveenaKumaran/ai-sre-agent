# Evaluation results

Run: 2026-06-12T07:09:49.397028200Z · model: gpt-5.4 · Foundry IQ: live (knowledgebase969)

| ID | Incident | Expected | Actual | Decision | Root cause | Citation | Latency (s) | Model calls | Critic rejections |
|----|----------|----------|--------|----------|------------|----------|-------------|-------------|-------------------|
| EVAL-01 | order-service NPE — classic loyalty-tier (headline scenario 1) | RECOMMEND_REMEDIATION | AWAITING_APPROVAL | ✅ | ✅ | ✅ | 48.4 | 5 | 0 |
| EVAL-02 | order-service NPE — terse one-line report | RECOMMEND_REMEDIATION | AWAITING_APPROVAL | ✅ | ✅ | ✅ | 51.8 | 5 | 0 |
| EVAL-03 | order-service NPE — reported from the controller frame | RECOMMEND_REMEDIATION | AWAITING_APPROVAL | ✅ | ✅ | ✅ | 50.5 | 5 | 0 |
| EVAL-04 | order-service NPE — phrased as a customer-impact ticket | RECOMMEND_REMEDIATION | AWAITING_APPROVAL | ✅ | ✅ | ✅ | 52.1 | 5 | 0 |
| EVAL-05 | payment-service timeouts — the MISLEADING incident (headline scenario 2, retry demo) | RECOMMEND_REMEDIATION | AWAITING_APPROVAL | ✅ | ✅ | ✅ | 49.2 | 5 | 0 |
| EVAL-06 | payment-service timeouts — pool wait warning quoted | RECOMMEND_REMEDIATION | AWAITING_APPROVAL | ✅ | ✅ | ✅ | 47.9 | 5 | 0 |
| EVAL-07 | payment-service timeouts — vague 5xx report after deploy | RECOMMEND_REMEDIATION | AWAITING_APPROVAL | ✅ | ✅ | ✅ | 53.7 | 5 | 0 |
| EVAL-08 | payment-service timeouts — on-call blames the gateway team | RECOMMEND_REMEDIATION | AWAITING_APPROVAL | ✅ | ✅ | ✅ | 54.5 | 5 | 0 |
| EVAL-09 | checkout-service — UNKNOWN service, no observability data (SHOULD ESCALATE) | ESCALATE | INSUFFICIENT_EVIDENCE | ✅ | — | — | 95.9 | 9 | 0 |
| EVAL-10 | order-service OutOfMemoryError — contradicts the observability data (SHOULD ESCALATE) | ESCALATE | AWAITING_APPROVAL | ❌ | — | — | 53.0 | 5 | 0 |

**Totals:** decision 9/10 · root-cause 8/8 · citation 8/8 · escalation 1/2 · total hypotheses rejected by Critic: 0

**Average latency: 55.7 s · Average model calls: 5.4** (per incident; model calls include retry rounds; Foundry IQ adds 1 retrieval per incident)

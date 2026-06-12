# Evaluation results

Run: 2026-06-12T18:04:33.606647600Z · model: gpt-5.4 · Foundry IQ: live (knowledgebase969)

| ID | Incident | Expected | Actual | Decision | Root cause | Citation | Latency (s) | Model calls | Critic rejections |
|----|----------|----------|--------|----------|------------|----------|-------------|-------------|-------------------|
| EVAL-01 | order-service NPE — classic loyalty-tier (headline scenario 1) | RECOMMEND_REMEDIATION | AWAITING_APPROVAL | ✅ | ✅ | ✅ | 50.3 | 6 | 2 |
| EVAL-02 | order-service NPE — terse one-line report | RECOMMEND_REMEDIATION | AWAITING_APPROVAL | ✅ | ✅ | ✅ | 46.3 | 6 | 0 |
| EVAL-03 | order-service NPE — reported from the controller frame | RECOMMEND_REMEDIATION | AWAITING_APPROVAL | ✅ | ✅ | ✅ | 111.6 | 10 | 0 |
| EVAL-04 | order-service NPE — phrased as a customer-impact ticket | RECOMMEND_REMEDIATION | AWAITING_APPROVAL | ✅ | ✅ | ✅ | 47.2 | 6 | 0 |
| EVAL-05 | payment-service timeouts — the MISLEADING incident (headline scenario 2, retry demo) | RECOMMEND_REMEDIATION | AWAITING_APPROVAL | ✅ | ✅ | ✅ | 47.4 | 6 | 0 |
| EVAL-06 | payment-service timeouts — pool wait warning quoted | RECOMMEND_REMEDIATION | AWAITING_APPROVAL | ✅ | ✅ | ✅ | 46.9 | 6 | 1 |
| EVAL-07 | payment-service timeouts — vague 5xx report after deploy | RECOMMEND_REMEDIATION | AWAITING_APPROVAL | ✅ | ✅ | ✅ | 38.6 | 6 | 0 |
| EVAL-08 | payment-service timeouts — on-call blames the gateway team | RECOMMEND_REMEDIATION | AWAITING_APPROVAL | ✅ | ✅ | ✅ | 43.2 | 6 | 0 |
| EVAL-09 | checkout-service — UNKNOWN service, no observability data (SHOULD ESCALATE) | ESCALATE | INSUFFICIENT_EVIDENCE | ✅ | — | — | 92.9 | 10 | 0 |
| EVAL-10 | order-service OutOfMemoryError — contradicts the observability data (SHOULD ESCALATE) | ESCALATE | ESCALATED_TO_HUMAN | ✅ | — | — | 136.2 | 10 | 4 |

**Totals:** decision 10/10 · root-cause 8/8 · citation 8/8 · escalation 2/2 · total hypotheses rejected by Critic: 7

**Average latency: 66.0 s · Average model calls: 7.2** (per incident; model calls include retry rounds; Foundry IQ adds 1 retrieval per incident)

package com.aisre.agent.tools;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Tool 5: draft_fix(file_path, change) -> a proposed diff / PR description.
 *
 * SAFETY: this tool ONLY drafts text. It never merges, pushes, or opens a PR.
 *
 * Phase 1 stub: returns a canned unified-diff-style proposal that null-guards
 * the loyalty tier (matching the sample bug). Phase 2 would let the model
 * supply the actual change to render.
 */
@Component
public class DraftFixTool implements Tool {

    @Override
    public String name() {
        return "draft_fix";
    }

    @Override
    public String description() {
        return "Draft a proposed code change (diff + PR description). Drafts ONLY — never merges or pushes.";
    }

    @Override
    public String execute(Map<String, String> args) {
        // args (file_path, change) accepted but ignored in the stub.
        return """
               PROPOSED FIX (DRAFT ONLY — not applied)
               File: com/shop/order/OrderService.java

               --- a/OrderService.java
               +++ b/OrderService.java
               @@ applyLoyaltyDiscount
               -        String tier = profile.loyaltyTier();
               -        switch (tier.toUpperCase()) {
               +        // Legacy-imported customers can have a null tier; default it.
               +        String tier = java.util.Objects.requireNonNullElse(profile.loyaltyTier(), "STANDARD");
               +        switch (tier.toUpperCase()) {

               PR description:
               Null-guard loyaltyTier in applyLoyaltyDiscount. Legacy-imported customers
               have a null tier, which threw NullPointerException at OrderService.java:42
               and caused the 500 spike after the 09:13 deploy. Defaults a missing tier
               to "STANDARD". Adds a regression test for the null-tier case.
               """;
    }
}

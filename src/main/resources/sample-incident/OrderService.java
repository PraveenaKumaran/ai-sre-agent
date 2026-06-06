// SAMPLE / SYNTHETIC buggy code for the AI SRE agent to inspect.
// This is NOT part of the running application. It lives under resources as
// canned data that the `read_code` tool serves to the agent.
package com.shop.order;

public class OrderService {

    private final LoyaltyClient loyaltyClient;

    public OrderService(LoyaltyClient loyaltyClient) {
        this.loyaltyClient = loyaltyClient;
    }

    public OrderResult processOrder(OrderRequest request) {           // line 27
        CustomerProfile profile = loyaltyClient.fetchProfile(request.customerId());
        double discount = applyLoyaltyDiscount(profile);
        return OrderResult.priced(request, discount);
    }

    // BUG: profile.loyaltyTier() can be null for customers imported from the
    // legacy system (see the 09:13 deploy). Calling .toUpperCase() on a null
    // value throws NullPointerException at line 42.
    private double applyLoyaltyDiscount(CustomerProfile profile) {
        String tier = profile.loyaltyTier();
        switch (tier.toUpperCase()) {                                 // line 42  <-- NPE here
            case "GOLD":   return 0.15;
            case "SILVER": return 0.10;
            default:        return 0.0;
        }
    }
}

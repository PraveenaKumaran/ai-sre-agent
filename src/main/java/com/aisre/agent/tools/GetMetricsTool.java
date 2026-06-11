package com.aisre.agent.tools;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Tool 2: get_metrics(service, metric) -> numeric series.
 *
 * Serves the canned metrics FOR THE REQUESTED SERVICE (order-service or
 * payment-service), or an honest "no metrics found" for anything else.
 */
@Component
public class GetMetricsTool implements Tool {

    @Override
    public String name() {
        return "get_metrics";
    }

    @Override
    public String description() {
        return "Return a numeric time series (e.g. error_rate, p95_latency) for a service.";
    }

    @Override
    public String parametersJsonSchema() {
        return """
               {
                 "type": "object",
                 "properties": {
                   "service": { "type": "string", "description": "service name, e.g. order-service" },
                   "metric": { "type": "string", "description": "e.g. error_rate or p95_latency" }
                 },
                 "required": ["service", "metric"]
               }
               """;
    }

    @Override
    public String execute(Map<String, String> args) {
        // Default to order-service so legacy callers without a service arg still work.
        String service = args.getOrDefault("service", "order-service");
        return switch (service) {
            case "order-service" -> Resources.read("sample-incident/order-service-metrics.json");
            case "payment-service" -> Resources.read("sample-incident/payment-service-metrics.json");
            default -> "No metrics found for service '" + service + "' in the requested window.";
        };
    }
}

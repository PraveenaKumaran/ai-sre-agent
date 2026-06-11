package com.aisre.agent.tools;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Tool 1: get_logs(service, time_window) -> recent log lines.
 *
 * Serves the canned sample logs FOR THE REQUESTED SERVICE: order-service (the NPE
 * scenario), payment-service (the misleading pool-exhaustion scenario), or an
 * honest "no logs found" for anything else — an unknown service yields thin
 * evidence, which is exactly what should push the pipeline toward escalation.
 */
@Component
public class GetLogsTool implements Tool {

    @Override
    public String name() {
        return "get_logs";
    }

    @Override
    public String description() {
        return "Return recent log lines for a service and time window.";
    }

    @Override
    public String parametersJsonSchema() {
        return """
               {
                 "type": "object",
                 "properties": {
                   "service": { "type": "string", "description": "service name, e.g. order-service" },
                   "time_window": { "type": "string", "description": "e.g. last_15m" }
                 },
                 "required": ["service"]
               }
               """;
    }

    @Override
    public String execute(Map<String, String> args) {
        // Default to order-service so legacy callers without a service arg still work.
        String service = args.getOrDefault("service", "order-service");
        return switch (service) {
            case "order-service" -> Resources.read("sample-incident/order-service.log");
            case "payment-service" -> Resources.read("sample-incident/payment-service.log");
            default -> "No logs found for service '" + service + "' in the requested window.";
        };
    }
}

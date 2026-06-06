package com.aisre.agent.tools;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Tool 2: get_metrics(service, metric) -> numeric series.
 *
 * Phase 1 stub: returns the canned metrics file (error rate + p95 latency + deploys).
 * Phase 2+/stretch: would query a real metrics backend.
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
    public String execute(Map<String, String> args) {
        // Args (service, metric) accepted but ignored in the stub.
        return Resources.read("sample-incident/order-service-metrics.json");
    }
}

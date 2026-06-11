package com.aisre.agent.tools;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * get_logs / get_metrics serve data for the REQUESTED service: the two canned
 * scenarios by name, an honest "no data" answer for unknown services (thin
 * evidence -> the pipeline should escalate), and order-service as the legacy
 * default when no service arg is given.
 */
class ToolDataSelectionTest {

    private final GetLogsTool logs = new GetLogsTool();
    private final GetMetricsTool metrics = new GetMetricsTool();

    @Test
    void orderServiceGetsTheNpeScenario() {
        assertThat(logs.execute(Map.of("service", "order-service")))
                .contains("NullPointerException")
                .contains("loyaltyTier");
        assertThat(metrics.execute(Map.of("service", "order-service", "metric", "error_rate")))
                .contains("legacy customer import");
    }

    @Test
    void paymentServiceGetsTheMisleadingPoolScenario() {
        String logText = logs.execute(Map.of("service", "payment-service"));
        // The bait (downstream timeout) AND the truth (pool saturation, healthy gateway).
        assertThat(logText)
                .contains("SocketTimeoutException")
                .contains("active=5 max=5")
                .contains("bank-gateway responded 200 OK");
        assertThat(metrics.execute(Map.of("service", "payment-service", "metric", "error_rate")))
                .contains("connection_pool_active")
                .contains("bank_gateway_p95_latency_ms");
    }

    @Test
    void unknownServiceGetsAnHonestNoDataAnswer() {
        assertThat(logs.execute(Map.of("service", "checkout-service")))
                .contains("No logs found for service 'checkout-service'");
        assertThat(metrics.execute(Map.of("service", "checkout-service", "metric", "error_rate")))
                .contains("No metrics found for service 'checkout-service'");
    }

    @Test
    void missingServiceArgDefaultsToOrderService() {
        assertThat(logs.execute(Map.of())).contains("loyaltyTier");
    }
}

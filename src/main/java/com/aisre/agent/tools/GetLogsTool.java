package com.aisre.agent.tools;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Tool 1: get_logs(service, time_window) -> recent log lines.
 *
 * Phase 1 stub: ignores the arguments and returns the canned sample log file.
 * Phase 2+/stretch: would query a real log backend for the given service/window.
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
    public String execute(Map<String, String> args) {
        // Args (service, time_window) are accepted but ignored in the stub.
        return Resources.read("sample-incident/order-service.log");
    }
}

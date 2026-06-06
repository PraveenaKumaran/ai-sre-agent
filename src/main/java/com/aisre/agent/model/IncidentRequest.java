package com.aisre.agent.model;

/**
 * What the caller POSTs to /triage: the minimum needed to describe an incident.
 *
 * A Java {@code record} is a compact, immutable data holder — Spring fills these
 * fields automatically from the incoming JSON (matching by field name).
 *
 * @param service    the name of the failing service, e.g. "order-service"
 * @param stackTrace the error / stack trace text pasted from the logs
 */
public record IncidentRequest(
        String service,
        String stackTrace
) {
}

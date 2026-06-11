// SAMPLE / SYNTHETIC buggy config for the AI SRE agent to inspect.
// This is NOT part of the running application. It lives under resources as
// canned data, like OrderService.java.
package com.shop.payment;

public class HttpClientConfig {

    // BUG (deploy v3.2.0, 09:05): "connection pool tuning" cut the pool from 50
    // to 5. Under normal traffic the pool exhausts immediately, so requests wait
    // for a connection and time out CLIENT-SIDE before ever reaching the
    // perfectly healthy bank-gateway. Was: MAX_CONNECTIONS = 50.
    public static final int MAX_CONNECTIONS = 5;            // <-- root cause

    public static final int CONNECT_TIMEOUT_MS = 2_000;
    public static final int READ_TIMEOUT_MS = 15_000;

    public HttpClientPool bankGatewayPool() {
        return new HttpClientPool("bank-gateway", MAX_CONNECTIONS, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
    }
}

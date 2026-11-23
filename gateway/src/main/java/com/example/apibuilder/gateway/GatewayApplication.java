package com.example.apibuilder.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The public entry point. It serves the single-page UI and reverse-proxies management traffic to the
 * builder service and published-API traffic to the runtime service, so the browser only ever sees one origin.
 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}

package com.example.apibuilder.builder;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The builder service: it composes, validates, versions and stores APIs, and exposes the time machine
 * and curl builder. The runtime service asks it for the definition behind each published API.
 */
@SpringBootApplication(scanBasePackages = "com.example.apibuilder")
public class BuilderApplication {

    public static void main(String[] args) {
        SpringApplication.run(BuilderApplication.class, args);
    }
}

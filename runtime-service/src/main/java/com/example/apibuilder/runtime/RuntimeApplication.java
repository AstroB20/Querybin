package com.example.apibuilder.runtime;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The runtime service: it serves every published API. It never stores definitions itself; it asks the
 * builder service for the definition behind a slug, compiles it to SQL and runs it against the database.
 */
@SpringBootApplication(scanBasePackages = "com.example.apibuilder")
public class RuntimeApplication {

    public static void main(String[] args) {
        SpringApplication.run(RuntimeApplication.class, args);
    }
}

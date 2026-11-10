package com.example.discount.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;

/** Wires the shared client the test suite talks to the running service through. */
@TestConfiguration
public class TestSupportConfig {

    @Bean
    public CompatibilityClient compatibilityClient(TestRestTemplate rest) {
        return new CompatibilityClient(rest);
    }
}

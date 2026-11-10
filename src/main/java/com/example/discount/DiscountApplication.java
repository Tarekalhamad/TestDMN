package com.example.discount;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * There is one evaluation path: the hot-reloadable DMN runtime behind
 * {@code POST /api/compatibility/check}. The Kogito Maven plugin still runs at build time as a
 * model validator, but it no longer generates a REST resource
 * ({@code kogito.generate.rest=false}), so there is no second endpoint to drift from this one.
 */
@SpringBootApplication(scanBasePackages = {"com.example.discount", "org.kie.kogito.**", "org.kie.kogito.app.**"})
public class DiscountApplication {

    public static void main(String[] args) {
        SpringApplication.run(DiscountApplication.class, args);
    }
}

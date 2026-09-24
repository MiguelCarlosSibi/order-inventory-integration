package edu.cit.sibi.supplier;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * A short timeout is Part D's first resilience requirement: 3 seconds is
 * generous for a healthy call and short enough that one slow LegacySupply
 * request can't stall the whole reorder flow (or, worse, an HTTP request
 * thread on our side) for long.
 */
@Configuration
class SupplierRestTemplateConfig {

    @Bean
    RestTemplate supplierRestTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(3))
                .setReadTimeout(Duration.ofSeconds(3))
                .build();
    }
}

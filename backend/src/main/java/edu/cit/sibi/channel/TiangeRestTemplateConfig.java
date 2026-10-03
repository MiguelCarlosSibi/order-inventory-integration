package edu.cit.sibi.channel;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * Same reasoning as supplier.SupplierRestTemplateConfig: a short timeout so
 * a slow Tiangge call can't stall the feed poller or heartbeat thread for
 * long. 3 seconds is generous for a healthy call and short enough that one
 * slow request doesn't threaten the 60-second decision deadline.
 */
@Configuration
class TiangeRestTemplateConfig {

    @Bean
    RestTemplate tiangeRestTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(3))
                .setReadTimeout(Duration.ofSeconds(3))
                .build();
    }
}

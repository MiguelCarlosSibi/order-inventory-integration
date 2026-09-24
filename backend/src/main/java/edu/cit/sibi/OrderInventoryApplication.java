package edu.cit.sibi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Parent application class. Living in edu.cit.sibi means component scanning
 * automatically covers edu.cit.sibi.shop, .inventory, .notification, and
 * .supplier without any extra configuration. @EnableScheduling turns on the
 * supplier module's @Scheduled jobs (PendingReorderRetryJob,
 * DeliveryTrackingJob).
 */
@SpringBootApplication
@EnableScheduling @EnableAsync
public class OrderInventoryApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderInventoryApplication.class, args);
    }
}

package edu.cit.sibi.channel;

import edu.cit.sibi.common.ClientInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * Task 1 (go live): sends the very first heartbeat as soon as the app
 * starts — before any other Tiangge call, per the manual — then one every
 * 30 seconds for as long as the process runs. Tiangge treats an instance
 * silent for more than 90 seconds as offline, so this alone is what keeps
 * "App online" true on the self-check page.
 */
@Component
class HeartbeatScheduler {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatScheduler.class);

    private final TiangeClient client;
    private final ClientInstance clientInstance;
    private final String appName;

    HeartbeatScheduler(TiangeClient client, ClientInstance clientInstance,
                        @Value("${tiangge.app-name:order-inventory-integration}") String appName) {
        this.client = client;
        this.clientInstance = clientInstance;
        this.appName = appName;
    }

    /** @Order(1): must run before ListingAndStockPublisher's @Order(2) listener. */
    @EventListener(ApplicationReadyEvent.class)
    @Order(1)
    void sendFirstHeartbeat() {
        beat();
    }

    @Scheduled(fixedRate = 30_000)
    void sendHeartbeat() {
        beat();
    }

    private void beat() {
        long uptimeSeconds = Duration.between(clientInstance.startedAt(), Instant.now()).toSeconds();
        HeartbeatRequest request = new HeartbeatRequest(
                appName,
                DateTimeFormatter.ISO_INSTANT.format(clientInstance.startedAt()),
                uptimeSeconds);
        try {
            HeartbeatResponse response = client.sendHeartbeat(request);
            log.info("Tiangge heartbeat ok (instance {}), server time {}", clientInstance.id(), response.serverTime());
        } catch (RuntimeException e) {
            log.warn("Tiangge heartbeat failed: {}", e.getMessage());
        }
    }
}

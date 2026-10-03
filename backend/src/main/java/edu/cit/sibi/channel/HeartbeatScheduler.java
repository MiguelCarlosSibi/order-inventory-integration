package edu.cit.sibi.channel;

import edu.cit.sibi.common.ClientInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Sends the very first heartbeat as soon as the app starts, then one every 5 seconds.
 * TiangeClient also refreshes it inline before any other call if it has gone stale.
 */
@Component
class HeartbeatScheduler {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatScheduler.class);

    /** Own thread: the shared @Scheduled thread can be busy for seconds (feed polling) and must not delay a heartbeat. */
    private final ScheduledExecutorService beats = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "tiangge-heartbeat");
        t.setDaemon(true);
        return t;
    });

    private final TiangeClient client;
    private final ClientInstance clientInstance;

    HeartbeatScheduler(TiangeClient client, ClientInstance clientInstance) {
        this.client = client;
        this.clientInstance = clientInstance;
    }

    /** @Order(1): must run before ListingAndStockPublisher's @Order(2) listener. */
    @EventListener(ApplicationReadyEvent.class)
    @Order(1)
    void sendFirstHeartbeat() {
        beat();
        beats.scheduleWithFixedDelay(this::beat, 5, 5, TimeUnit.SECONDS);
    }

    private void beat() {
        try {
            HeartbeatResponse response = client.sendHeartbeat();
            log.info("Tiangge heartbeat ok (instance {}), server time {}", clientInstance.id(), response.serverTime());
        } catch (RuntimeException e) {
            log.warn("Tiangge heartbeat failed: {}", e.getMessage());
        }
    }
}
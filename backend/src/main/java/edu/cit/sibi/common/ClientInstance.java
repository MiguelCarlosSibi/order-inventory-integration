package edu.cit.sibi.common;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * One random instance ID generated when the JVM starts, held for the life
 * of the process. Lab 4 (Tiangge) requires this exact UUID to be sent as
 * X-Client-Instance on every call to BOTH Tiangge and LegacySupply, so both
 * external systems can tell which running copy of this app made a given
 * request. Lives outside edu.cit.sibi.channel (rather than being one of
 * that module's public domain types) specifically so edu.cit.sibi.supplier
 * can depend on it too without creating a supplier -> channel coupling.
 */
@Component
public class ClientInstance {

    private final String instanceId = UUID.randomUUID().toString();
    private final Instant startedAt = Instant.now();

    public String id() {
        return instanceId;
    }

    public Instant startedAt() {
        return startedAt;
    }

    private volatile Instant lastHeartbeatOk;

    /** Called by the Tiangge client each time Tiangge accepts a heartbeat from this instance. */
    public void heartbeatAccepted() {
        lastHeartbeatOk = Instant.now();
    }

    /** True if Tiangge has accepted a heartbeat from this instance within the given time. */
    public boolean hasRecentHeartbeat(Duration maxAge) {
        Instant t = lastHeartbeatOk;
        return t != null && Duration.between(t, Instant.now()).compareTo(maxAge) < 0;
    }
}

package edu.cit.sibi.channel;

import edu.cit.sibi.inventory.InventoryChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Task 3: publishes stock to Tiangge after ANY committed inventory change,
 * driven by InventoryChangedEvent (no timer decides WHEN stock changed).
 * <ul>
 *   <li>Always sends the CURRENT figure, built fresh on every attempt (a failed
 *       request is never replayed with an old number).</li>
 *   <li>Runs on one background thread, so feed and backorder workers never wait on Tiangge.</li>
 *   <li>Has no dependency on any other Tiangge call succeeding. Ordering against decisions is
 *       handled by the figure itself: see {@link ChannelStore#publishableStock()}.</li>
 *   <li>A product whose update fails stays "dirty" and is retried every half second.</li>
 *   <li>An event-driven update is skipped when the figure is the same as the last one Tiangge
 *       received. (Reserving stock for an order does not change the figure until the decision has
 *       been delivered, so no update can land after a decision while still ignoring that order.)
 *       Updates requested through publishProducts/publishAll are always sent.</li>
 * </ul>
 */
@Component
class StockSyncListener {

    private static final Logger log = LoggerFactory.getLogger(StockSyncListener.class);

    private final TiangeClient client;
    private final ChannelStore store;
    private final Set<String> dirty = ConcurrentHashMap.newKeySet();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    /** Products whose next update must be sent even if the figure looks unchanged. */
    private final Set<String> forced = ConcurrentHashMap.newKeySet();
    /** The figure Tiangge last accepted for each product. */
    private final Map<String, Integer> lastSent = new ConcurrentHashMap<>();
    private final AtomicBoolean flushQueued = new AtomicBoolean(false);
    private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "stock-sync");
        t.setDaemon(true);
        return t;
    });

    StockSyncListener(TiangeClient client, ChannelStore store) {
        this.client = client;
        this.store = store;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onInventoryChanged(InventoryChangedEvent event) {
        publish(List.of(event.productId()));
    }

    /** Re-sends every product. Used after a decision, a resolution or a cancellation confirmation has been delivered. */
    void publishAll() {
        Set<String> all = store.publishableStock().keySet();
        forced.addAll(all);
        publish(all);
    }

    /** Always sends these products, even if the figure looks unchanged (used after a decision, resolution or cancellation confirmation). */
    void publishProducts(Collection<String> productIds) {
        forced.addAll(productIds);
        publish(productIds);
    }

    private void publish(Collection<String> productIds) {
        dirty.addAll(productIds);
        requestFlush();
    }

    private void requestFlush() {
        if (flushQueued.compareAndSet(false, true)) {
            sender.submit(() -> {
                flushQueued.set(false);
                flush();
            });
        }
    }

    /** Resends updates that failed earlier. */
    @Scheduled(fixedDelay = 500)
    void retryFailed() {
        if (!dirty.isEmpty()) {
            requestFlush();
        }
    }

    private void flush() {
        List<String> ids = new ArrayList<>(dirty);
        if (ids.isEmpty()) {
            return;
        }
        inFlight.addAll(ids);
        dirty.removeAll(ids);
        List<String> forcedNow = ids.stream().filter(forced::contains).toList();
        forced.removeAll(forcedNow);
        try {
            Map<String, Integer> available = store.publishableStock();
            List<StockDto> stock = ids.stream()
                    .filter(available::containsKey)
                    .filter(id -> forcedNow.contains(id) || !Objects.equals(lastSent.get(id), available.get(id)))
                    .map(id -> new StockDto(id, available.get(id)))
                    .toList();
            if (stock.isEmpty()) {
                return;   // nothing Tiangge does not already know
            }
            client.publishStock(stock);
            stock.forEach(sent -> lastSent.put(sent.sellerSku(), sent.available()));
            log.info("Synced Tiangge stock: {}", stock);

            // The figure may have moved while the request was in flight: send again right away.
            Map<String, Integer> after = store.publishableStock();
            boolean moved = false;
            for (StockDto sent : stock) {
                Integer now = after.get(sent.sellerSku());
                if (now != null && now != sent.available()) {
                    dirty.add(sent.sellerSku());
                    moved = true;
                }
            }
            if (moved) {
                requestFlush();
            }
        } catch (RuntimeException e) {
            dirty.addAll(ids);
            forced.addAll(forcedNow);
            // The request may or may not have reached Tiangge: do not trust lastSent for these.
            ids.forEach(lastSent::remove);
            log.warn("Stock sync for {} failed, will retry: {}", ids, e.getMessage());
        } finally {
            inFlight.removeAll(ids);
        }
    }

    /** True when nothing is waiting to be sent and nothing is being sent right now. */
    boolean caughtUp() {
        return dirty.isEmpty() && inFlight.isEmpty();
    }

    /** Waits (at most maxMillis) until Tiangge has our latest stock figures. Never call this from the stock-sync thread. */
    void awaitCaughtUp(long maxMillis) {
        long end = System.currentTimeMillis() + maxMillis;
        while (!caughtUp() && System.currentTimeMillis() < end) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
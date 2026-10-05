package edu.cit.sibi.channel;

import edu.cit.sibi.inventory.InventoryChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

@Component
class StockSyncListener {

    private static final Logger log = LoggerFactory.getLogger(StockSyncListener.class);

    private final TiangeClient client;
    private final ChannelStore store;
    private final Set<String> dirty = ConcurrentHashMap.newKeySet();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    /** The last figure Tiangge actually accepted, per product. */
    private final Map<String, Integer> lastSent = new ConcurrentHashMap<>();
    private final AtomicBoolean flushQueued = new AtomicBoolean(false);

    /** If a decision's outcome is still unknown after this long, stop holding stock back for it. */
    private static final long AMBIGUOUS_MAX_MILLIS = 60_000;
    /** After a stock PUT times out Tiangge may still apply it late: keep acceptances away for a moment. */
    private static final long PUT_UNCERTAIN_MILLIS = 3_500;

    /**
     * One lock per product. A stock PUT takes the WRITE lock; delivering an acceptance takes the READ
     * lock. So a stock figure can never be in flight at the same moment as an acceptance for the same
     * product (that is exactly how a figure ends up "ignoring" an accepted order), while acceptances
     * still run in parallel with each other.
     */
    private final Map<String, ReentrantReadWriteLock> locks = new ConcurrentHashMap<>();
    /** What Tiangge believes is available per product: last accepted figure minus acceptances delivered since. */
    private final Object viewMonitor = new Object();
    private final Map<String, Integer> view = new HashMap<>();
    /** Decisions whose delivery failed in a way where Tiangge may or may not have received them. */
    private final Map<String, Ambiguous> ambiguous = new ConcurrentHashMap<>();
    /** Products whose stock must not be published right now (a cancellation is being confirmed). */
    private final Map<String, Integer> embargo = new ConcurrentHashMap<>();
    private final Map<String, Long> putUncertainUntil = new ConcurrentHashMap<>();

    private record Ambiguous(Set<String> products, long since) {
    }
    private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "stock-sync");
        t.setDaemon(true);
        return t;
    });
    /** Own timer thread, so retries can't be starved by the shared Spring scheduler. */
    private final ScheduledExecutorService retryTicker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "stock-retry");
        t.setDaemon(true);
        return t;
    });

    StockSyncListener(TiangeClient client, ChannelStore store) {
        this.client = client;
        this.store = store;
        retryTicker.scheduleWithFixedDelay(this::retryFailed, 500, 500, TimeUnit.MILLISECONDS);
        log.info("Stock sync v2 active: ordered sends, dedicated retry timer");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onInventoryChanged(InventoryChangedEvent event) {
        publish(List.of(event.productId()));
    }

    /** Re-sends every product. Used after a decision, a resolution or a cancellation confirmation. */
    void publishAll() {
        publish(store.publishableStock().keySet());
    }

    private void publish(Collection<String> productIds) {
        dirty.addAll(productIds);
        log.info("DIAG stock-publish-request products={} dirtyNow={}", productIds, dirty);
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

    private void retryFailed() {
        try {
            if (!dirty.isEmpty()) {
                requestFlush();
            }
        } catch (RuntimeException e) {
            log.warn("Stock retry tick failed: {}", e.getMessage());
        }
    }

    private void flush() {
        List<String> ids = new ArrayList<>(dirty);
        if (ids.isEmpty()) {
            return;
        }
        inFlight.addAll(ids);
        dirty.removeAll(ids);
        List<Lock> held = new ArrayList<>();
        try {
            // Only products with an UNKNOWN decision outcome (sent, but we never heard back) are held
            // back: if that decision did reach Tiangge, a figure sent now would arrive after the
            // acceptance and ignore it. Merely "not delivered yet" is NOT a reason to wait any more -
            // publishableStock() already adds those units back, and the per-product lock below keeps
            // figure and acceptance apart.
            Set<String> unsure = ambiguousProducts();
            List<String> sendable = new ArrayList<>();
            for (String id : ids) {
                if (unsure.contains(id) || embargo.containsKey(id)) {
                    dirty.add(id);
                    log.info("DIAG stock-defer product={} ambiguous={} embargoed={} embargoCount={}",
                            id, unsure.contains(id), embargo.containsKey(id), embargo.get(id));
                } else {
                    sendable.add(id);
                }
            }
            if (sendable.isEmpty()) {
                log.info("DIAG stock-flush produced nothing sendable, ids={} unsure={} embargoed={}",
                        ids, unsure, embargo.keySet());
                return;
            }

            held = acquire(sendable, true);
            // Read AFTER taking the lock: no acceptance can be delivered while we hold it, so the
            // figure we compute is the figure Tiangge will have when it receives it.
            Map<String, Integer> available = store.publishableStock();
            List<StockDto> stock = sendable.stream()
                    .filter(available::containsKey)
                    .map(id -> new StockDto(id, available.get(id)))
                    .toList();
            if (stock.isEmpty()) {
                return;
            }
            putStock(stock);
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
            log.warn("Stock sync for {} failed, will retry: {}", ids, e.getMessage());
        } finally {
            release(held);
            inFlight.removeAll(ids);
        }
    }

    /** One PUT /stock. Records what Tiangge now believes; remembers a timeout, because it may still land late. */
    private void putStock(List<StockDto> stock) {
        try {
            client.publishStock(stock);
        } catch (ResourceAccessException e) {
            long until = System.currentTimeMillis() + PUT_UNCERTAIN_MILLIS;
            for (StockDto s : stock) {
                putUncertainUntil.put(s.sellerSku(), until);
            }
            throw e;
        }
        synchronized (viewMonitor) {
            for (StockDto s : stock) {
                view.put(s.sellerSku(), s.available());
            }
        }
        for (StockDto s : stock) {
            lastSent.put(s.sellerSku(), s.available());
        }
    }

    // ------------------------------------------------------------ locking helpers

    private ReentrantReadWriteLock lockFor(String productId) {
        return locks.computeIfAbsent(productId, k -> new ReentrantReadWriteLock());
    }

    /** Always in sorted order, so two callers can never wait on each other. */
    private List<Lock> acquire(Collection<String> productIds, boolean write) {
        List<Lock> held = new ArrayList<>();
        for (String id : new TreeSet<>(productIds)) {
            Lock l = write ? lockFor(id).writeLock() : lockFor(id).readLock();
            l.lock();
            held.add(l);
        }
        return held;
    }

    private static void release(List<Lock> held) {
        for (int i = held.size() - 1; i >= 0; i--) {
            held.get(i).unlock();
        }
    }

    // ------------------------------------------------- cancellation: confirm first, THEN stock

    /** While held, no stock figure for these products goes out (the cancellation must be confirmed first). */
    void holdPublishing(Collection<String> productIds) {
        for (String id : productIds) {
            int now = embargo.merge(id, 1, Integer::sum);
            log.info("DIAG embargo-hold product={} count={}", id, now);
        }
    }

    void releasePublishing(Collection<String> productIds) {
        for (String id : productIds) {
            embargo.computeIfPresent(id, (k, v) -> v <= 1 ? null : v - 1);
            log.info("DIAG embargo-release product={} countAfter={}", id, embargo.get(id));
        }
    }

    // ------------------------------------------------------------- unknown outcomes

    private void markAmbiguous(String orderKey, Collection<String> products) {
        ambiguous.put(orderKey, new Ambiguous(Set.copyOf(products), System.currentTimeMillis()));
    }

    /** The decision was delivered (or abandoned): stock for its products may flow again. */
    void clearAmbiguous(String orderKey) {
        Ambiguous a = ambiguous.remove(orderKey);
        if (a != null) {
            publish(a.products());
        }
    }

    private Set<String> ambiguousProducts() {
        long now = System.currentTimeMillis();
        Set<String> out = new java.util.HashSet<>();
        ambiguous.entrySet().removeIf(e -> now - e.getValue().since() > AMBIGUOUS_MAX_MILLIS);
        for (Ambiguous a : ambiguous.values()) {
            out.addAll(a.products());
        }
        return out;
    }

    // ------------------------------------------------------------ acceptances

    private boolean reserveView(Map<String, Integer> need) {
        synchronized (viewMonitor) {
            for (Map.Entry<String, Integer> e : need.entrySet()) {
                Integer v = view.get(e.getKey());
                if (v == null || v < e.getValue()) {
                    return false;
                }
            }
            for (Map.Entry<String, Integer> e : need.entrySet()) {
                view.merge(e.getKey(), -e.getValue(), Integer::sum);
            }
            return true;
        }
    }

    private void unreserveView(Map<String, Integer> need) {
        synchronized (viewMonitor) {
            for (Map.Entry<String, Integer> e : need.entrySet()) {
                view.merge(e.getKey(), e.getValue(), Integer::sum);
            }
        }
    }

    /**
     * Delivers an ACCEPTED decision (or an ACCEPTED backorder resolution) safely:
     * <ul>
     *   <li>it only goes out if what Tiangge currently believes is available - the last figure it
     *       accepted MINUS acceptances delivered since - covers the order; otherwise a fresh figure is
     *       sent first (so an order is never accepted from stock Tiangge has not been told about);</li>
     *   <li>it holds the READ lock of every product on the order for the duration of the call, so no
     *       stock figure can cross it on the wire.</li>
     * </ul>
     * Retries (3 attempts) happen between lock holds, never inside one, so a slow call cannot starve stock updates.
     */
    <T> T sendAcceptance(String orderKey, Map<String, Integer> need, Supplier<T> call) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            awaitQuiet(need.keySet());
            boolean needsFreshFigure = false;
            boolean reserved = false;
            List<Lock> held = acquire(need.keySet(), false);
            try {
                boolean ok = reserveView(need);
                log.info("DIAG sendAcceptance order={} need={} reserveViewOk={} attempt={}/3",
                        orderKey, need, ok, attempt);
                if (!ok) {
                    needsFreshFigure = true;
                } else {
                    reserved = true;
                    T result = call.get();
                    clearAmbiguous(orderKey);
                    return result;
                }
            } catch (HttpServerErrorException | ResourceAccessException e) {
                // Tiangge may or may not have received it: hold this product's stock until we know.
                last = e;
                if (reserved) {
                    unreserveView(need);
                }
                markAmbiguous(orderKey, need.keySet());
                log.warn("Tiangge acceptance for {} failed (attempt {}/3): {}", orderKey, attempt, e.getMessage());
            } catch (IllegalStateException e) {
                last = e;
                if (reserved) {
                    unreserveView(need);
                }
                log.warn("Tiangge acceptance for {} not sent (attempt {}/3): {}", orderKey, attempt, e.getMessage());
            } catch (RuntimeException e) {
                if (reserved) {
                    unreserveView(need);   // a definite refusal: Tiangge did not take it
                }
                throw e;
            } finally {
                release(held);
            }

            if (needsFreshFigure) {
                try {
                    publishCovering(need);
                } catch (HttpServerErrorException | ResourceAccessException | IllegalStateException e) {
                    last = e;
                    log.warn("Could not send a covering stock figure for {} (attempt {}/3): {}", orderKey, attempt, e.getMessage());
                }
            }
            if (attempt < 3 && !pause(400L * attempt)) {
                break;
            }
        }
        throw last != null ? last : new IllegalStateException("Acceptance for " + orderKey + " could not be delivered");
    }

    /** Sends the current figure for the order's products, exclusively, ahead of an acceptance. */
    private void publishCovering(Map<String, Integer> need) {
        for (String id : need.keySet()) {
            if (embargo.containsKey(id)) {
                throw new IllegalStateException("Stock publishing for " + id + " is on hold for a cancellation");
            }
        }
        List<Lock> held = acquire(need.keySet(), true);
        try {
            Map<String, Integer> available = store.publishableStock();
            List<StockDto> stock = new ArrayList<>();
            for (Map.Entry<String, Integer> e : need.entrySet()) {
                Integer figure = available.get(e.getKey());
                if (figure == null || figure < e.getValue()) {
                    throw new IllegalStateException("Stock figure for " + e.getKey() + " does not cover the order yet");
                }
                stock.add(new StockDto(e.getKey(), figure));
            }
            putStock(stock);
            log.info("Sent stock {} ahead of an acceptance Tiangge could not yet cover", stock);
        } finally {
            release(held);
        }
    }

    private void awaitQuiet(Collection<String> ids) {
        long until = 0;
        for (String id : ids) {
            until = Math.max(until, putUncertainUntil.getOrDefault(id, 0L));
        }
        long wait = until - System.currentTimeMillis();
        if (wait > 0) {
            pause(wait);
        }
    }

    /**
     * Delivers ONE decision/resolution call plus the DB flag that records it. Transient failures are
     * retried up to 3 times with a short backoff.
     * <p>
     * An earlier version held a single app-wide lock across this entire method AND every stock
     * flush, so only one decision or one stock update could be in flight anywhere in the app at a
     * time. Under real order volume that serialized everything behind whichever call happened to be
     * slow - the direct cause of stock updates arriving late or looking "ignored". Ordering here does
     * not need a lock: publishCovering (below) always re-reads live data and compares against what was
     * actually last sent, so it is correct however many of these run at once.
     */
    <T> T sendInOrder(Supplier<T> call) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return call.get();
            } catch (HttpServerErrorException | ResourceAccessException | IllegalStateException e) {
                last = e;
                log.warn("Tiangge call failed (attempt {}/3): {}", attempt, e.getMessage());
            }
            if (attempt < 3 && !pause(400L * attempt)) {
                break;
            }
        }
        throw last;
    }

    /** Waits until Tiangge has been SENT a figure covering the needed units for every product. */
    boolean awaitPublishedAtLeast(Map<String, Integer> need, long maxMillis) {
        long end = System.currentTimeMillis() + maxMillis;
        boolean nudged = false;
        while (true) {
            boolean ok = need.entrySet().stream().allMatch(e -> {
                Integer sent = lastSent.get(e.getKey());
                return sent != null && sent >= e.getValue()
                        && !dirty.contains(e.getKey()) && !inFlight.contains(e.getKey());
            });
            if (ok) {
                return true;
            }
            if (!nudged) {
                publish(need.keySet());
                nudged = true;
            }
            if (System.currentTimeMillis() >= end || !pause(100)) {
                return false;
            }
        }
    }

    private static boolean pause(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
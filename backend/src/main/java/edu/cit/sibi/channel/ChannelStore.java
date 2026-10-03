package edu.cit.sibi.channel;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.HashSet;
import java.util.Set;

@Component
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class ChannelStore {

    record ChannelOrder(String tianggeOrderId, long shopOrderId, String decision, Instant placedAt,
                        boolean decisionSent, boolean resolved, boolean cancelConfirmed) {
    }

    private static final String COLS =
            "tiangge_order_id, shop_order_id, decision, placed_at, decision_sent, resolved, cancel_confirmed";

    private static final RowMapper<ChannelOrder> MAPPER = (rs, i) -> new ChannelOrder(
            rs.getString("tiangge_order_id"), rs.getLong("shop_order_id"), rs.getString("decision"),
            rs.getTimestamp("placed_at").toInstant(), rs.getBoolean("decision_sent"),
            rs.getBoolean("resolved"), rs.getBoolean("cancel_confirmed"));

    private final JdbcTemplate jdbc;

    ChannelStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    long cursor() {
        Long v = jdbc.queryForObject("select last_seq from channel_cursor where id = 1", Long.class);
        return v == null ? 0 : v;
    }

    void saveCursor(long seq) {
        jdbc.update("update channel_cursor set last_seq = ? where id = 1 and last_seq < ?", seq, seq);
    }

    boolean eventSeen(String eventId) {
        Integer n = jdbc.queryForObject("select count(*) from channel_events where event_id = ?", Integer.class, eventId);
        return n != null && n > 0;
    }

    void markEventSeen(FeedEvent ev) {
        jdbc.update("insert into channel_events (event_id, seq, type, tiangge_order_id) values (?,?,?,?) "
                + "on conflict (event_id) do nothing", ev.eventId(), ev.seq(), ev.type(), ev.orderId());
    }

    Optional<ChannelOrder> find(String tianggeOrderId) {
        return jdbc.query("select " + COLS + " from channel_orders where tiangge_order_id = ?", MAPPER, tianggeOrderId)
                .stream().findFirst();
    }

    /** Plain insert on purpose: a duplicate throws, rolling back the shop order created in the same transaction. */
    void insertOrder(String tianggeOrderId, long shopOrderId, Instant placedAt, String decision) {
        jdbc.update("insert into channel_orders (tiangge_order_id, shop_order_id, placed_at, decision) values (?,?,?,?)",
                tianggeOrderId, shopOrderId, Timestamp.from(placedAt), decision);
    }

    List<ChannelOrder> unsentDecisions() {
        return jdbc.query("select " + COLS + " from channel_orders where decision_sent = false order by placed_at", MAPPER);
    }

    List<ChannelOrder> openBackorders() {
        return jdbc.query("select " + COLS + " from channel_orders where decision = 'BACKORDERED' "
                + "and decision_sent = true and resolved = false order by placed_at", MAPPER);
    }

    void markDecisionSent(String id) {
        jdbc.update("update channel_orders set decision_sent = true where tiangge_order_id = ?", id);
    }

    /** Only before the decision has been sent: a backorder that was filled in the meantime becomes an acceptance. */
    void changeDecision(String id, String decision) {
        jdbc.update("update channel_orders set decision = ? where tiangge_order_id = ? and decision_sent = false",
                decision, id);
    }
    /** Products that still have a reservation Tiangge has not been told about (decision or resolution not delivered). */
    Set<String> productsWithUndeliveredDecisions() {
        String sql = """
                select distinct oi.product_id
                from channel_orders co
                join orders o on o.order_id = co.shop_order_id and o.status = 'CONFIRMED'
                join order_items oi on oi.order_id = o.order_id
                where (co.decision = 'ACCEPTED' and co.decision_sent = false)
                   or (co.decision = 'BACKORDERED' and co.resolved = false)
                """;
        Set<String> out = new HashSet<>();
        jdbc.query(sql, rs -> {
            out.add(rs.getString("product_id"));
        });
        return out;
    }

    /** Units of a product that Tiangge orders still waiting for stock (BACKORDERED) have been promised. */
    int backorderedUnits(String productId) {
        Integer n = jdbc.queryForObject(
                "select coalesce(sum(oi.quantity), 0) from order_items oi "
                        + "join orders o on o.order_id = oi.order_id "
                        + "join channel_orders co on co.shop_order_id = o.order_id "
                        + "where o.status = 'BACKORDERED' and oi.product_id = ?",
                Integer.class, productId);
        return n == null ? 0 : n;
    }

    /**
     * Units of a product promised to Tiangge orders that are still BACKORDERED and were placed before
     * the given shop order. Those customers are ahead in the queue for any stock that arrives.
     */
    int backorderedUnitsBefore(String productId, long shopOrderId) {
        Integer n = jdbc.queryForObject(
                "select coalesce(sum(oi.quantity), 0) from order_items oi "
                        + "join orders o on o.order_id = oi.order_id "
                        + "join channel_orders co on co.shop_order_id = o.order_id "
                        + "where o.status = 'BACKORDERED' and oi.product_id = ? and o.order_id < ?",
                Integer.class, productId, shopOrderId);
        return n == null ? 0 : n;
    }

    void markResolved(String id) {
        jdbc.update("update channel_orders set resolved = true where tiangge_order_id = ?", id);
    }

    void markCancelConfirmed(String id) {
        jdbc.update("update channel_orders set cancel_confirmed = true where tiangge_order_id = ?", id);
    }

    /**
     * The stock figure Tiangge should be shown for every product, computed in ONE statement:
     * our real stock PLUS the units already reserved for Tiangge orders whose acceptance Tiangge
     * has not been told about yet.
     */
    Map<String, Integer> publishableStock() {
        String sql = """
                select i.product_id, i.stock + coalesce(p.units, 0) as available
                from inventory i
                left join (
                    select oi.product_id, sum(oi.quantity) as units
                    from channel_orders co
                    join orders o on o.order_id = co.shop_order_id and o.status = 'CONFIRMED'
                    join order_items oi on oi.order_id = o.order_id
                    where (co.decision = 'ACCEPTED' and co.decision_sent = false)
                       or (co.decision = 'BACKORDERED' and co.resolved = false)
                    group by oi.product_id
                ) p on p.product_id = i.product_id
                """;
        Map<String, Integer> out = new HashMap<>();
        jdbc.query(sql, rs -> {
            out.put(rs.getString("product_id"), rs.getInt("available"));
        });
        return out;
    }
}
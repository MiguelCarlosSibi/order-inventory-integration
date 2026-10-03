package edu.cit.sibi.channel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * One row from GET /feed. A single class covers both ORDER_PLACED and
 * ORDER_CANCELLED shapes (ignoring unknown/irrelevant fields per type)
 * rather than two DTOs plus polymorphic deserialization — simpler given
 * the only fields that matter are type, orderId, eventId/seq, and (for
 * ORDER_PLACED) lines.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record FeedEvent(
        long seq,
        String eventId,
        String type, // "ORDER_PLACED" | "ORDER_CANCELLED"
        String orderId,
        String placedAt,
        String decisionDeadline,
        List<FeedLine> lines,
        BuyerDto buyer,
        String cancelledAt,
        String confirmDeadline
) {
}

package edu.cit.sibi.shop.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record OrderSummary(
        Long orderId,
        String status,
        String reason,
        OffsetDateTime createdAt,
        List<OrderItemResult> items
) {
}

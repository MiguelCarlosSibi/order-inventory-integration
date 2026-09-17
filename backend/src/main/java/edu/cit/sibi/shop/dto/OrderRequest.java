package edu.cit.sibi.shop.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** { items: [{ productId, quantity }, ...] } */
public record OrderRequest(
        @NotEmpty(message = "must contain at least one item")
        @Valid
        List<OrderItemRequest> items
) {
}

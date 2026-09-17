package edu.cit.sibi.shop.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record OrderItemRequest(
        @NotBlank(message = "must not be blank") String productId,
        @Positive(message = "must be greater than zero") int quantity
) {
}

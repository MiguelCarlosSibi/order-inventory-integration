package edu.cit.sibi.channel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Response shape for GET /orders/{orderId} and the decision/resolution/cancellation endpoints (all return the order). */
@JsonIgnoreProperties(ignoreUnknown = true)
record TiangeOrderView(String orderId, String status) {
}

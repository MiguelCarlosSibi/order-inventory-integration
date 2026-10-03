package edu.cit.sibi.channel;

import com.fasterxml.jackson.annotation.JsonInclude;

/** decision is one of ACCEPTED | REJECTED | BACKORDERED. */
@JsonInclude(JsonInclude.Include.NON_NULL)
record DecisionRequest(String decision, String shopOrderId, String reason) {
}
package edu.cit.sibi.channel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
record FeedResponse(List<FeedEvent> events, Long nextCursor) {
}

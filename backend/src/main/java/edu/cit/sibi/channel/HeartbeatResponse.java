package edu.cit.sibi.channel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
record HeartbeatResponse(String serverTime, int nextHeartbeatSeconds) {
}

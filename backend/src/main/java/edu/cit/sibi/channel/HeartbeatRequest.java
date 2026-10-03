package edu.cit.sibi.channel;

record HeartbeatRequest(String appName, String startedAt, long uptimeSeconds) {
}

package edu.cit.sibi.channel;

/**
 * The only public type of the channel module. Everything that talks to the
 * marketplace (HTTP client, JSON classes, feed poller, translators) stays
 * package-private.
 */
public interface SalesChannel {

    /** Human-readable name of the external sales channel. */
    String name();

    /** Sequence number of the last marketplace event this app has finished processing. */
    long lastProcessedEventSeq();
}
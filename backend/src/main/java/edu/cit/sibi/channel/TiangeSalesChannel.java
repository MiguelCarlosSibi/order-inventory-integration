package edu.cit.sibi.channel;

import org.springframework.stereotype.Component;

@Component
class TiangeSalesChannel implements SalesChannel {

    private final ChannelStore store;

    TiangeSalesChannel(ChannelStore store) {
        this.store = store;
    }

    @Override
    public String name() {
        return "Tiangge";
    }

    @Override
    public long lastProcessedEventSeq() {
        return store.cursor();
    }
}
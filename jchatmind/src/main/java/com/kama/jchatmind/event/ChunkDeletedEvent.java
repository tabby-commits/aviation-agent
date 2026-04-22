package com.kama.jchatmind.event;

import java.util.List;

/**
 * Fired when chunks are removed from a knowledge base. Used to keep the BM25
 * in-memory inverted index in sync with the database.
 */
public class ChunkDeletedEvent {

    private final String kbId;
    private final List<String> chunkIds;

    public ChunkDeletedEvent(String kbId, List<String> chunkIds) {
        this.kbId = kbId;
        this.chunkIds = chunkIds;
    }

    public String getKbId() {
        return kbId;
    }

    public List<String> getChunkIds() {
        return chunkIds;
    }
}

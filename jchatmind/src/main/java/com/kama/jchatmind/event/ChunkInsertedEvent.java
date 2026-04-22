package com.kama.jchatmind.event;

/**
 * Fired when a new chunk is inserted into the chunk_bge_m3 table. Used for
 * incremental BM25 inverted-index maintenance.
 */
public class ChunkInsertedEvent {

    private final String kbId;
    private final String chunkId;
    private final String content;

    public ChunkInsertedEvent(String kbId, String chunkId, String content) {
        this.kbId = kbId;
        this.chunkId = chunkId;
        this.content = content;
    }

    public String getKbId() {
        return kbId;
    }

    public String getChunkId() {
        return chunkId;
    }

    public String getContent() {
        return content;
    }
}

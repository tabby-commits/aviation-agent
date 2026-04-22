package com.kama.jchatmind.service.rag.bm25;

import com.kama.jchatmind.config.RagHybridProperties;
import com.kama.jchatmind.event.ChunkDeletedEvent;
import com.kama.jchatmind.event.ChunkInsertedEvent;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.model.entity.ChunkBgeM3;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages BM25 inverted indices keyed by kbId.
 * <p>
 * - Indices are built lazily on first use (loaded from the database).
 * - {@link ChunkInsertedEvent} and {@link ChunkDeletedEvent} drive incremental
 *   maintenance.
 * - ConcurrentHashMap.computeIfAbsent prevents concurrent rebuilds for the
 *   same kbId.
 */
@Slf4j
@Component
public class BM25IndexManager {

    private final Map<String, InvertedIndex> indexByKb = new ConcurrentHashMap<>();

    private final ChunkBgeM3Mapper chunkBgeM3Mapper;
    private final HybridTokenizer tokenizer;
    private final RagHybridProperties properties;

    public BM25IndexManager(ChunkBgeM3Mapper chunkBgeM3Mapper,
                            HybridTokenizer tokenizer,
                            RagHybridProperties properties) {
        this.chunkBgeM3Mapper = chunkBgeM3Mapper;
        this.tokenizer = tokenizer;
        this.properties = properties;
    }

    /**
     * Get or lazily build the inverted index for the given kbId.
     */
    public InvertedIndex getOrBuild(String kbId) {
        if (kbId == null || kbId.isEmpty()) {
            return null;
        }
        return indexByKb.computeIfAbsent(kbId, this::buildForKb);
    }

    private InvertedIndex buildForKb(String kbId) {
        long start = System.currentTimeMillis();
        InvertedIndex index = new InvertedIndex();
        List<ChunkBgeM3> chunks;
        try {
            chunks = chunkBgeM3Mapper.selectAllByKbIdLite(kbId);
        } catch (Exception e) {
            log.error("Failed to load chunks for BM25 index, returning empty index: kbId={}", kbId, e);
            return index;
        }
        if (chunks == null || chunks.isEmpty()) {
            log.info("BM25 index build: kbId={} has no chunks; using empty index", kbId);
            return index;
        }
        for (ChunkBgeM3 c : chunks) {
            List<String> tokens = tokenizer.tokenize(c.getContent());
            index.addDocument(c.getId(), tokens);
        }
        log.info("BM25 index build complete: kbId={}, docs={}, elapsed {} ms",
                kbId, chunks.size(), System.currentTimeMillis() - start);
        return index;
    }

    /**
     * Run BM25 search on the current index for the given kbId.
     */
    public List<InvertedIndex.ScoredDoc> search(String kbId, String query, int topK) {
        InvertedIndex index = getOrBuild(kbId);
        if (index == null) {
            return Collections.emptyList();
        }
        List<String> queryTokens = tokenizer.tokenize(query);
        if (queryTokens.isEmpty()) {
            return Collections.emptyList();
        }
        RagHybridProperties.Bm25 bm25 = properties.getBm25();
        return index.search(queryTokens, topK, new BM25Params(bm25.getK1(), bm25.getB()));
    }

    /**
     * Force a full rebuild of the index for the given kbId.
     */
    public void rebuild(String kbId) {
        if (kbId == null) return;
        indexByKb.remove(kbId);
        getOrBuild(kbId);
    }

    /**
     * Drop all cached indices. Mainly useful in tests.
     */
    public void clearAll() {
        indexByKb.clear();
    }

    @EventListener
    public void onChunkInserted(ChunkInsertedEvent event) {
        try {
            InvertedIndex index = indexByKb.get(event.getKbId());
            // If the KB is not yet loaded, skip: the next search will lazily
            // build the index from the database and thus include this chunk.
            if (index == null) {
                return;
            }
            List<String> tokens = tokenizer.tokenize(event.getContent());
            index.addDocument(event.getChunkId(), tokens);
        } catch (Exception e) {
            log.warn("BM25 index failed to handle ChunkInsertedEvent: kbId={}, chunkId={}",
                    event.getKbId(), event.getChunkId(), e);
        }
    }

    @EventListener
    public void onChunkDeleted(ChunkDeletedEvent event) {
        try {
            InvertedIndex index = indexByKb.get(event.getKbId());
            if (index == null || event.getChunkIds() == null) {
                return;
            }
            for (String id : event.getChunkIds()) {
                index.removeDocument(id);
            }
        } catch (Exception e) {
            log.warn("BM25 index failed to handle ChunkDeletedEvent: kbId={}", event.getKbId(), e);
        }
    }
}

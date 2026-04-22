package com.kama.jchatmind.service.rag.bm25;

import com.kama.jchatmind.config.RagHybridProperties;
import com.kama.jchatmind.event.ChunkDeletedEvent;
import com.kama.jchatmind.event.ChunkInsertedEvent;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.model.entity.ChunkBgeM3;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BM25IndexManagerTest {

    private ChunkBgeM3Mapper mapper;
    private BM25IndexManager manager;

    @BeforeEach
    void setUp() {
        mapper = Mockito.mock(ChunkBgeM3Mapper.class);
        HybridTokenizer tokenizer = new HybridTokenizer();
        RagHybridProperties props = new RagHybridProperties();
        manager = new BM25IndexManager(mapper, tokenizer, props);
    }

    private ChunkBgeM3 chunk(String id, String content) {
        return ChunkBgeM3.builder().id(id).content(content).build();
    }

    @Test
    void lazyBuildFromMapper() {
        Mockito.when(mapper.selectAllByKbIdLite("kb1")).thenReturn(List.of(
                chunk("c1", "Spring AI is an AI framework"),
                chunk("c2", "RAG is retrieval augmented generation"),
                chunk("c3", "pgvector is used for vector search")
        ));

        List<InvertedIndex.ScoredDoc> hits = manager.search("kb1", "spring", 5);
        assertEquals(1, hits.size());
        assertEquals("c1", hits.get(0).getDocId());

        // Second search must not hit the mapper again.
        manager.search("kb1", "rag", 5);
        Mockito.verify(mapper, Mockito.times(1)).selectAllByKbIdLite("kb1");
    }

    @Test
    void incrementalAddViaEvent() {
        Mockito.when(mapper.selectAllByKbIdLite("kb1")).thenReturn(List.of(
                chunk("c1", "document mentions spring only")
        ));
        manager.search("kb1", "spring", 5);
        assertTrue(manager.search("kb1", "fastapi", 5).isEmpty());

        manager.onChunkInserted(new ChunkInsertedEvent("kb1", "c2", "FastAPI is a Python framework"));
        List<InvertedIndex.ScoredDoc> hits = manager.search("kb1", "fastapi", 5);
        assertEquals(1, hits.size());
        assertEquals("c2", hits.get(0).getDocId());
    }

    @Test
    void incrementalDeleteViaEvent() {
        Mockito.when(mapper.selectAllByKbIdLite("kb1")).thenReturn(List.of(
                chunk("c1", "spring boot"),
                chunk("c2", "spring ai")
        ));
        manager.search("kb1", "spring", 5);
        assertEquals(2, manager.search("kb1", "spring", 5).size());

        manager.onChunkDeleted(new ChunkDeletedEvent("kb1", List.of("c1")));
        List<InvertedIndex.ScoredDoc> hits = manager.search("kb1", "spring", 5);
        assertEquals(1, hits.size());
        assertEquals("c2", hits.get(0).getDocId());
    }

    @Test
    void addEventBeforeBuildIsIgnoredSafely() {
        // Before any search has triggered the lazy build, the event must not
        // NPE and must not preemptively build the index.
        manager.onChunkInserted(new ChunkInsertedEvent("kb2", "c1", "hello"));
        Mockito.verify(mapper, Mockito.never()).selectAllByKbIdLite("kb2");
    }

    @Test
    void rebuildReloadsFromMapper() {
        Mockito.when(mapper.selectAllByKbIdLite("kb1"))
                .thenReturn(List.of(chunk("c1", "first")))
                .thenReturn(List.of(chunk("c2", "second")));

        manager.search("kb1", "first", 5);
        manager.rebuild("kb1");
        Mockito.verify(mapper, Mockito.times(2)).selectAllByKbIdLite("kb1");

        assertEquals(1, manager.search("kb1", "second", 5).size());
        assertTrue(manager.search("kb1", "first", 5).isEmpty());
    }
}

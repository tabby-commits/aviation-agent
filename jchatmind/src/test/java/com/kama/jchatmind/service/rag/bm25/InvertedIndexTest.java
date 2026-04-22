package com.kama.jchatmind.service.rag.bm25;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvertedIndexTest {

    @Test
    void emptyIndexReturnsEmptyResult() {
        InvertedIndex index = new InvertedIndex();
        assertTrue(index.search(List.of("a"), 3, BM25Params.DEFAULT).isEmpty());
    }

    @Test
    void buildSearchAndRemove() {
        InvertedIndex index = new InvertedIndex();
        index.addDocument("d1", Arrays.asList("spring", "ai", "agent"));
        index.addDocument("d2", Arrays.asList("java", "spring", "boot"));
        index.addDocument("d3", Arrays.asList("rag", "vector", "search"));

        assertEquals(3, index.getTotalDocs());
        assertEquals(3.0, index.getAvgDocLength(), 1e-9);

        List<InvertedIndex.ScoredDoc> hits = index.search(List.of("spring"), 10, BM25Params.DEFAULT);
        assertEquals(2, hits.size());
        assertEquals(hits.get(0).getScore(), hits.get(1).getScore(), 1e-9);

        List<InvertedIndex.ScoredDoc> hits2 = index.search(List.of("spring", "agent"), 10, BM25Params.DEFAULT);
        assertEquals(2, hits2.size());
        assertEquals("d1", hits2.get(0).getDocId());

        index.removeDocument("d1");
        assertEquals(2, index.getTotalDocs());

        List<InvertedIndex.ScoredDoc> afterRemove = index.search(List.of("agent"), 10, BM25Params.DEFAULT);
        assertTrue(afterRemove.isEmpty());
    }

    @Test
    void addDocumentOverwritesExisting() {
        InvertedIndex index = new InvertedIndex();
        index.addDocument("d1", Arrays.asList("foo", "bar"));
        index.addDocument("d1", Arrays.asList("baz"));
        assertEquals(1, index.getTotalDocs());
        assertTrue(index.search(List.of("foo"), 10, BM25Params.DEFAULT).isEmpty());
        assertFalse(index.search(List.of("baz"), 10, BM25Params.DEFAULT).isEmpty());
    }

    @Test
    void topKBoundsResultSize() {
        InvertedIndex index = new InvertedIndex();
        for (int i = 0; i < 10; i++) {
            index.addDocument("d" + i, List.of("common", "t" + i));
        }
        List<InvertedIndex.ScoredDoc> hits = index.search(List.of("common"), 3, BM25Params.DEFAULT);
        assertEquals(3, hits.size());
    }
}

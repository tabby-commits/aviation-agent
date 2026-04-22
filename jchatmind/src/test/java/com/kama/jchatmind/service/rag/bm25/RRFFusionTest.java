package com.kama.jchatmind.service.rag.bm25;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RRFFusionTest {

    @Test
    void emptyInputsReturnEmpty() {
        assertTrue(RRFFusion.fuse(60, 5, null).isEmpty());
        assertTrue(RRFFusion.fuse(60, 5, Collections.emptyList()).isEmpty());
        assertTrue(RRFFusion.fuse(60, 0, List.of(List.of("a"))).isEmpty());
    }

    @Test
    void sameIdInBothListsScoresHigher() {
        List<String> vec = List.of("a", "b", "c");
        List<String> bm = List.of("b", "d", "e");
        List<String> result = RRFFusion.fuse(60, 5, List.of(vec, bm));
        // "b" appears in both lists so it should rank first.
        assertEquals("b", result.get(0));
        assertEquals(5, result.size());
    }

    @Test
    void higherRankScoresHigher() {
        List<String> list = List.of("a", "b", "c", "d");
        List<String> result = RRFFusion.fuse(60, 4, List.of(list));
        assertEquals(List.of("a", "b", "c", "d"), result);
    }

    @Test
    void topNLimitsResult() {
        List<String> a = List.of("a", "b", "c", "d", "e");
        List<String> result = RRFFusion.fuse(60, 2, List.of(a));
        assertEquals(2, result.size());
        assertEquals("a", result.get(0));
        assertEquals("b", result.get(1));
    }

    @Test
    void duplicatesWithinOneListAreSummed() {
        // Repeated ids within a single ranked list accumulate; in practice
        // callers should de-duplicate, but we keep the behaviour well-defined.
        List<String> vec = Arrays.asList("a", "a", "b");
        List<String> result = RRFFusion.fuse(60, 3, List.of(vec));
        assertEquals("a", result.get(0));
    }

    @Test
    void nullIdsSkipped() {
        List<String> vec = Arrays.asList(null, "a", null, "b");
        List<String> result = RRFFusion.fuse(60, 3, List.of(vec));
        assertEquals(List.of("a", "b"), result);
    }
}

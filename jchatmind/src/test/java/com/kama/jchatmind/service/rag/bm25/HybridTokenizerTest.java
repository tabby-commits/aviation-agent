package com.kama.jchatmind.service.rag.bm25;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HybridTokenizerTest {

    private final HybridTokenizer tokenizer = new HybridTokenizer();

    @Test
    void nullOrEmptyReturnsEmpty() {
        assertTrue(tokenizer.tokenize(null).isEmpty());
        assertTrue(tokenizer.tokenize("").isEmpty());
    }

    @Test
    void asciiLowercased() {
        List<String> tokens = tokenizer.tokenize("Spring AI 1.1.0");
        assertTrue(tokens.contains("spring"));
        assertTrue(tokens.contains("ai"));
        assertTrue(tokens.contains("1"));
        assertTrue(tokens.contains("0"));
    }

    @Test
    void chineseTokenizedAndPunctuationDropped() {
        // "\u673a\u5668\u5b66\u4e60\u5f88\u6709\u8da3\uff01" -> machine learning is fun!
        List<String> tokens = tokenizer.tokenize("\u673a\u5668\u5b66\u4e60\u5f88\u6709\u8da3\uff01");
        assertFalse(tokens.contains("\uff01"));
        boolean hasMl = tokens.stream().anyMatch(t -> t.contains("\u673a\u5668") || t.equals("\u673a\u5668\u5b66\u4e60"));
        assertTrue(hasMl, "expected a token related to machine learning, got: " + tokens);
    }

    @Test
    void mixedChineseAndEnglishKeptSeparately() {
        // "\u4f7f\u7528 DeepSeek \u548c glm-4.6 \u6a21\u578b" -> using DeepSeek and glm-4.6 model
        List<String> tokens = tokenizer.tokenize("\u4f7f\u7528 DeepSeek \u548c glm-4.6 \u6a21\u578b");
        assertTrue(tokens.contains("deepseek"));
        assertTrue(tokens.contains("glm"));
        boolean hasModel = tokens.contains("\u6a21\u578b");
        assertTrue(hasModel, "expected model token, got: " + tokens);
    }

    @Test
    void stopWordsFiltered() {
        List<String> tokens = tokenizer.tokenize("the a of AI");
        assertFalse(tokens.contains("the"));
        assertFalse(tokens.contains("a"));
        assertFalse(tokens.contains("of"));
        assertTrue(tokens.contains("ai"));
    }

    @Test
    void numericTokensKept() {
        // "\u7248\u672c 2026" -> version 2026
        List<String> tokens = tokenizer.tokenize("\u7248\u672c 2026");
        assertTrue(tokens.contains("2026"));
    }

    @Test
    void pureWhitespaceReturnsEmpty() {
        assertEquals(0, tokenizer.tokenize("   \t\n").size());
    }
}

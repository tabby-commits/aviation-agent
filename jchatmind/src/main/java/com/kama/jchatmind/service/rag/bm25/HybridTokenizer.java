package com.kama.jchatmind.service.rag.bm25;

import com.huaban.analysis.jieba.JiebaSegmenter;
import com.huaban.analysis.jieba.SegToken;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mixed Chinese + English tokenizer.
 * <p>
 * - ASCII letters/digits: extracted via regex [A-Za-z0-9_]+ and lower-cased.
 * - Remaining characters (typically CJK): handed to jieba SEARCH mode for
 *   recall-friendly fine-grained segmentation.
 * - Filtering: punctuation-only or whitespace-only tokens are dropped; a small
 *   built-in stop-word list is applied.
 */
@Component
public class HybridTokenizer {

    private static final Pattern ASCII_PATTERN = Pattern.compile("[A-Za-z0-9_]+");

    private static final Set<String> DEFAULT_STOP_WORDS = new HashSet<>();

    static {
        String[] stops = {
                "\u7684", "\u4e86", "\u548c", "\u662f", "\u5c31", "\u90fd", "\u800c", "\u53ca", "\u4e0e", "\u4e5f",
                "\u8fd9", "\u90a3", "\u6709", "\u5728", "\u6211", "\u4f60", "\u4ed6", "\u5979", "\u5b83", "\u4eec",
                "a", "an", "the", "is", "are", "was", "were", "be", "of", "to",
                "in", "on", "for", "and", "or", "but", "if", "then", "as", "at",
                "by", "with"
        };
        for (String s : stops) {
            DEFAULT_STOP_WORDS.add(s);
        }
    }

    private final JiebaSegmenter segmenter = new JiebaSegmenter();

    /**
     * Tokenize the given text into a list of lower-cased tokens.
     */
    public List<String> tokenize(String text) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return result;
        }

        Matcher matcher = ASCII_PATTERN.matcher(text);
        int cursor = 0;
        while (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();

            if (start > cursor) {
                String nonAscii = text.substring(cursor, start);
                collectJieba(nonAscii, result);
            }

            String ascii = matcher.group().toLowerCase();
            if (isValidToken(ascii)) {
                result.add(ascii);
            }
            cursor = end;
        }
        if (cursor < text.length()) {
            collectJieba(text.substring(cursor), result);
        }

        return result;
    }

    private void collectJieba(String segment, List<String> result) {
        if (segment == null || segment.isEmpty()) {
            return;
        }
        List<SegToken> tokens = segmenter.process(segment, JiebaSegmenter.SegMode.SEARCH);
        for (SegToken t : tokens) {
            String w = t.word;
            if (w == null) {
                continue;
            }
            w = w.trim().toLowerCase();
            if (isValidToken(w)) {
                result.add(w);
            }
        }
    }

    private boolean isValidToken(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        boolean hasWordChar = false;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                hasWordChar = true;
                break;
            }
        }
        if (!hasWordChar) {
            return false;
        }
        return !DEFAULT_STOP_WORDS.contains(token);
    }
}

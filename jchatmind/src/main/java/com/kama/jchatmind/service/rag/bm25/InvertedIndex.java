package com.kama.jchatmind.service.rag.bm25;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * BM25 inverted index for a single knowledge base (kbId).
 * <p>
 * Structures:
 * - term -> (docId -> tf) posting lists
 * - docId -> total token length
 * - total docs and sum of lengths (avg is derived)
 * <p>
 * Concurrency: a read-write lock allows concurrent searches while add/remove
 * operations are serialized.
 */
public class InvertedIndex {

    private final Map<String, Map<String, Integer>> termPosting = new HashMap<>();
    private final Map<String, Integer> docLength = new HashMap<>();
    private long sumDocLength = 0L;

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * Add or overwrite a document. If docId already exists, old postings are
     * removed first so add implements upsert semantics.
     */
    public void addDocument(String docId, List<String> tokens) {
        if (docId == null || tokens == null) {
            return;
        }
        lock.writeLock().lock();
        try {
            if (docLength.containsKey(docId)) {
                removeDocumentInternal(docId);
            }

            int length = tokens.size();
            docLength.put(docId, length);
            sumDocLength += length;

            Map<String, Integer> tfInDoc = new HashMap<>();
            for (String t : tokens) {
                tfInDoc.merge(t, 1, Integer::sum);
            }

            for (Map.Entry<String, Integer> e : tfInDoc.entrySet()) {
                termPosting
                        .computeIfAbsent(e.getKey(), k -> new HashMap<>())
                        .put(docId, e.getValue());
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Remove a document from the index. No-op if the docId is unknown.
     */
    public void removeDocument(String docId) {
        lock.writeLock().lock();
        try {
            removeDocumentInternal(docId);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void removeDocumentInternal(String docId) {
        Integer length = docLength.remove(docId);
        if (length == null) {
            return;
        }
        sumDocLength -= length;

        // Sweeps all postings. For mid-sized KBs this is acceptable. If we
        // need to scale, we could maintain an auxiliary docId -> terms map.
        termPosting.values().removeIf(posting -> {
            posting.remove(docId);
            return posting.isEmpty();
        });
    }

    public int getTotalDocs() {
        lock.readLock().lock();
        try {
            return docLength.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    public double getAvgDocLength() {
        lock.readLock().lock();
        try {
            int total = docLength.size();
            if (total == 0) return 0.0;
            return (double) sumDocLength / total;
        } finally {
            lock.readLock().unlock();
        }
    }

    public boolean containsDocument(String docId) {
        lock.readLock().lock();
        try {
            return docLength.containsKey(docId);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Run BM25 search against the current index and return the Top-K docs.
     */
    public List<ScoredDoc> search(List<String> queryTokens, int topK, BM25Params params) {
        if (queryTokens == null || queryTokens.isEmpty() || topK <= 0) {
            return Collections.emptyList();
        }

        lock.readLock().lock();
        try {
            int totalDocs = docLength.size();
            if (totalDocs == 0) {
                return Collections.emptyList();
            }
            double avgLen = (double) sumDocLength / totalDocs;
            double k1 = params.getK1();
            double b = params.getB();

            Map<String, Double> scoreByDoc = new HashMap<>();
            Set<String> uniqueTerms = new HashSet<>(queryTokens);

            for (String term : uniqueTerms) {
                Map<String, Integer> posting = termPosting.get(term);
                if (posting == null || posting.isEmpty()) {
                    continue;
                }
                int df = posting.size();
                double idf = Math.log(1.0 + (totalDocs - df + 0.5) / (df + 0.5));

                for (Map.Entry<String, Integer> e : posting.entrySet()) {
                    String docId = e.getKey();
                    int tf = e.getValue();
                    int dl = docLength.getOrDefault(docId, 0);
                    double norm = tf * (k1 + 1.0)
                            / (tf + k1 * (1.0 - b + b * (dl / (avgLen == 0 ? 1.0 : avgLen))));
                    double contribution = idf * norm;
                    scoreByDoc.merge(docId, contribution, Double::sum);
                }
            }

            if (scoreByDoc.isEmpty()) {
                return Collections.emptyList();
            }

            PriorityQueue<ScoredDoc> heap = new PriorityQueue<>(
                    Math.min(topK, scoreByDoc.size()),
                    (a, b2) -> Double.compare(a.getScore(), b2.getScore())
            );
            for (Map.Entry<String, Double> e : scoreByDoc.entrySet()) {
                ScoredDoc sd = new ScoredDoc(e.getKey(), e.getValue());
                if (heap.size() < topK) {
                    heap.offer(sd);
                } else if (heap.peek() != null && sd.getScore() > heap.peek().getScore()) {
                    heap.poll();
                    heap.offer(sd);
                }
            }
            List<ScoredDoc> out = new ArrayList<>(heap);
            out.sort((a, b2) -> Double.compare(b2.getScore(), a.getScore()));
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * A BM25 scoring result: document id plus its score.
     */
    public static class ScoredDoc {
        private final String docId;
        private final double score;

        public ScoredDoc(String docId, double score) {
            this.docId = docId;
            this.score = score;
        }

        public String getDocId() {
            return docId;
        }

        public double getScore() {
            return score;
        }
    }
}

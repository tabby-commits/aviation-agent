package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.config.RagHybridProperties;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.model.entity.ChunkBgeM3;
import com.kama.jchatmind.service.RagService;
import com.kama.jchatmind.service.rag.bm25.BM25IndexManager;
import com.kama.jchatmind.service.rag.bm25.InvertedIndex;
import com.kama.jchatmind.service.rag.bm25.RRFFusion;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
public class RagServiceImpl implements RagService {

    private final WebClient webClient;
    private final ChunkBgeM3Mapper chunkBgeM3Mapper;
    private final BM25IndexManager bm25IndexManager;
    private final RagHybridProperties hybridProperties;

    public RagServiceImpl(WebClient.Builder builder,
                          ChunkBgeM3Mapper chunkBgeM3Mapper,
                          BM25IndexManager bm25IndexManager,
                          RagHybridProperties hybridProperties) {
        this.webClient = builder.baseUrl("http://localhost:11434").build();
        this.chunkBgeM3Mapper = chunkBgeM3Mapper;
        this.bm25IndexManager = bm25IndexManager;
        this.hybridProperties = hybridProperties;
    }

    @Data
    private static class EmbeddingResponse {
        private float[] embedding;
    }

    @Data
    private static class BatchEmbeddingResponse {
        private String model;
        private List<float[]> embeddings;
    }

    private float[] doEmbed(String text) {
        EmbeddingResponse resp = webClient.post()
                .uri("/api/embeddings")
                .bodyValue(Map.of(
                        "model", "bge-m3",
                        "prompt", text
                ))
                .retrieve()
                .bodyToMono(EmbeddingResponse.class)
                .block();
        Assert.notNull(resp, "Embedding response cannot be null");
        return resp.getEmbedding();
    }

    @Override
    public float[] embed(String text) {
        return doEmbed(text);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return Collections.emptyList();
        }
        BatchEmbeddingResponse resp = webClient.post()
                .uri("/api/embed")
                .bodyValue(Map.of(
                        "model", "bge-m3",
                        "input", texts
                ))
                .retrieve()
                .bodyToMono(BatchEmbeddingResponse.class)
                .block();
        Assert.notNull(resp, "Batch embedding response cannot be null");
        return resp.getEmbeddings() != null ? resp.getEmbeddings() : Collections.emptyList();
    }

    @Override
    public List<String> similaritySearch(String kbId, String title) {
        List<ChunkBgeM3> chunks = vectorRecall(kbId, title, 3);
        return chunks.stream().map(ChunkBgeM3::getContent).toList();
    }

    @Override
    public List<String> hybridSearch(String kbId, String query, int topN) {
        int finalTopN = topN > 0 ? topN : hybridProperties.getFinalTopN();

        if (!hybridProperties.isEnabled()) {
            return similaritySearch(kbId, query).stream()
                    .limit(finalTopN)
                    .toList();
        }

        int vectorK = Math.max(finalTopN, hybridProperties.getVectorTopK());
        int bm25K = Math.max(finalTopN, hybridProperties.getBm25TopK());

        CompletableFuture<List<ChunkBgeM3>> vectorFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return vectorRecall(kbId, query, vectorK);
            } catch (Exception e) {
                log.warn("Vector recall failed, falling back to empty result: kbId={}, err={}", kbId, e.getMessage());
                return Collections.emptyList();
            }
        });
        CompletableFuture<List<InvertedIndex.ScoredDoc>> bm25Future = CompletableFuture.supplyAsync(() -> {
            try {
                return bm25IndexManager.search(kbId, query, bm25K);
            } catch (Exception e) {
                log.warn("BM25 recall failed, falling back to empty result: kbId={}, err={}", kbId, e.getMessage());
                return Collections.emptyList();
            }
        });

        List<ChunkBgeM3> vectorHits;
        List<InvertedIndex.ScoredDoc> bm25Hits;
        try {
            vectorHits = vectorFuture.get();
            bm25Hits = bm25Future.get();
        } catch (Exception e) {
            log.warn("Hybrid recall await failed, falling back to vector only: kbId={}, err={}", kbId, e.getMessage());
            return similaritySearch(kbId, query).stream().limit(finalTopN).toList();
        }

        List<String> fusedIds = rrfFuse(vectorHits, bm25Hits, finalTopN);
        if (fusedIds.isEmpty()) {
            return Collections.emptyList();
        }

        return resolveContents(fusedIds, vectorHits);
    }

    private List<ChunkBgeM3> vectorRecall(String kbId, String query, int k) {
        String queryEmbedding = toPgVector(doEmbed(query));
        List<ChunkBgeM3> chunks = chunkBgeM3Mapper.similaritySearch(kbId, queryEmbedding, k);
        return chunks != null ? chunks : Collections.emptyList();
    }

    private List<String> rrfFuse(List<ChunkBgeM3> vectorHits,
                                 List<InvertedIndex.ScoredDoc> bm25Hits,
                                 int topN) {
        List<String> vectorIds = vectorHits.stream()
                .map(ChunkBgeM3::getId)
                .filter(Objects::nonNull)
                .toList();
        List<String> bm25Ids = bm25Hits.stream()
                .map(InvertedIndex.ScoredDoc::getDocId)
                .filter(Objects::nonNull)
                .toList();
        return RRFFusion.fuse(hybridProperties.getRrfK(), topN, List.of(vectorIds, bm25Ids));
    }

    private List<String> resolveContents(List<String> ids, List<ChunkBgeM3> vectorHits) {
        Map<String, String> byId = new LinkedHashMap<>();
        for (ChunkBgeM3 c : vectorHits) {
            if (c.getId() != null) byId.put(c.getId(), c.getContent());
        }

        List<String> missing = new ArrayList<>();
        for (String id : ids) {
            if (!byId.containsKey(id)) missing.add(id);
        }
        if (!missing.isEmpty()) {
            try {
                List<ChunkBgeM3> fetched = chunkBgeM3Mapper.selectByIds(missing);
                if (fetched != null) {
                    for (ChunkBgeM3 c : fetched) {
                        if (c.getId() != null) byId.put(c.getId(), c.getContent());
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to backfill chunk content: ids={}, err={}", missing, e.getMessage());
            }
        }

        List<String> out = new ArrayList<>(ids.size());
        for (String id : ids) {
            String content = byId.get(id);
            if (content != null) out.add(content);
        }
        return out;
    }

    private String toPgVector(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            sb.append(v[i]);
            if (i < v.length - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }
}

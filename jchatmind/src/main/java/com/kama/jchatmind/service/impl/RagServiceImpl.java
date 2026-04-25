package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.config.RagHybridProperties;
import com.kama.jchatmind.evaluation.model.RetrievalHit;
import com.kama.jchatmind.evaluation.model.StructuredRetrievalResult;
import com.kama.jchatmind.evaluation.model.VectorSearchHit;
import com.kama.jchatmind.evaluation.service.StructuredRetrievalService;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
public class RagServiceImpl implements RagService, StructuredRetrievalService {

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
        List<float[]> embeddings = embedBatch(Collections.singletonList(text));
        Assert.notEmpty(embeddings, "Embedding response cannot be empty");
        return embeddings.get(0);
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
        StructuredRetrievalResult retrievalResult = buildVectorOnlyResult(kbId, title, 3, 3);
        return retrievalResult.getHits().stream()
                .map(RetrievalHit::getContent)
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public List<String> hybridSearch(String kbId, String query, int topN) {
        return retrieve(kbId, query, topN).getHits().stream()
                .map(RetrievalHit::getContent)
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public StructuredRetrievalResult retrieve(String kbId, String query, int topN) {
        int requestedTopN = topN > 0 ? topN : hybridProperties.getFinalTopN();
        int finalTopN = Math.min(requestedTopN, hybridProperties.getMaxTopN());

        if (!hybridProperties.isEnabled()) {
            return buildVectorOnlyResult(kbId, query, 3, finalTopN);
        }

        int vectorK = Math.max(finalTopN, hybridProperties.getVectorTopK());
        int bm25K = Math.max(finalTopN, hybridProperties.getBm25TopK());

        CompletableFuture<List<VectorSearchHit>> vectorFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return vectorRecallDetailed(kbId, query, vectorK);
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

        List<VectorSearchHit> vectorHits;
        List<InvertedIndex.ScoredDoc> bm25Hits;
        try {
            vectorHits = vectorFuture.get();
            bm25Hits = bm25Future.get();
        } catch (Exception e) {
            log.warn("Hybrid recall await failed, falling back to vector only: kbId={}, err={}", kbId, e.getMessage());
            return buildVectorOnlyResult(kbId, query, 3, finalTopN);
        }

        List<String> fusedIds = rrfFuse(vectorHits, bm25Hits, finalTopN);
        if (fusedIds.isEmpty()) {
            return StructuredRetrievalResult.builder()
                    .kbId(kbId)
                    .query(query)
                    .topN(finalTopN)
                    .vectorK(vectorK)
                    .bm25K(bm25K)
                    .hits(Collections.emptyList())
                    .build();
        }

        return StructuredRetrievalResult.builder()
                .kbId(kbId)
                .query(query)
                .topN(finalTopN)
                .vectorK(vectorK)
                .bm25K(bm25K)
                .hits(mergeHybridHits(fusedIds, vectorHits, bm25Hits))
                .build();
    }

    private StructuredRetrievalResult buildVectorOnlyResult(String kbId, String query, int vectorLimit, int finalTopN) {
        List<VectorSearchHit> vectorHits = vectorRecallDetailed(kbId, query, vectorLimit);
        List<RetrievalHit> hits = new ArrayList<>();
        int limit = Math.min(finalTopN, vectorHits.size());
        for (int i = 0; i < limit; i++) {
            VectorSearchHit hit = vectorHits.get(i);
            hits.add(RetrievalHit.builder()
                    .chunkId(hit.getId())
                    .docId(hit.getDocId())
                    .content(hit.getContent())
                    .rank(i + 1)
                    .retrievalSource("vector")
                    .vectorRank(i + 1)
                    .vectorDistance(hit.getVectorDistance())
                    .bm25Rank(null)
                    .bm25Score(null)
                    .finalTopK(true)
                    .build());
        }
        return StructuredRetrievalResult.builder()
                .kbId(kbId)
                .query(query)
                .topN(finalTopN)
                .vectorK(vectorLimit)
                .bm25K(0)
                .hits(hits)
                .build();
    }

    private List<VectorSearchHit> vectorRecallDetailed(String kbId, String query, int k) {
        String queryEmbedding = toPgVector(doEmbed(query));
        List<VectorSearchHit> chunks = chunkBgeM3Mapper.similaritySearchWithDistance(kbId, queryEmbedding, k);
        return chunks != null ? chunks : Collections.emptyList();
    }

    private List<String> rrfFuse(List<VectorSearchHit> vectorHits,
                                 List<InvertedIndex.ScoredDoc> bm25Hits,
                                 int topN) {
        List<String> vectorIds = vectorHits.stream()
                .map(VectorSearchHit::getId)
                .filter(Objects::nonNull)
                .toList();
        List<String> bm25Ids = bm25Hits.stream()
                .map(InvertedIndex.ScoredDoc::getDocId)
                .filter(Objects::nonNull)
                .toList();
        return RRFFusion.fuse(hybridProperties.getRrfK(), topN, List.of(vectorIds, bm25Ids));
    }

    private List<RetrievalHit> mergeHybridHits(List<String> ids,
                                               List<VectorSearchHit> vectorHits,
                                               List<InvertedIndex.ScoredDoc> bm25Hits) {
        Map<String, String> contentById = new LinkedHashMap<>();
        Map<String, String> docIdById = new HashMap<>();
        Map<String, Integer> vectorRankById = new HashMap<>();
        Map<String, Double> vectorDistanceById = new HashMap<>();
        for (int i = 0; i < vectorHits.size(); i++) {
            VectorSearchHit hit = vectorHits.get(i);
            if (hit.getId() == null) {
                continue;
            }
            contentById.put(hit.getId(), hit.getContent());
            docIdById.put(hit.getId(), hit.getDocId());
            vectorRankById.put(hit.getId(), i + 1);
            vectorDistanceById.put(hit.getId(), hit.getVectorDistance());
        }

        List<String> missing = new ArrayList<>();
        for (String id : ids) {
            if (!contentById.containsKey(id)) {
                missing.add(id);
            }
        }
        if (!missing.isEmpty()) {
            try {
                List<ChunkBgeM3> fetched = chunkBgeM3Mapper.selectByIds(missing);
                if (fetched != null) {
                    for (ChunkBgeM3 c : fetched) {
                        if (c.getId() != null) {
                            contentById.put(c.getId(), c.getContent());
                            docIdById.put(c.getId(), c.getDocId());
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to backfill chunk content: ids={}, err={}", missing, e.getMessage());
            }
        }

        Map<String, Integer> bm25RankById = new HashMap<>();
        Map<String, Double> bm25ScoreById = new HashMap<>();
        for (int i = 0; i < bm25Hits.size(); i++) {
            InvertedIndex.ScoredDoc hit = bm25Hits.get(i);
            if (hit.getDocId() == null) {
                continue;
            }
            bm25RankById.put(hit.getDocId(), i + 1);
            bm25ScoreById.put(hit.getDocId(), hit.getScore());
        }

        List<RetrievalHit> out = new ArrayList<>(ids.size());
        int rank = 1;
        for (String id : ids) {
            String content = contentById.get(id);
            if (content == null) {
                continue;
            }
            Integer vectorRank = vectorRankById.get(id);
            Integer bm25Rank = bm25RankById.get(id);
            out.add(RetrievalHit.builder()
                    .chunkId(id)
                    .docId(docIdById.get(id))
                    .content(content)
                    .rank(rank++)
                    .retrievalSource(resolveRetrievalSource(vectorRank, bm25Rank))
                    .vectorRank(vectorRank)
                    .vectorDistance(vectorDistanceById.get(id))
                    .bm25Rank(bm25Rank)
                    .bm25Score(bm25ScoreById.get(id))
                    .finalTopK(true)
                    .build());
        }
        return out;
    }

    private String resolveRetrievalSource(Integer vectorRank, Integer bm25Rank) {
        if (vectorRank != null && bm25Rank != null) {
            return "rrf(vector+bm25)";
        }
        if (vectorRank != null) {
            return "rrf(vector)";
        }
        if (bm25Rank != null) {
            return "rrf(bm25)";
        }
        return "rrf";
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

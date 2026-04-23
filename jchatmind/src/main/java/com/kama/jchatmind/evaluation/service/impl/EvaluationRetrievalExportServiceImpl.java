package com.kama.jchatmind.evaluation.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.evaluation.model.EvaluationQuerySample;
import com.kama.jchatmind.evaluation.model.EvaluationRetrievalExportRecord;
import com.kama.jchatmind.evaluation.model.StructuredRetrievalResult;
import com.kama.jchatmind.evaluation.request.ExportEvaluationRetrievalRequest;
import com.kama.jchatmind.evaluation.response.ExportEvaluationRetrievalResponse;
import com.kama.jchatmind.evaluation.service.EvaluationRetrievalExportService;
import com.kama.jchatmind.evaluation.service.StructuredRetrievalService;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Slf4j
@Service
public class EvaluationRetrievalExportServiceImpl implements EvaluationRetrievalExportService {

    private static final DateTimeFormatter TS_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final StructuredRetrievalService structuredRetrievalService;
    private final ChunkBgeM3Mapper chunkBgeM3Mapper;
    private final ObjectMapper objectMapper;
    private final Path evaluationBasePath;

    public EvaluationRetrievalExportServiceImpl(
            StructuredRetrievalService structuredRetrievalService,
            ChunkBgeM3Mapper chunkBgeM3Mapper,
            ObjectMapper objectMapper,
            @Value("${evaluation.base-path:./rag_eval}") String evaluationBasePath) {
        this.structuredRetrievalService = structuredRetrievalService;
        this.chunkBgeM3Mapper = chunkBgeM3Mapper;
        this.objectMapper = objectMapper;
        this.evaluationBasePath = Paths.get(evaluationBasePath).normalize();
    }

    @Override
    public ExportEvaluationRetrievalResponse exportRetrieval(ExportEvaluationRetrievalRequest request) {
        if (request == null) {
            throw new BizException("评测导出请求不能为空");
        }
        if (request.getKbId() == null || request.getKbId().isBlank()) {
            throw new BizException("kbId 不能为空");
        }
        if (request.getDatasetFilename() == null || request.getDatasetFilename().isBlank()) {
            throw new BizException("datasetFilename 不能为空");
        }
        if (chunkBgeM3Mapper.countByKbId(request.getKbId()) <= 0) {
            throw new BizException("当前知识库没有可检索的 chunks，请先确保 embedding 服务可用并完成 chunk 入库");
        }

        Path datasetsDir = evaluationBasePath.resolve("datasets").normalize();
        Path artifactsDir = evaluationBasePath.resolve("artifacts").normalize();
        Path datasetPath = resolveWithinBase(datasetsDir, request.getDatasetFilename());
        if (!Files.exists(datasetPath)) {
            throw new BizException("评测集文件不存在: " + datasetPath);
        }

        String outputFilename = resolveOutputFilename(request.getDatasetFilename(), request.getOutputFilename());
        Path outputPath = resolveWithinBase(artifactsDir, outputFilename);

        try {
            Files.createDirectories(datasetPath.getParent());
            Files.createDirectories(outputPath.getParent());
        } catch (IOException e) {
            throw new BizException("创建评测目录失败: " + e.getMessage());
        }

        int totalSamples = 0;
        int exportedSamples = 0;
        int requestedTopN = request.getTopN() == null ? 0 : request.getTopN();

        try (BufferedReader reader = Files.newBufferedReader(datasetPath, StandardCharsets.UTF_8);
             BufferedWriter writer = Files.newBufferedWriter(
                     outputPath,
                     StandardCharsets.UTF_8,
                     StandardOpenOption.CREATE,
                     StandardOpenOption.TRUNCATE_EXISTING,
                     StandardOpenOption.WRITE)) {

            String line;
            int rowNumber = 0;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                rowNumber++;
                totalSamples++;

                EvaluationQuerySample sample = parseSample(line, rowNumber, datasetPath);
                String userInput = safeText(sample.getUserInput());
                if (userInput == null || userInput.isBlank()) {
                    throw new BizException("评测样本缺少 user_input: line=" + rowNumber);
                }

                StructuredRetrievalResult retrievalResult =
                        structuredRetrievalService.retrieve(request.getKbId(), userInput, requestedTopN);

                EvaluationRetrievalExportRecord record = EvaluationRetrievalExportRecord.builder()
                        .sampleId(resolveSampleId(sample, rowNumber))
                        .kbId(request.getKbId())
                        .userInput(userInput)
                        .reference(safeText(sample.getReference()))
                        .referenceContextIds(sample.getReferenceContextIds())
                        .referenceContexts(sample.getReferenceContexts())
                        .topic(safeText(sample.getTopic()))
                        .difficulty(safeText(sample.getDifficulty()))
                        .topN(retrievalResult.getTopN())
                        .retrieved(retrievalResult.getHits())
                        .build();

                writer.write(objectMapper.writeValueAsString(record));
                writer.newLine();
                exportedSamples++;
            }
        } catch (IOException e) {
            throw new BizException("导出评测检索结果失败: " + e.getMessage());
        }

        log.info("评测检索导出完成: kbId={}, dataset={}, output={}, exported={}",
                request.getKbId(), datasetPath, outputPath, exportedSamples);

        return ExportEvaluationRetrievalResponse.builder()
                .kbId(request.getKbId())
                .datasetFilename(request.getDatasetFilename())
                .outputFilename(outputFilename)
                .outputPath(outputPath.toString().replace("\\", "/"))
                .totalSamples(totalSamples)
                .exportedSamples(exportedSamples)
                .topN(requestedTopN)
                .message("评测检索结果导出完成")
                .build();
    }

    private EvaluationQuerySample parseSample(String line, int rowNumber, Path datasetPath) {
        try {
            return objectMapper.readValue(line, EvaluationQuerySample.class);
        } catch (IOException e) {
            throw new BizException("解析评测样本失败: file=" + datasetPath + ", line=" + rowNumber + ", err=" + e.getMessage());
        }
    }

    private String resolveSampleId(EvaluationQuerySample sample, int rowNumber) {
        String sampleId = safeText(sample.getSampleId());
        return sampleId == null || sampleId.isBlank()
                ? String.format("sample_%04d", rowNumber)
                : sampleId;
    }

    private String resolveOutputFilename(String datasetFilename, String requestedOutputFilename) {
        String trimmed = safeText(requestedOutputFilename);
        if (trimmed != null && !trimmed.isBlank()) {
            return ensureJsonlSuffix(trimmed);
        }

        String sourceName = Paths.get(datasetFilename).getFileName().toString();
        int dotIndex = sourceName.lastIndexOf('.');
        String baseName = dotIndex > 0 ? sourceName.substring(0, dotIndex) : sourceName;
        return "retrieval_export_" + baseName + "_" + LocalDateTime.now().format(TS_FORMATTER) + ".jsonl";
    }

    private String ensureJsonlSuffix(String filename) {
        return filename.endsWith(".jsonl") ? filename : filename + ".jsonl";
    }

    private String safeText(String value) {
        return value == null ? null : value.trim();
    }

    private Path resolveWithinBase(Path baseDir, String child) {
        Path normalizedBase = baseDir.normalize();
        Path resolved = normalizedBase.resolve(child).normalize();
        if (!resolved.startsWith(normalizedBase)) {
            throw new BizException("非法路径: " + child);
        }
        return resolved;
    }
}

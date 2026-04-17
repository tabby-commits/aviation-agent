package com.kama.jchatmind.model.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchResultResponse {
    private String batchId;
    private String status;
    private int totalCount;
    private int successCount;
    private int failCount;
    private List<FileResult> results;
}

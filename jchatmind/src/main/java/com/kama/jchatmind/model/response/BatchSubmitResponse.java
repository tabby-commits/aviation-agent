package com.kama.jchatmind.model.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchSubmitResponse {
    private String batchId;
    private int totalCount;
    private String status;
    private String message;

    public enum Status {
        PROCESSING,
        COMPLETED,
        TIMEOUT,
        FAILED
    }
}

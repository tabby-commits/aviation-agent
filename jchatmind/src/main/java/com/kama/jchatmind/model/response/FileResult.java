package com.kama.jchatmind.model.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileResult {
    private String filename;
    private String documentId;
    private Status status;
    private String error;

    public enum Status {
        SUCCESS,
        FAILED,
        SKIPPED
    }
}

package com.kama.jchatmind.service.paper;

import lombok.Getter;

/**
 * 论文解析异常（携带 CSV 行号或记录序号）
 */
@Getter
public class PaperParseException extends RuntimeException {

    private final int lineNumber;

    public PaperParseException(String message, int lineNumber) {
        super(message + "（第 " + lineNumber + " 行）");
        this.lineNumber = lineNumber;
    }
}

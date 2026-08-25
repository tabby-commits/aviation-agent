package com.kama.jchatmind.service.paper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 论文列表字段（作者/机构）的 JSON 序列化工具
 */
public final class PaperJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PaperJson() {
    }

    /**
     * 列表转紧凑 JSON 数组字符串；入参 null 时返回 null（保持数据库列可空）
     */
    public static String toJson(List<String> list) {
        if (list == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("作者/机构序列化失败", e);
        }
    }
}

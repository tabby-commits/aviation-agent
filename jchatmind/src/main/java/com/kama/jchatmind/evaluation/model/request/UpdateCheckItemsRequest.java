package com.kama.jchatmind.evaluation.model.request;

import java.util.List;

import lombok.Data;

/**
 * 批量登记检查结论请求
 */
@Data
public class UpdateCheckItemsRequest {

    private List<CheckItemUpdate> items;

    @Data
    public static class CheckItemUpdate {
        /** 八项检查 item_key 之一 */
        private String itemKey;
        /** 符合 / 不符合 / 证据不足 */
        private String status;
        private String note;
    }
}

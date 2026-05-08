package com.kama.jchatmind.message;

import com.kama.jchatmind.model.chart.ChartArtifact;
import com.kama.jchatmind.model.response.BatchResultResponse;
import com.kama.jchatmind.model.vo.ChatMessageVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@AllArgsConstructor
@Builder
public class SseMessage {

    private Type type;
    private Payload payload;
    private Metadata metadata;

    @Data
    @AllArgsConstructor
    @Builder
    public static class Payload {
        private ChatMessageVO message;
        private String statusText;
        private Boolean done;
        // 批量上传进度
        private String batchId;
        private Integer processed;
        private Integer total;
        private Integer successCount;
        private Integer failCount;
        // 批量上传最终结果
        private BatchResultResponse batchResult;
        // Agentic Search 阶段信息（AGENTIC_* 类型消息使用）
        private String stage;
        private Integer step;
        private Integer totalSteps;
        private String toolName;
        private String taskId;
        private ChartArtifact chart;
    }

    @Data
    @AllArgsConstructor
    @Builder
    public static class Metadata {
        private String chatMessageId;
    }

    public enum Type {
        // 原有类型
        AI_GENERATED_CONTENT,
        AI_PLANNING,
        AI_THINKING,
        AI_EXECUTING,
        AI_DONE,
        BATCH_PROGRESS,
        BATCH_COMPLETE,
        // Agentic Search 阶段类型（前端未适配时未知 Type 自动忽略，向后兼容）
        /** 意图路由完成，payload.stage 说明路由决策 */
        AGENTIC_ROUTING,
        /** 主 Agent 已调用 delegateSearchTask，开始分发子任务，payload.total 为子任务总数 */
        AGENTIC_DELEGATING,
        /** 某子任务执行进度更新，payload.taskId + payload.step + payload.totalSteps */
        AGENTIC_SUBAGENT_PROGRESS,
        /** 某子任务发生自动降级（如 web→kb），payload.taskId + payload.stage 说明降级原因 */
        AGENTIC_FALLBACK,
        /** 所有子任务执行完毕，聚合结果已返回主 Agent */
        AGENTIC_DONE,
        AGENT_HOOK_RECOVERY,
        CHART_GENERATED,
    }
}

package com.kama.jchatmind.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Agentic Search 全局配置。
 * PR1 阶段仅占位，enabled 默认 false，不影响现有功能。
 * 实际接入点见 PR2（SubAgentExecutionService）、PR5（SearchIntentRouter 集成）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "jchatmind.agentic-search")
public class AgenticSearchProperties {

    /** 是否启用 Agentic Search 能力；false 时主 Agent 走原有单 Agent 路径 */
    private boolean enabled = false;

    private Router router = new Router();
    private Delegation delegation = new Delegation();
    private SubAgent subAgent = new SubAgent();
    private Search search = new Search();

    /** 意图路由器配置（SearchIntentRouter） */
    @Data
    public static class Router {
        /** 是否启用意图路由；false 时跳过路由直接走 Agentic 路径（仅在 enabled=true 时生效） */
        private boolean enabled = true;

        /** 路由使用的模型名称；建议使用非 reasoning 系列的快速模型以降低延迟 */
        private String model = "deepseek";

        /** 路由调用超时（毫秒）；超时则降级为非 Agentic 模式 */
        private int timeoutMs = 3000;
    }

    /** 委派执行配置（SubAgentExecutionService） */
    @Data
    public static class Delegation {
        /** 单次委派内最多同时运行的子任务数；受 GlobalPolicy.maxParallel 的用户请求值约束 */
        private int maxParallel = 4;

        /** 子 Agent MAX_STEPS 的硬上限；优先级高于 SubTaskSpec.searchPolicy.maxSubSteps */
        private int maxSubStepsCap = 8;

        /** 单任务默认超时秒数（当 SubTaskSpec.searchPolicy.timeoutSeconds 未指定时使用） */
        private int defaultTimeoutSeconds = 45;

        /** 本次委派的全局超时秒数（包含所有子任务并行执行的总时间） */
        private int globalTimeoutSeconds = 60;

        /** 每个用户请求并发子任务数上限（Semaphore 控制），防止单用户打满线程池 */
        private int perUserMaxParallel = 6;

        /**
         * 单个子任务在 runSubAgent 抛错后，允许因 hook 决策再跑的最大次数（不含首次执行）。
         * 例如 1 表示：首次失败最多再执行 1 次；仍失败则返回 failures 条目并交由主 Agent 降级综述。
         */
        private int maxSubAgentRedelegations = 1;
    }

    /** 子 Agent 工具配置 */
    @Data
    public static class SubAgent {
        /** SubAgentKnowledgeTool 的默认 topK（宽召回） */
        private int kbWideTopK = 30;

        /** SubAgentKnowledgeTool topK 的硬上限 */
        private int kbWideTopKMax = 60;
    }

    /** Agentic Search 搜索计数配置 */
    @Data
    public static class Search {
        /** WebSearchTool 单次搜索默认返回条数 */
        private int defaultCount = 8;

        /** WebSearchTool 单次搜索最大允许返回条数 */
        private int countMax = 15;
    }
}

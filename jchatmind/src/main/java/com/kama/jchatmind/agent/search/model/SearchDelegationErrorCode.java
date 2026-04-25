package com.kama.jchatmind.agent.search.model;

/**
 * 子 Agent 执行失败时的统一错误码。
 * 主 Agent 收到含 failures 的 DelegationResult 时，不得将其作为异常处理，
 * 应在综述里降级说明"某维度材料不足"。
 */
public enum SearchDelegationErrorCode {

    /** 子 Agent 执行超过 SubTaskSpec.searchPolicy.timeoutSeconds */
    SEARCH_DELEGATION_TIMEOUT,

    /** 所有检索来源均返回 0 结果 */
    SEARCH_DELEGATION_NO_EVIDENCE,

    /** 子 Agent 达到 maxSubSteps 上限仍未完成 */
    SEARCH_DELEGATION_TOOL_LIMIT,

    /** Tavily / RAG 异常且无任何降级路径可用 */
    SEARCH_DELEGATION_PROVIDER_ERROR,

    /** SubTaskSpec 入参校验失败（必填字段缺失或格式错误） */
    SEARCH_DELEGATION_INVALID_INPUT,
}

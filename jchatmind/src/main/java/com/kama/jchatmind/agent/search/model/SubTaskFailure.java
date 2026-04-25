package com.kama.jchatmind.agent.search.model;

/**
 * 单个子任务的失败记录。
 * 主 Agent 收到此对象时，不得将其当作异常处理，应在综述里说明"某维度材料不足"。
 *
 * @param taskId    与 SubTaskSpec.taskId 对应
 * @param errorCode 失败原因，见 {@link SearchDelegationErrorCode}
 * @param message   人类可读的失败说明（可包含 cause 摘要）
 */
public record SubTaskFailure(
        String taskId,
        String errorCode,
        String message
) {
}

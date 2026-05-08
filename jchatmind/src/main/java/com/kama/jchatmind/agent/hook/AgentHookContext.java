package com.kama.jchatmind.agent.hook;

import com.kama.jchatmind.agent.AgentRole;
import lombok.Builder;

@Builder
public record AgentHookContext(
        String sessionId,
        AgentRole agentRole,
        int stepIndex,
        String toolName,
        String toolArguments,
        String taskId,
        Object rawResult,
        Throwable error,
        int retryCount,
        RecoveryBudget recoveryBudget
) {
    public RecoveryBudget effectiveBudget() {
        return recoveryBudget == null ? RecoveryBudget.defaults() : recoveryBudget;
    }
}

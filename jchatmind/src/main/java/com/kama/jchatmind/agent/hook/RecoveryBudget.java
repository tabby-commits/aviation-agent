package com.kama.jchatmind.agent.hook;

public record RecoveryBudget(
        int maxToolRetries,
        int maxSubAgentRetries,
        int maxFinalAnswerSyntheses
) {

    public static RecoveryBudget defaults() {
        return new RecoveryBudget(1, 1, 1);
    }
}

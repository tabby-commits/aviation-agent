package com.kama.jchatmind.agent.hook;

public record RecoveryDecision(
        RecoveryAction action,
        String observation
) {

    public static RecoveryDecision continueWith(String observation) {
        return new RecoveryDecision(RecoveryAction.CONTINUE, observation);
    }
}

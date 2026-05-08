package com.kama.jchatmind.agent.hook;

public interface AgentHook {

    default RecoveryDecision beforeToolCall(AgentHookContext context) {
        return RecoveryDecision.continueWith(null);
    }

    default RecoveryDecision afterToolCall(AgentHookContext context) {
        return RecoveryDecision.continueWith(null);
    }

    default RecoveryDecision onToolEmptyResult(AgentHookContext context) {
        return RecoveryDecision.continueWith(null);
    }

    default RecoveryDecision onToolError(AgentHookContext context) {
        return RecoveryDecision.continueWith(null);
    }

    default RecoveryDecision onSubAgentFailure(AgentHookContext context) {
        return RecoveryDecision.continueWith(null);
    }

    default RecoveryDecision onRecoveryRequested(AgentHookContext context) {
        return RecoveryDecision.continueWith(null);
    }

    default RecoveryDecision beforeFinalAnswer(AgentHookContext context) {
        return RecoveryDecision.continueWith(null);
    }

    default RecoveryDecision onTaskCompleted(AgentHookContext context) {
        return RecoveryDecision.continueWith(null);
    }
}

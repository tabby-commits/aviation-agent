package com.kama.jchatmind.agent.hook;

import org.springframework.stereotype.Component;

@Component
public class RecoveryHookHandler implements AgentHook {

    @Override
    public RecoveryDecision onToolEmptyResult(AgentHookContext context) {
        if (context.retryCount() < context.effectiveBudget().maxToolRetries()) {
            return new RecoveryDecision(
                    RecoveryAction.RETRY_TOOL,
                    observation("tool_empty", context, "empty result; retrying tool")
            );
        }
        return RecoveryDecision.continueWith(
                observation("tool_empty_exhausted", context, "empty result retry budget exhausted")
        );
    }

    @Override
    public RecoveryDecision onToolError(AgentHookContext context) {
        if (context.retryCount() < context.effectiveBudget().maxToolRetries()) {
            return new RecoveryDecision(
                    RecoveryAction.RETRY_TOOL,
                    observation("tool_error", context, "tool error; retrying tool")
            );
        }
        return RecoveryDecision.continueWith(
                observation("tool_error_exhausted", context, "tool error retry budget exhausted")
        );
    }

    @Override
    public RecoveryDecision onSubAgentFailure(AgentHookContext context) {
        if (context.retryCount() < context.effectiveBudget().maxSubAgentRetries()) {
            return new RecoveryDecision(
                    RecoveryAction.REDELEGATE_SUBTASK,
                    observation("sub_agent_failure", context, "sub agent failed; redelegate once")
            );
        }
        return new RecoveryDecision(
                RecoveryAction.SYNTHESIZE_FINAL_ANSWER,
                observation("sub_agent_failure_exhausted", context, "sub agent retry budget exhausted")
        );
    }

    @Override
    public RecoveryDecision beforeFinalAnswer(AgentHookContext context) {
        return new RecoveryDecision(
                RecoveryAction.SYNTHESIZE_FINAL_ANSWER,
                observation("final_answer_synthesis", context, "synthesize final answer from available evidence")
        );
    }

    private String observation(String status, AgentHookContext context, String message) {
        return """
                {"status":"%s","toolName":"%s","taskId":"%s","message":"%s","error":"%s","retryCount":%d}
                """.formatted(
                escape(status),
                escape(context.toolName()),
                escape(context.taskId()),
                escape(message),
                escape(errorMessage(context.error())),
                context.retryCount()
        ).trim();
    }

    private String errorMessage(Throwable error) {
        if (error == null) {
            return "";
        }
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

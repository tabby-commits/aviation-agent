package com.kama.jchatmind.agent.hook;

import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.util.StringUtils;

import java.util.List;

public class ToolRecoverySupport {

    private final AgentHook agentHook;

    public ToolRecoverySupport(AgentHook agentHook) {
        this.agentHook = agentHook == null ? new RecoveryHookHandler() : agentHook;
    }

    public ToolResponseMessage recoverEmptyResponses(ToolResponseMessage message, AgentHookContext baseContext) {
        List<ToolResponseMessage.ToolResponse> responses = message.getResponses()
                .stream()
                .map(response -> recoverEmptyResponse(response, baseContext))
                .toList();
        return ToolResponseMessage.builder().responses(responses).build();
    }

    public ToolResponseMessage errorResponse(String callId,
                                             String toolName,
                                             Throwable error,
                                             AgentHookContext baseContext) {
        return ToolResponseMessage.builder()
                .responses(List.of(errorToolResponse(callId, toolName, error, baseContext)))
                .build();
    }

    public ToolResponseMessage.ToolResponse errorToolResponse(String callId,
                                                              String toolName,
                                                              Throwable error,
                                                              AgentHookContext baseContext) {
        AgentHookContext context = copy(baseContext, toolName, null, error, baseContext.retryCount());
        RecoveryDecision decision = agentHook.onToolError(context);
        return new ToolResponseMessage.ToolResponse(
                safeCallId(callId, toolName),
                safeToolName(toolName),
                decision.observation()
        );
    }

    public boolean isEmptyResult(String value) {
        if (!StringUtils.hasText(value)) {
            return true;
        }
        String trimmed = value.trim();
        return "[]".equals(trimmed) || "{}".equals(trimmed) || "null".equalsIgnoreCase(trimmed);
    }

    public ToolResponseMessage.ToolResponse recoverEmptyResponse(ToolResponseMessage.ToolResponse response,
                                                                 AgentHookContext baseContext) {
        if (!isEmptyResult(response.responseData())) {
            return response;
        }
        AgentHookContext context = copy(
                baseContext,
                response.name(),
                response.responseData(),
                null,
                baseContext.retryCount()
        );
        RecoveryDecision decision = agentHook.onToolEmptyResult(context);
        return new ToolResponseMessage.ToolResponse(
                response.id(),
                response.name(),
                decision.observation()
        );
    }

    private AgentHookContext copy(AgentHookContext baseContext,
                                  String toolName,
                                  Object rawResult,
                                  Throwable error,
                                  int retryCount) {
        return AgentHookContext.builder()
                .sessionId(baseContext.sessionId())
                .agentRole(baseContext.agentRole())
                .stepIndex(baseContext.stepIndex())
                .toolName(toolName)
                .toolArguments(baseContext.toolArguments())
                .taskId(baseContext.taskId())
                .rawResult(rawResult)
                .error(error)
                .retryCount(retryCount)
                .recoveryBudget(baseContext.effectiveBudget())
                .build();
    }

    private String safeCallId(String callId, String toolName) {
        return StringUtils.hasText(callId) ? callId : safeToolName(toolName) + "-recovery";
    }

    private String safeToolName(String toolName) {
        return StringUtils.hasText(toolName) ? toolName : "unknownTool";
    }
}

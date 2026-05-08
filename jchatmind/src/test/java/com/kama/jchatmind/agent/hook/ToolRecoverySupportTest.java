package com.kama.jchatmind.agent.hook;

import com.kama.jchatmind.agent.AgentRole;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ToolRecoverySupportTest {

    private final ToolRecoverySupport support = new ToolRecoverySupport(new RecoveryHookHandler());

    @Test
    void wrapsEmptyToolResponseAsRecoveryObservation() {
        ToolResponseMessage message = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "KnowledgeTool", "")))
                .build();

        ToolResponseMessage recovered = support.recoverEmptyResponses(message, baseContext("KnowledgeTool", 0));

        assertThat(recovered.getResponses()).hasSize(1);
        assertThat(recovered.getResponses().get(0).responseData()).contains("tool_empty").contains("KnowledgeTool");
    }

    @Test
    void wrapsToolExecutionExceptionAsRecoveryObservation() {
        ToolResponseMessage recovered = support.errorResponse(
                "call-1",
                "KnowledgeTool",
                new IllegalStateException("provider unavailable"),
                baseContext("KnowledgeTool", 1)
        );

        assertThat(recovered.getResponses()).hasSize(1);
        assertThat(recovered.getResponses().get(0).responseData())
                .contains("tool_error_exhausted")
                .contains("provider unavailable");
    }

    private AgentHookContext baseContext(String toolName, int retryCount) {
        return AgentHookContext.builder()
                .sessionId("session-1")
                .agentRole(AgentRole.MAIN)
                .stepIndex(1)
                .toolName(toolName)
                .retryCount(retryCount)
                .recoveryBudget(RecoveryBudget.defaults())
                .build();
    }
}

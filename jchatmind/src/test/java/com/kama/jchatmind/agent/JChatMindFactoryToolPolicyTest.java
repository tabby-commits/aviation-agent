package com.kama.jchatmind.agent;

import com.kama.jchatmind.agent.tools.Tool;
import com.kama.jchatmind.agent.tools.ToolType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JChatMindFactoryToolPolicyTest {

    @Test
    void noKnowledgeBaseRemovesKnowledgeAndDelegationTools() {
        List<Tool> selected = JChatMindFactory.selectFixedTools(List.of(
                tool("KnowledgeTool"),
                tool("delegateSearchTask"),
                tool("terminate")), false, false);

        assertEquals(List.of("terminate"), selected.stream().map(Tool::getName).toList());
    }

    @Test
    void configuredKnowledgeBaseKeepsKnowledgeTools() {
        List<Tool> selected = JChatMindFactory.selectFixedTools(List.of(
                tool("KnowledgeTool"),
                tool("delegateSearchTask"),
                tool("terminate")), true, true);

        assertEquals(3, selected.size());
    }

    private Tool tool(String name) {
        return new Tool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return name;
            }

            @Override
            public ToolType getType() {
                return ToolType.FIXED;
            }
        };
    }
}

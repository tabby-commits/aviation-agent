package com.kama.jchatmind.agent.search;

import com.kama.jchatmind.agent.search.model.SearchPolicy;
import com.kama.jchatmind.agent.search.model.SubTaskSpec;
import com.kama.jchatmind.agent.tools.SubAgentKnowledgeTool;
import com.kama.jchatmind.agent.tools.Tool;
import com.kama.jchatmind.agent.tools.WebSearchTool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SubAgentToolsetPolicyTest {

    private final SubAgentKnowledgeTool knowledgeTool = mock(SubAgentKnowledgeTool.class);
    private final WebSearchTool webSearchTool = mock(WebSearchTool.class);
    private final SubAgentToolsetPolicy policy = new SubAgentToolsetPolicy(knowledgeTool, webSearchTool);

    @Test
    void kbOnly_containsKnowledgeAndNoWeb() {
        when(knowledgeTool.getName()).thenReturn("knowledgeWideQuery");
        when(webSearchTool.getName()).thenReturn("webSearch");
        List<String> names = resolveNames(new SearchPolicy(true, false, 6, 45));

        assertThat(names).contains("directAnswer", "knowledgeWideQuery");
        assertThat(names).doesNotContain("terminate", "webSearch", "delegateSearchTask");
    }

    @Test
    void webOnly_containsWebAndNoKnowledge() {
        when(knowledgeTool.getName()).thenReturn("knowledgeWideQuery");
        when(webSearchTool.getName()).thenReturn("webSearch");
        List<String> names = resolveNames(new SearchPolicy(false, true, 6, 45));

        assertThat(names).contains("directAnswer", "webSearch");
        assertThat(names).doesNotContain("terminate", "knowledgeWideQuery", "delegateSearchTask");
    }

    @Test
    void kbAndWeb_containsBothButNeverDelegation() {
        when(knowledgeTool.getName()).thenReturn("knowledgeWideQuery");
        when(webSearchTool.getName()).thenReturn("webSearch");
        List<String> names = resolveNames(new SearchPolicy(true, true, 6, 45));

        assertThat(names).contains("directAnswer", "knowledgeWideQuery", "webSearch");
        assertThat(names).doesNotContain("terminate", "delegateSearchTask");
    }

    private List<String> resolveNames(SearchPolicy searchPolicy) {
        return policy.resolve(new SubTaskSpec("t1", "task", "kb1", null, searchPolicy))
                .stream()
                .map(Tool::getName)
                .toList();
    }
}

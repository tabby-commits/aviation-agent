package com.kama.jchatmind.agent.search;

import com.kama.jchatmind.agent.search.model.SearchPolicy;
import com.kama.jchatmind.agent.search.model.SubTaskSpec;
import com.kama.jchatmind.agent.tools.DirectAnswerTool;
import com.kama.jchatmind.agent.tools.SubAgentKnowledgeTool;
import com.kama.jchatmind.agent.tools.TerminateTool;
import com.kama.jchatmind.agent.tools.Tool;
import com.kama.jchatmind.agent.tools.WebSearchTool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class SubAgentToolsetPolicy {

    private final TerminateTool terminateTool;
    private final SubAgentKnowledgeTool knowledgeTool;
    private final WebSearchTool webSearchTool;

    public SubAgentToolsetPolicy(TerminateTool terminateTool,
                                 SubAgentKnowledgeTool knowledgeTool,
                                 WebSearchTool webSearchTool) {
        this.terminateTool = terminateTool;
        this.knowledgeTool = knowledgeTool;
        this.webSearchTool = webSearchTool;
    }

    public List<Tool> resolve(SubTaskSpec spec) {
        SearchPolicy policy = spec.searchPolicy();
        List<Tool> tools = new ArrayList<>();
        tools.add(terminateTool);
        tools.add(new DirectAnswerTool());
        if (policy != null && policy.allowKbSearch()) {
            tools.add(knowledgeTool);
        }
        if (policy != null && policy.allowWebSearch()) {
            tools.add(webSearchTool);
        }
        return tools;
    }
}
